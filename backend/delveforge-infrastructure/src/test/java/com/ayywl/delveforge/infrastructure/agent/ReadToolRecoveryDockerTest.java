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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 确定性模型在同一 Run 中纠正读取错误；真实源码修改、检查和冻结不使用替身。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_AGENT_DOCKER", matches = "true")
class ReadToolRecoveryDockerTest {
    @TempDir
    Path temporary;
    private final ObjectMapper json = new ObjectMapper();

    private AgentProtocol.ToolCall call(String name, Map<String, ?> arguments) throws Exception {
        return new AgentProtocol.ToolCall(UUID.randomUUID().toString(), name, json.writeValueAsString(arguments));
    }

    @Test
    void readErrorsPreserveActiveEditsAndEveryCallIdentityUntilVerifiedFreeze() throws Exception {
        var fixture = new DockerAgentSandboxTest();
        fixture.temporary = Files.createDirectories(temporary.resolve("asset"));
        Path original = fixture.fixture();
        Files.writeString(original.resolve(".gitattributes"), "* -text\n");
        Files.writeString(original.resolve("long-line.txt"), "x".repeat(300000) + "\nsecond\n");
        fixture.git(original, "add", ".gitattributes", "long-line.txt");
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
        var edited = call("edit_file", Map.of("path", path, "old_text", "return n;", "new_text", "return n + 1;"));
        var malformed = call("read_file", Map.of("path", "src\\main\\java\\example\\Counter.java"));
        var missing = call("read_file", Map.of("path", "src/main/java/example/Missing.java"));
        var invalidRange = call("read_file", Map.of("path", path, "start_line", 0));
        var pagination = call("list_files", Map.of("offset", -1));
        var query = call("search_code", Map.of("query", "x".repeat(201)));
        var lookup = call("list_files", Map.of("path", "src/main/java"));
        var oversized = call("read_file", Map.of("path", "long-line.txt"));
        var resumed = call("read_file", Map.of("path", "long-line.txt", "start_line", 2));
        var corrected = call("read_file", Map.of("path", path, "start_line", 1, "line_count", 10));
        String testPath = "src/test/java/example/BoundaryTest.java";
        String test = "package example;\nimport org.junit.jupiter.api.Test;\n"
                + "import static org.junit.jupiter.api.Assertions.assertEquals;\n"
                + "class BoundaryTest { @Test void zero() { assertEquals(1, new Counter().next(0)); } }\n";
        var addedTest = call("edit_file", Map.of("path", testPath, "old_text", "", "new_text", test));
        var checks = call("exec_command", Map.of("command", "mvn -B test"));
        List<List<AgentProtocol.ToolCall>> turns = List.of(
                List.of(edited, malformed, missing, invalidRange, pagination, query),
                List.of(lookup, corrected, oversized), List.of(resumed, addedTest), List.of(checks));
        AtomicInteger requests = new AtomicInteger();
        List<AgentProtocol.Message> observed = new ArrayList<>();
        AgentModelPort model = (history, tools) -> {
            observed.clear();
            observed.addAll(history);
            int turn = requests.getAndIncrement();
            return turn < turns.size() ? new AgentProtocol.Reply("", turns.get(turn), 1L)
                    : new AgentProtocol.Reply(FixtureCompletion.report(history), List.of(), 1L);
        };
        AgentProtocol.Run terminal;
        try (var agent = new CodingAgentService(eligibility, database.store, model, sandbox,
                new DeterministicRepositorySecretPolicy(), AgentProtocol.Limits.defaults())) {
            String id = agent.start("plan", "step", 21).id();
            terminal = CodingAgentServiceTest.awaitTerminal(agent, id, 600);
            assertEquals(AgentRunState.SUCCEEDED, terminal.state(), terminal.reason());
        }
        for (AgentProtocol.ToolCall issued : turns.stream().flatMap(List::stream).toList()) {
            assertEquals(1, observed.stream().filter(message -> issued.id().equals(message.callId())).count());
        }
        List<AgentProtocol.ToolCall> failures = List.of(malformed, missing, invalidRange, pagination, query);
        List<String> reasons = List.of("RELATIVE_PATH_REQUIRED", "PATH_NOT_FOUND", "INVALID_RANGE",
                "INVALID_PAGINATION", "INVALID_QUERY");
        for (int index = 0; index < failures.size(); index++) {
            String callId = failures.get(index).id();
            var result = json.readTree(observed.stream().filter(message -> callId.equals(message.callId()))
                    .findFirst().orElseThrow().text());
            assertEquals(false, result.path("ok").asBoolean());
            var details = result.path("result");
            assertEquals("INVALID_TOOL_ARGUMENT", details.path("classification").asText());
            assertEquals(reasons.get(index), details.path("reason").asText());
            assertTrue(details.path("readNotStarted").asBoolean());
            assertTrue(details.path("mutationNotStarted").asBoolean());
            assertTrue(details.path("instruction").asText().length() > 0);
            assertEquals(false, result.toString().contains("Counter.java"));
        }
        var read = json.readTree(observed.stream().filter(message -> corrected.id().equals(message.callId()))
                .findFirst().orElseThrow().text());
        assertEquals(initial.replace("return n;", "return n + 1;"), read.path("result").path("content").asText());
        var files = json.readTree(observed.stream().filter(message -> lookup.id().equals(message.callId()))
                .findFirst().orElseThrow().text()).path("result").path("files");
        assertEquals(path, files.get(0).path("path").asText());
        assertEquals(read.path("result").path("content").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                files.get(0).path("sizeBytes").asInt());
        var limit = json.readTree(observed.stream().filter(message -> oversized.id().equals(message.callId()))
                .findFirst().orElseThrow().text());
        assertEquals("OUTPUT_LIMIT", limit.path("classification").asText());
        var second = json.readTree(observed.stream().filter(message -> resumed.id().equals(message.callId()))
                .findFirst().orElseThrow().text()).path("result");
        assertEquals("second\n", second.path("content").asText());
        assertEquals(1, second.path("lineCount").asInt());
        assertTrue(second.path("nextStartLine").isNull());
        var candidate = database.store.candidate(terminal.candidateId());
        var evidence = json.readTree(candidate.testEvidence());
        assertEquals(8, evidence.path("evidenceVersion").asInt());
        var executed = json.readTree(observed.stream().filter(message -> checks.id().equals(message.callId()))
                .findFirst().orElseThrow().text()).path("result");
        assertEquals(terminal.id(), evidence.path("runId").asText());
        assertEquals(terminal.id(), executed.path("runId").asText());
        assertEquals(executed.path("invocationId"), evidence.path("checks").get(0).path("invocationId"));
        assertEquals(json.createArrayNode().add(executed.path("invocationId")),
                evidence.path("agentReport").path("executions"));
        assertEquals(evidence.path("finalSnapshot"), executed.path("snapshot"));
        assertEquals(evidence.path("finalSnapshot"), executed.path("afterSnapshot"));
        assertEquals("FACTUAL_RECORD", evidence.path("verificationLevel").asText());
        assertEquals(List.of(path, testPath), candidate.changedPaths());
        var events = database.store.events(terminal.id(), 0, 200);
        assertEquals(12, terminal.toolCalls());
        assertEquals(12, events.stream().filter(event -> event.kind().equals("TOOL_FINISHED")).count());
        assertEquals(5, events.stream().filter(event -> event.kind().equals("TOOL_FINISHED")
                && event.summary().contains("INVALID_TOOL_ARGUMENT") && event.summary().contains("readNotStarted")).count());
        for (Path repository : List.of(original, official)) {
            assertEquals(baseline, fixture.git(repository, "rev-parse", "HEAD"));
            assertEquals("", fixture.git(repository, "status", "--porcelain"));
            assertEquals(initial, Files.readString(repository.resolve(path)));
        }
        var proof = json.createObjectNode().put("kind", "DETERMINISTIC_MODEL_REAL_SANDBOX")
                .put("modelRequests", terminal.modelCalls()).put("toolCalls", terminal.toolCalls())
                .put("recoveredReadErrors", 5).put("providerRequests", 0).put("candidateId", terminal.candidateId())
                .put("baseline", baseline).put("deliveryPerformed", false);
        proof.set("evidence", evidence);
        proof.set("events", json.valueToTree(events.stream().map(event -> Map.of("sequence", event.sequence(),
                "kind", event.kind(), "summary", event.summary(), "at", event.at().toString())).toList()));
        Path output = Files.createDirectories(Path.of("target/read-tool-recovery"));
        Files.writeString(output.resolve(terminal.id() + ".json"), proof.toPrettyString());
    }
}
