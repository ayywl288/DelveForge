package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentPolicyRejection;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DockerPatchPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AgentProcess commands = mock(AgentProcess.class);
    private final DockerAgentSandbox sandbox = new DockerAgentSandbox(null,
            new DeterministicRepositorySecretPolicy(), commands);

    @ParameterizedTest
    @ValueSource(strings = {"src\\Counter.java", "./src/Counter.java", "src//Counter.java", "src/Counter.java/"})
    void ordinaryReadSpellingReturnsFixedFeedbackWithoutExecutingAnything(String path) throws Exception {
        var result = json.readTree(sandbox.readPathFeedback(path));
        assertFalse(result.path("ok").asBoolean());
        var details = result.path("result");
        assertEquals("INVALID_TOOL_ARGUMENT", details.path("classification").asText());
        assertEquals("RELATIVE_PATH_REQUIRED", details.path("reason").asText());
        assertTrue(details.path("readNotStarted").asBoolean());
        assertTrue(details.path("mutationNotStarted").asBoolean());
        assertFalse(result.toString().contains(path));
        verifyNoInteractions(commands);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside", "src\\..\\outside", "/etc/passwd", "C:\\outside", "\\\\host\\file",
            ".ssh\\config", ".git\\config", "src/CON"})
    void sensitiveMalformedReadPathsRemainFatalBeforeAnyAccess(String path) {
        assertThrows(AgentPolicyRejection.class, () -> sandbox.readPathFeedback(path));
        verifyNoInteractions(commands);
    }

    @Test
    void checkOutputsRedactRecognizableCredentialsWithoutAssignmentContext() {
        String output = "Compilation error at Auth.java:12\nBearer synthetic-bearer-value\nBasic dXNlcjpwYXNz\n"
                + "ghp_" + "a".repeat(36) + "\n" + "sk-" + "b".repeat(32);
        for (String tool : java.util.List.of("run_checks", "run_frontend_checks", "describe_checks")) {
            var response = json.createObjectNode().put("ok", true).set("result",
                    json.createObjectNode().put("output", output).put("classification", "TESTS_FAILED"));
            String visible = sandbox.sourceResponse(tool, json.createObjectNode(), response, Map.of()).toString();
            assertTrue(visible.contains("Compilation error at Auth.java:12"));
            for (String secret : java.util.List.of("synthetic-bearer-value", "dXNlcjpwYXNz",
                    "ghp_" + "a".repeat(36), "sk-" + "b".repeat(32))) {
                assertFalse(visible.contains(secret));
            }
        }
    }

    @Test
    void redactionWritebackDiagnosticsContainOnlyBoundedInputLineNumbers() throws Exception {
        String proposed = "class Example {\nString password=\"[redacted-credential]\";\nint ordinary=1;\n"
                + "String accessToken=\"[redacted-credential]\";\n}";
        var rejection = sandbox.contentRejection("new_text", proposed);
        assertEquals(AgentPolicyRejection.Rule.PATCH_CREDENTIALS, rejection.rule());
        assertTrue(rejection.sourceEditRejectedBeforeMutation());
        assertEquals("new_text", rejection.rejectedInput().field());
        assertEquals(java.util.List.of(2, 4), rejection.rejectedInput().lines());
        String metadata = json.writeValueAsString(rejection.rejectedInput());
        for (String privateText : java.util.List.of("fixture-private-value", "password", "accessToken", "generatedValue", "Example")) {
            assertFalse(metadata.contains(privateText));
        }
        String many = java.util.stream.IntStream.range(0, 20).mapToObj(index -> "credential=[redacted-credential]" + index)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertEquals(8, sandbox.contentRejection("patch", many).rejectedInput().lines().size());
        assertEquals(null, AgentPolicyRejection.sourceEditRejectedBeforeApply().rejectedInput());
    }

    @Test
    void proposedSourceLocationsDoNotFlagLegitimateRuntimeExpressions() {
        String proposed = "const token = this.getToken();\nconst password='synthetic-private-value';\n"
                + "const ordinary='[redacted-credential]';\n";
        var rejection = sandbox.contentRejection("new_text", proposed, "src/Auth.js");
        assertEquals(java.util.List.of(3), rejection.rejectedInput().lines());
        assertEquals(null, sandbox.contentRejection("new_text", "const token=this.getToken();", "src/Auth.js")
                .rejectedInput());
    }

    @Test
    void multilineSourceIsNotRejectedBasedOnSecretShape() throws Exception {
        var rejection = sandbox.contentRejection("new_text", "ordinary\n-----BEGIN PRIVATE KEY-----\n"
                + "synthetic-private-material\n-----END PRIVATE KEY-----\n");
        assertEquals(null, rejection.rejectedInput());
        assertFalse(json.writeValueAsString(rejection.rejectedInput()).contains("synthetic"));
    }

    @Test
    void selectedConfigDefaultsCanBeEditedWithExactWitnesses() {
        sandbox.requirePatchContent(preview("application.properties", "ordinary=1;", "password='synthetic-private-value';"),
                json.createObjectNode().put("patch", "controlled patch"));
    }

    @Test
    void onlyKnownReadOnlyParserFailuresHaveSafeStructuralDiagnostics() {
        assertEquals("HUNK_COUNTS_INVALID", DockerAgentSandbox.patchSyntaxReason(json.valueToTree(Map.of(
                "ok", false, "classification", "TOOL_ERROR", "reason", "ValueError: Invalid unified diff hunk counts"))));
        assertEquals(null, DockerAgentSandbox.patchSyntaxReason(json.valueToTree(Map.of(
                "ok", false, "classification", "TOOL_ERROR", "reason", "ValueError: private-source-body"))));
        assertEquals(null, DockerAgentSandbox.patchSyntaxReason(json.valueToTree(Map.of(
                "ok", false, "classification", "PATCH_OUTCOME_UNCERTAIN", "reason", "ValueError: Invalid unified diff hunk counts"))));
    }

    @Test
    void readOnlyInspectionErrorsReturnBoundedFeedbackWithoutSuppressingPolicyOrUnknownApplication() {
        var failed = sandbox.patchInspectionFailure("apply_patch", json.valueToTree(Map.of(
                "ok", false, "classification", "TOOL_ERROR", "reason", "OSError: fixture-private-source")));
        assertEquals("PATCH_CONFLICT", failed.path("result").path("classification").asText());
        assertEquals("CHECK_FAILED", failed.path("result").path("conflictReason").asText());
        assertEquals("INSPECTION_UNAVAILABLE", failed.path("result").path("patchDiagnostic").asText());
        assertTrue(failed.path("result").path("mutationNotStarted").asBoolean());
        assertFalse(failed.toString().contains("fixture-private-source"));
        for (String classification : java.util.List.of("POLICY_REJECTED", "PATCH_OUTCOME_UNCERTAIN")) {
            assertEquals(null, sandbox.patchInspectionFailure("apply_patch", json.valueToTree(Map.of(
                    "ok", false, "classification", classification))));
        }
        assertEquals(null, sandbox.patchInspectionFailure("apply_patch", json.createObjectNode().put("ok", true)));
    }

    @Test
    void uncertainApplicationCannotBecomeRecoverableToolFeedback() {
        var failure = assertThrows(AgentException.class, () -> DockerAgentSandbox.requireCertainPatchOutcome(
                json.valueToTree(Map.of("ok", false, "classification", "PATCH_OUTCOME_UNCERTAIN", "output", "private-body"))));
        assertEquals("PATCH_OUTCOME_UNCERTAIN", failure.code());
        assertFalse(failure.getMessage().contains("private-body"));
    }

    @AfterEach
    void noExternalCommandsOrMutation() {
        verifyNoInteractions(commands);
    }

    @Test
    void defaultsAreEditableButRedactedWritebackIsRejected() {
        String before = "String password='synthetic-private-value';";
        for (String after : new String[]{"ordinary=2;", "String password='other-private-value';",
                "String password='[redacted-credential]';"}) {
            if (after.contains("[redacted-credential]")) {
                var failure = assertThrows(AgentPolicyRejection.class,
                        () -> sandbox.requirePatchContent(preview("src/Config.java", before, after),
                                json.createObjectNode().put("patch", "controlled patch")));
                assertEquals(AgentPolicyRejection.Rule.PATCH_CREDENTIALS, failure.rule());
            } else {
                sandbox.requirePatchContent(preview("src/Config.java", before, after), json.createObjectNode());
            }
        }
        var arguments = json.createObjectNode().put("patch", "controlled patch");
        sandbox.requirePatchContent(preview("src/Config.java", "int token = 0;", "int token = next();"), arguments);
        assertTrue(arguments.has("preimages"));
        assertTrue(arguments.has("postimages"));
        assertFalse(new AgentPolicyRejection(AgentPolicyRejection.Rule.SOURCE_CREDENTIALS,
                AgentPolicyRejection.Stage.PATCH_SOURCE).sourceEditRejectedBeforeMutation());
    }

    @Test
    void previewContentAndWitnessMustAgreeAndMissingPreviewFailsClosed() {
        var inspection = preview("src/Config.java", "int ordinary=1;", "int ordinary=2;");
        ((com.fasterxml.jackson.databind.node.ObjectNode) inspection.path("result").path("files").path("src/Config.java"))
                .put("preimage", "0".repeat(64) + ":100644");
        assertEquals("SNAPSHOT_MISMATCH", assertThrows(AgentException.class,
                () -> sandbox.requirePatchContent(inspection, json.createObjectNode())).code());
        assertThrows(AgentException.class, () -> sandbox.requirePatchContent(
                json.valueToTree(Map.of("ok", true, "result", Map.of())), json.createObjectNode()));
    }

    @Test
    void selectedSourceSlicesRemainExactForEditing() {
        String content = "synthetic-private-material\r\n";
        var data = json.createObjectNode().put("path", "src/Auth.js").put("startLine", 3).put("lineCount", 1)
                .put("hasMore", true).put("nextStartLine", 4).put("content", content);
        var args = json.createObjectNode().put("path", "src/Auth.js").put("start_line", 3);
        var envelope = json.createObjectNode().put("ok", true).set("result", data);
        var read = sandbox.sourceResponse("read_file", args, envelope, Map.of());
        assertEquals(data.deepCopy().put("publicResource", false), read.path("result"));
        var resources = Map.of("src/Auth.js", Map.<String, Object>of("kind", "PUBLIC_RESOURCE"));
        assertTrue(sandbox.sourceResponse("read_file", args, envelope, resources).at("/result/publicResource").asBoolean());

        var search = json.createObjectNode().put("truncated", false);
        search.putArray("matches").addObject().put("path", "src/Auth.js").put("line", 3)
                .put("excerpt", "synthetic-private-material");
        var visible = sandbox.sourceResponse("search_code", args, json.createObjectNode().put("ok", true)
                .set("result", search), resources);
        assertEquals("synthetic-private-material", visible.path("result").path("matches").get(0).path("excerpt").asText());
        assertTrue(visible.at("/result/matches/0/publicResource").asBoolean());
    }

    @Test
    void incompleteOrInconsistentSourceResponsesFailClosed() {
        var data = json.createObjectNode().put("path", "a").put("startLine", 2).put("lineCount", 1)
                .put("hasMore", true).put("nextStartLine", 3).put("content", "a\r");
        for (var invalid : java.util.List.of(data.deepCopy().without("content"), data.deepCopy().without("nextStartLine"),
                data.deepCopy().put("nextStartLine", 4), data.deepCopy().put("hasMore", false),
                data.deepCopy().put("lineCount", 2), data.deepCopy().put("content", "partial"),
                data.deepCopy().put("startLine", "2"), data.deepCopy().put("path", "../outside"))) {
            assertThrows(AgentException.class, () -> sandbox.sourceResponse("read_file",
                    json.createObjectNode().put("path", "a").put("start_line", 2),
                    json.createObjectNode().put("ok", true).set("result", invalid), Map.of()));
        }
        var match = json.createObjectNode().put("path", "a").put("line", 1).put("excerpt", "a");
        for (var invalid : java.util.List.of(match.deepCopy().without("excerpt"), match.deepCopy().put("line", 0),
                match.deepCopy().put("excerpt", "x".repeat(401)), match.deepCopy().put("path", "../outside"))) {
            var result = json.createObjectNode().put("truncated", false);
            result.putArray("matches").add(invalid);
            assertThrows(AgentException.class, () -> sandbox.sourceResponse("search_code", json.createObjectNode(),
                    json.createObjectNode().put("ok", true).set("result", result), Map.of()));
        }
        assertThrows(AgentException.class, () -> sandbox.sourceResponse("search_code", json.createObjectNode(),
                json.createObjectNode().put("ok", true).set("result", json.createObjectNode()), Map.of()));
    }

    private com.fasterxml.jackson.databind.JsonNode preview(String path, String before, String after) {
        var file = json.createObjectNode().put("before", before).put("after", after)
                .put("preimage", before == null ? "absent" : com.ayywl.delveforge.domain.agent.Candidate.digest(before) + ":100644")
                .put("postimage", after == null ? "absent" : com.ayywl.delveforge.domain.agent.Candidate.digest(after) + ":100644");
        file.putArray("changes").addArray().add(before == null ? 0 : 1).add(before == null ? 0 : 1)
                .add(after == null ? 0 : 1).add(after == null ? 0 : 1);
        return json.createObjectNode().put("ok", true).set("result",
                json.createObjectNode().set("files", json.createObjectNode().set(path, file)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../host", "/host/path", ".git/config", ".ssh/config"})
    void inspectedProtectedPathsCannotBypassJavaGuard(String path) {
        var failure = assertThrows(AgentPolicyRejection.class, () -> sandbox.requirePatchContent(
                preview(path, "ordinary=1", "ordinary=2"),
                json.createObjectNode().put("patch", "controlled patch")));
        assertEquals("POLICY_REJECTED", failure.code());
        assertEquals(AgentPolicyRejection.Stage.PATH_VALIDATION, failure.stage());
        assertFalse(failure.getMessage().contains(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PATCH_TYPE", "PATCH_PATH_FORMAT", "PATCH_DIFF_PATHS", "PATCH_FILE_MODE",
            "GENERATED_PATH", "PROTECTED_CREDENTIAL_PATH", "SYMLINK_PATH", "PATCH_SIZE", "COMMAND_BUDGET"})
    void controlledRuleAndCorrectStageAreRetained(String rule) {
        var response = json.valueToTree(Map.of("ok", false, "classification", "POLICY_REJECTED",
                "policyRule", rule, "reason", "private text must not survive", "policyStage", "PATCH_CONTENT"));
        var failure = assertThrows(AgentPolicyRejection.class,
                () -> sandbox.requirePatchContent(response, json.valueToTree(Map.of("patch", "private patch"))));
        assertEquals(rule, failure.rule().name());
        assertEquals(AgentPolicyRejection.Stage.PATCH_INSPECTION, failure.stage());
        assertFalse(failure.getMessage().contains("private"));
    }

    @Test
    void legacyUnknownRulesCannotInjectFreeTextAndPostApplyCaptureIsDistinct() {
        var old = json.valueToTree(Map.of("classification", "POLICY_REJECTED", "reason", "private text",
                "policyRule", "UNTRUSTED/private-value"));
        var unknown = assertThrows(AgentPolicyRejection.class,
                () -> DockerAgentSandbox.rejectPolicy(old, AgentPolicyRejection.Stage.PATCH_APPLICATION));
        assertEquals(AgentPolicyRejection.Rule.UNCLASSIFIED_POLICY, unknown.rule());
        assertFalse(unknown.getMessage().contains("private"));
        var capture = json.valueToTree(Map.of("classification", "POLICY_REJECTED", "policyRule", "SOURCE_CAPTURE_LIMIT",
                "policyStage", "SOURCE_CAPTURE"));
        var failure = assertThrows(AgentPolicyRejection.class,
                () -> DockerAgentSandbox.rejectPolicy(capture, AgentPolicyRejection.Stage.PATCH_APPLICATION));
        assertEquals(AgentPolicyRejection.Stage.SOURCE_CAPTURE, failure.stage());
        assertThrows(AgentException.class, () -> sandbox.requirePatchContent(
                json.valueToTree(Map.of("ok", false, "classification", "TOOL_ERROR")),
                json.createObjectNode().put("patch", "invalid patch")));
    }
}
