package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 只检查未经修改的公开基线；不生成补丁、Candidate 或正式提交。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_REAL_BASELINE", matches = "true")
class PublicRepositoryBaselineLiveTest {
    @Test
    void unmodifiedPublicBaselineExecutesPositiveMavenTestsInControlledDocker() throws Exception {
        Path source = Path.of(System.getenv("DELVEFORGE_REAL_SOURCE")).toAbsolutePath();
        Path evidence = Path.of(System.getenv("DELVEFORGE_REAL_EVIDENCE")).toAbsolutePath();
        Files.createDirectories(evidence);
        String baseline = git(source, "rev-parse", "HEAD");
        assertEquals("", git(source, "status", "--porcelain"));
        String owner = System.getenv().getOrDefault("DELVEFORGE_AGENT_OWNER", "df-real-e2e-validation");
        var properties = new AgentSandboxProperties(evidence.resolve("baseline-runs"), "docker",
                Map.of(17, System.getenv("DELVEFORGE_AGENT_IMAGE_17"),
                        21, System.getenv("DELVEFORGE_AGENT_IMAGE_21")), 17, owner,
                System.getenv("DELVEFORGE_MAVEN_CACHE"), "2g", "2", 256);
        var sandbox = new DockerAgentSandbox(properties, new DeterministicRepositorySecretPolicy());
        String run = UUID.randomUUID().toString();
        var defaults = AgentProtocol.Limits.defaults();
        var limits = new AgentProtocol.Limits(defaults.maxContextChars(), defaults.maxOutputBytes(), defaults.maxPatchBytes(),
                defaults.maxFileBytes(), defaults.maxCandidateBytes(), defaults.maxFiles(),
                600);
        try {
            sandbox.prepare(new AgentSandboxPort.Task(run, "baseline-only", "baseline-only", "baseline-only",
                    source.toString(), evidence.resolve("unused-original").toString(), baseline,
                    "Unmodified upstream baseline check", "complete", "none", 17), limits, () -> false);
            String result = sandbox.tool(run, new AgentProtocol.ToolCall("baseline-test", "exec_command",
                    "{\"command\":\"mvn -B test\",\"directory\":\"complete\",\"verification_scope\":\"Upstream reactor tests\",\"check_kind\":\"test\"}"), () -> false);
            var json = new ObjectMapper();
            Files.writeString(evidence.resolve("baseline.json"), json.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of("baseline", baseline, "identity", sandbox.identity(run),
                            "check", json.readTree(result))));
            assertTrue(result.contains("COMMAND_SUCCEEDED"), result);
            assertTrue(json.readTree(result).path("result").path("executedTests").asInt() > 0, result);
            assertEquals(baseline, git(source, "rev-parse", "HEAD"));
            assertEquals("", git(source, "status", "--porcelain"));
        } finally {
            sandbox.cleanup(run);
        }
    }

    private static String git(Path directory, String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(java.util.List.of("git", "-C", directory.toString()));
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output.strip();
    }
}
