package com.ayywl.delveforge.application.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 版本化完成合同：v7 保留历史门槛，v8 只核验事实身份；完成判断归属于 Agent。 */
public final class CompletionEvidence {
    private CompletionEvidence() {
    }

    public static void requireAcceptable(JsonNode evidence) {
        boolean factual = evidence.path("evidenceVersion").asInt() == 8;
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
        if (!report.path("status").asText().equals("completed")) {
            throw new AgentException("UNRESOLVED_CHECK_FAILURE", "Agent did not report completion");
        }
        if (factual) {
            if (!report.path("summary").isTextual() || report.path("summary").asText().isBlank()
                    || report.has("executions") && !report.path("executions").isArray()
                    || report.has("limitations") && !report.path("limitations").isArray()) {
                throw new AgentException("EVIDENCE_INVALID", "Invalid completion report structure");
            }
            for (JsonNode limitation : report.path("limitations")) {
                if (!limitation.isTextual()) {
                    throw new AgentException("EVIDENCE_INVALID", "Limitations must be text");
                }
            }
        } else if (!report.path("validationComplete").isBoolean() || !report.path("validationComplete").asBoolean()
                || !report.path("executions").isArray() || report.path("executions").isEmpty()) {
            throw new AgentException("UNRESOLVED_CHECK_FAILURE", "Historical v7 requires successful validation");
        }
        Map<String, JsonNode> receipts = new HashMap<>();
        for (JsonNode receipt : evidence.path("checks")) {
            String id = receipt.path("invocationId").asText();
            if (id.isBlank() || receipts.put(id, receipt) != null
                    || !runId.equals(receipt.path("runId").asText())
                    || !receipt.path("exitCode").isIntegralNumber() || !receipt.path("exitCode").canConvertToInt()
                    || !receipt.path("timedOut").isBoolean() || !receipt.path("cancelled").isBoolean()) {
                throw new AgentException("EVIDENCE_INVALID", "Invalid execution receipt");
            }
        }
        if (factual) {
            for (JsonNode receipt : receipts.values()) {
                requireReceipt(receipt);
            }
        }
        Set<String> referenced = new HashSet<>();
        for (JsonNode reference : report.path("executions")) {
            JsonNode receipt = receipts.get(reference.asText());
            if (!reference.isTextual() || !referenced.add(reference.asText()) || receipt == null) {
                throw new AgentException("EVIDENCE_INVALID", "Unknown or duplicate execution reference");
            }
            if (!factual && (receipt.path("exitCode").asInt() != 0 || receipt.path("timedOut").asBoolean()
                    || receipt.path("cancelled").asBoolean()
                    || !receipt.path("classification").asText().equals("COMMAND_SUCCEEDED"))
                    || !snapshot.equals(receipt.path("snapshot").asText())
                    || !snapshot.equals(receipt.path("afterSnapshot").asText())
                    || !view.equals(receipt.path("sourceView"))) {
                throw new AgentException("UNRESOLVED_CHECK_FAILURE", "Referenced execution failed or is source/view stale");
            }
            JsonNode tests = receipt.path("testReport");
            if (!factual && tests.path("availability").asText().equals("AVAILABLE")
                    && (tests.path("failures").asInt() > 0 || tests.path("errors").asInt() > 0)) {
                throw new AgentException("UNRESOLVED_CHECK_FAILURE", "Observed test failures contradict passing report");
            }
        }
    }
    private static void requireReceipt(JsonNode receipt) {
        for (String field : Set.of("command", "directory", "stdout", "stderr")) {
            if (!receipt.path(field).isTextual()) {
                throw new AgentException("EVIDENCE_INVALID", "Execution text is missing");
            }
        }
        for (String field : Set.of("snapshot", "afterSnapshot")) {
            if (!receipt.path(field).asText().matches("[0-9a-f]{64}")) {
                throw new AgentException("EVIDENCE_INVALID", "Execution source identity is invalid");
            }
        }
        JsonNode view = receipt.path("sourceView");
        if (!Set.of("FULL", "PROJECTED").contains(view.path("kind").asText())) {
            throw new AgentException("EVIDENCE_INVALID", "Execution view is missing");
        }
        for (String count : Set.of("preservedOnlyCount", "readOnlyResourceCount", "withheldSourceCount")) {
            if (!view.path(count).isIntegralNumber() || !view.path(count).canConvertToInt()
                    || view.path(count).asInt() < 0) {
                throw new AgentException("EVIDENCE_INVALID", "Execution view counts are invalid");
            }
        }
        if (view.path("kind").asText().equals("PROJECTED") != (view.path("preservedOnlyCount").asInt() > 0)) {
            throw new AgentException("EVIDENCE_INVALID", "Execution view contradicts resource counts");
        }
        String expected = receipt.path("cancelled").asBoolean() ? "COMMAND_CANCELLED"
                : receipt.path("timedOut").asBoolean() ? "COMMAND_TIMED_OUT"
                : receipt.path("exitCode").asInt() == 0 ? "COMMAND_SUCCEEDED" : "COMMAND_FAILED";
        if (!expected.equals(receipt.path("classification").asText())
                || !receipt.path("durationMs").isIntegralNumber() || receipt.path("durationMs").asLong() < 0
                || !receipt.path("outputTruncated").isBoolean() || !receipt.path("sourceChanged").isBoolean()
                || receipt.path("sourceChanged").asBoolean()
                    == receipt.path("snapshot").equals(receipt.path("afterSnapshot"))) {
            throw new AgentException("EVIDENCE_INVALID", "Execution outcome contradicts recorded facts");
        }
    }

}
