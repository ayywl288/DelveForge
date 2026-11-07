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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模型只提供确定动作；冲突、读取、编辑、完整 Maven 检查与冻结均使用真实沙箱。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_AGENT_DOCKER", matches = "true")
class PatchConflictOrchestrationDockerTest {
    @TempDir
    Path temporary;
    private final ObjectMapper json = new ObjectMapper();

    private AgentProtocol.ToolCall call(String name, Map<String, String> arguments) throws Exception {
        return new AgentProtocol.ToolCall(UUID.randomUUID().toString(), name, json.writeValueAsString(arguments));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conflictDefersSameAndDifferentFileWritesUntilFreshObservation(boolean earlierSuccess) throws Exception {
        var fixture = new DockerAgentSandboxTest();
        fixture.temporary = Files.createDirectories(temporary.resolve("asset"));
        Path original = fixture.fixture();
        Files.writeString(original.resolve(".gitattributes"), "* -text\n");
        fixture.git(original, "add", ".gitattributes");
        fixture.git(original, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-qm", "bytes");
        String baseline = fixture.git(original, "rev-parse", "HEAD");
        var workspace = new GitWorkspaceAdapter(Files.createDirectories(temporary.resolve("managed")));
        Path official = Path.of(workspace.provision(new WorkspaceRef(original.toString()), baseline, "copy")
                .workspace().value());
        var database = new SqliteAgentStoreTest();
        database.temporary = temporary;
        database.setup(baseline, "standalone");
        database.sql("UPDATE software_asset SET location='" + original.toString().replace("'", "''") + "'");
        database.sql("UPDATE working_copy SET location='" + official.toString().replace("'", "''") + "'");
        var eligibility = CodingAgentServiceTest.eligibility(baseline, official, workspace, original,
                PreparationProvenance.unchanged(baseline));
        var sandbox = new DockerAgentSandbox(DockerAgentSandboxTest.properties(temporary.resolve("runs")),
                new DeterministicRepositorySecretPolicy());
        String path = "src/main/java/example/Counter.java";
        String initial = Files.readString(original.resolve(path));
        String observed = earlierSuccess ? initial.replace("public class Counter {", "public class Counter { /* retained */")
                : initial;
        var conflict = call("edit_file", Map.of("path", path, "old_text", "return n - 1;", "new_text", "return n + 2;"));
        var skippedSame = call("edit_file", Map.of("path", path, "old_text", "return n + 2;",
                "new_text", "return n + 3;"));
        String testPath = "src/test/java/example/BoundaryTest.java";
        String test = """
                package example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class BoundaryTest { @Test void zero() { assertEquals(1, new Counter().next(0)); } }
                """;
        var skippedDifferent = call("edit_file", Map.of("path", testPath, "old_text", "", "new_text", test));
        var read = call("read_file", Map.of("path", path));
        List<AgentProtocol.ToolCall> first = new ArrayList<>();
        if (earlierSuccess) {
            first.add(call("edit_file", Map.of("path", path, "old_text", "public class Counter {",
                    "new_text", "public class Counter { /* retained */")));
        }
        first.addAll(List.of(conflict, skippedSame, skippedDifferent));
        var corrected = call("edit_file", Map.of("path", path, "old_text", "return n;", "new_text", "return n + 1;"));
        var addedTest = call("edit_file", Map.of("path", testPath, "old_text", "", "new_text", test));
        var checks = call("exec_command", Map.of("command", "mvn -B test", "verification_scope", "Repository regression tests", "check_kind", "test"));
        AtomicInteger requests = new AtomicInteger();
        List<List<AgentProtocol.Message>> observations = new ArrayList<>();
        AgentModelPort model = (history, tools) -> {
            observations.add(List.copyOf(history));
            int turn = requests.incrementAndGet();
            if (turn == 1) {
                return new AgentProtocol.Reply("", first, 1L);
            }
            if (turn == 2) {
                return new AgentProtocol.Reply("", List.of(read), 1L);
            }
            if (turn == 3) {
                return new AgentProtocol.Reply("", List.of(corrected, addedTest), 1L);
            }
            if (turn == 4) {
                return new AgentProtocol.Reply("", List.of(checks), 1L);
            }
            return new AgentProtocol.Reply("{\"status\":\"completed\"}", List.of(), 1L);
        };
        AgentProtocol.Run terminal;
        try (var agent = new CodingAgentService(eligibility, database.store, model, sandbox,
                new DeterministicRepositorySecretPolicy(), AgentProtocol.Limits.defaults())) {
            String id = agent.start("plan", "step", 21).id();
            terminal = CodingAgentServiceTest.awaitTerminal(agent, id, 600);
            assertEquals(AgentRunState.SUCCEEDED, terminal.state(), terminal.reason());
        }
        for (AgentProtocol.ToolCall issued : first) {
            assertEquals(1, observations.get(1).stream().filter(message -> issued.id().equals(message.callId())).count());
        }
        for (AgentProtocol.ToolCall skipped : List.of(skippedSame, skippedDifferent)) {
            assertTrue(observations.get(1).stream().anyMatch(message -> skipped.id().equals(message.callId())
                    && message.text().contains("PATCH_NOT_EXECUTED")));
        }
        String response = observations.get(2).stream().filter(message -> read.id().equals(message.callId()))
                .findFirst().orElseThrow().text();
        assertEquals(observed, json.readTree(response).path("result").path("content").asText());
        var events = database.store.events(terminal.id(), 0, 200);
        assertEquals(2, events.stream().filter(event -> event.kind().equals("TOOL_NOT_EXECUTED")).count());
        assertEquals(earlierSuccess ? 6 : 5, terminal.toolCalls());
        assertEquals(terminal.toolCalls(), events.stream().filter(event -> event.kind().equals("TOOL_FINISHED")).count());
        var candidate = database.store.candidate(terminal.candidateId());
        var evidence = json.readTree(candidate.testEvidence());
        assertEquals(6, evidence.path("evidenceVersion").asInt());
        assertEquals("COMMAND_VERIFIED", evidence.path("verificationLevel").asText());
        assertEquals(List.of(path, testPath), candidate.changedPaths());
        if (earlierSuccess) {
            assertTrue(candidate.diff().contains("/* retained */"));
        }
        for (Path repository : List.of(original, official)) {
            assertEquals(baseline, fixture.git(repository, "rev-parse", "HEAD"));
            assertEquals("", fixture.git(repository, "status", "--porcelain"));
            assertEquals(initial, Files.readString(repository.resolve(path)));
        }
        var proof = json.createObjectNode().put("kind", "DETERMINISTIC_MODEL_REAL_SANDBOX")
                .put("earlierSuccess", earlierSuccess).put("modelRequests", terminal.modelCalls())
                .put("toolCalls", terminal.toolCalls()).put("skippedMutations", 2).put("providerRequests", 0)
                .put("candidateId", terminal.candidateId()).put("baseline", baseline).put("deliveryPerformed", false);
        proof.set("evidence", evidence);
        proof.set("events", json.valueToTree(events.stream().map(event -> Map.of(
                "sequence", event.sequence(), "kind", event.kind(), "summary", event.summary(),
                "at", event.at().toString())).toList()));
        Path output = Files.createDirectories(Path.of("target/patch-conflict-orchestration"));
        Files.writeString(output.resolve((earlierSuccess ? "prior-success-" : "first-conflict-")
                + terminal.id() + ".json"), proof.toPrettyString());
    }
}
