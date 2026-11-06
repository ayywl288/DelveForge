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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 固定公开样本的分阶段评估；失败保留为结果，不调用模型或生成 Candidate。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_PUBLIC_PREFLIGHT", matches = "true")
class PublicRepositoryPreflightLiveTest {
    @TempDir
    Path temporary;

    static Stream<Arguments> samples() {
        String source = System.getProperty("delveforge.public.source");
        if (source != null) {
            return Stream.of(Arguments.of(System.getProperty("delveforge.public.name"), source,
                    System.getProperty("delveforge.public.revision"), System.getProperty("delveforge.public.entries", "")));
        }
        return Stream.of(
                Arguments.of("gs-rest-service", "../../target/admission-boundary-20261102/repositories/gs-rest-service",
                        "e15fec6773dc1eb6028055630e3e68cbd8653240", "complete"),
                Arguments.of("homer", "../../target/compatibility-cycle-20261102/repositories/homer",
                        "daa017dfe1ea8d0875697aede091319b6134bb4b", "."),
                Arguments.of("sa-token", "../../target/admission-boundary-20261102/repositories/sa-token",
                        "cb191342372cec5045a8c26f38c61a6ea2147ff8",
                        "sa-token-demo/sa-token-demo-remember-me/sa-token-demo-remember-me-server|sa-token-demo/sa-token-demo-remember-me/page_project"));
    }

    @ParameterizedTest
    @MethodSource("samples")
    void recordEveryStageIncludingBlockedSamples(String name, String location, String expected, String entries)
            throws Exception {
        org.junit.jupiter.api.Assertions.assertTrue(name.matches("[a-z0-9-]+"));
        Path source = Path.of(location).toAbsolutePath().normalize();
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
                    "Public fixed baseline", entries, "No edits", Integer.getInteger("delveforge.public.jdk"), null,
                    entries.isBlank() ? List.of() : List.of(entries.split("\\|"))),
                    AgentProtocol.Limits.defaults(), () -> false);
            evidence.put("preparation", "SANDBOX_PREPARED");
            evidence.set("identity", json.valueToTree(sandbox.identity(id)));
            stage = "execution";
            var milestones = evidence.putArray("milestones");
            var result = sandbox.preflight(id, () -> false, progress -> {
                milestones.add(json.valueToTree(progress));
                System.out.println("PUBLIC_PREFLIGHT " + name + " " + progress.stage() + " " + progress.status()
                        + " " + progress.classification() + " " + progress.durationMs());
            });
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
            evidence.put("cleanup", "COMPLETED");
            Path directory = Files.createDirectories(Path.of("target/preflight-public"));
            Files.writeString(directory.resolve(name + ".json"), evidence.toPrettyString());
        }
    }
}
