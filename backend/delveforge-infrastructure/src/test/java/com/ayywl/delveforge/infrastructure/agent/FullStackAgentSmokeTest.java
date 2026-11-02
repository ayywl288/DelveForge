package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.CandidateDeliveryService;
import com.ayywl.delveforge.application.agent.CodingAgentService;
import com.ayywl.delveforge.application.agent.Delivery;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.domain.agent.AgentRunState;
import com.ayywl.delveforge.domain.evolution.PreparationProvenance;
import com.ayywl.delveforge.infrastructure.workspace.GitWorkspaceAdapter;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 脚本化模型只决定工具动作；所有检查、冻结、Git 与 SQLite 接受都实际执行。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_AGENT_DOCKER", matches = "true")
class FullStackAgentSmokeTest {
    @TempDir
    Path temporary;
    private final ObjectMapper json = new ObjectMapper();

    private AgentProtocol.ToolCall call(String tool, Map<String, String> arguments) throws Exception {
        return new AgentProtocol.ToolCall(java.util.UUID.randomUUID().toString(), tool, json.writeValueAsString(arguments));
    }

    @ParameterizedTest
    @ValueSource(strings = {"npm", "pnpm"})
    void failedFullStackChecksAreCorrectedFrozenAndDeliveredWithoutTouchingOriginal(String manager) throws Exception {
        var fixture = new DockerAgentSandboxTest();
        fixture.temporary = Files.createDirectories(temporary.resolve("asset"));
        Path original = fixture.fixture();
        Path resources = Path.of("src/test/resources/agent/fullstack/frontend");
        try (var paths = Files.walk(resources)) {
            for (Path path : paths.toList()) {
                Path destination = original.resolve("frontend").resolve(resources.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
        if (manager.equals("npm")) {
            Files.delete(original.resolve("frontend/pnpm-lock.yaml"));
            Path npm = Path.of("src/test/resources/agent/fullstack-npm");
            Files.copy(npm.resolve("package.json"), original.resolve("frontend/package.json"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(npm.resolve("package-lock.json"), original.resolve("frontend/package-lock.json"));
        }
        Files.writeString(original.resolve(".gitignore"), "target/\nnode_modules/\ndist/\ncoverage/\n");
        // 固定夹具的原始字节，避免宿主系统 autocrlf 与受控 Git 探针采用不同转换规则。
        Files.writeString(original.resolve(".gitattributes"), "* -text\n");
        fixture.git(original, "add", ".");
        fixture.git(original, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-qm", "fullstack");
        String baseline = fixture.git(original, "rev-parse", "HEAD");
        Path managed = Files.createDirectories(temporary.resolve("managed"));
        var workspace = new GitWorkspaceAdapter(managed);
        Path official = Path.of(workspace.provision(new WorkspaceRef(original.toString()), baseline, "copy").workspace().value());
        var database = new SqliteAgentStoreTest();
        database.temporary = temporary;
        database.setup(baseline, "standalone");
        database.sql("UPDATE software_asset SET location='" + original.toString().replace("'", "''") + "'");
        database.sql("UPDATE working_copy SET location='" + official.toString().replace("'", "''") + "'");
        database.sql("INSERT INTO product_direction(id,user_profile_id,user_profile_revision,title,problem,target_product,"
                + "user_fit,differentiation,technical_value,estimated_complexity,status,flow_id) VALUES"
                + "('direction','user',1,'Counter','problem','target','fit','difference','value','small','SELECTED','standalone')");
        var eligibility = CodingAgentServiceTest.eligibility(baseline, official, workspace, original,
                PreparationProvenance.unchanged(baseline), true);
        AgentProcess process = org.mockito.Mockito.spy(new AgentProcess());
        var sandbox = new DockerAgentSandbox(DockerAgentSandboxTest.properties(temporary.resolve("runs")),
                new DeterministicRepositorySecretPolicy(), process);
        List<AgentProtocol.ToolCall> actions = List.of(
                call("read_file", Map.of("path", "src/main/java/example/Counter.java")),
                call("read_file", Map.of("path", "frontend/src/counter.ts")),
                call("run_checks", Map.of("goal", "test")),
                call("edit_file", Map.of("path", "frontend/package.json", "old_text", "\"vue\": \"3.5.17\"",
                        "new_text", "\"vue\": \"3.5.18\"")),
                call("run_frontend_checks", Map.of("goal", "test")),
                call("edit_file", Map.of("path", "frontend/package.json", "old_text", "\"vue\": \"3.5.18\"",
                        "new_text", "\"vue\": \"3.5.17\"")),
                call("run_frontend_checks", Map.of("goal", "test")),
                call("edit_file", Map.of("path", "src/main/java/example/Counter.java", "old_text", "return n;", "new_text", "return n + 1;")),
                call("edit_file", Map.of("path", "frontend/src/counter.ts", "old_text", "return value;", "new_text", "return 'bad';")),
                call("run_frontend_checks", Map.of("goal", "typecheck")),
                call("edit_file", Map.of("path", "frontend/src/counter.ts", "old_text", "return 'bad';", "new_text", "return value + 1;")),
                call("run_checks", Map.of("goal", "test")),
                call("run_frontend_checks", Map.of("goal", "typecheck")),
                call("edit_file", Map.of("path", "frontend/src/App.vue", "old_text", "'./counter'", "new_text", "'./missing'")),
                call("run_frontend_checks", Map.of("goal", "build")),
                call("edit_file", Map.of("path", "frontend/src/App.vue", "old_text", "'./missing'", "new_text", "'./counter'")),
                call("run_frontend_checks", Map.of("goal", "build")),
                call("run_frontend_checks", Map.of("goal", "test")));
        AtomicInteger turn = new AtomicInteger();
        List<String> observations = new ArrayList<>();
        AgentModelPort model = (history, tools) -> {
            observations.clear();
            history.stream().filter(message -> message.role().equals("tool"))
                    .forEach(message -> observations.add(message.text()));
            int index = turn.getAndIncrement();
            return index < actions.size() ? new AgentProtocol.Reply("", List.of(actions.get(index)), 1L)
                    : new AgentProtocol.Reply("{\"status\":\"completed\",\"summary\":\"Counter increment\"}", List.of(), 1L);
        };
        String candidateId;
        try (var agent = new CodingAgentService(eligibility, database.store, model, sandbox,
                new DeterministicRepositorySecretPolicy(), AgentProtocol.Limits.defaults())) {
            var run = agent.start("plan", "step", 21);
            var terminal = CodingAgentServiceTest.awaitTerminal(agent, run.id(), 600);
            assertEquals(AgentRunState.SUCCEEDED, terminal.state(), terminal.reason()
                    + "\n" + String.join("\n", observations));
            candidateId = terminal.candidateId();
        }
        String observed = String.join("\n", observations);
        assertTrue(observed.contains("TESTS_FAILED"));
        assertTrue(observed.contains("TYPECHECK_FAILED"));
        assertTrue(observed.contains("FRONTEND_BUILD_FAILED"));
        assertTrue(observed.contains("LOCKFILE_INVALID"));
        assertTrue(observed.contains("TESTS_PASSED"));
        var candidate = database.store.candidate(candidateId);
        assertTrue(candidate.changedPaths().contains("frontend/src/counter.ts"));
        assertTrue(candidate.changedPaths().contains("src/main/java/example/Counter.java"));
        assertEquals(2, json.readTree(candidate.testEvidence()).path("evidenceVersion").asInt());
        assertFalse(candidate.diff().contains("INSTALL_SCRIPT_RAN"));
        List<List<String>> containers = org.mockito.Mockito.mockingDetails(process).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("run"))
                .map(invocation -> ((List<?>) invocation.getArgument(0)).stream().map(Object::toString).toList())
                .filter(command -> command.contains("run") && command.contains("--name")).toList();
        assertEquals(2, containers.stream().filter(command -> command.stream()
                .anyMatch(value -> value.endsWith("-frontend-prepare"))).count());
        for (List<String> command : containers) {
            if (command.stream().anyMatch(value -> value.endsWith("-frontend-check"))) {
                assertEquals("none", command.get(command.indexOf("--network") + 1));
                assertTrue(command.contains("--read-only"));
                assertTrue(command.stream().anyMatch(value -> value.endsWith("target=/dependencies,readonly")));
            }
        }
        assertEquals(baseline, fixture.git(official, "rev-parse", "HEAD"));
        assertEquals("", fixture.git(official, "status", "--porcelain"));
        var deliveries = new SqliteDeliveryStore(database.source, eligibility);
        try (var delivery = new CandidateDeliveryService(database.store, deliveries, workspace, eligibility)) {
            assertEquals(Delivery.State.COMPLETED, delivery.deliver(candidateId).state());
        }
        assertTrue(Files.readString(official.resolve("frontend/src/counter.ts")).contains("value + 1"));
        String officialStatus = fixture.git(official, "status", "--porcelain");
        var proof = json.createObjectNode().put("packageManager", manager).put("candidateId", candidateId)
                .put("baseline", baseline).put("revision", fixture.git(official, "rev-parse", "HEAD"))
                .put("delivery", "COMPLETED").put("officialStatus", officialStatus)
                .put("originalStatus", fixture.git(original, "status", "--porcelain"));
        proof.set("evidence", json.readTree(candidate.testEvidence()));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/fullstack-smoke-" + manager + ".json"), proof.toPrettyString());
        assertEquals("", officialStatus);
        assertFalse(Files.exists(official.resolve("frontend/node_modules")));
        assertEquals(baseline, fixture.git(original, "rev-parse", "HEAD"));
        assertTrue(fixture.git(original, "status", "--porcelain").isEmpty());
    }
}
