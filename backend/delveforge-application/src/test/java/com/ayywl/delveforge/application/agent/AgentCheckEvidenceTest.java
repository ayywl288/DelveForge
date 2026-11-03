package com.ayywl.delveforge.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentCheckEvidenceTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String SNAPSHOT = "a".repeat(64);

    @Test
    void preflightFailureRequiresCurrentMatchingRecheckAndCannotSupplySuccess() {
        var baseline = check("FRONTEND", ".", "test", "TESTS_FAILED", true)
                .put("executionStatus", "EXECUTED").put("runner", "node-junit");
        var result = evidence().put("evidenceVersion", 4);
        result.putArray("baselineFailures").add(baseline);
        blocked(result);
        result.withArray("checks").add(check("FRONTEND", ".", "build", "BUILD_PASSED_NOT_TESTED", false)
                .put("executionStatus", "EXECUTED"));
        blocked(result);
        result.withArray("checks").add(check("FRONTEND", ".", "test", "TESTS_PASSED", false)
                .put("executionStatus", "EXECUTED").put("runner", "node-junit"));
        result.put("status", "TESTED");
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(result));
        baseline.put("classification", "TESTS_PASSED");
        assertEquals("EVIDENCE_INVALID", assertThrows(AgentException.class,
                () -> AgentCheckEvidence.requireAcceptable(result)).code());
    }

    private ObjectNode check(String type, String directory, String goal, String status, boolean stale) {
        return json.createObjectNode().put("checkType", type).put("directory", directory).put("goal", goal)
                .put("classification", status).put("snapshot", stale ? "b".repeat(64) : SNAPSHOT).put("stale", stale)
                .put("tests", goal.equals("test") ? 1 : 0).put("executedTests", goal.equals("test") ? 1 : 0)
                .put("failures", status.equals("TESTS_FAILED") ? 1 : 0).put("errors", 0).put("skipped", 0)
                .put("reports", goal.equals("test") ? 1 : 0);
    }

    private ObjectNode evidence(ObjectNode... checks) {
        ObjectNode result = json.createObjectNode().put("evidenceVersion", 2).put("finalSnapshot", SNAPSHOT);
        var rows = result.putArray("checks");
        boolean tested = false;
        for (ObjectNode check : checks) {
            rows.add(check);
            tested |= !check.path("stale").asBoolean() && check.path("classification").asText().equals("TESTS_PASSED");
        }
        return result.put("status", tested ? "TESTED" : "NOT_TESTED");
    }

    private void blocked(ObjectNode evidence) {
        assertEquals("UNRESOLVED_CHECK_FAILURE", assertThrows(AgentException.class,
                () -> AgentCheckEvidence.requireAcceptable(evidence)).code());
    }

    @Test
    void projectionEvidenceCannotBorrowPassesFromAnotherExecutionView() {
        var full = json.createObjectNode().put("kind", "FULL").put("preservedOnlyCount", 0)
                .put("readOnlyResourceCount", 1).put("withheldSourceCount", 0);
        var projected = full.deepCopy().put("kind", "PROJECTED").put("preservedOnlyCount", 1);
        var passed = check("FRONTEND", ".", "test", "TESTS_PASSED", false);
        passed.set("sourceView", full);
        var result = evidence(passed);
        result.set("sourceView", projected);
        assertEquals("EVIDENCE_INVALID", assertThrows(AgentException.class,
                () -> AgentCheckEvidence.requireAcceptable(result)).code());
        passed.set("sourceView", projected);
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(result));
        result.set("sourceView", projected.deepCopy().put("preservedOnlyCount", 0));
        assertEquals("EVIDENCE_INVALID", assertThrows(AgentException.class,
                () -> AgentCheckEvidence.requireAcceptable(result)).code());
    }

    @Test
    void malformedOrWithheldExecutableScopeCannotSupportPassingEvidence() {
        var passed = check("FRONTEND", ".", "build", "BUILD_PASSED_NOT_TESTED", false);
        for (ObjectNode view : new ObjectNode[] {
                json.createObjectNode().put("kind", "PROJECTED").put("preservedOnlyCount", 1)
                        .put("readOnlyResourceCount", 0),
                json.createObjectNode().put("kind", "PROJECTED").put("preservedOnlyCount", 1)
                        .put("readOnlyResourceCount", 0).put("withheldSourceCount", "0"),
                json.createObjectNode().put("kind", "PROJECTED").put("preservedOnlyCount", 1)
                        .put("readOnlyResourceCount", 0).put("withheldSourceCount", -1),
                json.createObjectNode().put("kind", "PROJECTED").put("preservedOnlyCount", 1)
                        .put("readOnlyResourceCount", 0).put("withheldSourceCount", 2),
                json.createObjectNode().put("kind", "PROJECTED").put("preservedOnlyCount", 1)
                        .put("readOnlyResourceCount", 0).put("withheldSourceCount", 1)}) {
            passed.set("sourceView", view);
            var result = evidence(passed);
            result.set("sourceView", view);
            assertEquals("EVIDENCE_INVALID", assertThrows(AgentException.class,
                    () -> AgentCheckEvidence.requireAcceptable(result)).code());
        }
    }

    @Test
    void frontendFailureRequiresSameTypeDirectoryAndGoalWithActualTests() {
        var failed = check("FRONTEND", "frontend", "test", "TESTS_FAILED", true);
        blocked(evidence(failed, check("MAVEN", "frontend", "test", "TESTS_PASSED", false)));
        blocked(evidence(failed, check("FRONTEND", "other", "test", "TESTS_PASSED", false)));
        blocked(evidence(failed, check("FRONTEND", "frontend", "build", "BUILD_PASSED_NOT_TESTED", false)));
        blocked(evidence(failed, check("FRONTEND", "frontend", "test", "TESTS_PASSED", true)));
        blocked(evidence(failed, check("FRONTEND", "frontend", "test", "BUILD_PASSED_NOT_TESTED", false)));
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(evidence(failed,
                check("FRONTEND", "frontend", "test", "TESTS_PASSED", false))));
    }

    @Test
    void typecheckAndBuildFailuresRemainIndependentAndCanBeCorrected() {
        for (String goal : new String[] {"typecheck", "build"}) {
            var failed = check("FRONTEND", "frontend", goal, "CHECK_RESULT_UNAVAILABLE", true);
            blocked(evidence(failed, check("FRONTEND", "frontend", "test", "TESTS_PASSED", false)));
            assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(evidence(failed,
                    check("FRONTEND", "frontend", goal, "BUILD_PASSED_NOT_TESTED", false))));
        }
    }

    @Test
    void staleOrPartialMavenRecheckDoesNotClearFailureAndLegacyRemainsReadable() throws Exception {
        var failed = check("MAVEN", ".", "test", "TESTS_FAILED", true);
        blocked(evidence(failed, check("MAVEN", ".", "compile", "BUILD_PASSED_NOT_TESTED", false)));
        blocked(evidence(failed, check("MAVEN", ".", "test", "TESTS_PASSED", false).put("module", "one")));
        var full = check("MAVEN", ".", "test", "TESTS_PASSED", false);
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(evidence(failed, full)));
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(json.readTree("{\"status\":\"NOT_TESTED\"}")));
        failed.remove("checkType");
        full.remove("checkType");
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(evidence(failed, full)));
    }

    @Test
    void unavailableOptionalChecksKeepNotTestedButNeverClearKnownFailures() {
        for (String goal : new String[] {"typecheck", "test", "build"}) {
            var unavailable = check("FRONTEND", "frontend", goal, "CHECK_NOT_AVAILABLE", false)
                    .put("tests", 0).put("executedTests", 0).put("reports", 0);
            assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(evidence(unavailable,
                    check("FRONTEND", "frontend", "build", "BUILD_PASSED_NOT_TESTED", false))));
            blocked(evidence(check("FRONTEND", "frontend", goal,
                    goal.equals("test") ? "TESTS_FAILED" : "TYPECHECK_FAILED", true), unavailable));
            unavailable.put("executedTests", 1);
            assertEquals("EVIDENCE_INVALID", assertThrows(AgentException.class,
                    () -> AgentCheckEvidence.requireAcceptable(evidence(unavailable))).code());
        }
    }

    @Test
    void unsupportedAndUnexecutedOutcomesAreNeverPasses() {
        for (String status : new String[] {"UNSUPPORTED_FRONTEND_CONFIGURATION", "LOCKFILE_INVALID", "DEPENDENCY_MISSING",
                "CHECK_TIMEOUT", "CHECK_RESULT_UNAVAILABLE"}) {
            blocked(evidence(check("FRONTEND", "frontend", "test", status, false)));
        }
        var impossible = check("FRONTEND", "frontend", "build", "TESTS_PASSED", false);
        assertEquals("EVIDENCE_INVALID", assertThrows(AgentException.class,
                () -> AgentCheckEvidence.requireAcceptable(evidence(impossible))).code());
        var mismatch = evidence(check("FRONTEND", "frontend", "test", "TESTS_PASSED", false));
        mismatch.put("finalSnapshot", "b".repeat(64));
        assertEquals("EVIDENCE_INVALID", assertThrows(AgentException.class,
                () -> AgentCheckEvidence.requireAcceptable(mismatch)).code());
        var unversioned = evidence(check("FRONTEND", "frontend", "test", "TESTS_PASSED", false));
        unversioned.remove("evidenceVersion");
        assertEquals("EVIDENCE_INVALID", assertThrows(AgentException.class,
                () -> AgentCheckEvidence.requireAcceptable(unversioned)).code());
    }

    @Test
    void scriptEvidenceDistinguishesConfigurationRejectionFromExecutedFailures() {
        var rejected = check("FRONTEND", ".", "test", "UNSUPPORTED_FRONTEND_CONFIGURATION", true)
                .put("executionStatus", "NOT_STARTED").put("tests", 0).put("executedTests", 0).put("reports", 0);
        var unverified = check("FRONTEND", ".", "test", "TEST_EXECUTION_UNVERIFIED", false)
                .put("executionStatus", "EXECUTED").put("tests", 0).put("executedTests", 0).put("reports", 0);
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(evidence(rejected, unverified)
                .put("evidenceVersion", 3)));
        var failed = check("FRONTEND", ".", "test", "TESTS_FAILED", true)
                .put("executionStatus", "EXECUTED").put("runner", "node-junit");
        blocked(evidence(failed, rejected, unverified).put("evidenceVersion", 3));
        var passed = check("FRONTEND", ".", "test", "TESTS_PASSED", false)
                .put("executionStatus", "EXECUTED").put("runner", "vitest-json");
        blocked(evidence(failed, passed).put("evidenceVersion", 3));
        passed.put("runner", "node-junit");
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(evidence(failed, passed)
                .put("evidenceVersion", 3)));
        passed.put("runner", "node-junit+vitest-json").put("reports", 2);
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(evidence(failed, passed)
                .put("evidenceVersion", 3)));
        var removed = rejected.deepCopy().put("classification", "CHECK_NOT_AVAILABLE");
        blocked(evidence(failed, removed).put("evidenceVersion", 3));
        passed.put("reports", 0);
        assertEquals("EVIDENCE_INVALID", assertThrows(AgentException.class,
                () -> AgentCheckEvidence.requireAcceptable(evidence(passed).put("evidenceVersion", 3))).code());
        // 旧证据不具备未启动的证明，保持历史保守解释。
        blocked(evidence(rejected));
    }
}
