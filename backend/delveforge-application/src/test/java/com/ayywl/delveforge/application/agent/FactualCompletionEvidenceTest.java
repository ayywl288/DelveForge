package com.ayywl.delveforge.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FactualCompletionEvidenceTest {
    private final ObjectMapper json = new ObjectMapper();

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
}
