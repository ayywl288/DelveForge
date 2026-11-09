package com.ayywl.delveforge.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 引用拒绝只公开有界身份及收据事实，不复制模型正文、命令、输出或测试用例名。 */
public final class CompletionReferenceFailure extends AgentException {
    private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private final ObjectNode feedback;

    CompletionReferenceFailure(String code, String issue, JsonNode evidence, int index,
            JsonNode reference, JsonNode receipt) {
        super(code, (index < 0 ? "executions" : "executions[" + index + "] " + displayId(reference)) + "：" + switch (issue) {
            case "INVALID_REFERENCE_LIST" -> "请用数组列出调用 ID。";
            case "UNKNOWN_REFERENCE" -> "本 Run 没有这条执行收据。";
            case "INVALID_REFERENCE" -> "引用必须是 invocationId 字符串。";
            case "DUPLICATE_REFERENCE" -> "同一执行被重复引用。";
            case "STALE_SOURCE" -> "执行前或执行后的源码与最终源码不同。";
            case "VIEW_MISMATCH" -> "执行视图与最终视图不同。";
            case "FAILED_EXECUTION" -> "历史验证要求成功，此执行未成功。";
            case "FAILED_TEST_REPORT" -> "历史验证要求通过，此执行的测试报告有失败。";
            default -> throw new IllegalArgumentException("Unknown reference rejection");
        });
        String snapshot = evidence.path("finalSnapshot").asText();
        JsonNode view = evidence.path("sourceView");
        feedback = JsonNodeFactory.instance.objectNode().put("classification", code).put("reason", getMessage())
                .put("issue", issue).put("referenceIndex", index).put("reference", displayId(reference))
                .put("finalSnapshot", snapshot);
        feedback.set("finalView", viewFacts(view));
        if (receipt != null) {
            ObjectNode detail = receiptFacts(receipt);
            detail.put("snapshot", safeHash(receipt.path("snapshot")));
            detail.put("afterSnapshot", safeHash(receipt.path("afterSnapshot")));
            detail.set("sourceView", viewFacts(receipt.path("sourceView")));
            feedback.set("referencedReceipt", detail);
        }
        // 优先展示有实际测试且报告无失败的当前收据，同类按最近在前。排序不改变引用资格。
        JsonNode checks = evidence.path("checks");
        ArrayNode current = feedback.putArray("currentExecutions");
        int eligible = 0;
        for (int rank = 0; rank <= 2; rank++) {
            for (int i = checks.size() - 1; i >= 0; i--) {
                JsonNode check = checks.get(i);
                if (hintRank(check) == rank && CompletionEvidence.matchesFinalSource(snapshot, view, check)) {
                    eligible++;
                    if (current.size() < 6) {
                        current.add(receiptFacts(check));
                    }
                }
            }
        }
        feedback.put("currentExecutionCount", eligible);
        feedback.put("instruction", "对照当前源码和视图，从 currentExecutions 核实并选用相关调用，无需为取得已有 ID 重跑。"
                + (evidence.path("evidenceVersion").asInt() == 8
                        ? "executions 可省略；若有失败或未验证事项，请如实说明。"
                        : "历史 v7 需要引用当前成功的验证。"));
    }

    private static int hintRank(JsonNode check) {
        JsonNode tests = check.path("testReport");
        if (!tests.path("availability").asText().equals("AVAILABLE")) {
            return 2;
        }
        return check.path("classification").asText().equals("COMMAND_SUCCEEDED")
                && tests.path("executedTests").asInt() > 0 && tests.path("failures").asInt(-1) == 0
                && tests.path("errors").asInt() == 0 ? 0 : 1;
    }

    public String feedback() {
        return feedback.toString();
    }

    static String displayId(JsonNode value) {
        return value.isTextual() && ID.matcher(value.textValue()).matches() ? value.textValue() : "(非标准 ID)";
    }

    private static String safeHash(JsonNode value) {
        return value.asText().matches("[0-9a-f]{64}") ? value.asText() : "(未知)";
    }

    private static ObjectNode viewFacts(JsonNode view) {
        String kind = view.path("kind").asText();
        ObjectNode facts = JsonNodeFactory.instance.objectNode().put("kind",
                Set.of("FULL", "PROJECTED").contains(kind) ? kind : "UNKNOWN");
        for (String key : List.of("preservedOnlyCount", "readOnlyResourceCount", "withheldSourceCount")) {
            if (view.path(key).isIntegralNumber()) {
                facts.put(key, view.path(key).asInt());
            }
        }
        return facts;
    }

    private static ObjectNode receiptFacts(JsonNode receipt) {
        ObjectNode facts = JsonNodeFactory.instance.objectNode().put("invocationId", displayId(receipt.path("invocationId")))
                .put("exitCode", receipt.path("exitCode").asInt()).put("timedOut", receipt.path("timedOut").asBoolean())
                .put("cancelled", receipt.path("cancelled").asBoolean());
        String outcome = receipt.path("classification").asText();
        facts.put("outcome", Set.of("COMMAND_SUCCEEDED", "COMMAND_FAILED", "COMMAND_CANCELLED", "COMMAND_TIMED_OUT")
                .contains(outcome) ? outcome : "UNKNOWN");
        JsonNode tests = receipt.path("testReport");
        boolean available = tests.path("availability").asText().equals("AVAILABLE");
        facts.put("testDetails", available ? "AVAILABLE" : "UNAVAILABLE");
        if (available) {
            for (String key : List.of("executedTests", "failures", "errors", "skipped")) {
                if (tests.path(key).isIntegralNumber()) {
                    facts.put(key, tests.path(key).asInt());
                }
            }
        }
        return facts;
    }
}
