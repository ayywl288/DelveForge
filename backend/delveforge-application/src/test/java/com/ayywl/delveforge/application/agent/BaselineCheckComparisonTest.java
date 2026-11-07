package com.ayywl.delveforge.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineCheckComparisonTest {
    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode test(String id, String outcome, String source) {
        var test = json.createObjectNode().put("id", id.repeat(64)).put("outcome", outcome)
                .put("failure", outcome.equals("FAILED") ? "f".repeat(64) : "")
                .put("source", source).put("sourceSha256", "c".repeat(64));
        test.putArray("affectedSources");
        return test;
    }

    private ObjectNode check(String snapshot, boolean broken, boolean feature) {
        var check = json.createObjectNode().put("checkType", "MAVEN").put("directory", ".").put("goal", "test")
                .put("snapshot", snapshot.repeat(64)).put("stale", snapshot.equals("b"))
                .put("classification", broken ? "TESTS_FAILED" : "TESTS_PASSED")
                .put("tests", feature ? 2 : 1).put("executedTests", feature ? 2 : 1).put("reports", 1)
                .put("failures", broken ? 1 : 0).put("errors", 0).put("skipped", 0);
        check.putObject("environment").put("image", "sha256:" + "d".repeat(64)).put("configuration", "e".repeat(64))
                .put("runtimeDependencies", "9".repeat(64));
        check.putObject("sourceView").put("kind", "FULL").put("preservedOnlyCount", 0)
                .put("readOnlyResourceCount", 0).put("withheldSourceCount", 0);
        var cases = check.putObject("testManifest").put("version", 1).put("complete", true).putArray("cases");
        cases.add(test("1", broken ? "FAILED" : "PASSED", "LegacyTest.java"));
        if (feature) {
            cases.add(test("2", "PASSED", "FeatureTest.java"));
        }
        return check;
    }

    private ObjectNode evidence(boolean broken) {
        var evidence = json.createObjectNode().put("evidenceVersion", 5).put("finalSnapshot", "a".repeat(64))
                .put("status", broken ? "NOT_TESTED" : "TESTED");
        var baseline = check("b", broken, false);
        evidence.set("sourceView", baseline.path("sourceView").deepCopy());
        evidence.putArray("changedPaths").add("Feature.java").add("FeatureTest.java");
        evidence.putArray("baselineChecks").add(baseline);
        evidence.putArray("checks").add(check("a", broken, true));
        evidence.putArray("baselineFailures");
        if (broken) {
            evidence.withArray("baselineFailures").add(baseline.deepCopy());
        }
        return summarize(evidence);
    }

    @Test
    void changedFailureDiagnosticIdentifiesCheckSourceAndComparisonDifference() {
        var evidence = evidence(true);
        ((ObjectNode) evidence.path("checks").get(0).path("testManifest").path("cases").get(0))
                .put("failure", "e".repeat(64));
        var failure = assertThrows(AgentException.class, () -> BaselineCheckComparison.compare(evidence));
        assertEquals("UNRESOLVED_CHECK_FAILURE", failure.code());
        assertTrue(failure.getMessage().contains("MAVEN:.:test"));
        assertTrue(failure.getMessage().contains("LegacyTest.java"));
        assertTrue(failure.getMessage().contains("FAILURE_CHANGED"));
        assertTrue(failure.getMessage().contains("1".repeat(64)));
    }

    @Test
    void affectedSourceDiagnosticNamesActualChangedDependency() {
        var evidence = evidence(true);
        ((ArrayNode) evidence.path("checks").get(0).path("testManifest").path("cases").get(0)
                .path("affectedSources")).add("Feature.java");
        var failure = assertThrows(AgentException.class, () -> BaselineCheckComparison.compare(evidence));
        assertTrue(failure.getMessage().contains("AFFECTED_SOURCE_CHANGED"));
        assertTrue(failure.getMessage().contains("Feature.java"));
        assertTrue(failure.getMessage().contains("LegacyTest.java"));
    }

    private ObjectNode summarize(ObjectNode evidence) {
        var comparison = BaselineCheckComparison.compare(evidence);
        evidence.put("verificationLevel", comparison.level());
        evidence.set("baselineDebt", comparison.debt());
        return evidence;
    }

    @Test
    void cleanBaselineWithPassingFeatureIsFullyTested() {
        var evidence = evidence(false);
        assertEquals("FULLY_TESTED", evidence.path("verificationLevel").asText());
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(evidence));
    }

    @Test
    void passingRelevantFinalCheckDoesNotRequireIdenticalSuccessfulTestInventory() {
        var evidence = evidence(false);
        var current = (ObjectNode) evidence.path("checks").get(0);
        ((ArrayNode) current.path("testManifest").path("cases")).remove(0);
        current.put("tests", 1).put("executedTests", 1);
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(evidence));
    }

    @Test
    void unchangedHistoricalFailureIsDebtAndNeverPassingTests() {
        var evidence = evidence(true);
        assertEquals("BASELINE_DEBT", evidence.path("verificationLevel").asText());
        assertEquals("TESTS_FAILED", evidence.path("checks").get(0).path("classification").asText());
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(evidence));
        evidence.put("verificationLevel", "FULLY_TESTED");
        assertEquals("EVIDENCE_INVALID", assertThrows(AgentException.class,
                () -> AgentCheckEvidence.requireAcceptable(evidence)).code());
    }

    private void rejected(Consumer<ObjectNode> mutation) {
        var evidence = evidence(true);
        mutation.accept(evidence);
        assertThrows(AgentException.class, () -> AgentCheckEvidence.requireAcceptable(evidence));
    }

    @Test
    void fewerFailuresCannotHideANewFailure() {
        rejected(evidence -> {
            var baseline = (ObjectNode) evidence.path("baselineChecks").get(0);
            baseline.put("tests", 2).put("executedTests", 2).put("failures", 2);
            ((ArrayNode) baseline.path("testManifest").path("cases")).add(test("3", "FAILED", "AnotherLegacyTest.java"));
            var cases = evidence.path("checks").get(0).path("testManifest").withArray("cases");
            ((ObjectNode) cases.get(0)).put("outcome", "PASSED").put("failure", "");
            ((ObjectNode) cases.get(1)).put("outcome", "FAILED").put("failure", "e".repeat(64));
            ((ArrayNode) cases).add(test("3", "PASSED", "AnotherLegacyTest.java"));
            ((ObjectNode) evidence.path("checks").get(0)).put("tests", 3).put("executedTests", 3);
        });
    }

    @Test
    void deletionAndSkippingCannotHideHistoricalFailures() {
        rejected(evidence -> ((ArrayNode) evidence.path("checks").get(0).path("testManifest").path("cases")).remove(0));
        rejected(evidence -> ((ObjectNode) evidence.path("checks").get(0).path("testManifest").path("cases").get(0))
                .put("outcome", "SKIPPED"));
    }

    @Test
    void incompleteDifferentEnvironmentOrChangedFailureIsIncomparable() {
        rejected(evidence -> ((ObjectNode) evidence.path("checks").get(0).path("testManifest")).put("complete", false));
        rejected(evidence -> ((ObjectNode) evidence.path("checks").get(0).path("environment")).put("image", "other"));
        rejected(evidence -> ((ObjectNode) evidence.path("checks").get(0).path("testManifest").path("cases").get(0))
                .put("failure", "e".repeat(64)));
    }

    @Test
    void failureInChangedFeatureOrItsStackCannotBecomeBaselineDebt() {
        rejected(evidence -> evidence.withArray("changedPaths").add("LegacyTest.java"));
        rejected(evidence -> ((ArrayNode) evidence.path("checks").get(0).path("testManifest").path("cases").get(0)
                .path("affectedSources")).add("Feature.java"));
    }

    @Test
    void staleSelectiveAndUnexecutedFeatureEvidenceDoesNotQualify() {
        rejected(evidence -> ((ObjectNode) evidence.path("checks").get(0)).put("stale", true));
        rejected(evidence -> ((ObjectNode) evidence.path("checks").get(0)).put("selectedTest", "FeatureTest"));
        rejected(evidence -> ((ObjectNode) evidence.path("checks").get(0)).put("executedTests", 0));
    }

    @Test
    void passingExistingTestsDoNotRequireAnEditedTestOrIdenticalPom() {
        var evidence = evidence(false);
        evidence.withArray("changedPaths").removeAll().add("Feature.java").add("pom.xml");
        ((ObjectNode) evidence.path("checks").get(0).path("environment")).put("configuration", "f".repeat(64));
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(evidence));
    }

    @Test
    void resolvedHistoricalFailuresNeedNoDebtAcknowledgment() {
        var evidence = evidence(true);
        var current = check("a", false, true);
        evidence.withArray("checks").removeAll().add(current);
        evidence.put("status", "TESTED");
        summarize(evidence);
        assertEquals("FULLY_TESTED", evidence.path("verificationLevel").asText());
        assertDoesNotThrow(() -> AgentCheckEvidence.requireAcceptable(evidence));
    }
}
