package com.ayywl.delveforge.infrastructure.workbench.model;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentTokenCounter;

import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

import java.util.List;

/** 近似值包含协议封装、工具定义、私有回放和安全余量；账单仍从提供方 Usage 读取。 */
final class WorkbenchTokenEstimate implements AgentTokenCounter {
    private final JTokkitTokenCountEstimator tokenizer = new JTokkitTokenCountEstimator();

    @Override
    public long countPromptTokens(List<AgentProtocol.Message> messages, List<AgentProtocol.ToolSpec> tools) {
        long tokens = 16;
        for (var message : messages) {
            tokens += 12 + tokenizer.estimate(message.role()) + tokenizer.estimate(message.callId())
                    + tokenizer.estimate(message.toolName());
            if (message.stored() != null) {
                tokens += tokenizer.estimate(message.stored().partsJson());
            } else {
                tokens += tokenizer.estimate(message.text()) + tokenizer.estimate(message.reasoningContent());
                for (var call : message.calls()) {
                    tokens += 8 + tokenizer.estimate(call.id()) + tokenizer.estimate(call.name())
                            + tokenizer.estimate(call.arguments());
                }
            }
        }
        for (var tool : tools) {
            tokens += 16 + tokenizer.estimate(tool.name()) + tokenizer.estimate(tool.description())
                    + tokenizer.estimate(tool.schema());
        }
        return (tokens * 5 + 3) / 4;
    }
}
