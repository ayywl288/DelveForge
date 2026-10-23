package com.ayywl.delveforge.application.agent;

import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolActivityTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AgentToolActivity activity = new AgentToolActivity(new DeterministicRepositorySecretPolicy(),
            AgentProtocol.Limits.defaults());
    private final String identity = "12345678-1234-4567-8123-123456789abc";

    private AgentProtocol.ToolCall call(String name, Map<String, Object> args) throws Exception {
        return new AgentProtocol.ToolCall("model-id-never-persisted", name, json.writeValueAsString(args));
    }

    @Test
    void stableServerIdentityAndAllowlistedTargetsOnly() throws Exception {
        var call = call("search_code", Map.of("path", "src/中文.java", "query", "private-search-text"));
        JsonNode start = json.readTree(activity.started(call, identity));
        String finished = activity.finished(call, identity, "{\"ok\":true,\"durationMs\":12,\"result\":{"
                + "\"content\":\"private-source\",\"output\":\"private-output\",\"path\":\"/host/unsafe\"}}");
        assertEquals(identity, start.path("toolCallId").asText());
        assertEquals(identity, json.readTree(finished).path("toolCallId").asText());
        assertEquals(1, start.path("metadataVersion").asInt());
        assertEquals("src/中文.java", start.path("path").asText());
        assertEquals("src/中文.java", json.readTree(finished).path("path").asText());
        for (String secret : new String[]{"private-search-text", "private-source", "private-output", "/host/unsafe", "model-id-never-persisted"}) {
            assertFalse(finished.contains(secret));
            assertFalse(start.toString().contains(secret));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../secret", "src/../../secret", "/etc/passwd", "C:/host/data", "src\\file.java",
            ".git/config", "src/.env.local", ".ssh/config", ".m2/settings.xml", ".aws/credentials",
            "keys/private.pem", "src/id_rsa", "src/CON.txt", "src//file.java", "src/./file.java",
            "src/file.java/", "src/bad\nname", "src/bad\u0000name", "src/%2e%2e/secret"})
    void neverPersistsUnsafeOrProtectedPaths(String path) throws Exception {
        var call = call("read_file", Map.of("path", path));
        JsonNode start = json.readTree(activity.started(call, identity));
        JsonNode finish = json.readTree(activity.finished(call, identity, "{\"ok\":false,\"classification\":\"TOOL_ERROR\"}"));
        assertFalse(start.has("path"));
        assertFalse(finish.has("path"));
    }

    @Test
    void invalidArgumentsOversizedPathsAndSecretLikeNamesHaveNoDisplayTarget() throws Exception {
        for (String args : new String[]{"{", "[]", "{\"path\":5}", "{\"path\":\"src/A.java\",\"extra\":\"private\"}"}) {
            JsonNode value = json.readTree(activity.started(new AgentProtocol.ToolCall("call", "read_file", args), identity));
            assertEquals(3, value.size());
        }
        for (String path : new String[]{"x".repeat(513), "src/sk-" + "a".repeat(48) + ".java"}) {
            assertFalse(json.readTree(activity.started(call("read_file", Map.of("path", path)), identity)).has("path"));
        }
        var oversized = new AgentProtocol.ToolCall("call", "apply_patch", "x".repeat(300000));
        assertEquals(3, json.readTree(activity.started(oversized, identity)).size());
    }

    @Test
    void mavenScopeIsValidatedAndNeverCopiesTestSelectorsOrArbitraryOutput() throws Exception {
        var check = call("run_checks", Map.of("goal", "test", "module", "library", "directory", "backend", "test", "PrivateTest"));
        JsonNode fields = json.readTree(activity.finished(check, identity,
                "{\"ok\":true,\"durationMs\":10,\"result\":{\"classification\":\"TESTS_FAILED\",\"tests\":2,\"failures\":1,"
                        + "\"module\":\"/host/location\",\"selectedTest\":\"private\",\"output\":\"secret\"}}"));
        assertEquals("backend", fields.path("directory").asText());
        assertEquals("library", fields.path("module").asText());
        assertEquals("test", fields.path("goal").asText());
        assertEquals(1, fields.path("failures").asInt());
        assertFalse(fields.has("selectedTest"));
        assertFalse(fields.toString().contains("PrivateTest"));
        assertFalse(fields.toString().contains("/host/location"));
        for (String directory : new String[]{"../../host", ".aws", "nested/private.key", "x".repeat(1000)}) {
            assertFalse(json.readTree(activity.started(call("run_checks", Map.of("directory", directory)), identity)).has("directory"));
        }
    }

    @Test
    void patchPathsRequireSuccessfulValidatedResultAndStayBounded() throws Exception {
        var patch = call("apply_patch", Map.of("patch", "private-patch-body"));
        assertFalse(activity.started(patch, identity).contains("private-patch-body"));
        for (String paths : new String[]{"[\"src/A.java\",\".env\"]", "[\"../x\"]", "[5]"}) {
            JsonNode result = json.readTree(activity.finished(patch, identity,
                    "{\"ok\":true,\"result\":{\"classification\":\"PATCH_APPLIED\",\"paths\":" + paths + "}}"));
            assertFalse(result.has("paths"));
        }
        JsonNode success = json.readTree(activity.finished(patch, identity,
                "{\"ok\":true,\"result\":{\"classification\":\"PATCH_APPLIED\",\"paths\":[\"src/A.java\"]}}"));
        assertEquals("src/A.java", success.path("paths").get(0).asText());
        JsonNode conflict = json.readTree(activity.finished(patch, identity,
                "{\"ok\":true,\"result\":{\"classification\":\"PATCH_CONFLICT\",\"paths\":[\"src/A.java\"]}}"));
        assertTrue(conflict.path("ok").asBoolean());
        assertEquals("PATCH_CONFLICT", conflict.path("classification").asText());
        assertFalse(conflict.has("paths"));
        String safePath = "中".repeat(251) + ".java";
        String eightPaths = json.writeValueAsString(java.util.Collections.nCopies(8, safePath));
        String bounded = activity.finished(patch, identity,
                "{\"ok\":true,\"result\":{\"classification\":\"PATCH_APPLIED\",\"paths\":" + eightPaths + "}}");
        assertEquals(8, json.readTree(bounded).path("paths").size());
        assertTrue(bounded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 24000);
        String ninePaths = json.writeValueAsString(java.util.Collections.nCopies(9, "src/A.java"));
        assertFalse(json.readTree(activity.finished(patch, identity,
                "{\"ok\":true,\"result\":{\"classification\":\"PATCH_APPLIED\",\"paths\":" + ninePaths + "}}"))
                .has("paths"));
    }
}
