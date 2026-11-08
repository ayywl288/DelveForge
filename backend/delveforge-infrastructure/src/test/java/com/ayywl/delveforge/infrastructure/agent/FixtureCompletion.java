package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** 确定性夹具的作者报告；只引用夹具实际观察到的当前成功调用，不参与生产完成判断。 */
final class FixtureCompletion {
    private static final ObjectMapper JSON = new ObjectMapper();

    private FixtureCompletion() {
    }

    static String report(List<AgentProtocol.Message> history) {
        List<JsonNode> receipts = history.stream().filter(message -> "tool".equals(message.role())
                && "exec_command".equals(message.toolName())).map(message -> parse(message.text()).path("result"))
                .filter(result -> result.has("invocationId")).toList();
        String snapshot = receipts.isEmpty() ? "" : receipts.getLast().path("afterSnapshot").asText();
        var report = JSON.createObjectNode().put("status", "completed").put("validationComplete", true)
                .put("summary", "Deterministic fixture checks completed; earlier failures retained")
                .put("limitations", "Fixture execution view only; no live model or product acceptance");
        var ids = report.putArray("executions");
        receipts.stream().filter(receipt -> receipt.path("classification").asText().equals("COMMAND_SUCCEEDED")
                && snapshot.equals(receipt.path("snapshot").asText())
                && snapshot.equals(receipt.path("afterSnapshot").asText()))
                .forEach(receipt -> ids.add(receipt.path("invocationId").asText()));
        return report.toString();
    }

    static String submit(AgentSandboxPort sandbox, String id, JsonNode result) {
        var report = JSON.createObjectNode().put("status", "completed").put("validationComplete", true)
                .put("summary", "Fixture author selected this actual execution")
                .put("limitations", "Recorded execution view only");
        report.putArray("executions").add(result.path("invocationId").asText());
        return sandbox.completionFeedback(id, report.toString(), () -> false);
    }

    private static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }
}
