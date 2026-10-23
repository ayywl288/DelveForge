package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentPolicyRejection;
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

    @AfterEach
    void noExternalCommandsOrMutation() {
        verifyNoInteractions(commands);
    }

    @Test
    void unrelatedEditOfCredentialBearingFileStillRejectsAndSanitizedWritebackIsNotAllowed() {
        var inspection = json.valueToTree(Map.of("ok", true, "result", Map.of("src/Config.java",
                "password=\"fixture-private-value\"\nordinary=1\n")));
        for (String patch : new String[]{"change ordinary to 2", "password=[redacted-credential]"}) {
            var failure = assertThrows(AgentPolicyRejection.class,
                    () -> sandbox.requirePatchContent(inspection, json.valueToTree(Map.of("patch", patch))));
            assertEquals(AgentPolicyRejection.Rule.SOURCE_CREDENTIALS, failure.rule());
            assertEquals(AgentPolicyRejection.Stage.PATCH_SOURCE, failure.stage());
            assertFalse(failure.getMessage().contains("fixture-private-value"));
            assertFalse(failure.getMessage().contains("Config.java"));
        }
    }

    @Test
    void patchCredentialsRejectBeforeExecutionButOrdinaryContentRemainsPermitted() {
        var inspection = json.valueToTree(Map.of("ok", true, "result", Map.of("src/Config.java", "ordinary=1\n")));
        var failure = assertThrows(AgentPolicyRejection.class, () -> sandbox.requirePatchContent(inspection,
                json.valueToTree(Map.of("patch", "password=\"fixture-private-value\""))));
        assertEquals(AgentPolicyRejection.Rule.PATCH_CREDENTIALS, failure.rule());
        assertEquals(AgentPolicyRejection.Stage.PATCH_CONTENT, failure.stage());
        sandbox.requirePatchContent(inspection, json.valueToTree(Map.of("patch", "ordinary=2")));
    }

    @Test
    void literalCredentialAndOrdinaryCodeAssignmentBothRemainProtectedWithoutExposingValues() {
        for (String source : new String[]{"password=\"synthetic-private-value\"\nordinary=1\n",
                "class Parser { int token = 0; int ordinary = 1; }"}) {
            var inspection = json.valueToTree(Map.of("ok", true, "result", Map.of("src/Config.java", source)));
            for (String edit : new String[]{"change ordinary only", "change protected value", "[redacted-credential]"}) {
                var failure = assertThrows(AgentPolicyRejection.class,
                        () -> sandbox.requirePatchContent(inspection, json.valueToTree(Map.of("patch", edit))));
                assertTrue(failure.sourceEditRejectedBeforeMutation());
                assertEquals(AgentPolicyRejection.Rule.SOURCE_CREDENTIALS, failure.rule());
                assertEquals(AgentPolicyRejection.Stage.PATCH_SOURCE, failure.stage());
                assertFalse(failure.getMessage().contains(source));
            }
        }
        sandbox.requirePatchContent(json.valueToTree(Map.of("ok", true, "result", Map.of("src/Other.java", "int ordinary = 1;"))),
                json.valueToTree(Map.of("patch", "change ordinary only")));
        assertFalse(new AgentPolicyRejection(AgentPolicyRejection.Rule.SOURCE_CREDENTIALS,
                AgentPolicyRejection.Stage.PATCH_SOURCE).sourceEditRejectedBeforeMutation());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../host", "/host/path", ".git/config", ".env", "keys/private.pem"})
    void inspectedProtectedPathsCannotBypassJavaGuard(String path) {
        var failure = assertThrows(AgentPolicyRejection.class, () -> sandbox.requirePatchContent(
                json.valueToTree(Map.of("ok", true, "result", Map.of(path, "ordinary=1"))),
                json.valueToTree(Map.of("patch", "ordinary=2"))));
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
        sandbox.requirePatchContent(json.valueToTree(Map.of("ok", false, "classification", "TOOL_ERROR")),
                json.valueToTree(Map.of("patch", "invalid patch")));
    }
}
