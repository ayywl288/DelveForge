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
            ".env", "src\\.env", ".ssh\\config", "src\\private.key", ".git\\config", "src/CON"})
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
            String visible = sandbox.sourceResponse(tool, response).toString();
            assertTrue(visible.contains("Compilation error at Auth.java:12"));
            for (String secret : java.util.List.of("synthetic-bearer-value", "dXNlcjpwYXNz",
                    "ghp_" + "a".repeat(36), "sk-" + "b".repeat(32))) {
                assertFalse(visible.contains(secret));
            }
        }
    }

    @Test
    void proposedCredentialDiagnosticsContainOnlyBoundedInputLineNumbers() throws Exception {
        String proposed = "class Example {\nString password=\"fixture-private-value\";\nint ordinary=1;\n"
                + "String accessToken=generatedValue();\n}";
        var rejection = sandbox.contentRejection("new_text", proposed);
        assertEquals(AgentPolicyRejection.Rule.PATCH_CREDENTIALS, rejection.rule());
        assertTrue(rejection.sourceEditRejectedBeforeMutation());
        assertEquals("new_text", rejection.rejectedInput().field());
        assertEquals(java.util.List.of(2, 4), rejection.rejectedInput().lines());
        String metadata = json.writeValueAsString(rejection.rejectedInput());
        for (String privateText : java.util.List.of("fixture-private-value", "password", "accessToken", "generatedValue", "Example")) {
            assertFalse(metadata.contains(privateText));
        }
        String many = java.util.stream.IntStream.range(0, 20).mapToObj(index -> "credential=private-" + index)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertEquals(8, sandbox.contentRejection("patch", many).rejectedInput().lines().size());
        assertEquals(null, AgentPolicyRejection.sourceEditRejectedBeforeApply().rejectedInput());
    }

    @Test
    void proposedSourceLocationsDoNotFlagLegitimateRuntimeExpressions() {
        String proposed = "const token = this.getToken();\nconst password='synthetic-private-value';\n"
                + "const ordinary='[redacted-credential]';\n";
        var rejection = sandbox.contentRejection("new_text", proposed, "src/Auth.js");
        assertEquals(java.util.List.of(2, 3), rejection.rejectedInput().lines());
        assertEquals(null, sandbox.contentRejection("new_text", "const token=this.getToken();", "src/Auth.js")
                .rejectedInput());
    }

    @Test
    void multilineRecognizedInputKeepsProtectedLinesWithoutReturningAnyBlock() throws Exception {
        var rejection = sandbox.contentRejection("new_text", "ordinary\n-----BEGIN PRIVATE KEY-----\n"
                + "synthetic-private-material\n-----END PRIVATE KEY-----\n");
        assertEquals(java.util.List.of(2, 3, 4), rejection.rejectedInput().lines());
        assertFalse(json.writeValueAsString(rejection.rejectedInput()).contains("synthetic"));
    }

    @Test
    void recognizedCredentialInNewPatchIsStillRejectedWithProvenPreMutationStage() {
        var failure = assertThrows(AgentPolicyRejection.class, () -> sandbox.requirePatchContent(
                preview("src/Example.java", "ordinary=1;", "password='synthetic-private-value';"),
                json.createObjectNode().put("patch", "controlled patch")));
        assertEquals(AgentPolicyRejection.Rule.PATCH_CREDENTIALS, failure.rule());
        assertEquals(AgentPolicyRejection.Stage.PATCH_CONTENT, failure.stage());
        assertTrue(failure.sourceEditRejectedBeforeMutation());
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
    void credentialMutationsAndRedactedWritebackRemainRejectedWhileOrdinaryTokenCodeIsEditable() {
        String before = "String password='synthetic-private-value';";
        for (String after : new String[]{"ordinary=2;", "String password='other-private-value';",
                "String password='[redacted-credential]';"}) {
            var failure = assertThrows(AgentPolicyRejection.class,
                    () -> sandbox.requirePatchContent(preview("src/Config.java", before, after),
                            json.createObjectNode().put("patch", "controlled patch")));
            assertEquals(AgentPolicyRejection.Rule.SOURCE_CREDENTIALS, failure.rule());
            assertEquals(AgentPolicyRejection.Stage.PATCH_SOURCE, failure.stage());
            assertFalse(failure.getMessage().contains("synthetic-private-value"));
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
    void completeSourceIsSanitizedBeforeReadingOrSearchingAnInteriorPrivateKeyLine() {
        String complete = "ordinary();\n-----BEGIN PRIVATE KEY-----\nsynthetic-private-material\n-----END PRIVATE KEY-----\nnext();\n";
        var data = json.createObjectNode().put("path", "src/Auth.js").put("startLine", 3).put("count", 1)
                .put("totalLines", 5).put("_source", complete);
        var read = sandbox.sourceResponse("read_file", json.createObjectNode().put("ok", true).set("result", data));
        assertFalse(read.toString().contains("synthetic-private-material"));
        assertFalse(read.toString().contains("_source"));
        assertEquals("[redacted-credential]\n", read.path("result").path("content").asText());
        var search = json.createObjectNode();
        search.putArray("matches").addObject().put("path", "src/Auth.js").put("line", 3);
        search.set("_sources", json.createObjectNode().put("src/Auth.js", complete));
        var visible = sandbox.sourceResponse("search_code", json.createObjectNode().put("ok", true).set("result", search));
        assertFalse(visible.toString().contains("synthetic-private-material"));
        assertFalse(visible.toString().contains("_sources"));
        assertEquals("[redacted-credential]", visible.path("result").path("matches").get(0).path("excerpt").asText());
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
    @ValueSource(strings = {"../host", "/host/path", ".git/config", ".env", "keys/private.pem"})
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
