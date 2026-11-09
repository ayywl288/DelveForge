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
            if (report.has("executions") && !report.path("executions").isArray()) {
                throw new CompletionReferenceFailure("EVIDENCE_INVALID", "INVALID_REFERENCE_LIST",
                        evidence, -1, report.path("executions"), null);
            }
        }
        Set<String> referenced = new HashSet<>();
        int index = -1;
        for (JsonNode reference : report.path("executions")) {
            index++;
            JsonNode receipt = receipts.get(reference.asText());
            if (!reference.isTextual() || receipt == null || !referenced.add(reference.asText())) {
                String issue = !reference.isTextual() ? "INVALID_REFERENCE"
                        : receipt == null ? "UNKNOWN_REFERENCE" : "DUPLICATE_REFERENCE";
                throw new CompletionReferenceFailure("EVIDENCE_INVALID", issue, evidence, index, reference, receipt);
            }
            if (!factual && (receipt.path("exitCode").asInt() != 0 || receipt.path("timedOut").asBoolean()
                    || receipt.path("cancelled").asBoolean()
                    || !receipt.path("classification").asText().equals("COMMAND_SUCCEEDED"))) {
                throw new CompletionReferenceFailure("UNRESOLVED_CHECK_FAILURE", "FAILED_EXECUTION",
                        evidence, index, reference, receipt);
            }
            if (!matchesFinalSource(snapshot, view, receipt)) {
                String issue = !snapshot.equals(receipt.path("snapshot").asText())
                        || !snapshot.equals(receipt.path("afterSnapshot").asText()) ? "STALE_SOURCE" : "VIEW_MISMATCH";
                throw new CompletionReferenceFailure("UNRESOLVED_CHECK_FAILURE", issue, evidence, index, reference, receipt);
            }
            JsonNode tests = receipt.path("testReport");
            if (!factual && tests.path("availability").asText().equals("AVAILABLE")
                    && (tests.path("failures").asInt() > 0 || tests.path("errors").asInt() > 0)) {
                throw new CompletionReferenceFailure("UNRESOLVED_CHECK_FAILURE", "FAILED_TEST_REPORT",
                        evidence, index, reference, receipt);
            }
        }
    }

    /** 收据只有在执行前后源码及执行视图均与最终状态一致时才能说明当前版本。 */
    public static boolean matchesFinalSource(String snapshot, JsonNode view, JsonNode receipt) {
        return snapshot.equals(receipt.path("snapshot").asText()) && snapshot.equals(receipt.path("afterSnapshot").asText())
                && view.equals(receipt.path("sourceView"));
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
