package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.infrastructure.workspace.GitWorkspaceAdapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实受控 Docker/Maven；不提供 Provider 凭据或调用模型。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_REPOSITORY_COMPATIBILITY_DOCKER", matches = "true")
class RepositoryPreparationDockerTest {
    @TempDir
    Path temporary;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void finTrackPreparedSourceEntersRealSandboxAndClassifiesActualBaselineCheck() throws Exception {
        Path source = Path.of(System.getProperty("delveforge.compatibility.fintrack")).toAbsolutePath();
        var fixture = new DockerAgentSandboxTest();
        fixture.temporary = temporary;
        String a = fixture.git(source, "rev-parse", "HEAD").strip();
        String before = com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories.runGit(
                source, "status", "--porcelain");
        assertTrue(before.isBlank());
        var workspace = new GitWorkspaceAdapter(temporary.resolve("managed"));
        var prepared = workspace.provision(new WorkspaceRef(source.toString()), a, "fintrack");
        String id = UUID.randomUUID().toString();
        var properties = properties(temporary.resolve("runs"));
        var sandbox = new DockerAgentSandbox(properties, new DeterministicRepositorySecretPolicy());
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "plan", "step", "copy", prepared.workspace().value(),
                    source.toString(), prepared.revision(), "baseline compatibility", "Maven", "none", null),
                    AgentProtocol.Limits.defaults(), () -> false);
            assertEquals(17, sandbox.identity(id).targetJdk());
            var response = json.readTree(sandbox.tool(id, new AgentProtocol.ToolCall("baseline", "exec_command", json.writeValueAsString(Map.of("command", "mvn -B test", "directory", "backend", "verification_scope", "Backend tests", "check_kind", "test"))),
                    () -> false));
            assertTrue(response.path("result").path("exitCode").isIntegralNumber(), response.toString());
            var result = response.path("result");
            assertEquals("backend", result.path("directory").asText());
            assertTrue(Set.of("COMMAND_SUCCEEDED", "COMMAND_FAILED").contains(result.path("classification").asText()), response.toString());
            Path evidence = source.getParent().resolve("fintrack-docker-baseline.json");
            Files.writeString(evidence, json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "original", a, "prepared", prepared.revision(), "identity", sandbox.identity(id),
                    "actualControlledMavenResult", response, "paidProviderCalls", 0)));
            assertEquals(a, fixture.git(source, "rev-parse", "HEAD").strip());
            assertEquals(before, com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories.runGit(
                    source, "status", "--porcelain"));
            assertTrue(fixture.git(Path.of(prepared.workspace().value()), "status", "--porcelain").isBlank());
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void actualDockerCandidatePreservesLargeOpaqueAssetsAndUsesPreparedBaseline() throws Exception {
        var fixture = new DockerAgentSandboxTest();
        fixture.temporary = temporary;
        Path source = fixture.fixture();
        byte[] bytes = new byte[6291456];
        bytes[0] = (byte) 255;
        for (int i = 0; i < 3; i++) {
            Files.write(source.resolve("asset-" + i + ".bin"), bytes);
        }
        Path compiled = source.resolve("target/classes");
        Files.createDirectories(compiled);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", compiled.toString(),
                source.resolve("src/main/java/example/Counter.java").toString()));
        fixture.git(source, "add", ".");
        fixture.git(source, "add", "-f", "target/classes/example/Counter.class");
        fixture.git(source, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "tracked output and assets");
        String a = fixture.git(source, "rev-parse", "HEAD").strip();
        var workspace = new GitWorkspaceAdapter(temporary.resolve("managed"));
        var prepared = workspace.provision(new WorkspaceRef(source.toString()), a, "copy");
        assertNotEquals(a, prepared.revision());
        var sandbox = new DockerAgentSandbox(properties(temporary.resolve("runs")), new DeterministicRepositorySecretPolicy());
        String id = UUID.randomUUID().toString();
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "plan", "step", "copy", prepared.workspace().value(),
                    source.toString(), prepared.revision(), "fix counter", "Counter", "increment", null),
                    AgentProtocol.Limits.defaults(), () -> false);
            var applied = json.readTree(sandbox.tool(id, new AgentProtocol.ToolCall("edit", "edit_file",
                    json.writeValueAsString(Map.of("path", "src/main/java/example/Counter.java",
                            "old_text", "return n;", "new_text", "return n + 1;"))), () -> false));
            assertTrue(applied.path("ok").asBoolean(), applied.toString());
            var checked = json.readTree(sandbox.tool(id, new AgentProtocol.ToolCall("test", "exec_command", json.writeValueAsString(Map.of("command", "mvn -B test", "verification_scope", "Repository tests", "check_kind", "test"))), () -> false));
            assertEquals("COMMAND_SUCCEEDED", checked.path("result").path("classification").asText(), checked.toString());
            assertTrue(checked.path("result").path("executedTests").asInt() > 0);
            var candidate = sandbox.finish(id, () -> false);
            assertEquals(prepared.revision(), candidate.baseline());
            assertEquals(java.util.List.of("src/main/java/example/Counter.java"), candidate.changedPaths());
            assertFalse(candidate.diff().contains("asset-"));
            assertEquals(a, fixture.git(source, "rev-parse", "HEAD").strip());
            assertTrue(fixture.git(source, "status", "--porcelain").isBlank());
            String delivered = deliver(candidate, prepared, source);
            assertEquals(delivered + " " + prepared.revision(), fixture.git(Path.of(prepared.workspace().value()),
                    "rev-list", "--parents", "-n", "1", delivered).strip());
            assertEquals(candidate.expectedTree(), fixture.git(Path.of(prepared.workspace().value()),
                    "rev-parse", delivered + "^{tree}").strip());
            Files.writeString(Path.of(System.getProperty("delveforge.compatibility.evidence", source.getParent().toString()))
                    .resolve("opaque-candidate-evidence.json"), json.writeValueAsString(Map.of(
                    "original", a, "prepared", prepared.revision(), "delivered", delivered, "candidate", Map.of(
                            "id", candidate.id(), "baseline", candidate.baseline(), "expectedTree", candidate.expectedTree(),
                            "sha256", candidate.sha256(), "changedPaths", candidate.changedPaths(),
                            "testEvidence", candidate.testEvidence()),
                    "actualChecks", checked, "opaqueResourceBytes", bytes.length * 3L)));
        } finally {
            sandbox.cleanup(id);
        }
    }

    private String deliver(com.ayywl.delveforge.domain.agent.Candidate candidate,
            com.ayywl.delveforge.application.port.workspace.PreparedWorkspace prepared, Path original) throws Exception {
        var source = new org.sqlite.SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + temporary.resolve("docker-delivery.db"));
        org.flywaydb.core.Flyway.configure().dataSource(source).load().migrate();
        PreparedCandidateDeliveryTest.execute(source,
                "INSERT INTO software_asset VALUES('asset','GIT_REPOSITORY','USER_SPECIFIED',?,1,'MIT','ALLOWED',0)",
                original.toString());
        PreparedCandidateDeliveryTest.execute(source,
                "INSERT INTO product_direction(id,user_profile_id,user_profile_revision,title,problem,target_product,"
                + "user_fit,differentiation,technical_value,estimated_complexity,status,flow_id) VALUES"
                + "('direction','user',1,'title','problem','target','fit','diff','value','small','SELECTED','standalone')");
        PreparedCandidateDeliveryTest.execute(source,
                "INSERT INTO working_copy(id,source_asset_id,source_revision,location,current_revision,last_verified_revision,"
                + "status,prepared_revision,preparation_json) VALUES('copy','asset',?,?,?,?,'READY',?,?)",
                prepared.provenance().originalRevision(), prepared.workspace().value(), prepared.revision(), prepared.revision(),
                prepared.revision(), com.ayywl.delveforge.infrastructure.persistence.workingcopy.PreparationProvenanceJson
                        .write(prepared.provenance()));
        PreparedCandidateDeliveryTest.execute(source,
                "INSERT INTO evolution_plan(id,product_direction_id,base_asset_id,base_repository_profile_id,working_copy_id,"
                + "current_summary,target_problem,target_product,target_differentiation,status,flow_id) VALUES"
                + "('plan','direction','asset','profile','copy','current','problem','target','difference','ACTIVE','standalone')");
        PreparedCandidateDeliveryTest.execute(source,
                "INSERT INTO evolution_step(id,plan_id,position,goal,scope,status) VALUES('step','plan',0,'goal','scope','READY')");
        var agents = new SqliteAgentStore(source);
        var state = com.ayywl.delveforge.domain.agent.AgentRunState.CREATED;
        agents.reserve(new AgentProtocol.Run(candidate.runId(), "plan", "step", "copy", prepared.revision(), state,
                java.time.Instant.now(), null, null, null, false, false, 0, 0, null));
        agents.advance(candidate.runId(), state, com.ayywl.delveforge.domain.agent.AgentRunState.PREPARING, null);
        agents.advance(candidate.runId(), com.ayywl.delveforge.domain.agent.AgentRunState.PREPARING,
                com.ayywl.delveforge.domain.agent.AgentRunState.RUNNING, null);
        agents.advance(candidate.runId(), com.ayywl.delveforge.domain.agent.AgentRunState.RUNNING,
                com.ayywl.delveforge.domain.agent.AgentRunState.FINALIZING, null);
        agents.freeze(candidate);
        agents.release(candidate.runId(), false);
        Path copy = Path.of(prepared.workspace().value());
        var workspace = new GitWorkspaceAdapter(copy.getParent());
        var eligibility = CodingAgentServiceTest.eligibility(prepared.revision(), copy, workspace, original, prepared.provenance());
        try (var service = new com.ayywl.delveforge.application.agent.CandidateDeliveryService(agents,
                new SqliteDeliveryStore(source, eligibility), workspace, eligibility)) {
            var result = service.deliver(candidate.id());
            assertEquals(com.ayywl.delveforge.application.agent.Delivery.State.COMPLETED, result.state());
            return result.revision();
        }
    }

    private static AgentSandboxProperties properties(Path runs) {
        var existing = DockerAgentSandboxTest.properties(runs);
        return new AgentSandboxProperties(runs, existing.docker(), existing.images(), existing.defaultJdk(),
                "delveforge-compatibility-tests", "delveforge-compatibility-20261024-cache",
                existing.memory(), existing.cpus(), existing.pids());
    }
}
