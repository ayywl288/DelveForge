package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实进程验证 cwd、离线安装及 /bin/sh 退出码，不请求模型或访问正式运行。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_AGENT_DOCKER", matches = "true")
class CommandUsabilityDockerTest {
    @TempDir
    Path temporary;

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode exec(DockerAgentSandbox sandbox, String id, Map<String, ?> arguments) throws Exception {
        return json.readTree(sandbox.tool(id, new AgentProtocol.ToolCall(UUID.randomUUID().toString(),
                "exec_command", json.writeValueAsString(arguments)), () -> false));
    }

    @Test
    void nestedPathsSetupAndPortablePipelinePreserveActualProcessResults() throws Exception {
        Path original = Files.createDirectories(temporary.resolve("original"));
        Path nested = Files.createDirectories(original.resolve("nested"));
        Files.writeString(nested.resolve("item.txt"), "nested fixture\n");
        Files.writeString(nested.resolve("package.json"), "{\"name\":\"offline-fixture\",\"version\":\"1.0.0\",\"private\":true}");
        Files.writeString(original.resolve("failing.sh"), "printf 'BUILD FAILURE\\n'\nexit 7\n");
        Files.writeString(original.resolve(".gitignore"), "node_modules/\n");
        var git = new DockerAgentSandboxTest();
        git.git(original, "init", "--template=");
        git.git(original, "add", ".");
        git.git(original, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-qm", "fixture");
        String baseline = git.git(original, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        var sandbox = new DockerAgentSandbox(DockerAgentSandboxTest.properties(temporary.resolve("runs")),
                new DeterministicRepositorySecretPolicy());
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "plan", "step", "copy", original.toString(),
                    temporary.resolve("unmounted").toString(), baseline, "check fixture", "nested", "check", 21),
                    AgentProtocol.Limits.defaults(), () -> false);
            JsonNode root = exec(sandbox, id, Map.of("command", "cat nested/item.txt", "directory", "."));
            JsonNode relative = exec(sandbox, id, Map.of("command", "cat item.txt", "directory", "nested"));
            assertEquals(0, root.path("result").path("exitCode").asInt(-1));
            assertEquals("nested fixture\n", relative.path("result").path("stdout").asText());
            assertEquals(0, relative.path("result").path("exitCode").asInt(-1));
            JsonNode repeated = exec(sandbox, id, Map.of("command", "cat nested/item.txt", "directory", "nested"));
            assertFalse(repeated.path("ok").asBoolean());
            assertTrue(repeated.path("result").path("exitCode").asInt() != 0);
            JsonNode missing = exec(sandbox, id, Map.of("command", "touch must-not-exist", "directory", "missing"));
            assertEquals("DIRECTORY_NOT_FOUND", missing.path("reason").asText());
            assertTrue(missing.path("mutationNotStarted").asBoolean());
            assertFalse(missing.has("exitCode"));
            assertTrue(exec(sandbox, id, Map.of("command", "test ! -e must-not-exist")).path("ok").asBoolean());

            for (String setup : new String[]{
                    "npm install --package-lock-only --ignore-scripts --offline --no-audit --no-fund",
                    "npm ci --ignore-scripts --offline --no-audit --no-fund"}) {
                JsonNode result = exec(sandbox, id, Map.of("command", setup, "directory", "nested"));
                assertTrue(result.path("ok").asBoolean(), result.toString());
                assertEquals(0, result.path("result").path("exitCode").asInt(-1));
            }
            assertNotNull(sandbox.completionFeedback(id, () -> false), "Setup alone is not verification");

            JsonNode masked = exec(sandbox, id, Map.of("command", "sh failing.sh 2>&1 | grep BUILD"));
            assertEquals(0, masked.path("result").path("exitCode").asInt(-1));
            assertTrue(masked.path("result").path("stdout").asText().contains("BUILD FAILURE"));
            JsonNode preserved = exec(sandbox, id, Map.of("command",
                    "sh failing.sh > /tmp/df-check.log 2>&1; status=$?; grep BUILD /tmp/df-check.log; exit \"$status\"",
                    "verification_scope", "fixture failing check", "check_kind", "test"));
            assertEquals(7, preserved.path("result").path("exitCode").asInt(-1));
            assertFalse(preserved.path("ok").asBoolean());
            assertNotNull(sandbox.completionFeedback(id, () -> false));
            assertEquals(baseline, git.git(original, "rev-parse", "HEAD"));
            assertEquals("", git.git(original, "status", "--porcelain"));
        } finally {
            sandbox.cleanup(id);
        }
    }
}
