package com.ayywl.delveforge.infrastructure.workbench;

import com.ayywl.delveforge.application.agent.AgentContextBudget;
import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.workbench.ConversationAgent;
import com.ayywl.delveforge.application.workbench.ConversationContext;
import com.ayywl.delveforge.application.workbench.ExecutionEnvironment;
import com.ayywl.delveforge.application.workbench.Workbench;
import com.ayywl.delveforge.domain.workbench.RunState;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "DF_WORKBENCH_TEST_FIXTURE", matches = ".+")
class WorkbenchDockerTest {
    @TempDir Path temp;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Path root() {
        return Path.of(System.getenv("DF_WORKBENCH_TEST_FIXTURE"));
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private record ToolResult(String out, String err, Integer exit) {}

    private static Workbench.Call prepare(
            DockerDevelopmentEnvironment env, Workbench.Run run, String tool, String arguments) {
        var proposed =
                new Workbench.Call(
                        id(), run.id(), id(), tool, arguments, "PROPOSED", null, null, null);
        var p = env.prepare(run, proposed, id());
        return new Workbench.Call(
                proposed.id(),
                run.id(),
                proposed.providerId(),
                tool,
                arguments,
                "ADMITTED",
                p.container(),
                p.generation(),
                p.execId());
    }

    private static ToolResult execute(
            DockerDevelopmentEnvironment env, Workbench.Run run, String tool, Map<String, ?> args)
            throws Exception {
        var call = prepare(env, run, tool, JSON.writeValueAsString(args));
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        env.execute(call, (s, b) -> (s.equals("stdout") ? out : err).writeBytes(b));
        var end = env.reconcile(call, true);
        assertTrue(end.ended());
        return new ToolResult(
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8),
                end.exit());
    }

    @Test
    void fourToolsUseWindowsWorkcopyLinuxVersionsAndFreshShells() throws Exception {
        String project = id();
        Path copy =
                Path.of(new ProjectWorkcopies(root().resolve("projects")).prepare(project, null));
        var run = new Workbench.Run(id(), id(), project, RunState.RUNNING, 1, false);
        try (var env = new DockerDevelopmentEnvironment(root(), JSON, 12)) {
            var created =
                    execute(
                            env,
                            run,
                            "Write",
                            Map.of("path", "test.txt", "content", "甲\r\n乙\r\n乙\r\n"));
            assertEquals(0, created.exit(), created.out() + created.err());
            assertEquals("甲\r\n乙\r\n乙\r\n", Files.readString(copy.resolve("test.txt")));
            assertEquals(
                    2,
                    execute(env, run, "Write", Map.of("path", "test.txt", "content", "overwrite"))
                            .exit());
            var page =
                    JSON.readTree(
                            execute(
                                            env,
                                            run,
                                            "Read",
                                            Map.of("path", "test.txt", "start", 2, "count", 1))
                                    .out());
            assertEquals("乙\r\n", page.path("text").asText());
            assertEquals(3, page.path("next").asInt());
            assertEquals(
                    2,
                    execute(env, run, "Edit", Map.of("path", "test.txt", "old", "乙", "new", "新"))
                            .exit());
            assertEquals(
                    0,
                    execute(env, run, "Edit", Map.of("path", "test.txt", "old", "甲", "new", "新"))
                            .exit());
            assertEquals(
                    2,
                    execute(
                                    env,
                                    run,
                                    "Write",
                                    Map.of(
                                            "path",
                                            "test.txt",
                                            "content",
                                            "stale",
                                            "expectedVersion",
                                            page.path("version").asText()))
                            .exit());
            String version =
                    JSON.readTree(execute(env, run, "Read", Map.of("path", "test.txt")).out())
                            .path("version")
                            .asText();
            assertEquals(
                    0,
                    execute(
                                    env,
                                    run,
                                    "Write",
                                    Map.of(
                                            "path",
                                            "test.txt",
                                            "expectedVersion",
                                            version,
                                            "content",
                                            "exact"))
                            .exit());
            assertEquals("exact", Files.readString(copy.resolve("test.txt")));
            String large = "春🌱\n".repeat(32768);
            assertEquals(
                    0,
                    execute(env, run, "Write", Map.of("path", "large.txt", "content", large))
                            .exit());
            assertEquals(large, Files.readString(copy.resolve("large.txt")));
            execute(
                    env,
                    run,
                    "Bash",
                    Map.of("command", "cd /tmp && export ONLY_THIS_INVOCATION=one"));
            var shell =
                    execute(
                            env,
                            run,
                            "Bash",
                            Map.of(
                                    "command",
                                    "pwd; test -z \"$ONLY_THIS_INVOCATION\"; cat test.txt; echo two"
                                            + " >&2; exit 19"));
            assertEquals(19, shell.exit());
            assertTrue(shell.out().contains("/projects/" + project));
            assertTrue(shell.out().endsWith("exact"));
            assertEquals("two\n", shell.err());
            var delayed =
                    prepare(env, run, "Bash", "{\"command\":\"echo unexpected > never-created\"}");
            assertTrue(env.reconcile(delayed, true).ended());
            env.execute(delayed, (s, b) -> {});
            assertFalse(Files.exists(copy.resolve("never-created")));
        }
    }

    static Workbench store(Path db) {
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db);
        Flyway.configure().dataSource(ds).locations("classpath:db/workbench").load().migrate();
        return new SqliteWorkbench(ds, JSON);
    }

    static ConversationAgent agent(Workbench store, ExecutionEnvironment env, AgentModelPort model)
            throws Exception {
        return new ConversationAgent(
                store,
                new ProjectWorkcopies(root().resolve("projects")),
                env,
                model,
                new ConversationContext(
                        store, model, (m, t) -> 10, new AgentContextBudget(1000, 10, .9)),
                JSON,
                3);
    }

    public static class LosingController {
        public static void main(String[] args) throws Exception {
            Workbench store = store(Path.of(args[0]));
            var actual = new DockerDevelopmentEnvironment(root(), JSON, 30);
            var breaking =
                    new ExecutionEnvironment() {
                        @Override
                        public Prepared prepare(Workbench.Run r, Workbench.Call c, String token) {
                            return actual.prepare(r, c, token);
                        }

                        @Override
                        public void execute(Workbench.Call c, BiConsumer<String, byte[]> capture) {
                            var channels = new java.util.HashSet<String>();
                            actual.execute(
                                    c,
                                    (s, b) -> {
                                        capture.accept(s, b);
                                        channels.add(s);
                                        if (channels.size() == 2) {
                                            Runtime.getRuntime().halt(75);
                                        }
                                    });
                        }

                        @Override
                        public End reconcile(Workbench.Call c, boolean stop) {
                            return actual.reconcile(c, stop);
                        }
                    };
            var command =
                    new AgentProtocol.ToolCall(
                            "once",
                            "Bash",
                            "{\"command\":\"echo once >> effects; echo captured; echo diagnostic"
                                    + " >&2; sleep 25; echo later\"}");
            agent(
                            store,
                            breaking,
                            (m, t) -> new AgentProtocol.Reply("", List.of(command), 1L, null, "r"))
                    .receive(args[1], "key", "一次命令");
            Thread.sleep(35000);
            throw new AssertionError("控制进程未在捕获后中断");
        }
    }

    @Test
    void restartedControllerFencesRealProcessAndDoesNotDuplicateSideEffects() throws Exception {
        Path db = temp.resolve("control.db");
        var store = store(db);
        String project = store.beginProject("故障夹具", null);
        var path = new ProjectWorkcopies(root().resolve("projects")).prepare(project, null);
        store.projectResult(project, path, null);
        String conversation = store.conversation(project, "一次");
        var child =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty(
                                        "surefire.test.class.path",
                                        System.getProperty("java.class.path")),
                                LosingController.class.getName(),
                                db.toString(),
                                conversation)
                        .redirectErrorStream(true)
                        .redirectOutput(temp.resolve("child.log").toFile())
                        .start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS));
            assertEquals(75, child.exitValue(), () -> read(temp.resolve("child.log")));
            var owner = store.owners().getFirst();
            String other = store.conversation(project, "二");
            assertFalse(store.receive(other, "new", "等待", 3).newlyStarted());
            var paid = new AtomicInteger();
            try (var env = new DockerDevelopmentEnvironment(root(), JSON, 10);
                    var restored =
                            agent(
                                    store,
                                    env,
                                    (m, t) -> {
                                        paid.incrementAndGet();
                                        return new AgentProtocol.Reply(
                                                "ok", List.of(), 1L, null, "r");
                                    })) {
                restored.startup();
                WorkbenchIntegrationTest.await(
                        () -> store.run(owner.id()).state() == RunState.PAUSED);
                assertTrue(store.owners().isEmpty());
                var last =
                        JSON.readTree(
                                store.timeline(conversation).getLast().get("text").toString());
                assertTrue(last.path("outputGap").asBoolean());
                assertTrue(last.path("stdout").asText().contains("captured"));
                assertTrue(last.path("stderr").asText().contains("diagnostic"));
                assertEquals(0, paid.get());
                assertEquals(List.of("once"), Files.readAllLines(Path.of(path, "effects")));
            }
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    private static String previewStatus(java.net.URI url) throws Exception {
        var request =
                new ProcessBuilder(
                                "curl.exe",
                                "--noproxy",
                                "*",
                                "--silent",
                                "--max-time",
                                "4",
                                "--output",
                                "NUL",
                                "--write-out",
                                "%{http_code}",
                                "--resolve",
                                url.getHost() + ":" + url.getPort() + ":127.0.0.1",
                                url.toString())
                        .start();
        String status =
                new String(request.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        assertEquals(0, request.waitFor());
        return status;
    }

    @Test
    void realGatewayRegistersOnlyCurrentProjectAndExpiresReusedHttpPort() throws Exception {
        var store = store(temp.resolve("preview.db"));
        var files = new ProjectWorkcopies(root().resolve("projects"));
        String project = store.beginProject("预览夹具", null);
        Path path = Path.of(files.prepare(project, null));
        store.projectResult(project, path.toString(), null);
        String conversation = store.conversation(project, "本机预览");
        String other = store.beginProject("另一项目", null);
        store.projectResult(other, files.prepare(other, null), null);
        String foreign = store.conversation(other, "独立对话");
        Files.writeString(
                path.resolve("fixture.py"),
                """
                from http.server import BaseHTTPRequestHandler, HTTPServer
                from pathlib import Path
                import sys
                class App(BaseHTTPRequestHandler):
                    def do_HEAD(self):
                        self.send_response(401)
                        self.end_headers()
                    def do_GET(self):
                        self.send_response(200)
                        self.end_headers()
                        self.wfile.write(b'project-specific')
                with HTTPServer(('127.0.0.1', int(sys.argv[1])), App) as http:
                    http.timeout = 0.1
                    Path('port').write_text(str(http.server_port))
                    while not Path('end-service').exists():
                        http.handle_request()
                """);
        String service =
                "setsid -f python3 fixture.py 0 </dev/null >service.log 2>&1; for i in {1..40}; do"
                    + " test -s port && break; sleep 0.1; done; df-workbench preview $(cat port)";
        var step = new AtomicInteger();
        var command = new java.util.concurrent.atomic.AtomicReference<>(service);
        AgentModelPort model =
                (m, t) -> {
                    if (step.getAndIncrement() % 2 == 1) {
                        return WorkbenchIntegrationTest.finalText("已观察结果");
                    }
                    try {
                        return new AgentProtocol.Reply(
                                "",
                                List.of(
                                        new AgentProtocol.ToolCall(
                                                id(),
                                                "Bash",
                                                JSON.writeValueAsString(
                                                        Map.of("command", command.get())))),
                                1L,
                                null,
                                "r");
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                };
        try (var env = new DockerDevelopmentEnvironment(root(), JSON, 20);
                var relay = new GatewayConnection(root(), env, store, JSON);
                var agent = agent(store, env, model)) {
            relay.start();
            var first = agent.receive(conversation, id(), "登记预览");
            WorkbenchIntegrationTest.await(
                    () -> store.run(first.runId()).state() == RunState.COMPLETED);
            var result = lastTool(store, conversation);
            assertEquals(0, result.path("exit").asInt(-1), result.toString());
            var url =
                    java.net.URI.create(
                            JSON.readTree(result.path("stdout").asText()).path("url").asText());
            assertEquals("200", previewStatus(url));
            String port = Files.readString(path.resolve("port"));
            command.set("df-workbench preview " + port);
            var denied = agent.receive(foreign, id(), "登记其他项目的端口");
            WorkbenchIntegrationTest.await(
                    () -> store.run(denied.runId()).state() == RunState.COMPLETED);
            var refused = lastTool(store, foreign);
            assertEquals(1, refused.path("exit").asInt(-1), refused.toString());
            assertTrue(refused.path("stderr").asText().contains("409"));
            Files.writeString(path.resolve("end-service"), "stop");
            WorkbenchIntegrationTest.await(
                    () -> env.previewListener(project, Integer.parseInt(port)).isEmpty());
            Files.delete(path.resolve("end-service"));
            Files.delete(path.resolve("port"));
            command.set(service.replace("fixture.py 0", "fixture.py " + port));
            var replacement = agent.receive(conversation, id(), "用新服务登记");
            WorkbenchIntegrationTest.await(
                    () -> store.run(replacement.runId()).state() == RunState.COMPLETED);
            var replacementReceipt = lastTool(store, conversation);
            assertEquals(
                    0, replacementReceipt.path("exit").asInt(-1), replacementReceipt.toString());
            var newUrl =
                    java.net.URI.create(
                            JSON.readTree(replacementReceipt.path("stdout").asText())
                                    .path("url")
                                    .asText());
            assertEquals("410", previewStatus(url));
            assertEquals("200", previewStatus(newUrl));
        } finally {
            Files.writeString(path.resolve("end-service"), "stop");
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode lastTool(
            Workbench store, String conversation) throws Exception {
        var tools =
                store.timeline(conversation).stream()
                        .filter(r -> "tool".equals(r.get("role")))
                        .toList();
        return JSON.readTree(tools.getLast().get("text").toString());
    }
}
