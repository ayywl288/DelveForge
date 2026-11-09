package com.ayywl.delveforge.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** 仅识别三次相同的短工具循环；新观察或真实源码修改不会因累计用量被终止。 */
final class AgentProgress {
    private final ObjectMapper json = new ObjectMapper();
    private final List<Action> recent = new ArrayList<>();

    private record Action(String tool, JsonNode arguments, JsonNode result) {
    }

    private boolean warning;

    boolean warning() {
        return warning;
    }

    /** 比较引用集合及重复项，忽略引用排序、报告说明和面向模型的纠正文本。 */
    void recordCompletion(JsonNode report, ObjectNode feedback) {
        JsonNode refs = report.path("executions");
        ObjectNode arguments = json.createObjectNode();
        if (refs.isArray()) {
            var ids = new TreeMap<String, Boolean>();
            for (JsonNode ref : refs) {
                String key = ref.isTextual() ? "id:" + ref.textValue() : "invalid";
                ids.put(key, ids.containsKey(key));
            }
            arguments.set("executions", json.valueToTree(ids));
        } else if (!refs.isMissingNode()) {
            arguments.put("executions", "invalid-list");
        } else {
            arguments.putObject("executions");
        }
        ObjectNode observation = json.createObjectNode();
        observation.put("classification", feedback.has("issue") ? "REFERENCE_REJECTION" : feedback.path("classification").asText());
        if (!feedback.has("issue") && feedback.path("reason").isTextual()) {
            observation.set("reason", feedback.get("reason"));
        }
        for (String fact : List.of("finalSnapshot", "finalView")) {
            if (feedback.has(fact)) {
                observation.set(fact, feedback.get(fact));
            }
        }
        record(new AgentProtocol.ToolCall("completion-check", "completion_check", arguments.toString()),
                observation.toString(), false);
    }

    void record(AgentProtocol.ToolCall call, String result, boolean sourceChanged) {
        warning = false;
        if (sourceChanged) {
            recent.clear();
            return;
        }
        try {
            JsonNode response = json.readTree(result);
            if (response instanceof ObjectNode object) {
                object.remove("durationMs");
                if (object.path("result") instanceof ObjectNode detail) {
                    detail.remove(List.of("durationMs", "invocationId", "logRef"));
                    if (call.name().equals("exec_command")) {
                        // 完整输出摘要专用于比较；原始摘要与预览长度噪声不能重新引入时间差异。
                        detail.remove("diagnosticDigest");
                        if (detail.path("progressDiagnosticDigest").asText().matches("[0-9a-f]{64}")) {
                            detail.remove(List.of("stdout", "stderr", "outputTruncated"));
                        } else {
                            detail.remove("progressDiagnosticDigest");
                            for (String stream : List.of("stdout", "stderr")) {
                                if (detail.path(stream).isTextual()) {
                                    detail.put(stream, AgentCommandDiagnostics.normalize(detail.path(stream).asText()));
                                }
                            }
                        }
                    }
                }
            }
            recent.add(new Action(call.name(), json.readTree(call.arguments()), response));
        } catch (IOException failure) {
            throw new AgentException("TOOL_PROTOCOL", "Progress requires structured tool results");
        }
        if (recent.size() > 12) {
            recent.removeFirst();
        }
        for (int length = 1; length <= 4 && length * 2 <= recent.size(); length++) {
            int end = recent.size();
            if (recent.subList(end - length, end).equals(recent.subList(end - length * 2, end - length))) {
                warning = true;
            }
        }
        for (int length = 1; length <= 4 && length * 3 <= recent.size(); length++) {
            int end = recent.size();
            List<Action> last = recent.subList(end - length, end);
            if (last.equals(recent.subList(end - length * 2, end - length))
                    && last.equals(recent.subList(end - length * 3, end - length * 2))) {
                throw new AgentException("NO_PROGRESS", "Repeated unchanged tool cycle without source progress");
            }
        }
    }
}
