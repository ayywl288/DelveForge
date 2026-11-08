package com.ayywl.delveforge.application.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 冻结与交付共用检查适用性规则；旧记录缺少类型时只按 Maven 解释。 */
public final class AgentCheckEvidence {
    private AgentCheckEvidence() {
    }

    public static void requireAcceptable(JsonNode evidence) {
        if (evidence != null && Set.of(7, 8).contains(evidence.path("evidenceVersion").asInt())) {
            CompletionEvidence.requireAcceptable(evidence);
            return;
        }
        if (evidence != null && evidence.path("evidenceVersion").asInt() == 6) {
            CommandCheckEvidence.requireAcceptable(evidence);
            return;
        }
        if (evidence == null || !Set.of("TESTED", "NOT_TESTED").contains(evidence.path("status").asText())
                || evidence.has("checks") && !evidence.path("checks").isArray()) {
            throw new AgentException("EVIDENCE_INVALID", "Frozen evidence status cannot be established");
        }
        Set<String> unresolved = new HashSet<>();
        Set<String> failedTests = new HashSet<>();
        if (evidence.path("evidenceVersion").asInt() > 5) {
            throw new AgentException("EVIDENCE_INVALID", "Unsupported evidence version");
        }
        BaselineCheckComparison.Result comparison = evidence.path("evidenceVersion").asInt() == 5
                ? BaselineCheckComparison.compare(evidence) : null;
        if (comparison != null && (!comparison.level().equals(evidence.path("verificationLevel").asText())
                || !comparison.debt().equals(evidence.path("baselineDebt")))) {
            throw new AgentException("EVIDENCE_INVALID", "Baseline debt summary differs from per-test evidence");
        }
        boolean versioned = evidence.path("evidenceVersion").asInt() >= 2;
        boolean scriptEvidence = evidence.path("evidenceVersion").asInt() >= 3;
        String snapshot = evidence.path("finalSnapshot").asText();
        boolean tested = false;
        if (evidence.has("sourceView")) {
            requireSourceView(evidence.path("sourceView"));
        }
        if (versioned && !snapshot.matches("[0-9a-f]{64}")) {
            throw new AgentException("EVIDENCE_INVALID", "Final source snapshot is required");
        }
        List<JsonNode> ordered = new ArrayList<>();
        if (evidence.has("baselineFailures")) {
            if (evidence.path("evidenceVersion").asInt() < 4 || !evidence.path("baselineFailures").isArray()) {
                throw new AgentException("EVIDENCE_INVALID", "Baseline failure evidence requires version 4");
            }
            for (JsonNode baseline : evidence.path("baselineFailures")) {
                if (!Set.of("TESTS_FAILED", "COMPILATION_FAILED", "TYPECHECK_FAILED", "FRONTEND_BUILD_FAILED")
                        .contains(baseline.path("classification").asText()) || !baseline.path("stale").asBoolean()) {
                    throw new AgentException("EVIDENCE_INVALID", "Baseline probes cannot attest final source");
                }
                ordered.add(baseline);
            }
        }
        evidence.path("checks").forEach(ordered::add);
        for (JsonNode check : ordered) {
            String type = check.path("checkType").asText("MAVEN");
            String goal = check.path("goal").asText();
            if (!Set.of("MAVEN", "FRONTEND").contains(type)
                    || type.equals("FRONTEND") && (!versioned
                        || !Set.of("typecheck", "build", "test").contains(goal))) {
                throw new AgentException("EVIDENCE_INVALID", "Unsupported check identity");
            }
            String key = type + ":" + check.path("directory").asText(".")
                    + (type.equals("FRONTEND") ? ":" + goal : "");
            String status = check.path("classification").asText();
            String execution = check.path("executionStatus").asText();
            String runner = check.path("runner").asText();
            if (scriptEvidence && type.equals("FRONTEND")
                    && (!Set.of("NOT_STARTED", "EXECUTED", "UNKNOWN").contains(execution)
                        || !runner.isEmpty() && !Set.of("node-junit", "vitest-json", "node-junit+vitest-json")
                            .contains(runner))) {
                throw new AgentException("EVIDENCE_INVALID", "Frontend execution identity is required");
            }
            boolean passed = Set.of("TESTS_PASSED", "BUILD_PASSED_NOT_TESTED").contains(status);
            boolean current = !check.path("stale").asBoolean(true)
                    && (!versioned || snapshot.equals(check.path("snapshot").asText()));
            if (evidence.has("sourceView") && passed && current) {
                requireSourceView(check.path("sourceView"));
                if (!evidence.path("sourceView").equals(check.path("sourceView"))) {
                    throw new AgentException("EVIDENCE_INVALID", "Check execution view differs from final source view");
                }
                if (evidence.path("sourceView").path("withheldSourceCount").asInt() > 0) {
                    throw new AgentException("EVIDENCE_INVALID", "Withheld executable source cannot produce passing checks");
                }
            }
            if (versioned) {
                for (String field : Set.of("tests", "executedTests", "failures", "errors", "skipped", "reports")) {
                    if (!check.path(field).isIntegralNumber() || !check.path(field).canConvertToInt()
                            || check.path(field).asInt() < 0) {
                        throw new AgentException("EVIDENCE_INVALID", "Check counts must be bounded nonnegative integers");
                    }
                }
            }
            if ((versioned || type.equals("FRONTEND")) && status.equals("TESTS_PASSED")
                    && (check.path("executedTests").asInt() <= 0
                        || check.path("failures").asInt() != 0 || check.path("errors").asInt() != 0
                        || type.equals("FRONTEND") && !goal.equals("test"))) {
                throw new AgentException("EVIDENCE_INVALID", "Passing tests require actual test execution");
            }
            if (scriptEvidence && type.equals("FRONTEND") && status.equals("TESTS_PASSED")
                    && (!execution.equals("EXECUTED") || runner.isEmpty() || check.path("reports").asInt() <= 0
                        || runner.contains("+") && check.path("reports").asInt() < 2
                        || check.path("tests").asInt() != check.path("executedTests").asInt()
                            + check.path("skipped").asInt())) {
                throw new AgentException("EVIDENCE_INVALID", "Passing frontend tests require an attested runner report");
            }
            tested |= current && status.equals("TESTS_PASSED");
            boolean unavailable = type.equals("FRONTEND") && status.equals("CHECK_NOT_AVAILABLE");
            boolean configurationRejected = scriptEvidence && type.equals("FRONTEND")
                    && execution.equals("NOT_STARTED")
                    && Set.of("UNSUPPORTED_FRONTEND_CONFIGURATION", "FRONTEND_ENVIRONMENT_MISMATCH").contains(status);
            boolean unverified = scriptEvidence && type.equals("FRONTEND")
                    && execution.equals("EXECUTED") && status.equals("TEST_EXECUTION_UNVERIFIED");
            if (unverified && (check.path("executedTests").asInt() != 0
                    || check.path("failures").asInt() != 0 || check.path("errors").asInt() != 0)) {
                throw new AgentException("EVIDENCE_INVALID", "Unverified execution cannot hide executed or failed tests");
            }
            if ((unavailable || configurationRejected) && (check.path("tests").asInt() != 0 || check.path("executedTests").asInt() != 0
                    || check.path("reports").asInt() != 0 || check.path("failures").asInt() != 0
                    || check.path("errors").asInt() != 0 || check.path("skipped").asInt() != 0)) {
                throw new AgentException("EVIDENCE_INVALID", "Unavailable checks cannot contain execution evidence");
            }
            boolean acceptedDebt = comparison != null && comparison.debtKeys().contains(key) && current
                    && status.equals("TESTS_FAILED") && Set.of("test", "verify").contains(goal)
                    && check.path("module").asText("").isBlank()
                    && check.path("selectedTest").asText("").isBlank();
            if (acceptedDebt) {
                // 只解除已逐例证明未回归的检查义务，保留失败分类和计数。
                unresolved.remove(key);
                failedTests.remove(key);
                for (String report : runner.split("\\+")) {
                    unresolved.remove(key + ":" + report);
                    failedTests.remove(key + ":" + report);
                }
            } else if (!passed && !unavailable && !configurationRejected && !unverified) {
                Set<String> failureKeys = new HashSet<>();
                if (scriptEvidence && type.equals("FRONTEND") && !runner.isEmpty()) {
                    for (String report : runner.split("\\+")) {
                        failureKeys.add(key + ":" + report);
                    }
                } else {
                    failureKeys.add(key);
                }
                unresolved.addAll(failureKeys);
                if (Set.of("TESTS_FAILED", "CHECK_RESULT_UNAVAILABLE").contains(status)
                        && (!type.equals("FRONTEND") || goal.equals("test"))
                        || scriptEvidence && type.equals("FRONTEND") && goal.equals("test")
                            && !execution.equals("NOT_STARTED")) {
                    failedTests.addAll(failureKeys);
                }
            } else if (passed && current && (type.equals("FRONTEND")
                    || Set.of("test", "verify").contains(goal)
                        && check.path("module").asText("").isBlank()
                        && check.path("selectedTest").asText("").isBlank())) {
                Set<String> matching = new HashSet<>();
                matching.add(key);
                for (String report : runner.split("\\+")) {
                    matching.add(key + ":" + report);
                }
                for (String resolved : matching) {
                    if (!failedTests.contains(resolved) || status.equals("TESTS_PASSED")) {
                        unresolved.remove(resolved);
                        failedTests.remove(resolved);
                    }
                }
            }
        }
        if (!unresolved.isEmpty()) {
            throw new AgentException("UNRESOLVED_CHECK_FAILURE", "Known unresolved checks require a current matching recheck");
        }
        if (versioned && tested != evidence.path("status").asText().equals("TESTED")) {
            throw new AgentException("EVIDENCE_INVALID", "Evidence summary does not match current test execution");
        }
    }

    private static void requireSourceView(JsonNode view) {
        if (!view.isObject() || !Set.of("FULL", "PROJECTED").contains(view.path("kind").asText())) {
            throw new AgentException("EVIDENCE_INVALID", "Execution view is missing or unsupported");
        }
        for (String field : Set.of("preservedOnlyCount", "readOnlyResourceCount", "withheldSourceCount")) {
            if (!view.path(field).isIntegralNumber() || !view.path(field).canConvertToInt() || view.path(field).asInt() < 0) {
                throw new AgentException("EVIDENCE_INVALID", "Execution resource counts must be bounded integers");
            }
        }
        if (view.path("kind").asText().equals("PROJECTED") != (view.path("preservedOnlyCount").asInt() > 0)) {
            throw new AgentException("EVIDENCE_INVALID", "Execution projection contradicts its omitted resources");
        }
        if (view.path("withheldSourceCount").asInt() > view.path("preservedOnlyCount").asInt()) {
            throw new AgentException("EVIDENCE_INVALID", "Withheld source exceeds preserved resources");
        }
    }
}
