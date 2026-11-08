package com.ayywl.delveforge.application.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** v7 只核验最终报告引用的执行事实；检查充分性和失败处理说明仍归属于 Agent。 */
public final class CompletionEvidence {
    private CompletionEvidence() {
    }

    public static void requireAcceptable(JsonNode evidence) {
        String snapshot = evidence.path("finalSnapshot").asText();
        String runId = evidence.path("runId").asText();
        JsonNode report = evidence.path("agentReport");
        JsonNode view = evidence.path("sourceView");
        if (!snapshot.matches("[0-9a-f]{64}") || runId.isBlank() || !evidence.path("checks").isArray()
                || !Set.of("FULL", "PROJECTED").contains(view.path("kind").asText())) {
            throw new AgentException("EVIDENCE_INVALID", "Execution identity or view is missing");
        }
        for (String count : Set.of("preservedOnlyCount", "readOnlyResourceCount", "withheldSourceCount")) {
            if (!view.path(count).canConvertToInt() || !view.path(count).isIntegralNumber()
                    || view.path(count).asInt() < 0) {
                throw new AgentException("EVIDENCE_INVALID", "Execution view counts are invalid");
            }
        }
        if (view.path("kind").asText().equals("PROJECTED") != (view.path("preservedOnlyCount").asInt() > 0)) {
            throw new AgentException("EVIDENCE_INVALID", "Execution projection contradicts resource counts");
        }
        if (!report.path("status").asText().equals("completed")
                || !report.path("validationComplete").isBoolean() || !report.path("validationComplete").asBoolean()
                || !report.path("executions").isArray() || report.path("executions").isEmpty()) {
            throw new AgentException("UNRESOLVED_CHECK_FAILURE",
                    "Report must reference actual final executions and declare necessary validation complete");
        }
        Map<String, JsonNode> receipts = new HashMap<>();
        for (JsonNode receipt : evidence.path("checks")) {
            String id = receipt.path("invocationId").asText();
            if (id.isBlank() || receipts.put(id, receipt) != null
                    || !runId.equals(receipt.path("runId").asText())
                    || !receipt.path("exitCode").isIntegralNumber()
                    || !receipt.path("timedOut").isBoolean() || !receipt.path("cancelled").isBoolean()) {
                throw new AgentException("EVIDENCE_INVALID", "Invalid execution receipt");
            }
        }
        Set<String> referenced = new HashSet<>();
        for (JsonNode reference : report.path("executions")) {
            JsonNode receipt = receipts.get(reference.asText());
            if (!reference.isTextual() || !referenced.add(reference.asText()) || receipt == null) {
                throw new AgentException("EVIDENCE_INVALID", "Unknown or duplicate execution reference");
            }
            if (receipt.path("exitCode").asInt() != 0 || receipt.path("timedOut").asBoolean()
                    || receipt.path("cancelled").asBoolean()
                    || !receipt.path("classification").asText().equals("COMMAND_SUCCEEDED")
                    || !snapshot.equals(receipt.path("snapshot").asText())
                    || !snapshot.equals(receipt.path("afterSnapshot").asText())
                    || !view.equals(receipt.path("sourceView"))) {
                throw new AgentException("UNRESOLVED_CHECK_FAILURE", "Referenced execution failed or is source/view stale");
            }
            JsonNode tests = receipt.path("testReport");
            if (tests.path("availability").asText().equals("AVAILABLE")
                    && (tests.path("failures").asInt() > 0 || tests.path("errors").asInt() > 0)) {
                throw new AgentException("UNRESOLVED_CHECK_FAILURE", "Observed test failures contradict passing report");
            }
        }
    }
}
