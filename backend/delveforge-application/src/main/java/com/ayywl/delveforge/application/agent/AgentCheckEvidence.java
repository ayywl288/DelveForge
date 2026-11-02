package com.ayywl.delveforge.application.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;

/** 冻结与交付共用检查适用性规则；旧记录缺少类型时只按 Maven 解释。 */
public final class AgentCheckEvidence {
    private AgentCheckEvidence() {
    }

    public static void requireAcceptable(JsonNode evidence) {
        if (evidence == null || !Set.of("TESTED", "NOT_TESTED").contains(evidence.path("status").asText())
                || evidence.has("checks") && !evidence.path("checks").isArray()) {
            throw new AgentException("EVIDENCE_INVALID", "Frozen evidence status cannot be established");
        }
        Set<String> unresolved = new HashSet<>();
        Set<String> failedTests = new HashSet<>();
        boolean versioned = evidence.path("evidenceVersion").asInt() >= 2;
        String snapshot = evidence.path("finalSnapshot").asText();
        boolean tested = false;
        if (versioned && !snapshot.matches("[0-9a-f]{64}")) {
            throw new AgentException("EVIDENCE_INVALID", "Final source snapshot is required");
        }
        for (JsonNode check : evidence.path("checks")) {
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
            boolean passed = Set.of("TESTS_PASSED", "BUILD_PASSED_NOT_TESTED").contains(status);
            boolean current = !check.path("stale").asBoolean(true)
                    && (!versioned || snapshot.equals(check.path("snapshot").asText()));
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
            tested |= current && status.equals("TESTS_PASSED");
            boolean unavailable = type.equals("FRONTEND") && status.equals("CHECK_NOT_AVAILABLE");
            if (unavailable && (check.path("tests").asInt() != 0 || check.path("executedTests").asInt() != 0
                    || check.path("reports").asInt() != 0 || check.path("failures").asInt() != 0
                    || check.path("errors").asInt() != 0 || check.path("skipped").asInt() != 0)) {
                throw new AgentException("EVIDENCE_INVALID", "Unavailable checks cannot contain execution evidence");
            }
            if (!passed && !unavailable) {
                unresolved.add(key);
                if (Set.of("TESTS_FAILED", "CHECK_RESULT_UNAVAILABLE").contains(status)
                        && (!type.equals("FRONTEND") || goal.equals("test"))) {
                    failedTests.add(key);
                }
            } else if (passed && current && (type.equals("FRONTEND")
                    || Set.of("test", "verify").contains(goal)
                        && check.path("module").asText("").isBlank()
                        && check.path("selectedTest").asText("").isBlank())
                    && (!failedTests.contains(key) || status.equals("TESTS_PASSED"))) {
                unresolved.remove(key);
                failedTests.remove(key);
            }
        }
        if (!unresolved.isEmpty()) {
            throw new AgentException("UNRESOLVED_CHECK_FAILURE", "Known unresolved checks require a current matching recheck");
        }
        if (versioned && tested != evidence.path("status").asText().equals("TESTED")) {
            throw new AgentException("EVIDENCE_INVALID", "Evidence summary does not match current test execution");
        }
    }
}
