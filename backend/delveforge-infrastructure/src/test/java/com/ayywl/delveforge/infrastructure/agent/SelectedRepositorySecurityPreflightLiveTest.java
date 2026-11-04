package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 对显式指定的保留源执行真实安全预检；无模型替身，也不创建业务 Run 或修改官方副本。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_SELECTED_SECURITY_PREFLIGHT", matches = "true")
class SelectedRepositorySecurityPreflightLiveTest {
    @TempDir
    Path temporary;

    @Test
    void withheldExecutableInputsBlockActualDockerChecksWithoutChangingEitherRepository() throws Exception {
        Path original = Path.of(System.getProperty("delveforge.validation.source")).toRealPath();
        Path official = Path.of(System.getProperty("delveforge.validation.working-copy")).toRealPath();
        String revision = System.getProperty("delveforge.validation.revision");
        var directories = Arrays.asList(System.getProperty("delveforge.validation.directories").split(","));
        var git = new DockerAgentSandboxTest();
        String originalStatus = git.git(original, "--no-optional-locks", "status", "--porcelain");
        String officialStatus = git.git(official, "--no-optional-locks", "status", "--porcelain");
        assertEquals(revision, git.git(original, "rev-parse", "HEAD"));
        assertEquals(revision, git.git(official, "rev-parse", "HEAD"));
        var properties = new AgentSandboxProperties(temporary.resolve("runs"), "docker",
                Map.of(17, System.getenv("DELVEFORGE_AGENT_IMAGE_17"),
                        21, System.getenv("DELVEFORGE_AGENT_IMAGE_21")),
                17, "delveforge-local-agent", "delveforge-maven-central-cache", "2g", "2", 256);
        var sandbox = new DockerAgentSandbox(properties, new DeterministicRepositorySecretPolicy());
        var json = new ObjectMapper();
        var evidence = json.createObjectNode().put("kind", "NON_LLM_ADAPTER_PROBE")
                .put("revision", revision).put("providerRequests", 0);
        String id = UUID.randomUUID().toString();
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "validation", "validation", "validation",
                    official.toString(), original.toString(), revision, "Observe preserved-source safety",
                    "Read-only baseline checks", "No source edits", 17, null, directories),
                    AgentProtocol.Limits.defaults(), () -> false);
            evidence.set("identity", json.valueToTree(sandbox.identity(id)));
            var capabilities = json.readTree(sandbox.checkCapabilities(id, () -> false));
            evidence.set("capabilities", capabilities);
            assertTrue(capabilities.path("sourceView").path("withheldSourceCount").asInt() > 0);
            var result = sandbox.preflight(id, () -> false);
            evidence.put("outcome", result.outcome());
            evidence.set("diagnostic", json.readTree(result.diagnostic()));
            assertEquals("ENVIRONMENT_BLOCKED", result.outcome());
            assertTrue(result.diagnostic().contains("withheld"));
        } finally {
            sandbox.cleanup(id);
            assertEquals(revision, git.git(original, "rev-parse", "HEAD"));
            assertEquals(revision, git.git(official, "rev-parse", "HEAD"));
            assertEquals(originalStatus, git.git(original, "--no-optional-locks", "status", "--porcelain"));
            assertEquals(officialStatus, git.git(official, "--no-optional-locks", "status", "--porcelain"));
            evidence.put("originalUnchanged", true).put("officialUnchanged", true);
            Path output = Path.of("target/selected-repository-security-preflight.json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, evidence.toPrettyString());
        }
    }
}
