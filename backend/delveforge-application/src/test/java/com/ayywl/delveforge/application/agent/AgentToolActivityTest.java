package com.ayywl.delveforge.application.agent;

import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void frontendActivityPreservesExecutionIdentityWithoutPersistingReportBodies() throws Exception {
        var response = json.createObjectNode().put("ok", true);
        var result = response.putObject("result").put("classification", "TEST_EXECUTION_UNVERIFIED")
                .put("executionStatus", "EXECUTED").put("runner", "node-junit")
                .put("configurationSnapshot", "a".repeat(64)).put("configurationChanged", true)
                .put("output", "private diagnostic body");
        var tool = call("run_frontend_checks", Map.of("goal", "test"));
        String encoded = activity.finished(tool, identity, response.toString());
        var summary = json.readTree(encoded);
        assertEquals("EXECUTED", summary.path("executionStatus").asText());
        assertEquals("node-junit", summary.path("runner").asText());
        assertTrue(summary.path("configurationChanged").asBoolean());
        assertFalse(encoded.contains("private diagnostic body"));
        result.put("runner", "arbitrary-body").put("executionStatus", "arbitrary-body");
        summary = json.readTree(activity.finished(tool, identity, response.toString()));
        assertFalse(summary.has("runner"));
        assertFalse(summary.has("executionStatus"));
    }

    @Test
    void sourceChangesPersistOnlyBoundedSafeMetadataAndClearlyMarkMissingDiagnostics() throws Exception {
        var before = new AgentPolicyRejection.SourceFileState("100644", "a".repeat(64));
        var after = new AgentPolicyRejection.SourceFileState("100644", "b".repeat(64));
        var visible = new AgentPolicyRejection.SourceChange("src/Counter.java",
                AgentPolicyRejection.SourceChangeType.CONTENT, before, after, false);
        var protectedFile = new AgentPolicyRejection.SourceChange("config/.env",
                AgentPolicyRejection.SourceChangeType.CONTENT, before, after, false);
        var changes = new AgentPolicyRejection.SourceChanges("c".repeat(64), "d".repeat(64), 2, 2,
                List.of(visible, protectedFile), false);
        var check = call("run_checks", Map.of("goal", "test"));
        String recorded = activity.failed(check, identity, AgentPolicyRejection.sourceChanged(changes), 123L);
        JsonNode metadata = json.readTree(recorded);
        assertEquals("CHECK_MUTATED_SOURCE", metadata.path("policyRule").asText());
        assertEquals("PARTIAL", metadata.path("sourceChangesStatus").asText());
        assertEquals(1, metadata.path("sourceChanges").path("files").size());
        assertEquals(1, metadata.path("sourceChanges").path("withheldFiles").asInt());
        assertEquals("src/Counter.java", metadata.path("sourceChanges").path("files").get(0).path("path").asText());
        assertEquals("b".repeat(64), metadata.path("sourceChanges").path("files").get(0)
                .path("after").path("sha256").asText());
        assertFalse(recorded.contains(".env"));
        assertFalse(metadata.path("resultRecorded").asBoolean());
        JsonNode missing = json.readTree(activity.failed(check, identity,
                new AgentPolicyRejection(AgentPolicyRejection.Rule.CHECK_MUTATED_SOURCE,
                        AgentPolicyRejection.Stage.TOOL_EXECUTION), 124L));
        assertEquals("UNAVAILABLE", missing.path("sourceChangesStatus").asText());
        assertFalse(missing.has("sourceChanges"));
    }

    @Test
    void proposalLocationsIgnoreArbitraryTextAndRejectUnboundedMetadata() throws Exception {
        var edit = call("edit_file", Map.of("path", "src/Example.java", "old_text", "before", "new_text", "private-body"));
        String base = "{\"ok\":false,\"classification\":\"POLICY_REJECTED\",\"policyRule\":\"PATCH_CREDENTIALS\","
                + "\"policyStage\":\"PATCH_CONTENT\",\"mutationNotStarted\":true,\"alternativeAllowed\":true,";
        String recorded = activity.finished(edit, identity, base + "\"rejectedInput\":{\"field\":\"new_text\","
                + "\"lines\":[2,4],\"value\":\"private-body\"}}");
        assertEquals(2, json.readTree(recorded).path("rejectedInput").path("lines").size());
        assertFalse(recorded.contains("private-body"));
        String invalid = activity.finished(edit, identity, base + "\"rejectedInput\":{\"field\":\"new_text\",\"lines\":[\"private-body\"]}}");
        assertFalse(json.readTree(invalid).has("rejectedInput"));
        var values = new java.util.ArrayList<Integer>(java.util.List.of(2));
        var safe = new AgentPolicyRejection.RejectedInput("new_text", values);
        values.add(3);
        assertEquals(java.util.List.of(2), safe.lines());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AgentPolicyRejection.RejectedInput("private-body", java.util.List.of(1)));
    }

    @Test
    void exactEditsExposeOnlySafePathAndClassificationNeverReplacementContent() throws Exception {
        var edit = call("edit_file", Map.of("path", "src/Example.java", "old_text", "private-before", "new_text", "private-after"));
        String started = activity.started(edit, identity);
        assertFalse(started.contains("private-before"));
        assertFalse(started.contains("private-after"));
        assertEquals("src/Example.java", json.readTree(started).path("path").asText());
        String finished = activity.finished(edit, identity,
                "{\"ok\":true,\"result\":{\"classification\":\"PATCH_APPLIED\",\"paths\":[\"src/Example.java\"]}}");
        assertEquals("PATCH_APPLIED", json.readTree(finished).path("classification").asText());
        assertEquals("available", json.readTree(finished).path("targetState").asText());
    }

    @Test
    void onlyTypedRuleAndStageArePersistedAndLegacyPolicyMessagesRemainPrivate() throws Exception {
        var patch = call("apply_patch", Map.of("patch", "private-patch"));
        JsonNode typed = json.readTree(activity.failed(patch, identity, new AgentPolicyRejection(
                AgentPolicyRejection.Rule.PATCH_CREDENTIALS, AgentPolicyRejection.Stage.PATCH_CONTENT), 12L));
        assertEquals("PATCH_CREDENTIALS", typed.path("policyRule").asText());
        assertEquals("PATCH_CONTENT", typed.path("policyStage").asText());
        assertEquals(identity, typed.path("toolCallId").asText());
        JsonNode legacy = json.readTree(activity.failed(patch, identity,
                new AgentException("POLICY_REJECTED", "private-exception-message"), 12L));
        assertEquals("UNCLASSIFIED_POLICY", legacy.path("policyRule").asText());
        assertEquals("TOOL_EXECUTION", legacy.path("policyStage").asText());
        String unknown = activity.failed(patch, identity, new IllegalStateException("private-exception-message"), null);
        assertFalse(typed.toString().contains("private"));
        assertFalse(legacy.toString().contains("private"));
        assertFalse(unknown.contains("private"));
        assertFalse(json.readTree(unknown).has("policyRule"));
    }

    @Test
    void invocationTimingIsSeparateFromInternalTimingAndUnavailableValuesAreNotInvented() throws Exception {
        var read = call("read_file", Map.of("path", "src/A.java"));
        for (long nanos : new long[]{0, 500000, 12000000}) {
            JsonNode result = json.readTree(activity.finished(read, identity, "{\"ok\":true,\"durationMs\":0}", nanos));
            assertEquals(nanos, result.path("invocationDurationNs").asLong());
            assertEquals(0, result.path("durationMs").asLong());
        }
        assertFalse(json.readTree(activity.finished(read, identity, "{\"ok\":true}")).has("invocationDurationNs"));
        assertFalse(json.readTree(activity.finished(read, identity, "{\"ok\":true}", -1L)).has("invocationDurationNs"));
        JsonNode failed = json.readTree(activity.failed(read, identity, "POLICY_REJECTED", 123L));
        assertEquals(identity, failed.path("toolCallId").asText());
        assertEquals(123L, failed.path("invocationDurationNs").asLong());
        assertFalse(failed.path("ok").asBoolean());
        assertFalse(failed.path("resultRecorded").asBoolean());
        assertFalse(failed.has("reason"));
        assertFalse(failed.has("durationMs"));
    }

    @ParameterizedTest
    @ValueSource(strings = {".github/workflows/build.yml", ".gitignore", ".mvn/wrapper/maven-wrapper.properties", "src/Order Service.java"})
    void permittedDotDirectoriesAndSpacesRemainVisible(String path) throws Exception {
        assertEquals(path, json.readTree(activity.started(call("read_file", Map.of("path", path)), identity)).path("path").asText());
    }

    @Test
    void withheldTargetsAreDistinguishedWithoutEchoingProtectedNames() throws Exception {
        JsonNode start = json.readTree(activity.started(call("read_file", Map.of("path", ".env")), identity));
        assertEquals("withheld", start.path("targetState").asText());
        assertFalse(start.toString().contains(".env"));
        JsonNode invalid = json.readTree(activity.started(new AgentProtocol.ToolCall("call", "read_file", "{}"), identity));
        assertFalse(invalid.has("targetState"));
        assertFalse(invalid.has("path"));
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

    @Test
    void selectedTestScopeIsRetainedSoItCannotReplaceFullSuiteResults() throws Exception {
        var selected = call("run_checks", Map.of("goal", "test", "directory", "backend", "test", "FeatureTest"));
        var result = json.readTree(activity.finished(selected, identity,
                "{\"ok\":true,\"result\":{\"classification\":\"TESTS_PASSED\"}}"));
        assertTrue(result.path("testSelectionFingerprint").asText().matches("[0-9a-f]{64}"));
        assertFalse(result.toString().contains("FeatureTest"));
        assertEquals(result.path("testSelectionFingerprint"),
                json.readTree(activity.started(selected, identity)).path("testSelectionFingerprint"));
        var other = call("run_checks", Map.of("goal", "test", "directory", "backend", "test", "OtherTest"));
        assertNotEquals(result.path("testSelectionFingerprint"),
                json.readTree(activity.started(other, identity)).path("testSelectionFingerprint"));
        assertEquals("backend", result.path("directory").asText());
    }

    @Test
    void conflictFeedbackHasSafeReasonAndNeverClaimsPathsWereApplied() throws Exception {
        var patch = call("apply_patch", Map.of("patch", "private-body"));
        var result = json.readTree(activity.finished(patch, identity,
                "{\"ok\":false,\"result\":{\"classification\":\"PATCH_CONFLICT\",\"mutationNotStarted\":true,"
                        + "\"conflictReason\":\"CONTEXT_MISMATCH\",\"paths\":[\"src/A.java\"],\"output\":\"private diagnostic\"}}"));
        assertEquals("PATCH_CONFLICT", result.path("classification").asText());
        assertTrue(result.path("mutationNotStarted").asBoolean());
        assertEquals("CONTEXT_MISMATCH", result.path("conflictReason").asText());
        assertFalse(result.has("paths"));
        assertFalse(result.toString().contains("private"));
    }

    @Test
    void provenSourceRefusalMetadataIsBoundedAndDistinctFromUnknownMutation() throws Exception {
        var patch = call("apply_patch", Map.of("patch", "private-patch-body"));
        var proven = json.readTree(activity.failed(patch, identity, AgentPolicyRejection.sourceEditRejectedBeforeApply(), 12L));
        assertTrue(proven.path("mutationNotStarted").asBoolean());
        assertFalse(proven.path("resultRecorded").asBoolean());
        assertFalse(proven.has("alternativeAllowed"));
        var unknown = json.readTree(activity.failed(patch, identity, new AgentPolicyRejection(
                AgentPolicyRejection.Rule.SOURCE_CAPTURE_LIMIT, AgentPolicyRejection.Stage.SOURCE_CAPTURE), 12L));
        assertFalse(unknown.has("mutationNotStarted"));
        var response = "{\"ok\":false,\"classification\":\"POLICY_REJECTED\",\"policyRule\":\"SOURCE_CREDENTIALS\","
                + "\"policyStage\":\"PATCH_SOURCE\",\"mutationNotStarted\":true,\"alternativeAllowed\":true,"
                + "\"instruction\":\"private response\",\"paths\":[\"protected/private\"]}";
        var recorded = json.readTree(activity.finished(patch, identity, response, 12L));
        assertTrue(recorded.path("resultRecorded").asBoolean());
        assertTrue(recorded.path("alternativeAllowed").asBoolean());
        assertFalse(recorded.toString().contains("private"));
        var invalid = json.readTree(activity.finished(patch, identity, response.replace("PATCH_SOURCE", "SOURCE_CAPTURE"), 12L));
        assertFalse(invalid.has("mutationNotStarted"));
        assertFalse(invalid.has("alternativeAllowed"));
    }
}
