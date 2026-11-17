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

    static void awaitRuntime(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(condition.getAsBoolean(), "等待独立开发环境或工具执行超时");
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    record ToolResult(String out, String err, Integer exit) {}

    static Workbench.Call prepare(
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

    static ToolResult execute(
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

    @Test
    void windowsEditorSaveBetweenReadAndMutationPreservesUserChanges() throws Exception {
        String project = id();
        Path copy = Path.of(new ProjectWorkcopies(root().resolve("projects")).prepare(project, null));
        Path file = copy.resolve("notes.txt");
        Files.writeString(file, "title=old\r\nvalue=one\r\n");
        var run = new Workbench.Run(id(), id(), project, RunState.RUNNING, 1, false);
        try (var env = new DockerDevelopmentEnvironment(root(), JSON, 20)) {
            var read = execute(env, run, "Read", Map.of("path", "notes.txt"));
            String observed = JSON.readTree(read.out()).get("version").textValue();
            Files.writeString(file, "title=edited in Windows\r\nvalue=one\r\n");
            var stale = execute(env, run, "Write", Map.of(
                    "path", "notes.txt", "content", "old snapshot", "expectedVersion", observed));
            assertEquals(2, stale.exit());
            assertTrue(stale.out().contains("VERSION_CONFLICT"), stale.out());
            var conflict = execute(env, run, "Edit", Map.of("path", "notes.txt", "old", "title=old", "new", "x"));
            assertEquals(2, conflict.exit());
            assertTrue(conflict.out().contains("EDIT_CONFLICT"), conflict.out());
            var disjoint = execute(env, run, "Edit", Map.of("path", "notes.txt", "old", "value=one", "new", "value=two"));
            assertEquals(0, disjoint.exit());
            assertEquals("title=edited in Windows\r\nvalue=two\r\n", Files.readString(file));
            var tools = execute(env, run, "Bash", Map.of("command",
                    "set -eu; rg -F 'value=two' notes.txt; unzip -v | head -1; command -v git curl"));
            assertEquals(0, tools.exit(), tools.out() + tools.err());
            assertTrue(tools.out().contains("UnZip"));
        }
    }

    @Test
    void mavenAndColdWrapperReadPersistentProjectHomeThroughTheProxy() throws Exception {
        String project = id();
        Path copy = Path.of(new ProjectWorkcopies(root().resolve("projects")).prepare(project, null));
        Path repository = Path.of("").toAbsolutePath();
        while (!Files.exists(repository.resolve(".mvn/wrapper/maven-wrapper.properties"))) {
            repository = java.util.Objects.requireNonNull(repository.getParent(), "找不到仓库 Maven Wrapper");
        }
        Files.createDirectories(copy.resolve(".mvn/wrapper"));
        Files.copy(repository.resolve(".mvn/wrapper/maven-wrapper.properties"),
                copy.resolve(".mvn/wrapper/maven-wrapper.properties"));
        Files.writeString(copy.resolve("mvnw"), Files.readString(repository.resolve("mvnw")).replace("\r\n", "\n"));
        Files.writeString(copy.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>local</groupId>
                <artifactId>home-check</artifactId><version>1</version></project>
                """);
        var run = new Workbench.Run(id(), id(), project, RunState.RUNNING, 1, false);
        try (var env = new DockerDevelopmentEnvironment(root(), JSON, 180)) {
            var result = execute(env, run, "Bash", Map.of("command", """
                    set -euo pipefail
                    java -XshowSettings:properties -version 2>&1 | grep -F "user.home = $HOME"
                    mvn -B -X validate > effective.log 2>&1
                    grep -F "Reading user settings from $HOME/.m2/settings.xml" effective.log
                    grep -F "Using local repository at /state/cache/maven" effective.log
                    test ! -d "$MAVEN_USER_HOME/wrapper/dists"
                    MVNW_VERBOSE=true sh mvnw -v > wrapper.log 2>&1
                    grep -F 'Apache Maven' wrapper.log
                    test -d "$MAVEN_USER_HOME/wrapper/dists"
                    mvn -B -Dmaven.repo.local="$HOME/cold" org.apache.maven.plugins:maven-clean-plugin:3.4.1:help > cold.log 2>&1
                    grep 'Downloaded from delveforge-public-central: http://maven-cache:8082/maven2/.*maven-clean-plugin.*jar' cold.log
                    """));
            assertEquals(0, result.exit(), result.out() + result.err() + " " + copy);
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
        return agent(root(), store, env, model);
    }

    static ConversationAgent agent(Path data, Workbench store, ExecutionEnvironment env, AgentModelPort model)
            throws Exception {
        return new ConversationAgent(
                store,
                new ProjectWorkcopies(data.resolve("projects")),
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
            var captured = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
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
                                        if (channels.size() == 2 && captured.add(c.id()) && captured.size() == 2) {
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
            var agent = agent(store, breaking,
                    (m, t) -> new AgentProtocol.Reply("", List.of(command), 1L, null, "r"));
            agent.receive(args[1], "key", "一次命令 A");
            agent.receive(args[2], "key", "一次命令 B");
            Thread.sleep(90000);
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
        String parallel = store.beginProject("同时故障的 B", null);
        String pathB = new ProjectWorkcopies(root().resolve("projects")).prepare(parallel, null);
        store.projectResult(parallel, pathB, null);
        String conversationB = store.conversation(parallel, "B 的一次命令");
        var child =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty(
                                        "surefire.test.class.path",
                                        System.getProperty("java.class.path")),
                                LosingController.class.getName(),
                                db.toString(),
                                conversation, conversationB)
                        .redirectErrorStream(true)
                        .redirectOutput(temp.resolve("child.log").toFile())
                        .start();
        try {
            assertTrue(child.waitFor(80, TimeUnit.SECONDS));
            assertEquals(75, child.exitValue(), () -> read(temp.resolve("child.log")));
            var owners = store.owners();
            assertEquals(2, owners.size());
            assertEquals(2, owners.stream().map(run -> store.pending(run.id()).getFirst().container()).distinct().count());
            String other = store.conversation(project, "二");
            var queued = store.receive(other, "new", "等待", 3);
            assertFalse(queued.newlyStarted());
            var paid = new AtomicInteger();
            try (var env = new DockerDevelopmentEnvironment(root(), JSON, 10);
                    var restored =
                            agent(
                                    store,
                                    env,
                                    (m, t) -> {
                                        assertEquals("等待", m.getLast().text());
                                        paid.incrementAndGet();
                                        return new AgentProtocol.Reply(
                                                "ok", List.of(), 1L, null, "r");
                                    })) {
                restored.startup();
                awaitRuntime(() -> owners.stream().allMatch(run -> store.run(run.id()).state() == RunState.PAUSED)
                        && store.run(queued.runId()).state() == RunState.COMPLETED);
                assertTrue(store.owners().isEmpty());
                for (String history : List.of(conversation, conversationB)) {
                    var results = store.timeline(history).stream().filter(row -> "tool".equals(row.get("role"))).toList();
                    assertEquals(1, results.size());
                    var receipt = JSON.readTree(results.getFirst().get("text").toString());
                    assertTrue(receipt.path("outputGap").asBoolean());
                    assertTrue(receipt.path("stdout").asText().contains("captured"));
                    assertTrue(receipt.path("stderr").asText().contains("diagnostic"));
                }
                assertEquals(1, paid.get());
                assertEquals(List.of("once"), Files.readAllLines(Path.of(path, "effects")));
                assertEquals(List.of("once"), Files.readAllLines(Path.of(pathB, "effects")));
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
                import time
                port = int(sys.argv[1])
                while not Path('end-service').exists():
                    with HTTPServer(('127.0.0.1', port), App) as http:
                        http.timeout = 0.1
                        port = http.server_port
                        Path('port').write_text(str(port))
                        while not Path('end-service').exists() and not Path('restart').exists():
                            http.handle_request()
                    while Path('restart').exists():
                        time.sleep(0.1)
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
            awaitRuntime(
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
            awaitRuntime(
                    () -> store.run(denied.runId()).state() == RunState.COMPLETED);
            var refused = lastTool(store, foreign);
            assertEquals(1, refused.path("exit").asInt(-1), refused.toString());
            assertTrue(refused.path("stderr").asText().contains("409"));
            Files.writeString(path.resolve("restart"), "pause");
            awaitRuntime(() -> env.previewListener(project, Integer.parseInt(port)).isEmpty());
            assertEquals("503", previewStatus(url));
            Files.delete(path.resolve("restart"));
            for (int attempt = 0; attempt < 30 && !"200".equals(previewStatus(url)); attempt++) {
                Thread.sleep(250);
            }
            assertEquals("200", previewStatus(url));
            Files.writeString(path.resolve("end-service"), "stop");
            awaitRuntime(
                    () -> env.previewListener(project, Integer.parseInt(port)).isEmpty());
            Files.delete(path.resolve("end-service"));
            Files.delete(path.resolve("port"));
            command.set(service.replace("fixture.py 0", "fixture.py " + port));
            var replacement = agent.receive(conversation, id(), "用新服务登记");
            awaitRuntime(
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

    @Test
    void viteReloadsConfigurationOnTheOriginalUrlButANewGenerationGetsANewOrigin() throws Exception {
        var store = store(temp.resolve("vite.db"));
        String project = store.beginProject("Vite 预览", null);
        var copy = Path.of(new ProjectWorkcopies(root().resolve("projects")).prepare(project, null));
        store.projectResult(project, copy.toString(), null);
        String conversation = store.conversation(project, "配置热重启");
        var config = copy.resolve("vite.config.mjs");
        Files.writeString(copy.resolve("index.html"), "<title>preview fixture</title>");
        Files.writeString(config, "export default {}");
        String command = """
                set -eu
                test -x node_modules/.bin/vite || npm install --no-audit --no-fund --no-save vite@8.3.8 >install.log 2>&1
                setsid -f node_modules/.bin/vite --host 0.0.0.0 --port 5173 --strictPort </dev/null >vite.log 2>&1
                for i in {1..100}; do curl -sf http://127.0.0.1:5173 >/dev/null && break; sleep .1; done
                df-workbench preview 5173
                """;
        String arguments = JSON.writeValueAsString(Map.of("command", command));
        AgentModelPort model = (messages, tools) -> messages.getLast().role().equals("tool")
                ? WorkbenchIntegrationTest.finalText("已取得真实回执")
                : new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall(id(), "Bash", arguments)), 1L, null, "r");
        try (var env = new DockerDevelopmentEnvironment(root(), JSON, 120);
                var relay = new GatewayConnection(root(), env, store, JSON);
                var agent = agent(store, env, model)) {
            relay.start();
            var first = agent.receive(conversation, id(), "启动预览");
            awaitRuntime(() -> store.run(first.runId()).state() == RunState.COMPLETED);
            var oldUrl = previewReceipt(store, conversation);
            assertEquals("200", previewStatus(oldUrl));
            String inode = env.previewListener(project, 5173);
            assertFalse(inode.isEmpty());
            // Docker Desktop 的 Windows 挂载不保证宿主写入产生 inotify 事件。
            var change = execute(env, store.run(first.runId()), "Bash",
                    Map.of("command", "echo 'export default { clearScreen: false }' > vite.config.mjs"));
            assertEquals(0, change.exit(), change.err());
            awaitRuntime(() -> {
                String rebound = env.previewListener(project, 5173);
                return !rebound.isEmpty() && !inode.equals(rebound);
            });
            assertEquals("200", previewStatus(oldUrl));
            String firstGeneration = env.registered(project).containerId();
            env.maintain(project, "Rebuild");
            assertFalse(firstGeneration.equals(env.registered(project).containerId()));
            var next = agent.receive(conversation, id(), "在新代次启动预览");
            awaitRuntime(() -> store.run(next.runId()).state() == RunState.COMPLETED);
            var newUrl = previewReceipt(store, conversation);
            assertFalse(oldUrl.getHost().equals(newUrl.getHost()));
            assertEquals("200", previewStatus(newUrl));
            var oldHostAtNewGateway = new java.net.URI("http", null, oldUrl.getHost(), newUrl.getPort(), "/", null, null);
            assertEquals("404", previewStatus(oldHostAtNewGateway));
        }
    }

    private static java.net.URI previewReceipt(Workbench store, String conversation) throws Exception {
        var receipt = lastTool(store, conversation);
        assertEquals(0, receipt.path("exit").asInt(-1), receipt.toString());
        return java.net.URI.create(JSON.readTree(receipt.path("stdout").asText()).path("url").asText());
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
