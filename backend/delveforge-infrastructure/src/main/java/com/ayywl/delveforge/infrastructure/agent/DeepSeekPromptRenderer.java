package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentProtocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/** DeepSeek recipe 0.2.0 的 V4.1 文本、非思考模板。仅模板插入的控制符占一个特殊 token。 */
final class DeepSeekPromptRenderer {
    private static final Pattern TEMPLATE_CONTROLS = Pattern.compile("｜DSML｜|</?think>");
    private static final Pattern ASSISTANT_CONTROLS = Pattern.compile("<｜/?(?:ref|box|point|polygon)｜>");
    private static final ObjectMapper JSON = JsonMapper.builder().disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE).build();
    private final List<String> regions = new ArrayList<>();
    private final StringBuilder plain = new StringBuilder();
    private int controls;

    record Rendered(List<String> regions, int controls) {
    }

    Rendered render(List<AgentProtocol.Message> messages, List<AgentProtocol.ToolSpec> tools, String instructions) {
        control(); // BOS
        var ordered = orderedResults(messages);
        if (!tools.isEmpty() && (ordered.isEmpty() || !"system".equals(ordered.getFirst().role()))) {
            ordered.addFirst(AgentProtocol.Message.text("system", ""));
        }
        String previous = "";
        for (int i = 0; i < ordered.size(); i++) {
            var message = ordered.get(i);
            switch (message.role()) {
                case "system" -> {
                    control();
                    plain.append(message.text());
                    if (i == 0 && !tools.isEmpty()) {
                        toolPrompt(tools, instructions);
                    }
                }
                case "user", "tool" -> {
                    if (previous.equals("user") || previous.equals("tool")) {
                        plain.append("\n\n");
                    } else {
                        control();
                    }
                    if (message.role().equals("tool")) {
                        plain.append("<tool_result>").append(message.text()).append("</tool_result>");
                    } else {
                        plain.append(message.text());
                    }
                }
                case "assistant" -> {
                    control();
                    control(); // 非思考模式的 </think>
                    markedText(message.text(), ASSISTANT_CONTROLS);
                    if (!message.calls().isEmpty()) {
                        toolCalls(message.calls());
                    }
                    control(); // EOS
                }
                default -> throw new IllegalArgumentException("Unsupported Agent message role");
            }
            previous = message.role();
        }
        control(); // 生成前缀：assistant + </think>
        control();
        return new Rendered(regions, controls);
    }

    private List<AgentProtocol.Message> orderedResults(List<AgentProtocol.Message> messages) {
        var ordered = new ArrayList<>(messages);
        for (int i = 0; i < ordered.size(); i++) {
            var message = ordered.get(i);
            if (message.calls().isEmpty()) {
                continue;
            }
            var ids = message.calls().stream().map(AgentProtocol.ToolCall::id).toList();
            List<Integer> positions = new ArrayList<>();
            List<AgentProtocol.Message> results = new ArrayList<>();
            for (int j = i + 1; j < ordered.size(); j++) {
                var next = ordered.get(j);
                if ("tool".equals(next.role())) {
                    positions.add(j);
                    results.add(next);
                } else if (!"user".equals(next.role())) {
                    break;
                }
            }
            results.sort(Comparator.comparingInt(result -> Math.max(0, ids.indexOf(result.callId()))));
            for (int j = 0; j < results.size(); j++) {
                ordered.set(positions.get(j), results.get(j));
            }
        }
        return ordered;
    }

    private void toolPrompt(List<AgentProtocol.ToolSpec> tools, String instructions) {
        plain.append("\n\n");
        markedText(instructions, TEMPLATE_CONTROLS);
        var schemas = new StringJoiner("\n");
        for (var tool : tools) {
            var schema = JSON.createObjectNode().put("name", tool.name()).put("description", tool.description());
            try {
                schema.set("parameters", JSON.readTree(tool.schema()));
            } catch (IOException failure) {
                throw new IllegalArgumentException("Invalid Agent tool schema", failure);
            }
            schemas.add(spacedJson(schema));
        }
        plain.append(schemas).append("\n\nYou MUST strictly follow the above defined tool name and parameter schemas to invoke tool calls.\n");
    }

    private void toolCalls(List<AgentProtocol.ToolCall> calls) {
        plain.append("\n\n");
        tag(false, " calls>\n");
        for (int i = 0; i < calls.size(); i++) {
            if (i > 0) {
                plain.append('\n');
            }
            var call = calls.get(i);
            tag(false, " invoke name=\"" + call.name() + "\">\n");
            JsonNode arguments = arguments(call.arguments());
            var fields = arguments.properties().iterator();
            while (fields.hasNext()) {
                var field = fields.next();
                var value = field.getValue();
                tag(false, " parameter name=\"" + field.getKey() + "\" string=\"" + value.isTextual() + "\">");
                plain.append(value.isTextual() ? value.textValue() : spacedJson(value));
                tag(true, " parameter>");
                if (fields.hasNext()) {
                    plain.append('\n');
                }
            }
            plain.append('\n');
            tag(true, " invoke>");
        }
        plain.append('\n');
        tag(true, " calls>");
    }

    private JsonNode arguments(String raw) {
        try {
            JsonNode parsed = JSON.readTree(raw);
            if (parsed != null && parsed.isObject()) {
                return parsed;
            }
        } catch (IOException invalid) {
            // 与官方模板一致，无法解析的参数作为一个普通字符串。
        }
        return JSON.createObjectNode().put("arguments", raw);
    }

    private static String spacedJson(JsonNode node) {
        if (node.isObject()) {
            var result = new StringJoiner(", ", "{", "}");
            node.properties().forEach(field -> result.add(quoted(field.getKey())
                    + ": " + spacedJson(field.getValue())));
            return result.toString();
        }
        if (node.isArray()) {
            var result = new StringJoiner(", ", "[", "]");
            node.forEach(item -> result.add(spacedJson(item)));
            return result.toString();
        }
        if (node.isFloatingPointNumber()) {
            double value = node.doubleValue();
            if (value == 0 || Math.abs(value) >= 0.0001 && Math.abs(value) < 1e16) {
                if (value == 0) {
                    return Double.toString(value);
                }
                String decimal = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
                return decimal.contains(".") ? decimal : decimal + ".0";
            }
            BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
            int exponent = decimal.precision() - decimal.scale() - 1;
            return decimal.movePointLeft(exponent).toPlainString() + "e"
                    + (exponent < 0 ? "-" : "+") + (Math.abs(exponent) < 10 ? "0" : "") + Math.abs(exponent);
        }
        return node.isTextual() ? quoted(node.textValue()) : node.toString();
    }

    private static String quoted(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid tool JSON string", failure);
        }
    }

    private void tag(boolean closing, String plainSuffix) {
        plain.append(closing ? "</" : "<");
        control(); // DSML
        plain.append(plainSuffix);
    }

    private void markedText(String value, Pattern controlsInTemplate) {
        var matcher = controlsInTemplate.matcher(value);
        int start = 0;
        while (matcher.find()) {
            plain.append(value, start, matcher.start());
            control();
            start = matcher.end();
        }
        plain.append(value, start, value.length());
    }

    private void control() {
        if (!plain.isEmpty()) {
            regions.add(plain.toString());
            plain.setLength(0);
        }
        controls++;
    }
}
