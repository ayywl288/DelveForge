package com.ayywl.delveforge.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompletionEvidenceTest {
    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode evidence() {
        ObjectNode root = json.createObjectNode().put("evidenceVersion", 7).put("runId", "run")
                .put("finalSnapshot", "a".repeat(64));
        root.putObject("sourceView").put("kind", "PROJECTED").put("preservedOnlyCount", 1)
                .put("readOnlyResourceCount", 0).put("withheldSourceCount", 1);
        root.putObject("agentReport").put("status", "completed").put("validationComplete", true)
                .put("summary", "Selected native check passed; old exploration failed")
                .put("limitations", "Private resource omitted; its behavior was not checked")
                .putArray("executions").add("pass");
        ObjectNode receipt = root.putArray("checks").addObject().put("runId", "run").put("invocationId", "pass")
                .put("exitCode", 0).put("timedOut", false).put("cancelled", false)
                .put("classification", "COMMAND_SUCCEEDED").put("snapshot", "a".repeat(64))
                .put("afterSnapshot", "a".repeat(64));
        receipt.set("sourceView", root.path("sourceView").deepCopy());
        return root;
    }

    @Test
    void projectionAndExploratoryFailuresDoNotBecomeBlanketVetoes() {
        ObjectNode root = evidence();
        ObjectNode old = ((ObjectNode) root.path("checks").get(0)).deepCopy();
        old.put("invocationId", "old").put("exitCode", 1).put("classification", "COMMAND_FAILED")
                .put("snapshot", "b".repeat(64));
        root.withArray("checks").add(old);
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(root));
    }

    @Test
    void missingFailedStaleForeignAndAlteredViewReferencesAreRejected() {
        for (String field : new String[]{"invocationId", "exitCode", "timedOut", "cancelled", "snapshot",
                "afterSnapshot", "runId", "classification", "sourceView"}) {
            ObjectNode root = evidence();
            ObjectNode check = (ObjectNode) root.path("checks").get(0);
            switch (field) {
                case "exitCode" -> check.put(field, 1);
                case "timedOut", "cancelled" -> check.put(field, true);
                case "sourceView" -> check.putObject(field).put("kind", "FULL");
                default -> check.put(field, "different");
            }
            assertThrows(AgentException.class, () -> AgentCheckEvidence.requireAcceptable(root), field);
        }
    }

    @Test
    void notesSummaryAndUnresolvedValidationCannotSupplyExecutionEvidence() {
        for (String field : new String[]{"executions", "validationComplete", "status"}) {
            ObjectNode root = evidence();
            ObjectNode report = (ObjectNode) root.path("agentReport");
            report.put("notes", "[x] All done");
            report.remove(field);
            assertThrows(AgentException.class, () -> AgentCheckEvidence.requireAcceptable(root));
        }
        ObjectNode root = evidence();
        ((ObjectNode) root.path("agentReport")).put("validationComplete", false);
        assertThrows(AgentException.class, () -> AgentCheckEvidence.requireAcceptable(root));
    }

    @Test
    void knownFailingReportCannotBeRelabeledButTestInventoryMayLegitimatelyChange() {
        ObjectNode root = evidence();
        ObjectNode check = (ObjectNode) root.path("checks").get(0);
        check.putObject("testReport").put("availability", "AVAILABLE").put("failures", 1);
        assertThrows(AgentException.class, () -> AgentCheckEvidence.requireAcceptable(root));
        ((ObjectNode) check.path("testReport")).put("failures", 0).put("executedTests", 1);
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(root));
    }
}
