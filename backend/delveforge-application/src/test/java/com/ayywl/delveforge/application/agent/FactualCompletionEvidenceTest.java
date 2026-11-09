package com.ayywl.delveforge.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactualCompletionEvidenceTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String OLD = "11111111-1111-4111-8111-111111111111";
    private static final String CURRENT = "22222222-2222-4222-8222-222222222222";

    private ObjectNode evidence() {
        ObjectNode evidence = json.createObjectNode().put("evidenceVersion", 8).put("runId", "run")
                .put("finalSnapshot", "a".repeat(64));
        evidence.putObject("sourceView").put("kind", "PROJECTED").put("preservedOnlyCount", 1)
                .put("readOnlyResourceCount", 0).put("withheldSourceCount", 1);
        evidence.putObject("agentReport").put("status", "completed").put("summary", "Implemented requested behavior")
                .putArray("limitations").add("E2E unavailable; existing suite failed");
        ObjectNode receipt = evidence.putArray("checks").addObject().put("invocationId", "actual").put("runId", "run")
                .put("command", "sh check.sh").put("directory", ".").put("durationMs", 2)
                .put("exitCode", 1).put("timedOut", false).put("cancelled", false)
                .put("stdout", "assertion failed").put("stderr", "diagnostic").put("outputTruncated", false)
                .put("sourceChanged", false).put("snapshot", "a".repeat(64)).put("afterSnapshot", "a".repeat(64))
                .put("classification", "COMMAND_FAILED");
        receipt.set("sourceView", evidence.path("sourceView").deepCopy());
        receipt.putObject("testReport").put("availability", "AVAILABLE").put("failures", 1).put("errors", 0);
        return evidence;
    }

    @Test
    void failedChecksAndUnavailableE2eAreFactsNotGlobalGates() {
        ObjectNode root = evidence();
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(root));
        ((ObjectNode) root.path("agentReport")).putArray("executions").add("actual");
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(root));
        ((ObjectNode) root.path("checks").get(0)).put("exitCode", 0).put("classification", "COMMAND_SUCCEEDED");
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(root));
    }

    @Test
    void noCheckCanBeReportedWithLimitationsAndFailedAssessmentRemainsFailed() {
        ObjectNode root = evidence();
        root.putArray("checks");
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(root));
        ((ObjectNode) root.path("agentReport")).put("status", "failed");
        assertThrows(AgentException.class, () -> AgentCheckEvidence.requireAcceptable(root));
    }

    @Test
    void fabricatedForeignDuplicateCorruptAndStaleReferencesAreRejected() {
        for (String problem : new String[]{"fabricated", "foreign", "duplicate", "corrupt", "stale", "view", "duplicateReceipt"}) {
            ObjectNode root = evidence();
            ObjectNode check = (ObjectNode) root.path("checks").get(0);
            var refs = ((ObjectNode) root.path("agentReport")).putArray("executions").add("actual");
            switch (problem) {
                case "fabricated" -> refs.add("invented");
                case "foreign" -> check.put("runId", "another-run");
                case "duplicate" -> refs.add("actual");
                case "duplicateReceipt" -> root.withArray("checks").add(check.deepCopy());
                case "corrupt" -> check.put("exitCode", 0);
                case "stale" -> check.put("snapshot", "b".repeat(64));
                case "view" -> check.putObject("sourceView").put("kind", "FULL");
                default -> throw new AssertionError(problem);
            }
            assertThrows(AgentException.class, () -> AgentCheckEvidence.requireAcceptable(root), problem);
        }
    }

    @Test
    void omittedReceiptsStillUndergoIntegrityChecks() {
        ObjectNode root = evidence();
        ((ObjectNode) root.path("checks").get(0)).put("classification", "COMMAND_SUCCEEDED");
        assertThrows(AgentException.class, () -> AgentCheckEvidence.requireAcceptable(root));
    }

    @Test
    void staleSuccessfulReferenceIdentifiesTheActualFinalCheckWithoutReplacingTheClaim() throws Exception {
        ObjectNode root = evidence();
        ObjectNode old = (ObjectNode) root.path("checks").get(0);
        old.put("invocationId", OLD).put("snapshot", "b".repeat(64)).put("afterSnapshot", "b".repeat(64))
                .put("exitCode", 0).put("classification", "COMMAND_SUCCEEDED");
        old.putObject("testReport").put("availability", "AVAILABLE").put("executedTests", 53).put("failures", 0);
        ObjectNode current = old.deepCopy().put("invocationId", CURRENT)
                .put("snapshot", root.path("finalSnapshot").asText()).put("afterSnapshot", root.path("finalSnapshot").asText());
        root.withArray("checks").add(current);
        var checksBefore = root.path("checks").deepCopy();
        var refs = ((ObjectNode) root.path("agentReport")).putArray("executions").add(OLD);
        var failure = assertThrows(CompletionReferenceFailure.class, () -> AgentCheckEvidence.requireAcceptable(root));
        var feedback = json.readTree(failure.feedback());
        assertEquals("STALE_SOURCE", feedback.path("issue").asText());
        assertEquals(OLD, feedback.path("reference").asText());
        assertEquals(old.path("snapshot"), feedback.path("referencedReceipt").path("snapshot"));
        assertEquals(old.path("afterSnapshot"), feedback.path("referencedReceipt").path("afterSnapshot"));
        assertEquals(root.path("finalSnapshot"), feedback.path("finalSnapshot"));
        var options = feedback.path("currentExecutions");
        assertEquals(1, options.size());
        assertEquals(CURRENT, options.get(0).path("invocationId").asText());
        assertEquals(53, options.get(0).path("executedTests").asInt());
        assertEquals(OLD, refs.get(0).asText());
        refs.removeAll().add(options.get(0).path("invocationId").asText());
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(root));
        assertEquals(checksBefore, root.path("checks"));
    }

    @Test
    void unknownReferencesExposeNoFreeTextAndCurrentFailedChecksRemainFailedFacts() throws Exception {
        ObjectNode root = evidence();
        ((ObjectNode) root.path("checks").get(0)).put("invocationId", CURRENT);
        var refs = ((ObjectNode) root.path("agentReport")).putArray("executions").add("synthetic-private-input");
        var rejection = assertThrows(CompletionReferenceFailure.class, () -> AgentCheckEvidence.requireAcceptable(root));
        assertFalse(rejection.feedback().contains("synthetic-private-input"));
        assertFalse(rejection.feedback().contains("assertion failed"));
        var feedback = json.readTree(rejection.feedback());
        assertEquals("UNKNOWN_REFERENCE", feedback.path("issue").asText());
        assertEquals(0, feedback.path("referenceIndex").asInt());
        var failed = feedback.path("currentExecutions").get(0);
        assertEquals("COMMAND_FAILED", failed.path("outcome").asText());
        assertEquals(1, failed.path("exitCode").asInt());
        assertEquals(1, failed.path("failures").asInt());
        refs.removeAll().add(CURRENT);
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(root));
        root.put("evidenceVersion", 7);
        ((ObjectNode) root.path("agentReport")).put("validationComplete", true);
        rejection = assertThrows(CompletionReferenceFailure.class, () -> AgentCheckEvidence.requireAcceptable(root));
        assertEquals("FAILED_EXECUTION", json.readTree(rejection.feedback()).path("issue").asText());
    }

    @Test
    void matchingSnapshotWithDifferentViewIsNotCurrentVerification() throws Exception {
        ObjectNode root = evidence();
        ObjectNode receipt = (ObjectNode) root.path("checks").get(0);
        receipt.put("invocationId", CURRENT);
        ((ObjectNode) receipt.path("sourceView")).put("readOnlyResourceCount", 1);
        ((ObjectNode) root.path("agentReport")).putArray("executions").add(CURRENT);
        var rejected = assertThrows(CompletionReferenceFailure.class, () -> AgentCheckEvidence.requireAcceptable(root));
        var feedback = json.readTree(rejected.feedback());
        assertEquals("VIEW_MISMATCH", feedback.path("issue").asText());
        assertEquals(root.path("sourceView"), feedback.path("finalView"));
        assertEquals(receipt.path("sourceView"), feedback.path("referencedReceipt").path("sourceView"));
        assertEquals(0, feedback.path("currentExecutions").size());
        assertFalse(CompletionEvidence.matchesFinalSource(root.path("finalSnapshot").asText(), root.path("sourceView"), receipt));
    }

    @Test
    void hintsAreBoundedAndRetainAnEarlierCurrentPassingReportAmongRecentFailures() throws Exception {
        ObjectNode root = evidence();
        ObjectNode passing = (ObjectNode) root.path("checks").get(0);
        passing.put("invocationId", CURRENT).put("classification", "COMMAND_SUCCEEDED").put("exitCode", 0);
        passing.putObject("testReport").put("availability", "AVAILABLE").put("executedTests", 53).put("failures", 0);
        for (int i = 0; i < 9; i++) {
            var failed = passing.deepCopy().put("invocationId", "00000000-0000-4000-8000-00000000000" + i)
                    .put("classification", "COMMAND_FAILED").put("exitCode", 1);
            root.withArray("checks").add(failed);
        }
        ((ObjectNode) root.path("agentReport")).put("executions", "private input");
        var failure = assertThrows(CompletionReferenceFailure.class, () -> AgentCheckEvidence.requireAcceptable(root));
        var feedback = json.readTree(failure.feedback());
        assertEquals("INVALID_REFERENCE_LIST", feedback.path("issue").asText());
        assertEquals(10, feedback.path("currentExecutionCount").asInt());
        assertEquals(6, feedback.path("currentExecutions").size());
        assertEquals(CURRENT, feedback.path("currentExecutions").get(0).path("invocationId").asText());
        assertFalse(failure.feedback().contains("private input"));
        ((ObjectNode) root.path("agentReport")).putArray("executions").add(CURRENT).add(CURRENT);
        var duplicate = assertThrows(CompletionReferenceFailure.class, () -> AgentCheckEvidence.requireAcceptable(root));
        assertEquals("DUPLICATE_REFERENCE", json.readTree(duplicate.feedback()).path("issue").asText());
        assertEquals(1, json.readTree(duplicate.feedback()).path("referenceIndex").asInt());
    }
}
