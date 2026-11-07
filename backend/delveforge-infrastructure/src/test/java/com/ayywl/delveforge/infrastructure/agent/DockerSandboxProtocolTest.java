package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentPolicyRejection;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

class DockerSandboxProtocolTest {
    @TempDir
    Path temporary;
    private final ObjectMapper json = new ObjectMapper();
    private final AgentProcess process = new AgentProcess();

    @Test
    void sourceChangeDiagnosticsAreValidatedAndNeverReplaceThePolicyRejection() throws Exception {
        var before = new AgentPolicyRejection.SourceFileState("100644", "a".repeat(64));
        var after = new AgentPolicyRejection.SourceFileState("100644", "b".repeat(64));
        var file = new AgentPolicyRejection.SourceChange("src/Example.java",
                AgentPolicyRejection.SourceChangeType.CONTENT, before, after, false);
        var changes = new AgentPolicyRejection.SourceChanges("c".repeat(64), "d".repeat(64),
                1, 1, List.of(file), false);
        var response = json.createObjectNode().put("ok", false).put("classification", "POLICY_REJECTED")
                .put("policyRule", "CHECK_MUTATED_SOURCE");
        response.set("sourceChanges", json.valueToTree(changes));
        AgentPolicyRejection valid = assertThrows(AgentPolicyRejection.class,
                () -> DockerAgentSandbox.rejectPolicy(response, AgentPolicyRejection.Stage.TOOL_EXECUTION));
        assertEquals(changes, valid.sourceChanges());
        ((com.fasterxml.jackson.databind.node.ObjectNode) response.path("sourceChanges").path("files").get(0))
                .put("path", "../private-source");
        AgentPolicyRejection invalid = assertThrows(AgentPolicyRejection.class,
                () -> DockerAgentSandbox.rejectPolicy(response, AgentPolicyRejection.Stage.TOOL_EXECUTION));
        assertEquals(AgentPolicyRejection.Rule.CHECK_MUTATED_SOURCE, invalid.rule());
        org.junit.jupiter.api.Assertions.assertNull(invalid.sourceChanges());
        assertFalse(invalid.getMessage().contains("private-source"));
    }

    @Test
    void acceptsOnlyExplicitCompatibleToolContract() throws Exception {
        assertDoesNotThrow(() -> DockerAgentSandbox.requireCompatibleProtocol(json.readTree(
                "{\"ok\":true,\"tool\":\"protocol\",\"result\":{\"protocol\":\"delveforge-sandbox\",\"version\":9}}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "{\"ok\":false,\"classification\":\"POLICY_REJECTED\",\"reason\":\"private-detail\"}",
            "{\"ok\":true,\"tool\":\"protocol\",\"result\":{\"protocol\":\"delveforge-sandbox\",\"version\":3}}",
            "{\"ok\":true,\"tool\":\"protocol\",\"result\":{\"protocol\":\"delveforge-sandbox\",\"version\":4}}",
            "{\"ok\":true,\"tool\":\"protocol\",\"result\":{\"protocol\":\"delveforge-sandbox\",\"version\":5}}",
            "{\"ok\":true,\"tool\":\"protocol\",\"result\":{\"protocol\":\"delveforge-sandbox\",\"version\":6}}",
            "{\"ok\":true,\"tool\":\"protocol\",\"result\":{\"protocol\":\"delveforge-sandbox\",\"version\":7}}",
            "{\"ok\":true,\"result\":{\"classification\":\"PATCH_CONFLICT\"}}",
            "{\"ok\":true,\"tool\":\"protocol\",\"result\":{\"protocol\":\"delveforge-sandbox\",\"version\":\"1\"}}",
            "{\"ok\":true,\"tool\":\"protocol\",\"result\":{\"protocol\":\"delveforge-sandbox\",\"version\":1.0}}",
            "{\"ok\":true,\"tool\":\"protocol\",\"result\":{\"protocol\":\"other\",\"version\":1}}",
            "{\"ok\":true,\"tool\":\"protocol\",\"result\":{\"protocol\":\"delveforge-sandbox\",\"version\":1}}",
            "{\"ok\":true,\"tool\":\"protocol\",\"result\":{\"protocol\":\"delveforge-sandbox\",\"version\":4294967297}}"})
    void legacyMalformedAndUnknownContractsFailWithSafeActionableError(String response) throws Exception {
        var failure = assertThrows(AgentException.class,
                () -> DockerAgentSandbox.requireCompatibleProtocol(json.readTree(response)));
        assertEquals("SANDBOX_PROTOCOL_INCOMPATIBLE", failure.code());
        assertTrue(failure.getMessage().contains("new image tag"));
        assertTrue(failure.getMessage().contains("delveforge.agent.sandbox.images"));
        assertFalse(failure.getMessage().contains("private-detail"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DELVEFORGE_SANDBOX_PROTOCOL_IMAGE", matches = ".+")
    void actualCompatibleImageIsCheckedBeforeInitializeAndReturnsSafePatchResults() throws Exception {
        exerciseImage(System.getenv("DELVEFORGE_SANDBOX_PROTOCOL_IMAGE"), true);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DELVEFORGE_SANDBOX_LEGACY_IMAGE", matches = ".+")
    void actualLegacyImageIsRejectedBeforeInitializeOrPatch() throws Exception {
        exerciseImage(System.getenv("DELVEFORGE_SANDBOX_LEGACY_IMAGE"), false);
    }

    private void exerciseImage(String image, boolean compatible) throws Exception {
        Path official = temporary.resolve("fixture");
        Files.createDirectories(official);
        Files.writeString(official.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>
                <artifactId>protocol</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release></properties></project>
                """);
        Files.writeString(official.resolve("Counter.java"), "class Counter { int value = 1; }\n");
        command(List.of("git", "init", "--template="), official);
        command(List.of("git", "add", "pom.xml", "Counter.java"), official);
        command(List.of("git", "-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid",
                "-c", "commit.gpgSign=false", "commit", "-m", "fixture"), official);
        String baseline = command(List.of("git", "rev-parse", "HEAD"), official).strip();
        String id = UUID.randomUUID().toString();
        String cache = "df-protocol-test-cache-" + id;
        var properties = new AgentSandboxProperties(temporary.resolve("runs"), "docker", Map.of(21, image),
                21, "delveforge-protocol-tests", cache, "512m", "1", 64);
        List<String> operations = new ArrayList<>();
        AgentProcess recorded = spy(new AgentProcess());
        doAnswer(invocation -> {
            List<String> args = invocation.getArgument(0);
            byte[] input = invocation.getArgument(2);
            if (args.contains("/opt/delveforge/sandbox.py") && input != null) {
                operations.add(json.readTree(input).path("name").asText());
            }
            return invocation.callRealMethod();
        }).when(recorded).run(any(), any(), any(), any(), any(Integer.class), any(), any());
        var sandbox = new DockerAgentSandbox(properties, new DeterministicRepositorySecretPolicy(), recorded);
        var task = new AgentSandboxPort.Task(id, "plan", "step", "copy", official.toString(),
                temporary.resolve("original").toString(), baseline, "fixture", "fixture", "fixture", 21);
        try {
            if (!compatible) {
                var failure = assertThrows(AgentException.class,
                        () -> sandbox.prepare(task, AgentProtocol.Limits.defaults(), () -> false));
                assertEquals("SANDBOX_PROTOCOL_INCOMPATIBLE", failure.code());
                assertEquals(List.of("protocol"), operations);
                return;
            }
            sandbox.prepare(task, AgentProtocol.Limits.defaults(), () -> false);
            assertEquals(List.of("protocol", "initialize"), operations);
            var applied = json.readTree(sandbox.tool(id, new AgentProtocol.ToolCall("one", "edit_file",
                    json.writeValueAsString(Map.of("path", "Counter.java", "old_text", "value = 1", "new_text", "value = 2"))), () -> false));
            assertTrue(applied.path("ok").asBoolean());
            assertEquals("PATCH_APPLIED", applied.path("result").path("classification").asText());
            String before = sandbox.tool(id, new AgentProtocol.ToolCall("read-one", "read_file",
                    "{\"path\":\"Counter.java\"}"), () -> false);
            var conflict = json.readTree(sandbox.tool(id, new AgentProtocol.ToolCall("two", "edit_file",
                    json.writeValueAsString(Map.of("path", "Counter.java", "old_text", "value = 1", "new_text", "value = 2"))), () -> false));
            assertFalse(conflict.path("ok").asBoolean());
            assertTrue(conflict.path("result").path("mutationNotStarted").asBoolean());
            assertEquals("PATCH_CONFLICT", conflict.path("result").path("classification").asText());
            // 精确编辑在原文缺失时即返回冲突，尚未进入旧 diff 的重复变更判断。
            assertEquals("CONTEXT_MISMATCH", conflict.path("result").path("conflictReason").asText());
            assertFalse(conflict.path("result").has("output"));
            String after = sandbox.tool(id, new AgentProtocol.ToolCall("read-two", "read_file",
                    "{\"path\":\"Counter.java\"}"), () -> false);
            assertEquals(json.readTree(before).path("result"), json.readTree(after).path("result"));
        } finally {
            sandbox.cleanup(id);
            command(List.of("docker", "volume", "rm", cache), null);
        }
    }

    private String command(List<String> arguments, Path directory) {
        var result = process.run(arguments, directory, null, Duration.ofSeconds(45), 16000, () -> false, Map.of());
        assertEquals(0, result.exitCode(), "Synthetic fixture command must succeed");
        return result.text();
    }
}
