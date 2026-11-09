package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentCommandCaptureException;
import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.agent.AgentToolActivity;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** 合成仓库、真实 Docker/Git/SQLite；网络准备故障只注入运输点，不模拟命令或源码捕获。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_AGENT_DOCKER", matches = "true")
class RepositoryInputExecutionTest {
    @TempDir
    Path temporary;
    private final ObjectMapper json = new ObjectMapper();
    private final DockerAgentSandboxTest git = new DockerAgentSandboxTest();
    private final DeterministicRepositorySecretPolicy secrets = new DeterministicRepositorySecretPolicy();

    private Path fixture() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("original"));
        Files.writeString(root.resolve("application.properties"), "ordinary=1\njwt.secret=synthetic-default\n");
        Files.writeString(root.resolve(".env.example"), "PASSWORD=synthetic-example\n");
        Files.writeString(root.resolve("unrelated.txt"), "preserve this\n");
        git.git(root, "init", "--template=");
        git.git(root, "add", ".");
        git.git(root, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-qm", "baseline");
        return root;
    }

    private DockerAgentSandbox offlineSandbox() {
        AgentProcess process = spy(new AgentProcess());
        doAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            if (command.size() > 2 && command.get(1).equals("network") && command.get(2).equals("create")) {
                return new AgentProcess.Result(1, new byte[0], "injected dependency network unavailable");
            }
            return invocation.callRealMethod();
        }).when(process).run(any(), any(), any(), any(), any(Integer.class), any(), any());
        return new DockerAgentSandbox(DockerAgentSandboxTest.properties(temporary.resolve("runs")), secrets, process);
    }

    private AgentProtocol.ToolCall call(String tool, Map<String, ?> arguments) throws Exception {
        return new AgentProtocol.ToolCall(UUID.randomUUID().toString(), tool, json.writeValueAsString(arguments));
    }

    @Test
    void offlineTaskEditsExistingAndNewConfigurationsFreezesAndDeliversWithoutTouchingOriginal() throws Exception {
        Path original = fixture();
        String baseline = git.git(original, "rev-parse", "HEAD");
        var workspace = new GitWorkspaceAdapter(Files.createDirectories(temporary.resolve("managed")));
        Path official = Path.of(workspace.provision(new WorkspaceRef(original.toString()), baseline, "copy").workspace().value());
        var database = new SqliteAgentStoreTest();
        database.temporary = temporary;
        database.setup(baseline, "standalone");
        database.sql("UPDATE software_asset SET location='" + original.toString().replace("'", "''") + "'");
        database.sql("UPDATE working_copy SET location='" + official.toString().replace("'", "''") + "'");
        database.sql("INSERT INTO product_direction(id,user_profile_id,user_profile_revision,title,problem,target_product,user_fit,"
                + "differentiation,technical_value,estimated_complexity,status,flow_id) VALUES"
                + "('direction','user',1,'Config','problem','target','fit','difference','value','small','SELECTED','standalone')");
        var eligibility = CodingAgentServiceTest.eligibility(baseline, official, workspace, original);
        var sandbox = offlineSandbox();
        List<AgentProtocol.ToolCall> actions = List.of(
                call("read_file", Map.of("path", "application.properties")),
                call("edit_file", Map.of("path", "application.properties", "old_text", "ordinary=1\njwt.secret=synthetic-default",
                        "new_text", "ordinary=2\njwt.secret=synthetic-updated")),
                call("edit_file", Map.of("path", ".env.new", "old_text", "", "new_text", "PASSWORD=synthetic-new\n")),
                call("exec_command", Map.of("command", "test -f application.properties && "
                        + "printf 'PASSWORD=synthetic-shell\n' > .env.example && "
                        + "printf 'token: synthetic-yaml\n' > application.yml && "
                        + "test ! -e /host && test ! -e /source && test ! -e /var/run/docker.sock && "
                        + "test -z \"$DEEPSEEK_API_KEY\"")));
        AtomicInteger turn = new AtomicInteger();
        AgentModelPort model = (history, tools) -> {
            int index = turn.getAndIncrement();
            if (index == 1) {
                assertTrue(history.stream().anyMatch(message -> message.text().contains("synthetic-default")));
                assertTrue(history.stream().anyMatch(message -> message.text().contains("OFFLINE")));
            }
            return index < actions.size() ? new AgentProtocol.Reply("", List.of(actions.get(index)), 1L)
                    : new AgentProtocol.Reply("{\"status\":\"completed\",\"summary\":\"Offline config update\"}", List.of(), 1L);
        };
        String candidateId;
        try (var service = TestAgentServices.create(eligibility, database.store, model, sandbox, secrets, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", 21).id();
            var terminal = CodingAgentServiceTest.awaitTerminal(service, id, 90);
            assertEquals(AgentRunState.SUCCEEDED, terminal.state(), terminal.reason());
            candidateId = terminal.candidateId();
            assertEquals(baseline, git.git(official, "rev-parse", "HEAD"));
            assertTrue(Files.readString(official.resolve("application.properties")).contains("ordinary=1"));
            assertFalse(database.store.events(id, 0, 200).toString().contains("synthetic-updated"));
        }
        var candidate = database.store.candidate(candidateId);
        assertEquals(List.of(".env.example", ".env.new", "application.properties", "application.yml"), candidate.changedPaths());
        JsonNode evidence = json.readTree(candidate.testEvidence());
        assertEquals(8, evidence.path("evidenceVersion").asInt());
        assertEquals("FULL", evidence.path("sourceView").path("kind").asText());
        assertEquals("CAPTURED", evidence.path("checks").get(0).path("captureStatus").asText());
        var deliveries = new SqliteDeliveryStore(database.source, eligibility);
        try (var delivery = new CandidateDeliveryService(database.store, deliveries, workspace, eligibility)) {
            delivery.start();
            for (int wait = 0; wait < 600 && (delivery.get(candidateId) == null
                    || delivery.get(candidateId).state() != Delivery.State.COMPLETED); wait++) {
                Thread.sleep(50);
            }
            assertNotNull(delivery.get(candidateId));
            assertEquals(Delivery.State.COMPLETED, delivery.get(candidateId).state());
        }
        assertTrue(Files.readString(official.resolve("application.properties")).contains("synthetic-updated"));
        assertTrue(Files.exists(official.resolve(".env.new")));
        assertEquals("preserve this\n", Files.readString(official.resolve("unrelated.txt")));
        assertEquals(baseline, git.git(original, "rev-parse", "HEAD"));
        assertTrue(git.git(original, "status", "--porcelain").isEmpty());
        assertTrue(Files.readString(original.resolve("application.properties")).contains("synthetic-default"));
    }

    @Test
    void actualDownloadFailureIsACommandFactAndPostCommandCaptureFailureKeepsItsExitAndLogs() throws Exception {
        Path original = fixture();
        String id = UUID.randomUUID().toString();
        var sandbox = offlineSandbox();
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "plan", "step", "copy", original.toString(),
                    temporary.resolve("unmounted").toString(), git.git(original, "rev-parse", "HEAD"), "config", "config", "edit", 21),
                    AgentProtocol.Limits.defaults(), () -> false);
            assertTrue(sandbox.preflight(id, () -> false).ready());
            assertTrue(sandbox.checkCapabilities(id, () -> false).contains("OFFLINE"));
            var download = json.readTree(sandbox.tool(id, call("exec_command", Map.of("command",
                    "python3 -c \"import urllib.request; urllib.request.urlopen('http://df-artifacts:8080/npm/is-number', timeout=2)\"")), () -> false));
            assertFalse(download.path("ok").asBoolean());
            assertEquals("COMMAND_FAILED", download.path("result").path("classification").asText());
            assertTrue(download.path("result").path("exitCode").asInt() != 0);
            assertEquals("CAPTURED", download.path("result").path("captureStatus").asText());
            assertFalse(download.path("result").path("stderr").asText().isBlank());
            var command = call("exec_command", Map.of("command", "printf 'recoverable\n' > work.txt; "
                    + "ln -s /etc/passwd forbidden-link; printf 'command finished\n'; exit 7"));
            var failure = assertThrows(AgentCommandCaptureException.class, () -> sandbox.tool(id, command, () -> false));
            var facts = json.readTree(failure.result()).path("result");
            assertEquals(7, facts.path("exitCode").asInt());
            assertEquals("EXECUTED", facts.path("executionStatus").asText());
            assertEquals("FAILED", facts.path("captureStatus").asText());
            assertFalse(facts.path("captureFailure").asText().isBlank());
            String activity = new AgentToolActivity(secrets, AgentProtocol.Limits.defaults())
                    .failed(command, UUID.randomUUID().toString(), failure, 1L);
            assertEquals(7, json.readTree(activity).path("exitCode").asInt());
            assertThrows(RuntimeException.class, () -> sandbox.retainPartial(id));
            sandbox.stop(id);
            assertTrue(sandbox.readLog(id, facts.path("invocationId").asText(), "stdout", 0, 1000).contains("command finished"));
            // 测试读取已停止卷确认原始现场存在；产品仍将它视为不可交付材料。
            String volume = "df-" + id + "-source";
            var resources = new AgentProcess().run(List.of("docker", "volume", "ls", "--filter", "label=delveforge.run=" + id,
                    "--format", "{{.Name}}"), null, null, java.time.Duration.ofSeconds(20), 8192, () -> false, Map.of());
            assertEquals(0, resources.exitCode());
            assertTrue(resources.text().contains("source"));
            var material = new AgentProcess().run(List.of("docker", "run", "--rm", "--network", "none", "--read-only",
                    "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--mount",
                    "type=volume,source=" + volume + ",target=/retained,readonly",
                    System.getenv("DELVEFORGE_AGENT_IMAGE_21"), "sh", "-c",
                    "test -f /retained/work.txt && test -L /retained/forbidden-link"), null, null,
                    java.time.Duration.ofSeconds(20), 8192, () -> false, Map.of());
            assertEquals(0, material.exitCode());
            assertNotNull(failure.getCause());
        } finally {
            sandbox.cleanup(id);
        }
    }
}
