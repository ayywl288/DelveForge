package com.ayywl.delveforge.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandCheckEvidenceTest {
    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode evidence() {
        ObjectNode root = json.createObjectNode().put("evidenceVersion", 6).put("status", "NOT_TESTED")
                .put("verificationLevel", "COMMAND_VERIFIED").put("finalSnapshot", "a".repeat(64));
        root.putObject("sourceView").put("withheldSourceCount", 0);
        root.putArray("requiredChecks").addObject().put("command", "sh check.sh").put("directory", ".")
                .put("scope", "README counter regression").put("kind", "test");
        root.putArray("checks").addObject().put("checkType", "COMMAND").put("invocationId", "first")
                .put("command", "sh check.sh").put("directory", ".").put("exitCode", 0)
                .put("timedOut", false).put("cancelled", false).put("snapshot", "a".repeat(64))
                .put("afterSnapshot", "a".repeat(64)).put("classification", "COMMAND_SUCCEEDED");
        return root;
    }

    @Test
    void successfulOutputFilterCannotOverrideObservedFailingTests() {
        ObjectNode root = evidence();
        ObjectNode receipt = (ObjectNode) root.path("checks").get(0);
        receipt.putObject("testReport").put("availability", "AVAILABLE")
                .put("executedTests", 21).put("failures", 3);
        assertEquals(0, receipt.path("exitCode").asInt());
        assertEquals("UNRESOLVED_CHECK_FAILURE", assertThrows(AgentException.class,
                () -> CommandCheckEvidence.requireAcceptable(root)).code());
    }

    @Test
    void successfulRepositoryCheckNeedsNoLanguageReportOrInventedCounts() {
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(evidence()));
    }

    @Test
    void sourceAndDependencyChangesOrInCommandMutationsInvalidateEvidence() {
        for (String field : new String[]{"snapshot", "afterSnapshot"}) {
            ObjectNode root = evidence();
            ((ObjectNode) root.path("checks").get(0)).put(field, "b".repeat(64));
            assertEquals("UNRESOLVED_CHECK_FAILURE", assertThrows(AgentException.class,
                    () -> CommandCheckEvidence.requireAcceptable(root)).code());
        }
    }

    @Test
    void failedTimeoutCancelledAndMissingRequiredCheckCannotBeReplacedByUnrelatedSuccess() {
        for (String kind : new String[]{"failed", "timeout", "cancelled", "missing"}) {
            ObjectNode root = evidence();
            ObjectNode check = (ObjectNode) root.path("checks").get(0);
            switch (kind) {
                case "failed" -> check.put("exitCode", 1);
                case "timeout" -> check.put("timedOut", true);
                case "cancelled" -> check.put("cancelled", true);
                case "missing" -> check.put("command", "echo success");
                default -> throw new AssertionError();
            }
            assertThrows(AgentException.class, () -> CommandCheckEvidence.requireAcceptable(root));
        }
        ObjectNode root = evidence();
        ((ObjectNode) root.path("requiredChecks").get(0)).put("command", "true");
        ((ObjectNode) root.path("checks").get(0)).put("command", "true");
        assertThrows(AgentException.class, () -> CommandCheckEvidence.requireAcceptable(root));
    }

    @Test
    void withheldExecutableSourceCannotManufactureSuccessfulVerification() {
        ObjectNode root = evidence();
        ((ObjectNode) root.path("sourceView")).put("withheldSourceCount", 1);
        assertEquals("UNRESOLVED_CHECK_FAILURE", assertThrows(AgentException.class,
                () -> CommandCheckEvidence.requireAcceptable(root)).code());
    }

    @Test
    void latestAttemptWinsButAllDistinctHistoryRemains() {
        ObjectNode root = evidence();
        ObjectNode retry = ((ObjectNode) root.path("checks").get(0)).deepCopy().put("invocationId", "retry")
                .put("exitCode", 1).put("classification", "COMMAND_FAILED");
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("checks")).add(retry);
        assertThrows(AgentException.class, () -> CommandCheckEvidence.requireAcceptable(root));
        retry.put("exitCode", 0).put("classification", "COMMAND_SUCCEEDED");
        assertDoesNotThrow(() -> CommandCheckEvidence.requireAcceptable(root));
        assertEquals(2, root.path("checks").size());
    }
    @Test
    void observedFailureCannotDisappearThroughSkippedDeletedOrRemovedReports() {
        for (String finalOutcome : new String[]{"SKIPPED", "", "PASSED"}) {
            ObjectNode root = evidence();
            ObjectNode first = (ObjectNode) root.path("checks").get(0);
            first.putObject("testReport").put("availability", "AVAILABLE")
                    .putObject("cases").put("counter#increments", "FAILED");
            ObjectNode last = first.deepCopy().put("invocationId", "last");
            ObjectNode report = last.putObject("testReport").put("availability", "AVAILABLE")
                    .put("executedTests", 1).put("failures", 0);
            report.putObject("cases").put("counter#increments", finalOutcome);
            ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("checks")).add(last);
            if (finalOutcome.equals("PASSED")) {
                assertDoesNotThrow(() -> CommandCheckEvidence.requireAcceptable(root));
            } else {
                assertThrows(AgentException.class, () -> CommandCheckEvidence.requireAcceptable(root));
            }
        }
    }

}
