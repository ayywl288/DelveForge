package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.infrastructure.workspace.GitWorkspaceAdapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 固定公开样本的分阶段评估；失败保留为结果，不调用模型或生成 Candidate。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_PUBLIC_PREFLIGHT", matches = "true")
class PublicRepositoryPreflightLiveTest {
    @TempDir
    Path temporary;

    @ParameterizedTest
    @CsvSource({
            "gs-rest-service,admission-boundary-20261102/repositories,e15fec6773dc1eb6028055630e3e68cbd8653240,complete",
            "homer,compatibility-cycle-20261102/repositories,daa017dfe1ea8d0875697aede091319b6134bb4b,.",
            "sa-token,admission-boundary-20261102/repositories,cb191342372cec5045a8c26f38c61a6ea2147ff8,sa-token-demo/sa-token-demo-remember-me/sa-token-demo-remember-me-server|sa-token-demo/sa-token-demo-remember-me/page_project"
    })
    void recordEveryStageIncludingBlockedSamples(String name, String parent, String expected, String entries)
            throws Exception {
        Path source = Path.of("../../target", parent, name).toAbsolutePath().normalize();
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("delveforge.public.sample") == null
                || name.equals(System.getProperty("delveforge.public.sample")), "Explicit single-sample regression rerun");
        var fixture = new DockerAgentSandboxTest();
        String before = fixture.git(source, "--no-optional-locks", "status", "--porcelain");
        assertEquals(expected, fixture.git(source, "rev-parse", "HEAD"));
        var json = new ObjectMapper();
        var evidence = json.createObjectNode().put("sample", name).put("revision", expected)
                .put("modelRequests", 0).put("tokens", 0).put("admission", "NOT_RUN")
                .put("preparation", "NOT_RUN").put("execution", "NOT_RUN").put("sourceResult", "UNPROVEN");
        var workspace = new GitWorkspaceAdapter(temporary.resolve("managed"));
        var sandbox = new DockerAgentSandbox(DockerAgentSandboxTest.properties(temporary.resolve("runs")),
                new DeterministicRepositorySecretPolicy());
        String id = UUID.randomUUID().toString();
        String stage = "admission";
        try {
            var provisioned = workspace.provision(new WorkspaceRef(source.toString()), expected, "sample");
            evidence.put("admission", "PREPARED");
            evidence.put("preparedRevision", provisioned.revision());
            stage = "preparation";
            sandbox.prepare(new AgentSandboxPort.Task(id, "sample", "sample", "sample",
                    provisioned.workspace().value(), source.toString(), provisioned.revision(),
                    "Public fixed baseline", entries, "No edits", null, null, List.of(entries.split("\\|"))),
                    AgentProtocol.Limits.defaults(), () -> false);
            evidence.put("preparation", "SANDBOX_PREPARED");
            evidence.set("identity", json.valueToTree(sandbox.identity(id)));
            stage = "execution";
            var result = sandbox.preflight(id, () -> false);
            evidence.put("execution", result.outcome());
            evidence.set("diagnostic", json.readTree(result.diagnostic()));
            evidence.put("sourceResult", result.outcome().equals("READY") ? "OBSERVED_CHECKS_PASSED"
                    : result.outcome().equals("READY_BASELINE_FAILURE") ? "OBSERVED_BASELINE_FAILURE" : "UNPROVEN");
        } catch (RuntimeException failure) {
            evidence.put(stage, "BLOCKED").put("failureType", failure.getClass().getSimpleName())
                    .put("reason", failure.getMessage());
        } finally {
            sandbox.cleanup(id);
            assertEquals(expected, fixture.git(source, "rev-parse", "HEAD"));
            assertEquals(before, fixture.git(source, "--no-optional-locks", "status", "--porcelain"));
            evidence.put("sourceUnchanged", true);
            Path directory = Files.createDirectories(Path.of("target/preflight-public"));
            Files.writeString(directory.resolve(name + ".json"), evidence.toPrettyString());
        }
    }
}
