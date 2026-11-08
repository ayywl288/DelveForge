package com.ayywl.delveforge.app.agent;

import com.ayywl.delveforge.app.DelveForgeApplication;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentStore;
import com.ayywl.delveforge.domain.agent.AgentRunState;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CodingAgentApiTest {
    private static final Path DATABASE = Path.of("target", "agent-api", UUID.randomUUID() + ".db");
    private static final Path LOG_ROOT = Path.of("target", "agent-api-logs", UUID.randomUUID().toString()).toAbsolutePath();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE::toString);
        registry.add("delveforge.agent.sandbox.root", LOG_ROOT::toString);
    }

    @Autowired
    TestRestTemplate http;
    @Autowired
    DataSource source;
    @Autowired
    AgentStore store;
    @LocalServerPort
    int port;

    @Test
    void retainedLogsArePagedAsJsonWithoutRequiringALiveSandbox() throws Exception {
        String run = UUID.randomUUID().toString();
        String invocation = UUID.randomUUID().toString();
        new JdbcTemplate(source).update("""
                INSERT INTO agent_run(id,plan_id,step_id,working_copy_id,baseline,state,started_at,occupied)
                VALUES(?,'logs-plan','logs-step','copy',?,'FAILED',?,0)
                """, run, "a".repeat(40), Instant.now().toString());
        Path logs = java.nio.file.Files.createDirectories(LOG_ROOT.resolve(run).resolve("command-logs"));
        java.nio.file.Files.writeString(logs.resolve(invocation + ".json"),
                "{\"stdout\":\"first-second\",\"logDiscarded\":false}");
        String path = "/api/agent-runs/" + run + "/logs/" + invocation;
        var response = http.getForEntity(path + "?offset=6&limit=3", com.fasterxml.jackson.databind.JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("sec", response.getBody().path("result").path("content").asText());
        assertEquals(9, response.getBody().path("result").path("nextOffset").asInt());
        assertTrue(response.getBody().path("result").path("hasMore").asBoolean());
        assertEquals(HttpStatus.BAD_REQUEST, http.getForEntity(path + "?limit=8001", String.class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, http.getForEntity("/api/agent-runs/absent/logs/" + invocation,
                String.class).getStatusCode());
    }

    @Test
    void paidStartRequiresAnExplicitBudgetAcknowledgmentBeforeAnyRunReservation() {
        String path = "/api/evolution-plans/absent/steps/absent/agent-runs";
        assertEquals(HttpStatus.BAD_REQUEST, http.postForEntity(path, null, String.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, http.postForEntity(path,
                java.util.Map.of("paidUsageAcknowledged", false), String.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, http.postForEntity(path,
                java.util.Map.of("targetJdk", 17), String.class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, http.postForEntity(path,
                java.util.Map.of("paidUsageAcknowledged", true), String.class).getStatusCode());
        assertEquals(java.util.List.of(), store.runs("absent", "absent", null, 50));
    }

    @Test
    void actualHttpQueriesEventsAndCancellationUsePersistedRun() throws Exception {
        String id = UUID.randomUUID().toString();
        try (var connection = source.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO agent_run(id,plan_id,step_id,working_copy_id,baseline,state,started_at,occupied)
                VALUES(?,'plan','step','copy',?,'RUNNING',?,0)
                """)) {
            statement.setString(1, id);
            statement.setString(2, "a".repeat(40));
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
        }
        store.event(id, "TOOL_FINISHED", "{\"tool\":\"read_file\",\"ok\":true}");
        var run = http.getForEntity("/api/agent-runs/" + id, AgentProtocol.Run.class);
        assertEquals(HttpStatus.OK, run.getStatusCode());
        assertEquals(AgentRunState.RUNNING, run.getBody().state());
        var events = http.getForEntity("/api/agent-runs/" + id + "/events?after=0&limit=10", String.class);
        assertTrue(events.getBody().contains("TOOL_FINISHED"));
        var cancelled = http.postForEntity("/api/agent-runs/" + id + "/cancel", null, AgentProtocol.Run.class);
        assertEquals(HttpStatus.OK, cancelled.getStatusCode());
        assertTrue(cancelled.getBody().cancelRequested());
        assertEquals(HttpStatus.NOT_FOUND, http.getForEntity("/api/agent-runs/absent", String.class).getStatusCode());
    }

    @Test
    void applicationCloseCompletesAnActiveHttpStreamBeforeGracefulShutdownTimeout() throws Exception {
        Path root = Path.of("target", "sse-shutdown", UUID.randomUUID().toString()).toAbsolutePath();
        try (var context = new SpringApplicationBuilder(DelveForgeApplication.class).run(
                "--server.port=0", "--delveforge.persistence.database-file=" + root.resolve("application.db"),
                "--delveforge.workspace.root=" + root.resolve("copies"),
                "--delveforge.agent.sandbox.root=" + root.resolve("runs"), "--logging.level.root=ERROR");
                HttpClient client = HttpClient.newHttpClient()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.update("""
                    INSERT INTO agent_run(id,plan_id,step_id,working_copy_id,baseline,state,started_at,occupied)
                    VALUES('shutdown-run','plan','step','copy',?,'RUNNING',?,0)
                    """, "a".repeat(40), Instant.now().toString());
            context.getBean(AgentStore.class).event("shutdown-run", "TOOL_STARTED", "read_file");
            int activePort = context.getEnvironment().getProperty("local.server.port", Integer.class);
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + activePort
                    + "/api/agent-runs/shutdown-run/stream")).timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = new BufferedReader(new InputStreamReader(response.body()))) {
                assertEquals(200, response.statusCode());
                assertTrue(nextEventId(reader) > 0);
                long started = System.nanoTime();
                context.close();
                assertTrue(System.nanoTime() - started < Duration.ofSeconds(5).toNanos(),
                        "Active SSE must finish before the default 30-second graceful shutdown timeout");
            }
        }
    }

    @Test
    void sseReplaysPersistedEventsAndReconnectCursorExcludesAlreadyDeliveredFacts() throws Exception {
        String id = UUID.randomUUID().toString();
        try (var connection = source.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO agent_run(id,plan_id,step_id,working_copy_id,baseline,state,started_at,occupied)
                VALUES(?,'sse-plan','sse-step','sse-copy',?,'RUNNING',?,0)
                """)) {
            statement.setString(1, id);
            statement.setString(2, "a".repeat(40));
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
        }
        store.event(id, "TOOL_STARTED", "read_file");
        store.event(id, "TOOL_FINISHED", "read_file complete");
        var history = store.events(id, 0, 100);
        try (HttpClient client = HttpClient.newHttpClient()) {
            URI uri = URI.create("http://127.0.0.1:" + port + "/api/agent-runs/" + id + "/stream");
            var first = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, first.statusCode());
            try (var reader = new BufferedReader(new InputStreamReader(first.body()))) {
                assertEquals(history.getFirst().sequence(), nextEventId(reader));
                assertEquals(history.getLast().sequence(), nextEventId(reader));
                // replay 已发送之后再追加实际事实，仍使用同一查询游标，无 live handoff 缺口。
                store.event(id, "DELIVERY_INTENT", "persisted during subscription");
                long live = store.events(id, history.getLast().sequence(), 100).getFirst().sequence();
                assertEquals(live, nextEventId(reader));
            }
            store.event(id, "DELIVERY_PUBLISHED", "published after disconnect");
            var all = store.events(id, 0, 100);
            long cursor = all.get(all.size() - 2).sequence();
            var reconnect = client.send(HttpRequest.newBuilder(uri).header("Last-Event-ID", Long.toString(cursor))
                    .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = new BufferedReader(new InputStreamReader(reconnect.body()))) {
                assertEquals(all.getLast().sequence(), nextEventId(reader));
            }
        }
        assertEquals(HttpStatus.BAD_REQUEST,
                http.getForEntity("/api/agent-runs/" + id + "/stream?after=-1", String.class).getStatusCode());
    }

    private static long nextEventId(BufferedReader reader) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return executor.submit(() -> {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("id:")) {
                        return Long.parseLong(line.substring(3).strip());
                    }
                }
                throw new AssertionError("SSE ended before the expected persisted event");
            }).get(10, TimeUnit.SECONDS);
        }
    }
}
