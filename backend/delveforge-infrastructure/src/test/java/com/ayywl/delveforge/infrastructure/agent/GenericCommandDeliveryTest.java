package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.agent.CandidateDeliveryService;
import com.ayywl.delveforge.application.agent.CodingAgentService;
import com.ayywl.delveforge.application.agent.Delivery;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.domain.agent.AgentRunState;
import com.ayywl.delveforge.infrastructure.workspace.GitWorkspaceAdapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 Docker/Git/SQLite，全程无语言分派、无报告解析器、无付费模型。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_AGENT_DOCKER", matches = "true")
class GenericCommandDeliveryTest {
    @TempDir
    Path temporary;
    private final ObjectMapper json = new ObjectMapper();
    private final DockerAgentSandboxTest git = new DockerAgentSandboxTest();

    private Path fixture() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("original"));
        Files.writeString(root.resolve("README.md"), "Check the increment function with: sh check.sh\n");
        Files.writeString(root.resolve(".gitignore"), "build/\nnode_modules/\n");
        Files.writeString(root.resolve("counter.cpp"), "int next(int n) { return n; }\n");
        Files.writeString(root.resolve("check.cpp"), "#include <cassert>\nint next(int);\nint main() { assert(next(1) == 2); assert(next(-1) == 0); }\n");
        Files.writeString(root.resolve("check.sh"), "set -eu\nmkdir -p build\ng++ counter.cpp check.cpp -o build/check\nbuild/check\n");
        Files.writeString(root.resolve(".env.example"), "API_URL=https://example.com\n");
        Files.writeString(root.resolve("docker-compose.yml"), "services:\n  db:\n    environment:\n      MYSQL_ROOT_PASSWORD: fixture-private-value\n");
        Files.writeString(root.resolve(".env.local"), "PASSWORD=private-fixture-value\n");
        git.git(root, "init", "--template=");
        git.git(root, "add", ".");
        git.git(root, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-qm", "cpp baseline");
        return root;
    }

    private DockerAgentSandbox sandbox() {
        var defaults = DockerAgentSandboxTest.properties(temporary.resolve("runs"));
        return new DockerAgentSandbox(new AgentSandboxProperties(defaults.root(), defaults.docker(),
                defaults.images(), defaults.defaultJdk(), "delveforge-generic-tests",
                "delveforge-generic-v9-cache", defaults.memory(), defaults.cpus(), defaults.pids()),
                new DeterministicRepositorySecretPolicy());
    }

    private AgentProtocol.ToolCall call(String name, Map<String, ?> arguments) throws Exception {
        return new AgentProtocol.ToolCall(UUID.randomUUID().toString(), name, json.writeValueAsString(arguments));
    }

    private JsonNode exec(DockerAgentSandbox sandbox, String id, String command) throws Exception {
        return json.readTree(sandbox.tool(id, call("exec_command", Map.of("command", command)), () -> false));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cppFailureCorrectionFrozenCandidateAndAutomaticDeliveryPreserveRemainingPlanSteps(boolean finalPlan) throws Exception {
        Path original = fixture();
        String baseline = git.git(original, "rev-parse", "HEAD");
        var workspace = new GitWorkspaceAdapter(Files.createDirectories(temporary.resolve("managed")));
        Path official = Path.of(workspace.provision(new WorkspaceRef(original.toString()), baseline, "copy").workspace().value());
        var database = new SqliteAgentStoreTest();
        database.temporary = temporary;
        database.setup(baseline, "standalone");
        database.sql("UPDATE software_asset SET location='" + original.toString().replace("'", "''") + "'");
        database.sql("UPDATE working_copy SET location='" + official.toString().replace("'", "''") + "'");
        if (!finalPlan) {
            database.sql("INSERT INTO evolution_step(id,plan_id,position,goal,scope,status) VALUES('later','plan',1,'later','later','PENDING_CONFIRMATION')");
        }
        database.sql("INSERT INTO product_direction(id,user_profile_id,user_profile_revision,title,problem,target_product,user_fit,differentiation,technical_value,estimated_complexity,status,flow_id) VALUES('direction','user',1,'Counter','problem','target','fit','difference','value','small','SELECTED','standalone')");
        var eligibility = CodingAgentServiceTest.eligibility(baseline, official, workspace, original,
                com.ayywl.delveforge.domain.evolution.PreparationProvenance.unchanged(baseline), false, !finalPlan);
        var sandbox = sandbox();
        List<AgentProtocol.ToolCall> actions = new ArrayList<>(List.of(
                call("read_file", Map.of("path", "README.md")),
                call("exec_command", Map.of("command", "sh check.sh")),
                call("update_plan", Map.of("tasks", List.of(Map.of("title", "Repair counter.cpp and recheck", "status", "in_progress")))),
                call("read_file", Map.of("path", "counter.cpp")),
                call("edit_file", Map.of("path", "counter.cpp", "old_text", "return n;", "new_text", "return n + 1;")),
                call("exec_command", Map.of("command", "sh check.sh"))));
        if (finalPlan) {
            actions.add(call("exec_command", Map.of("command", "printf 'E2E browser unavailable\\n' >&2; exit 1")));
        }
        AtomicInteger turn = new AtomicInteger();
        List<String> observations = new ArrayList<>();
        AgentModelPort model = (history, tools) -> {
            observations.clear();
            history.stream().filter(message -> message.role().equals("tool")).forEach(message -> observations.add(message.text()));
            int index = turn.getAndIncrement();
            return index < actions.size() ? new AgentProtocol.Reply("", List.of(actions.get(index)), 1L)
                    : new AgentProtocol.Reply(FixtureCompletion.report(history), List.of(), 1L);
        };
        String candidateId;
        try (var agent = TestAgentServices.create(eligibility, database.store, model, sandbox,
                new DeterministicRepositorySecretPolicy(), AgentProtocol.Limits.defaults())) {
            var run = agent.start("plan", "step", 21);
            var terminal = CodingAgentServiceTest.awaitTerminal(agent, run.id(), 300);
            assertEquals(AgentRunState.SUCCEEDED, terminal.state(), terminal.reason() + "\n" + observations
                    + "\n" + database.store.events(run.id(), 0, 200));
            candidateId = terminal.candidateId();
        }
        assertTrue(observations.stream().anyMatch(value -> value.contains("COMMAND_FAILED")));
        var candidate = database.store.candidate(candidateId);
        assertEquals(List.of("counter.cpp"), candidate.changedPaths());
        assertEquals(8, json.readTree(candidate.testEvidence()).path("evidenceVersion").asInt());
        assertEquals(baseline, git.git(official, "rev-parse", "HEAD"));
        var deliveries = new SqliteDeliveryStore(database.source, eligibility);
        var guides = new SqliteQuickStartStore(database.source);
        var generations = new AtomicInteger();
        var quickStart = new com.ayywl.delveforge.application.quickstart.QuickStartService(guides, workspace, request -> {
            generations.incrementAndGet();
            assertTrue(request.toString().contains("README.md"));
            throw new com.ayywl.delveforge.application.agent.AgentException("FIXTURE_GUIDE_FAILURE", "No provider called");
        }, new DeterministicRepositorySecretPolicy());
        try (var delivery = new CandidateDeliveryService(database.store, deliveries, workspace, eligibility, quickStart::afterDelivery)) {
            delivery.start();
            for (int wait = 0; wait < 600 && (delivery.get(candidateId) == null || delivery.get(candidateId).state() != Delivery.State.COMPLETED); wait++) {
                Thread.sleep(100);
            }
            assertEquals(Delivery.State.COMPLETED, delivery.get(candidateId).state(),
                    () -> database.store.events(candidate.runId(), 0, 200).toString());
        }
        assertEquals(candidate.expectedTree(), git.git(official, "rev-parse", "HEAD^{tree}"));
        assertEquals(baseline, git.git(original, "rev-parse", "HEAD"));
        assertFalse(Files.exists(official.resolve("build")));
        assertEquals(Files.readString(original.resolve("docker-compose.yml")), Files.readString(official.resolve("docker-compose.yml")));
        assertEquals(0, json.readTree(candidate.testEvidence()).path("sourceView").path("withheldSourceCount").asInt());
        try (var connection = database.source.getConnection(); var statement = connection.createStatement();
                var state = statement.executeQuery("SELECT p.status, s.status FROM evolution_plan p "
                        + "JOIN evolution_step s ON s.plan_id=p.id WHERE p.id='plan' AND s.id='step'")) {
            assertTrue(state.next());
            assertEquals(finalPlan ? "COMPLETED" : "ACTIVE", state.getString(1));
            assertEquals("SUCCEEDED", state.getString(2));
        }
        if (finalPlan) {
            for (int wait = 0; wait < 600 && (guides.find("plan").isEmpty()
                    || guides.find("plan").orElseThrow().status().equals("GENERATING")); wait++) {
                Thread.sleep(50);
            }
            assertEquals(1, generations.get(), () -> guides.find("plan").toString());
            assertEquals("UNAVAILABLE", guides.find("plan").orElseThrow().status());
            assertEquals(Delivery.State.COMPLETED, deliveries.get(candidateId).state());
            assertEquals(git.git(official, "rev-parse", "HEAD"), guides.find("plan").orElseThrow().revision());
            assertEquals(3, json.readTree(candidate.testEvidence()).path("checks").size());
            assertTrue(candidate.testEvidence().contains("E2E browser unavailable"));
        } else {
            assertEquals(0, generations.get());
            assertTrue(guides.finalDelivery("plan").isEmpty());
        }
    }

    @Test
    void shellBoundariesTimeoutOutputAndSourceChangesAreObserved() throws Exception {
        Path original = fixture();
        String baseline = git.git(original, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        var sandbox = sandbox();
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "plan", "step", "copy", original.toString(),
                    temporary.resolve("unmounted").toString(), baseline, "increment", "counter.cpp", "fix", 21),
                    AgentProtocol.Limits.defaults(), () -> false);
            assertTrue(sandbox.preflight(id, () -> false).ready());
            assertTrue(exec(sandbox, id, "test -f .env.local && test ! -e /var/run/docker.sock && test ! -e /source && test ! -e /host && test -z \"$DEEPSEEK_API_KEY\"").path("ok").asBoolean());
            assertTrue(exec(sandbox, id, "printf changed > .env.example").path("ok").asBoolean());
            assertTrue(exec(sandbox, id, "cat .env.example").path("result").path("stdout").asText().contains("changed"));
            var networkResult = exec(sandbox, id, "python3 -c \"import socket; s=socket.socket(); s.settimeout(1); assert s.connect_ex(('1.1.1.1',443)) != 0\"");
            assertTrue(networkResult.path("ok").asBoolean(), networkResult.toString());
            assertFalse(exec(sandbox, id, "printf nope > /opt/delveforge/sandbox.py").path("ok").asBoolean());
            var output = exec(sandbox, id, "python3 -c \"print('x'*100000)\"");
            assertTrue(output.path("result").path("outputTruncated").asBoolean());
            assertEquals(0, output.path("result").path("exitCode").asInt());
            String logId = output.path("result").path("logRef").asText();
            assertFalse(logId.isBlank());
            assertEquals(4000, json.readTree(sandbox.readLog(id, logId, "stdout", 4000, 4000))
                    .path("result").path("content").asText().length());
            var timeout = json.readTree(sandbox.tool(id, call("exec_command", Map.of("command", "sleep 30 & wait", "timeout_seconds", 1)), () -> false));
            assertTrue(timeout.path("result").path("timedOut").asBoolean());
            assertFalse(timeout.path("ok").asBoolean());
            long cancelAt = System.nanoTime() + java.time.Duration.ofSeconds(8).toNanos();
            var cancelled = json.readTree(sandbox.tool(id, call("exec_command",
                    Map.of("command", "sleep 60 & wait", "timeout_seconds", 60)),
                    () -> System.nanoTime() >= cancelAt));
            assertTrue(cancelled.path("result").path("cancelled").asBoolean(), cancelled.toString());
            assertFalse(cancelled.path("ok").asBoolean());
            var verified = json.readTree(sandbox.tool(id, call("exec_command", Map.of(
                    "command", "test -s counter.cpp")), () -> false));
            assertTrue(verified.path("ok").asBoolean(), verified.toString());
            assertNull(FixtureCompletion.submit(sandbox, id, verified.path("result")));
            assertTrue(exec(sandbox, id, "printf 'int next(int n) { return n + 1; }\\n' > counter.cpp").path("result").path("sourceChanged").asBoolean());
            assertTrue(new AgentProcess().run(List.of("docker", "ps", "-q", "--filter", "name=df-" + id + "-command"), null, null,
                    java.time.Duration.ofSeconds(10), 1000, () -> false, Map.of()).text().isBlank(), "writer remains");
            assertNotNull(sandbox.completionFeedback(id, () -> false));
            var renamed = exec(sandbox, id, "mv README.md GUIDE.md");
            assertTrue(renamed.path("ok").asBoolean(), renamed.toString());
            assertTrue(renamed.path("result").path("sourceChanged").asBoolean());
            String partial = sandbox.retainPartial(id);
            assertNotNull(partial);
            assertTrue(Files.readString(Path.of(partial)).contains("UNVERIFIED_NON_DELIVERABLE"));
            JsonNode retained = json.readTree(Files.readString(Path.of(partial)));
            assertTrue(retained.path("changedPaths").toString().contains("README.md"));
            assertTrue(retained.path("changedPaths").toString().contains("GUIDE.md"));
            sandbox.cleanup(id);
            assertTrue(Files.exists(Path.of(partial)));
            assertTrue(json.readTree(sandbox.readLog(id, logId, "stdout", 0, 4000)).path("ok").asBoolean());
            assertEquals(baseline, git.git(original, "rev-parse", "HEAD"));
        } finally {
            sandbox.cleanup(id);
        }
    }
}
