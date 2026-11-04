package com.ayywl.delveforge.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 只保存当前未解决冲突的至多三份有界参数用于直接比较；不记录正文或计算指纹。 */
final class AgentPatchCorrections {
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> rejected = new ArrayList<>();

    void before(AgentProtocol.ToolCall call) {
        if (AgentTools.mutation(call.name()) && rejected.contains(patch(call))) {
            throw new AgentPatchCorrectionException(true);
        }
    }

    boolean record(AgentProtocol.ToolCall call, String result) {
        if (!AgentTools.mutation(call.name())) {
            return false;
        }
        try {
            JsonNode response = json.readTree(result);
            JsonNode details = response.path("result");
            String classification = response.path("classification").asText(details.path("classification").asText());
            if (classification.equals("PATCH_APPLIED")) {
                rejected.clear();
            } else if (classification.equals("PATCH_CONFLICT")) {
                if (!details.path("mutationNotStarted").asBoolean()) {
                    throw new AgentException("PATCH_OUTCOME_UNCERTAIN", "Conflict did not establish a pre-mutation result");
                }
                rejected.add(patch(call));
                if (rejected.size() >= 3) {
                    throw new AgentPatchCorrectionException(false);
                }
                return true;
            }
            return false;
        } catch (IOException failure) {
            throw new AgentException("TOOL_PROTOCOL", "Tool result must be structured JSON");
        }
    }

    private String patch(AgentProtocol.ToolCall call) {
        try {
            // 行尾属于补丁语义：LF 冲突后改成 CRLF 可能是正确修正，不能折叠为重复。
            JsonNode arguments = json.readTree(call.arguments());
            return call.name().equals("edit_file") ? json.writeValueAsString(List.of(
                    arguments.path("path").asText(), arguments.path("old_text").asText(),
                    arguments.path("new_text").asText())) : arguments.path("patch").asText();
        } catch (IOException failure) {
            return "";
        }
    }
}
