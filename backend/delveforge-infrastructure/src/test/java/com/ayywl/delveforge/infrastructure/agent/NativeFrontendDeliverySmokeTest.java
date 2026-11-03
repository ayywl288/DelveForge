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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 无 POM、无 Vitest 的真实脚本回归；模型只提供动作，检查、冻结和正式交付均实际执行。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_AGENT_DOCKER", matches = "true")
class NativeFrontendDeliverySmokeTest {
    @TempDir
    Path temporary;
    private final ObjectMapper json = new ObjectMapper();

    private AgentProtocol.ToolCall call(String tool, Map<String, String> args) throws Exception {
        return new AgentProtocol.ToolCall(UUID.randomUUID().toString(), tool, json.writeValueAsString(args));
    }

    @ParameterizedTest
    @ValueSource(strings = {"npm", "pnpm"})
    void nativeFailureIsCorrectedRecheckedFrozenAndDelivered(String manager) throws Exception {
        Path resources = Path.of("src/test/resources/agent/native-frontend");
        Path original = Files.createDirectories(temporary.resolve("original"));
        try (var paths = Files.walk(resources.resolve("npm"))) {
            for (Path path : paths.toList()) {
                Path target = original.resolve(resources.resolve("npm").relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
        if (manager.equals("pnpm")) {
            Files.delete(original.resolve("package-lock.json"));
            Files.copy(resources.resolve("pnpm/package.json"), original.resolve("package.json"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(resources.resolve("pnpm/pnpm-lock.yaml"), original.resolve("pnpm-lock.yaml"));
        }
        // 原样目录入口在固定 Node 中会真实失败；随后由工具动作改成有效文件入口。
        Files.writeString(original.resolve("package.json"), Files.readString(original.resolve("package.json"))
                .replace("node --test tests/counter.test.mjs", "node --test tests/"));
        Files.writeString(original.resolve(".gitattributes"), "* -text\n");
        Files.writeString(original.resolve(".gitignore"), "node_modules/\ndist/\n");
        String secretFixture = "fixture-" + UUID.randomUUID();
        Files.writeString(original.resolve(".env.local"), "DB_PASSWORD=" + secretFixture + "\n");
        var git = new DockerAgentSandboxTest();
        git.git(original, "init", "--template=");
        git.git(original, "add", ".");
        git.git(original, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-qm", "native fixture");
        String baseline = git.git(original, "rev-parse", "HEAD");
        var workspace = new GitWorkspaceAdapter(Files.createDirectories(temporary.resolve("managed")));
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
        var sandbox = new DockerAgentSandbox(DockerAgentSandboxTest.properties(temporary.resolve("runs")),
                new DeterministicRepositorySecretPolicy());
        List<AgentProtocol.ToolCall> actions = List.of(
                call("run_frontend_checks", Map.of("goal", "test")),
                call("list_files", Map.of("path", "node_modules/.bin")),
                call("read_file", Map.of("path", "node_modules/vitest/vitest.mjs")),
                call("search_code", Map.of("path", "node_modules", "query", "vitest")),
                call("edit_file", Map.of("path", "package.json", "old_text", "node --test tests/",
                        "new_text", "node --test tests/counter.test.mjs")),
                call("run_frontend_checks", Map.of("goal", "test")),
                call("edit_file", Map.of("path", "src/counter.js", "old_text", "return value;", "new_text", "return value + 1;")),
                call("edit_file", Map.of("path", "tests/isolation.test.mjs", "old_text", "", "new_text", """
                        import { test } from 'node:test';
                        import assert from 'node:assert/strict';
                        import fs from 'node:fs';
                        import net from 'node:net';
                        test('untrusted scripts have no host secrets, network or authoritative writes', async () => {
                            assert.equal(process.env.DEEPSEEK_API_KEY, undefined);
                            for (const path of ['/source/.env.local', '/workspace/.env.local', '/var/run/docker.sock', '/host']) {
                                assert.equal(fs.existsSync(path), false);
                            }
                            assert.throws(() => fs.writeFileSync('/source/src/counter.js', 'bad'));
                            assert.throws(() => fs.writeFileSync('/opt/delveforge/injected', 'bad'));
                            assert.throws(() => fs.writeFileSync('/dependencies/.df-frontend-dependencies/package.json', '{}'));
                            await new Promise((resolve, reject) => {
                                const socket = net.connect({host: '1.1.1.1', port: 443});
                                socket.on('connect', () => { socket.destroy(); reject(new Error('network was available')); });
                                socket.on('error', resolve);
                                socket.setTimeout(1000, () => { socket.destroy(); resolve(); });
                            });
                        });
                        """)),
                call("edit_file", Map.of("path", "package.json", "old_text", "node --test tests/counter.test.mjs",
                        "new_text", "node --test tests/*.test.mjs")),
                call("run_frontend_checks", Map.of("goal", "test")),
                call("run_frontend_checks", Map.of("goal", "build")));
        AtomicInteger turn = new AtomicInteger();
        List<String> observed = new ArrayList<>();
        AgentModelPort model = (history, tools) -> {
            observed.clear();
            history.stream().filter(message -> message.role().equals("tool")).forEach(message -> observed.add(message.text()));
            int index = turn.getAndIncrement();
            return index < actions.size() ? new AgentProtocol.Reply("", List.of(actions.get(index)), 1L)
                    : new AgentProtocol.Reply("{\"status\":\"completed\",\"summary\":\"Correct due-date offset\"}", List.of(), 1L);
        };
        String candidateId;
        try (var agent = new CodingAgentService(eligibility, database.store, model, sandbox,
                new DeterministicRepositorySecretPolicy(), AgentProtocol.Limits.defaults())) {
            var run = agent.start("plan", "step", 21);
            var terminal = CodingAgentServiceTest.awaitTerminal(agent, run.id(), 1200);
            assertEquals(AgentRunState.SUCCEEDED, terminal.state(), terminal.reason() + "\n" + String.join("\n", observed));
            candidateId = terminal.candidateId();
        }
        var candidate = database.store.candidate(candidateId);
        assertTrue(String.join("\n", observed).contains("CHECK_MATERIAL_UNAVAILABLE"));
        var evidence = json.readTree(candidate.testEvidence());
        assertEquals(3, evidence.path("evidenceVersion").asInt());
        assertEquals("TESTS_FAILED", evidence.path("checks").get(0).path("classification").asText());
        assertEquals("TESTS_FAILED", evidence.path("checks").get(1).path("classification").asText());
        assertEquals(2, evidence.path("checks").get(1).path("executedTests").asInt());
        assertEquals(1, evidence.path("checks").get(1).path("failures").asInt());
        var passed = evidence.path("checks").get(2);
        assertEquals("TESTS_PASSED", passed.path("classification").asText());
        assertEquals("node-junit", passed.path("runner").asText());
        assertEquals(3, passed.path("executedTests").asInt());
        assertEquals(evidence.path("finalSnapshot").asText(), passed.path("snapshot").asText());
        assertNotEquals(evidence.path("checks").get(1).path("snapshot").asText(), passed.path("snapshot").asText());
        assertFalse(candidate.testEvidence().contains("MAVEN"));
        assertFalse(Files.exists(official.resolve("pom.xml")));
        assertEquals(baseline, git.git(official, "rev-parse", "HEAD"));
        assertEquals("", git.git(official, "status", "--porcelain"));
        assertEquals("DB_PASSWORD=" + secretFixture + "\n", Files.readString(official.resolve(".env.local")));
        assertFalse(String.join("\n", observed).contains(secretFixture));
        var deliveries = new SqliteDeliveryStore(database.source, eligibility);
        try (var delivery = new CandidateDeliveryService(database.store, deliveries, workspace, eligibility)) {
            assertEquals(Delivery.State.COMPLETED, delivery.deliver(candidateId).state());
        }
        String revision = git.git(official, "rev-parse", "HEAD");
        assertEquals(baseline, git.git(official, "rev-parse", "HEAD^"));
        assertEquals(candidate.expectedTree(), git.git(official, "rev-parse", "HEAD^{tree}"));
        assertTrue(Files.readString(official.resolve("src/counter.js")).contains("return value + 1;"));
        assertEquals(baseline, git.git(original, "rev-parse", "HEAD"));
        assertEquals("", git.git(original, "status", "--porcelain"));
        assertEquals("", git.git(official, "status", "--porcelain"));
        var proof = json.createObjectNode().put("manager", manager).put("candidateId", candidateId)
                .put("baseline", baseline).put("revision", revision).put("delivery", "COMPLETED");
        proof.set("evidence", evidence);
        Files.writeString(Path.of("target/native-frontend-delivery-" + manager + ".json"), proof.toPrettyString());
    }
}
