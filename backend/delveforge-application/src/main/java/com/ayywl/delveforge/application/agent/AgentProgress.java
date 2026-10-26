package com.ayywl.delveforge.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 仅识别三次相同的短工具循环；新观察或真实源码修改不会因累计用量被终止。 */
final class AgentProgress {
    private final ObjectMapper json = new ObjectMapper();
    private final List<Action> recent = new ArrayList<>();

    private record Action(String tool, JsonNode arguments, JsonNode result) {
    }

    void record(AgentProtocol.ToolCall call, String result, boolean sourceChanged) {
        if (sourceChanged) {
            recent.clear();
            return;
        }
        try {
            JsonNode response = json.readTree(result);
            if (response instanceof ObjectNode object) {
                object.remove("durationMs");
            }
            recent.add(new Action(call.name(), json.readTree(call.arguments()), response));
        } catch (IOException failure) {
            throw new AgentException("TOOL_PROTOCOL", "Progress requires structured tool results");
        }
        if (recent.size() > 12) {
            recent.removeFirst();
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
