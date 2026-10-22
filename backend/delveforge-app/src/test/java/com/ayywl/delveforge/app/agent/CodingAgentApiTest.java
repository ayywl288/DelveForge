package com.ayywl.delveforge.app.agent;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentStore;
import com.ayywl.delveforge.domain.agent.AgentRunState;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CodingAgentApiTest {
    private static final Path DATABASE = Path.of("target", "agent-api", UUID.randomUUID() + ".db");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE::toString);
    }

    @Autowired
    TestRestTemplate http;
    @Autowired
    DataSource source;
    @Autowired
    AgentStore store;

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
}
