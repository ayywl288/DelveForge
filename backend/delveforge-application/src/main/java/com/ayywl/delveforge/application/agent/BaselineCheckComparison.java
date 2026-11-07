package com.ayywl.delveforge.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** v5 仅接受同环境完整清单的逐例比较；基线债务不是 TESTS_PASSED。 */
public final class BaselineCheckComparison {
    private BaselineCheckComparison() {
    }

    public record Result(Set<String> debtKeys, ArrayNode debt) {
        public String level() {
            return debtKeys.isEmpty() ? "FULLY_TESTED" : "BASELINE_DEBT";
        }
    }

    static String key(JsonNode check) {
        String type = check.path("checkType").asText("MAVEN");
        return type + ":" + check.path("directory").asText(".")
                + (type.equals("FRONTEND") ? ":" + check.path("goal").asText() : "");
    }

    private static boolean full(JsonNode check) {
        return check.path("module").asText("").isBlank() && check.path("selectedTest").asText("").isBlank();
    }

    public static Result compare(JsonNode evidence) {
        if (!evidence.path("baselineChecks").isArray() || evidence.path("baselineChecks").isEmpty()
                || !evidence.path("changedPaths").isArray()) {
            throw invalid("Complete baseline checks and changed paths are required");
        }
        Set<String> changed = new HashSet<>();
        evidence.path("changedPaths").forEach(path -> changed.add(path.asText()));
        Map<String, JsonNode> latest = new HashMap<>();
        for (JsonNode check : evidence.path("checks")) {
            if (full(check) && !check.path("stale").asBoolean(true)
                    && evidence.path("finalSnapshot").asText().equals(check.path("snapshot").asText())) {
                latest.put(key(check), check);
            }
        }
        Set<String> debtKeys = new HashSet<>();
        ArrayNode debt = JsonNodeFactory.instance.arrayNode();
        Set<String> compared = new HashSet<>();
        for (JsonNode baseline : evidence.path("baselineChecks")) {
            String key = key(baseline);
            if (!compared.add(key) || !full(baseline)) {
                throw invalid("Baseline check identity is ambiguous");
            }
            JsonNode current = latest.get(key);
            if (current == null) {
                throw blocked("Current full check required for " + key);
            }
            if (!baseline.path("environment").isObject()
                    || !baseline.path("environment").path("image").asText().matches("sha256:[0-9a-f]{64}")
                    || !baseline.path("environment").path("configuration").asText().matches("[0-9a-f]{64}")
                    || !baseline.path("environment").equals(current.path("environment"))
                    || !baseline.path("sourceView").equals(current.path("sourceView"))
                    || !current.path("sourceView").equals(evidence.path("sourceView"))
                    || current.path("sourceView").path("withheldSourceCount").asInt() > 0) {
                throw invalid("Baseline and final execution environments differ for " + key);
            }
            String goal = baseline.path("goal").asText();
            if (!Set.of("test", "verify").contains(goal)) {
                if (!current.path("classification").asText().equals("BUILD_PASSED_NOT_TESTED")) {
                    throw blocked("Current build/typecheck must pass for " + key);
                }
                continue;
            }
            if (!baseline.path("environment").path("runtimeDependencies").asText().matches("[0-9a-f]{64}")) {
                throw invalid("Test runtime dependency identity is unknown for " + key);
            }
            Map<String, JsonNode> before = cases(baseline);
            Map<String, JsonNode> after = cases(current);
            if (after.isEmpty()) {
                throw invalid("Executed test inventory is empty");
            }
            boolean featurePassed = false;
            for (var entry : before.entrySet()) {
                JsonNode old = entry.getValue();
                JsonNode now = after.get(entry.getKey());
                if (now == null || !old.path("outcome").asText().equals("SKIPPED")
                        && now.path("outcome").asText().equals("SKIPPED")) {
                    throw testBlocked("BASELINE_TEST_MISSING_OR_SKIPPED", current, old, "");
                }
            }
            for (var entry : after.entrySet()) {
                JsonNode now = entry.getValue();
                JsonNode old = before.get(entry.getKey());
                boolean modifiedTest = changed.contains(now.path("source").asText());
                boolean affectedSource = false;
                String affectedPath = "";
                for (JsonNode path : now.path("affectedSources")) {
                    if (changed.contains(path.asText())) {
                        affectedSource = true;
                        affectedPath = path.asText();
                        break;
                    }
                }
                String outcome = now.path("outcome").asText();
                if (modifiedTest && !outcome.equals("PASSED")) {
                    throw testBlocked("CHANGED_TEST_NOT_PASSED", current, now, "");
                }
                featurePassed |= modifiedTest && outcome.equals("PASSED");
                if (Set.of("FAILED", "ERROR").contains(outcome)) {
                    var differences = new ArrayList<String>();
                    if (old == null) {
                        differences.add("NEW_TEST_FAILURE");
                    } else {
                        if (!old.path("outcome").equals(now.path("outcome"))) {
                            differences.add("OUTCOME_CHANGED");
                        }
                        if (!old.path("failure").equals(now.path("failure"))) {
                            differences.add("FAILURE_CHANGED");
                        }
                        if (!old.path("source").equals(now.path("source"))
                                || !old.path("sourceSha256").equals(now.path("sourceSha256"))) {
                            differences.add("TEST_DEFINITION_CHANGED");
                        }
                    }
                    if (affectedSource) {
                        differences.add("AFFECTED_SOURCE_CHANGED");
                    }
                    if (!differences.isEmpty()) {
                        throw testBlocked(String.join(",", differences), current, now, affectedPath);
                    }
                    debtKeys.add(key);
                    debt.addObject().put("check", key).put("testId", entry.getKey())
                            .put("failure", now.path("failure").asText()).put("source", now.path("source").asText());
                }
            }
            String directory = current.path("directory").asText(".");
            boolean projectChanged = changed.stream().anyMatch(path -> directory.equals(".")
                    || path.startsWith(directory + "/"));
            if (projectChanged && !featurePassed) {
                throw blocked("Passing changed-feature tests are required for " + key);
            }
        }
        return new Result(Set.copyOf(debtKeys), debt);
    }

    private static Map<String, JsonNode> cases(JsonNode check) {
        JsonNode manifest = check.path("testManifest");
        if (manifest.path("version").asInt() != 1 || !manifest.path("complete").asBoolean()
                || !manifest.path("cases").isArray() || manifest.path("cases").size() > 10000) {
            throw invalid("Complete versioned per-test inventory is required");
        }
        Map<String, JsonNode> result = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (JsonNode test : manifest.path("cases")) {
            String id = test.path("id").asText();
            String outcome = test.path("outcome").asText();
            if (!id.matches("[0-9a-f]{64}") || result.putIfAbsent(id, test) != null
                    || !Set.of("PASSED", "FAILED", "ERROR", "SKIPPED").contains(outcome)
                    || test.path("source").asText().isBlank()
                    || !test.path("sourceSha256").asText().matches("[0-9a-f]{64}")
                    || !test.path("affectedSources").isArray()
                    || Set.of("FAILED", "ERROR").contains(outcome)
                        && !test.path("failure").asText().matches("[0-9a-f]{64}")) {
                throw invalid("Per-test identity or source fingerprint is incomplete");
            }
            counts.merge(outcome, 1, Integer::sum);
        }
        if (result.size() != check.path("tests").asInt(-1)
                || counts.getOrDefault("SKIPPED", 0) != check.path("skipped").asInt(-1)
                || counts.getOrDefault("FAILED", 0) != check.path("failures").asInt(-1)
                || counts.getOrDefault("ERROR", 0) != check.path("errors").asInt(-1)
                || result.size() - counts.getOrDefault("SKIPPED", 0) != check.path("executedTests").asInt(-1)
                || check.path("reports").asInt() <= 0
                || !Set.of("TESTS_PASSED", "TESTS_FAILED").contains(check.path("classification").asText())) {
            throw invalid("Per-test inventory contradicts executed counts");
        }
        return result;
    }

    private static AgentException invalid(String message) {
        return new AgentException("EVIDENCE_INCOMPARABLE", message);
    }

    /** 只暴露比较条件与可定位路径，不把原始异常正文或测试内容带入模型反馈。 */
    private static AgentException testBlocked(String reason, JsonNode check, JsonNode test, String affectedPath) {
        String scope = check.path("checkType").asText("MAVEN") + ":" + check.path("directory").asText(".")
                + ":" + check.path("goal").asText("test");
        return blocked(reason + "; check=" + bounded(scope) + "; testSource=" + bounded(test.path("source").asText())
                + "; testId=" + test.path("id").asText()
                + (affectedPath.isEmpty() ? "" : "; affectedSource=" + bounded(affectedPath)));
    }

    private static String bounded(String value) {
        return value.substring(0, Math.min(value.length(), 512));
    }

    private static AgentException blocked(String message) {
        return new AgentException("UNRESOLVED_CHECK_FAILURE", message);
    }
}
