package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.CodingAgentService;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.domain.agent.AgentRunState;
import com.ayywl.delveforge.domain.evolution.PreparationProvenance;
import com.ayywl.delveforge.infrastructure.workspace.GitWorkspaceAdapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 Docker/Git/SQLite 的模型前门槛；模型只计数或给定补丁，不访问任何付费 Provider。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_AGENT_DOCKER", matches = "true")
class EnvironmentPreflightDockerTest {
    @TempDir
    Path temporary;
    private final ObjectMapper json = new ObjectMapper();
    private final DockerAgentSandboxTest git = new DockerAgentSandboxTest();

    private void frontend(Path target) throws Exception {
        Path fixture = Path.of("src/test/resources/agent/native-frontend/npm");
        try (var paths = Files.walk(fixture)) {
            for (Path source : paths.toList()) {
                Path destination = target.resolve(fixture.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination);
                }
            }
        }
        Path counter = target.resolve("src/counter.js");
        Files.writeString(counter, Files.readString(counter).replace("return value;", "return value + 1;"));
    }

    private void javaProject(Path root, boolean broken) throws Exception {
        Files.createDirectories(root.resolve("src/main/java/example"));
        Files.createDirectories(root.resolve("src/test/java/example"));
        Files.writeString(root.resolve("pom.xml"), DockerAgentSandboxTest.pom(17).replace("</project>", """
                <profiles><profile><id>ordinary</id><activation><activeByDefault>true</activeByDefault></activation>
                <properties><maven.compiler.release>17</maven.compiler.release></properties></profile></profiles></project>
                """));
        Files.writeString(root.resolve("src/main/java/example/Counter.java"), broken ? "BROKEN JAVA SOURCE\n"
                : "package example; public class Counter { public int next(int value) { return value + 1; } }\n");
        Files.writeString(root.resolve("src/test/java/example/CounterTest.java"), """
                package example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class CounterTest { @Test void increments() { assertEquals(2, new Counter().next(1)); } }
                """);
    }

    private record Fixture(Path original, Path official, String baseline, GitWorkspaceAdapter workspace,
            SqliteAgentStoreTest database, DockerAgentSandbox sandbox,
            com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationState eligibility) {
    }

    private Fixture fixture(String mode) throws Exception {
        Path original = Files.createDirectories(temporary.resolve("original"));
        if (mode.equals("maven-broken") || mode.equals("multiple")) {
            javaProject(original.resolve("server"), mode.equals("maven-broken"));
            if (mode.equals("multiple")) {
                Files.createDirectories(original.resolve("unrelated"));
                Files.writeString(original.resolve("unrelated/pom.xml"), DockerAgentSandboxTest.pom(25));
            }
        } else if (mode.equals("workspace")) {
            frontend(original.resolve("apps/web"));
            Files.delete(original.resolve("apps/web/package-lock.json"));
            Files.writeString(original.resolve("package.json"), "{\"private\":true,\"packageManager\":\"pnpm@12.9.1\"}");
            Files.writeString(original.resolve("pnpm-workspace.yaml"), "packages: ['apps/*', 'packages/*']\n");
            Path shared = Files.createDirectories(original.resolve("packages/shared"));
            Files.writeString(shared.resolve("package.json"),
                    "{\"name\":\"@fixture/shared\",\"version\":\"1.0.0\",\"type\":\"module\",\"exports\":\"./index.js\"}");
            Files.writeString(shared.resolve("index.js"), "export const increment = value => value + 1;\n");
            ObjectNode manifest = (ObjectNode) json.readTree(Files.readString(original.resolve("apps/web/package.json")));
            manifest.remove("packageManager");
            ((ObjectNode) manifest.path("dependencies")).put("@fixture/shared", "workspace:*");
            Files.writeString(original.resolve("apps/web/package.json"), manifest.toString());
            Files.writeString(original.resolve("apps/web/src/counter.js"),
                    "import { increment as sharedIncrement } from '@fixture/shared';\n"
                            + "export function increment(value) { return sharedIncrement(value); }\n"
                            + "export function isDue(value) { return value <= 0; }\n");
        } else {
            frontend(original);
            if (mode.equals("pnpm6")) {
                Files.delete(original.resolve("package-lock.json"));
                ObjectNode manifest = (ObjectNode) json.readTree(Files.readString(original.resolve("package.json")));
                manifest.put("packageManager", "pnpm@8.15.9");
                Files.writeString(original.resolve("package.json"), manifest.toString());
                Files.copy(Path.of("src/test/resources/agent/native-frontend/pnpm6/pnpm-lock.yaml"),
                        original.resolve("pnpm-lock.yaml"));
            }
            if (mode.equals("npm2")) {
                Files.copy(Path.of("src/test/resources/agent/native-frontend/npm2/package-lock.json"),
                        original.resolve("package-lock.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (mode.equals("shrinkwrap")) {
                Files.move(original.resolve("package-lock.json"), original.resolve("npm-shrinkwrap.json"));
            }
            if (mode.equals("malicious")) {
                ObjectNode lock = (ObjectNode) json.readTree(Files.readString(original.resolve("package-lock.json")));
                ((ObjectNode) lock.path("packages").path("node_modules/vue"))
                        .put("resolved", "https://untrusted.invalid/vue/-/vue-3.5.17.tgz");
                Files.writeString(original.resolve("package-lock.json"), lock.toString());
            }
            if (mode.equals("lockless") || mode.equals("missing")) {
                Files.delete(original.resolve("package-lock.json"));
            }
            if (mode.equals("missing")) {
                ObjectNode manifest = (ObjectNode) json.readTree(Files.readString(original.resolve("package.json")));
                ((ObjectNode) manifest.path("dependencies")).put("delveforge-nonexistent-preflight-fixture-20261103", "1.0.0");
                Files.writeString(original.resolve("package.json"), manifest.toString());
            }
        }
        Files.writeString(original.resolve(".gitattributes"), "* -text\n");
        git.git(original, "init", "--template=");
        git.git(original, "add", ".");
        git.git(original, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-qm", "preflight fixture");
        String baseline = git.git(original, "rev-parse", "HEAD");
        var workspace = new GitWorkspaceAdapter(temporary.resolve("managed"));
        Path official = Path.of(workspace.provision(new WorkspaceRef(original.toString()), baseline, "copy").workspace().value());
        var database = new SqliteAgentStoreTest();
        database.temporary = temporary;
        database.setup(baseline, "standalone");
        database.sql("UPDATE software_asset SET location='" + original.toString().replace("'", "''") + "'");
        database.sql("UPDATE working_copy SET location='" + official.toString().replace("'", "''") + "'");
        var eligibility = CodingAgentServiceTest.eligibility(baseline, official, workspace, original,
                PreparationProvenance.unchanged(baseline), true);
        var sandbox = new DockerAgentSandbox(DockerAgentSandboxTest.properties(temporary.resolve("runs")),
                new DeterministicRepositorySecretPolicy());
        return new Fixture(original, official, baseline, workspace, database, sandbox, eligibility);
    }

    private CodingAgentService agent(Fixture fixture, AgentModelPort model) {
        return new CodingAgentService(fixture.eligibility(), fixture.database().store, model, fixture.sandbox(),
                new DeterministicRepositorySecretPolicy(), AgentProtocol.Limits.defaults());
    }

    private String record(Fixture fixture, String id, String scenario) throws Exception {
        var run = fixture.database().store.get(id);
        var events = fixture.database().store.events(id, 0, 100);
        ObjectNode evidence = json.createObjectNode().put("scenario", scenario).put("baseline", fixture.baseline())
                .put("runState", run.state().name()).put("reason", run.reason()).put("modelCalls", run.modelCalls());
        evidence.set("tokens", json.valueToTree(run.tokens()));
        evidence.set("events", json.valueToTree(events.stream().filter(event -> event.kind().equals("ENVIRONMENT_PREFLIGHT")
                || event.kind().equals("MODEL_REQUEST")).map(event -> Map.of(
                        "sequence", event.sequence(), "kind", event.kind(), "summary", event.summary())).toList()));
        Path directory = Files.createDirectories(Path.of("target/preflight-evidence"));
        Files.writeString(directory.resolve(scenario + "-" + id + ".json"), evidence.toPrettyString());
        return evidence.toString();
    }

    private void awaitReleased(Fixture fixture) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
        while (!fixture.database().store.abandoned().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(fixture.database().store.abandoned().isEmpty(), "Owned Run cleanup must release its occupation");
    }

    private void immutable(Fixture fixture) throws Exception {
        assertEquals(fixture.baseline(), git.git(fixture.original(), "rev-parse", "HEAD"));
        assertEquals(fixture.baseline(), git.git(fixture.official(), "rev-parse", "HEAD"));
        assertEquals("", git.git(fixture.original(), "status", "--porcelain"));
        assertEquals("", git.git(fixture.official(), "status", "--porcelain"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lockless", "shrinkwrap", "pnpm6"})
    void actualNativePreparationAndChecksPrecedeTheFirstRequest(String mode) throws Exception {
        Fixture fixture = fixture(mode);
        AtomicInteger requests = new AtomicInteger();
        try (var service = agent(fixture, (history, tools) -> {
            requests.incrementAndGet();
            assertTrue(history.stream().anyMatch(message -> message.text().contains("系统基线环境预检")));
            return new AgentProtocol.Reply("{\"status\":\"failed\",\"summary\":\"Intentional fixture stop after preflight\"}", List.of(), 1L);
        })) {
            var run = service.start("plan", "step", 21);
            var terminal = CodingAgentServiceTest.awaitTerminal(service, run.id(), 1200);
            assertEquals(1, requests.get(), record(fixture, run.id(), mode));
            assertEquals(1, terminal.modelCalls());
            assertFalse(Files.exists(fixture.official().resolve("node_modules")));
            if (mode.equals("lockless")) {
                assertFalse(Files.exists(fixture.official().resolve("package-lock.json")));
            }
            awaitReleased(fixture);
            immutable(fixture);
        }
    }

    @Test
    void nativeGeneratedV2LockIncludingLegacyDependencyEdgesIsCheckedWithoutRewritingSource() throws Exception {
        actualNativePreparationAndChecksPrecedeTheFirstRequest("npm2");
    }

    @Test
    void nativeWorkspacePreparationAndChecksPrecedeTheFirstRequest() throws Exception {
        actualNativePreparationAndChecksPrecedeTheFirstRequest("workspace");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malicious"})
    void unavailableDependenciesBlockWithZeroRequestsAndTokens(String mode) throws Exception {
        Fixture fixture = fixture(mode);
        AtomicInteger requests = new AtomicInteger();
        try (var service = agent(fixture, (history, tools) -> {
            requests.incrementAndGet();
            throw new AssertionError("Model must not run");
        })) {
            var run = service.start("plan", "step", 21);
            var terminal = CodingAgentServiceTest.awaitTerminal(service, run.id(), 1200);
            assertEquals("ENVIRONMENT_BLOCKED", terminal.reason());
            assertEquals(0, requests.get());
            assertEquals(0L, terminal.tokens());
            assertEquals(0, terminal.modelCalls());
            record(fixture, run.id(), mode);
            awaitReleased(fixture);
            immutable(fixture);
        }
    }

    @Test
    void dependencyEditRepreparesAndFreezesANewResolutionWithoutWritingSourceLocks() throws Exception {
        Fixture fixture = fixture("lockless");
        AtomicInteger requests = new AtomicInteger();
        var baselineResolution = new java.util.concurrent.atomic.AtomicReference<String>();
        List<AgentProtocol.ToolCall> actions = List.of(
                new AgentProtocol.ToolCall("edit-dependency", "edit_file", json.writeValueAsString(Map.of(
                        "path", "package.json", "old_text", "\"vue\": \"3.5.17\"",
                        "new_text", "\"vue\": \"3.5.17\", \"is-number\": \"7.0.0\""))),
                new AgentProtocol.ToolCall("check-dependency", "run_frontend_checks", "{\"goal\":\"build\"}"));
        try (var service = agent(fixture, (history, tools) -> {
            if (baselineResolution.get() == null) {
                for (var message : history) {
                    var matcher = java.util.regex.Pattern.compile("\\\"resolutionSha256\\\":\\\"([a-f0-9]{64})\\\"")
                            .matcher(message.text());
                    if (matcher.find()) {
                        baselineResolution.set(matcher.group(1));
                        break;
                    }
                }
            }
            int index = requests.getAndIncrement();
            return index < actions.size() ? new AgentProtocol.Reply("", List.of(actions.get(index)), 1L)
                    : new AgentProtocol.Reply("{\"status\":\"completed\",\"summary\":\"Dependency probe fixture\"}", List.of(), 1L);
        })) {
            var run = service.start("plan", "step", 21);
            var terminal = CodingAgentServiceTest.awaitTerminal(service, run.id(), 1200);
            assertEquals(AgentRunState.SUCCEEDED, terminal.state(), record(fixture, run.id(), "dependency-edit"));
            var candidate = fixture.database().store.candidate(terminal.candidateId());
            var check = json.readTree(candidate.testEvidence()).path("checks").get(0);
            assertEquals("RUN_FROZEN_RESOLUTION", check.path("reproducibility").asText());
            assertTrue(check.path("resolutionSha256").asText().matches("[a-f0-9]{64}"));
            org.junit.jupiter.api.Assertions.assertNotEquals(baselineResolution.get(), check.path("resolutionSha256").asText());
            assertFalse(Files.exists(fixture.official().resolve("package-lock.json")));
            awaitReleased(fixture);
            immutable(fixture);
        }
    }

    @Test
    void multipleMavenRootsRequireSelectionAndSelectedProfileReallyRuns() throws Exception {
        Fixture fixture = fixture("multiple");
        AtomicInteger requests = new AtomicInteger();
        try (var service = agent(fixture, (history, tools) -> {
            requests.incrementAndGet();
            return new AgentProtocol.Reply("{\"status\":\"failed\",\"summary\":\"fixture stop\"}", List.of(), 1L);
        })) {
            var blocked = service.start("plan", "step", 21);
            var terminal = CodingAgentServiceTest.awaitTerminal(service, blocked.id(), 1200);
            assertEquals("SELECTION_REQUIRED", terminal.reason());
            assertEquals(0, requests.get());
            assertEquals(0L, terminal.tokens());
            record(fixture, blocked.id(), "multiple-selection");
            awaitReleased(fixture);
            var selected = service.start("plan", "step", 21, List.of("server"));
            CodingAgentServiceTest.awaitTerminal(service, selected.id(), 1200);
            assertEquals(1, requests.get(), record(fixture, selected.id(), "multiple-selected"));
            awaitReleased(fixture);
            immutable(fixture);
        }
    }

    @Test
    void compilationFailureIsRepairableButStillRequiresCurrentRecheck() throws Exception {
        Fixture fixture = fixture("maven-broken");
        List<AgentProtocol.ToolCall> actions = List.of(
                new AgentProtocol.ToolCall("edit", "edit_file", json.writeValueAsString(Map.of(
                        "path", "server/src/main/java/example/Counter.java", "old_text", "BROKEN JAVA SOURCE\n",
                        "new_text", "package example; public class Counter { public int next(int value) { return value + 1; } }\n"))),
                new AgentProtocol.ToolCall("check", "run_checks", "{\"directory\":\"server\",\"goal\":\"test\"}"));
        AtomicInteger requests = new AtomicInteger();
        try (var service = agent(fixture, (history, tools) -> {
            int index = requests.getAndIncrement();
            return index < actions.size() ? new AgentProtocol.Reply("", List.of(actions.get(index)), 1L)
                    : new AgentProtocol.Reply("{\"status\":\"completed\",\"summary\":\"Fixed syntax\"}", List.of(), 1L);
        })) {
            var run = service.start("plan", "step", 21);
            var terminal = CodingAgentServiceTest.awaitTerminal(service, run.id(), 1200);
            assertEquals(AgentRunState.SUCCEEDED, terminal.state(), record(fixture, run.id(), "compilation-repair"));
            var candidate = fixture.database().store.candidate(terminal.candidateId());
            var evidence = json.readTree(candidate.testEvidence());
            assertEquals("COMPILATION_FAILED", evidence.path("baselineFailures").get(0).path("classification").asText());
            assertEquals("TEST_RUNNER", evidence.path("baselineFailures").get(0).path("unprovenCapabilities").get(0).asText());
            assertEquals(1, evidence.path("checks").size());
            assertEquals("TESTS_PASSED", evidence.path("checks").get(0).path("classification").asText());
            awaitReleased(fixture);
            immutable(fixture);
        }
    }
}
