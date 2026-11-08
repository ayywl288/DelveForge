package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentPolicyRejection;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.agent.CodingAgentService;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.infrastructure.workspace.GitWorkspaceAdapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实进程验证 cwd、离线安装及 /bin/sh 退出码，不请求模型或访问正式运行。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_AGENT_DOCKER", matches = "true")
class CommandUsabilityDockerTest {
    @TempDir
    Path temporary;

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode exec(DockerAgentSandbox sandbox, String id, Map<String, ?> arguments) throws Exception {
        return json.readTree(sandbox.tool(id, new AgentProtocol.ToolCall(UUID.randomUUID().toString(),
                "exec_command", json.writeValueAsString(arguments)), () -> false));
    }

    @Test
    void temporaryDiagnosticsPreserveSourceAndExplorationDoesNotCreateRequiredChecks() throws Exception {
        Path original = Files.createDirectories(temporary.resolve("original"));
        Files.writeString(original.resolve("state.txt"), "ready");
        Files.writeString(original.resolve("check.sh"), "grep -q ready state.txt\n");
        var git = new DockerAgentSandboxTest();
        git.git(original, "init", "--template=");
        git.git(original, "add", ".");
        git.git(original, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-qm", "fixture");
        String baseline = git.git(original, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        var sandbox = new DockerAgentSandbox(DockerAgentSandboxTest.properties(temporary.resolve("runs")),
                new DeterministicRepositorySecretPolicy());
        Map<String, String> check = Map.of("command",
                "sh check.sh > /tmp/df-check.log 2>&1; status=$?; cat /tmp/df-check.log; exit \"$status\"",
                "directory", ".");
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "plan", "step", "copy", original.toString(),
                    "unmounted", baseline, "check fixture", "state", "check", 21),
                    AgentProtocol.Limits.defaults(), () -> false);
            JsonNode exploratory = exec(sandbox, id, Map.of("command", "grep absent state.txt"));
            assertFalse(exploratory.path("ok").asBoolean());
            assertNotNull(sandbox.completionFeedback(id, () -> false), "Exploration alone cannot verify source");
            JsonNode result = exec(sandbox, id, check).path("result");
            assertEquals(0, result.path("exitCode").asInt(-1));
            assertFalse(result.path("sourceChanged").asBoolean(true));
            assertEquals(result.path("snapshot"), result.path("afterSnapshot"));
            assertNull(FixtureCompletion.submit(sandbox, id, result), "Diagnostic failure is not an obligation");
            JsonNode logging = exec(sandbox, id, Map.of("command",
                    "printf diagnostic > /tmp/df-diagnostic.log; cat /tmp/df-diagnostic.log")).path("result");
            assertEquals("diagnostic", logging.path("stdout").asText());
            assertFalse(logging.path("sourceChanged").asBoolean(true));
            assertEquals(result.path("snapshot"), logging.path("afterSnapshot"));
            assertNull(sandbox.completionFeedback(id, () -> false), "Diagnostic logs must not stale a final check");
            assertTrue(exec(sandbox, id, Map.of("command", "printf ready-again > state.txt"))
                    .path("result").path("sourceChanged").asBoolean());
            assertNotNull(sandbox.completionFeedback(id, () -> false), "Actual source mutation still invalidates checks");
            assertNull(FixtureCompletion.submit(sandbox, id, exec(sandbox, id, check).path("result")));
            assertFalse(exec(sandbox, id, Map.of("command", "grep absent state.txt", "directory", ".")).path("ok").asBoolean());
            assertNull(FixtureCompletion.submit(sandbox, id, exec(sandbox, id, check).path("result")), "Exploratory variants create no permanent obligation; old failures remain recorded");
            assertEquals("", git.git(original, "status", "--porcelain"));
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void realShellSourceMutationsResetRepeatedCommandHistory() throws Exception {
        Path original = Files.createDirectories(temporary.resolve("original"));
        Files.writeString(original.resolve("state.txt"), "first");
        var git = new DockerAgentSandboxTest();
        git.git(original, "init", "--template=");
        git.git(original, "add", ".");
        git.git(original, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-qm", "fixture");
        String baseline = git.git(original, "rev-parse", "HEAD");
        var workspace = new GitWorkspaceAdapter(Files.createDirectories(temporary.resolve("managed")));
        Path official = Path.of(workspace.provision(new WorkspaceRef(original.toString()), baseline, "copy")
                .workspace().value());
        var database = new SqliteAgentStoreTest();
        database.temporary = temporary;
        database.setup(baseline, "standalone");
        var eligibility = CodingAgentServiceTest.eligibility(baseline, official, workspace, original);
        var sandbox = new DockerAgentSandbox(DockerAgentSandboxTest.properties(temporary.resolve("runs")),
                new DeterministicRepositorySecretPolicy());
        // 两种快照交替三次；若未清空历史，真实收据会构成三次相同的二步循环。
        String command = "if grep -q first state.txt; then printf second > state.txt; else printf first > state.txt; fi";
        AtomicInteger requests = new AtomicInteger();
        AgentModelPort model = (history, tools) -> {
            int index = requests.getAndIncrement();
            return index < 6 ? new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("change-" + index,
                    "exec_command", json.createObjectNode().put("command", command).toString())), 1L)
                    : new AgentProtocol.Reply("{\"status\":\"failed\",\"summary\":\"fixture finished\"}", List.of(), 1L);
        };
        try (var service = new CodingAgentService(eligibility, database.store, model, sandbox,
                new DeterministicRepositorySecretPolicy(), AgentProtocol.Limits.defaults())) {
            var run = CodingAgentServiceTest.awaitTerminal(service, service.start("plan", "step", 21).id(), 180);
            assertEquals("MODEL_INCOMPLETE", run.reason());
            assertEquals(6, run.toolCalls());
            assertEquals(7, requests.get());
            assertNull(run.candidateId());
            var commands = service.events(run.id(), 0, 100).stream()
                    .filter(event -> event.kind().equals("TOOL_FINISHED")).toList();
            assertEquals(6, commands.size());
            for (var event : commands) {
                JsonNode result = json.readTree(event.summary());
                assertTrue(result.path("sourceChanged").asBoolean());
                assertEquals(0, result.path("exitCode").asInt(-1));
            }
        }
        assertEquals("first", Files.readString(original.resolve("state.txt")));
        assertEquals("", git.git(original, "status", "--porcelain"));
        assertEquals(baseline, git.git(official, "rev-parse", "HEAD"));
        assertEquals("", git.git(official, "status", "--porcelain"));
    }

    @Test
    void nestedPathsSetupAndPortablePipelinePreserveActualProcessResults() throws Exception {
        Path original = Files.createDirectories(temporary.resolve("original"));
        Path nested = Files.createDirectories(original.resolve("nested"));
        Files.writeString(nested.resolve("item.txt"), "nested fixture\n");
        Files.writeString(nested.resolve("package.json"), "{\"name\":\"offline-fixture\",\"version\":\"1.0.0\",\"private\":true}");
        Files.writeString(original.resolve("failing.sh"), "printf 'BUILD FAILURE\\n'\nexit 7\n");
        Files.writeString(original.resolve(".gitignore"), "node_modules/\n");
        var git = new DockerAgentSandboxTest();
        git.git(original, "init", "--template=");
        git.git(original, "add", ".");
        git.git(original, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-qm", "fixture");
        String baseline = git.git(original, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        var sandbox = new DockerAgentSandbox(DockerAgentSandboxTest.properties(temporary.resolve("runs")),
                new DeterministicRepositorySecretPolicy());
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "plan", "step", "copy", original.toString(),
                    temporary.resolve("unmounted").toString(), baseline, "check fixture", "nested", "check", 21),
                    AgentProtocol.Limits.defaults(), () -> false);
            JsonNode root = exec(sandbox, id, Map.of("command", "cat nested/item.txt", "directory", "."));
            JsonNode relative = exec(sandbox, id, Map.of("command", "cat item.txt", "directory", "nested"));
            assertEquals(0, root.path("result").path("exitCode").asInt(-1));
            assertEquals("nested fixture\n", relative.path("result").path("stdout").asText());
            assertEquals(0, relative.path("result").path("exitCode").asInt(-1));
            JsonNode repeated = exec(sandbox, id, Map.of("command", "cat nested/item.txt", "directory", "nested"));
            assertFalse(repeated.path("ok").asBoolean());
            assertTrue(repeated.path("result").path("exitCode").asInt() != 0);
            JsonNode missing = exec(sandbox, id, Map.of("command", "touch must-not-exist", "directory", "missing"));
            assertEquals("DIRECTORY_NOT_FOUND", missing.path("reason").asText());
            assertTrue(missing.path("mutationNotStarted").asBoolean());
            assertFalse(missing.has("exitCode"));
            assertTrue(exec(sandbox, id, Map.of("command", "test ! -e must-not-exist")).path("ok").asBoolean());

            for (var rejected : Map.of("../outside", AgentPolicyRejection.Rule.FORBIDDEN_PATH,
                    ".git", AgentPolicyRejection.Rule.FORBIDDEN_PATH,
                    ".ssh", AgentPolicyRejection.Rule.FORBIDDEN_PATH,
                    "id_rsa", AgentPolicyRejection.Rule.PROTECTED_CREDENTIAL_PATH).entrySet()) {
                var failure = assertThrows(AgentPolicyRejection.class, () -> exec(sandbox, id,
                        Map.of("command", "touch must-not-exist", "directory", rejected.getKey())));
                assertEquals(rejected.getValue(), failure.rule());
                assertEquals(AgentPolicyRejection.Stage.PATH_VALIDATION, failure.stage());
            }

            for (String setup : new String[]{
                    "npm install --package-lock-only --ignore-scripts --offline --no-audit --no-fund",
                    "npm ci --ignore-scripts --offline --no-audit --no-fund"}) {
                JsonNode result = exec(sandbox, id, Map.of("command", setup, "directory", "nested"));
                assertTrue(result.path("ok").asBoolean(), result.toString());
                assertEquals(0, result.path("result").path("exitCode").asInt(-1));
            }
            assertNotNull(sandbox.completionFeedback(id, () -> false), "Setup alone is not verification");

            JsonNode masked = exec(sandbox, id, Map.of("command", "sh failing.sh 2>&1 | grep BUILD"));
            assertEquals(0, masked.path("result").path("exitCode").asInt(-1));
            assertTrue(masked.path("result").path("stdout").asText().contains("BUILD FAILURE"));
            JsonNode preserved = exec(sandbox, id, Map.of("command",
                    "sh failing.sh > /tmp/df-check.log 2>&1; status=$?; grep BUILD /tmp/df-check.log; exit \"$status\""));
            assertEquals(7, preserved.path("result").path("exitCode").asInt(-1));
            assertFalse(preserved.path("ok").asBoolean());
            assertNotNull(sandbox.completionFeedback(id, () -> false));
            assertEquals(baseline, git.git(original, "rev-parse", "HEAD"));
            assertEquals("", git.git(original, "status", "--porcelain"));
        } finally {
            sandbox.cleanup(id);
        }
    }
}
