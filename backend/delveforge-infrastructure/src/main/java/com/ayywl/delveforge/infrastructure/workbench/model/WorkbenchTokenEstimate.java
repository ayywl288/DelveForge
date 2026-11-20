package com.ayywl.delveforge.infrastructure.workbench.model;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentTokenCounter;

import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

import java.util.List;

/** 近似值包含协议封装、工具定义、私有回放和安全余量；账单仍从提供方 Usage 读取。 */
final class WorkbenchTokenEstimate implements AgentTokenCounter {
    private static final JTokkitTokenCountEstimator TOKENIZER = new JTokkitTokenCountEstimator();
    private final int toolEnvelope;

    WorkbenchTokenEstimate(ProviderFamily family) {
        // 实测服务端工具包装占用固定输入空间，小提示上的百分比余量不足。
        toolEnvelope = switch (family) {
            case DEEPSEEK -> 192;
            case ZAI -> 64;
            default -> 0;
        };
    }

    @Override
    public long countPromptTokens(List<AgentProtocol.Message> messages, List<AgentProtocol.ToolSpec> tools) {
        long tokens = 16;
        for (var message : messages) {
            tokens += 12 + TOKENIZER.estimate(message.role()) + TOKENIZER.estimate(message.callId())
                    + TOKENIZER.estimate(message.toolName());
            // 输入是最终请求视图。结构化转换后的 partsJson 只包含目标保留的部件。
            if (message.stored() != null) {
                tokens += TOKENIZER.estimate(message.stored().partsJson());
            } else {
                tokens += TOKENIZER.estimate(message.text()) + TOKENIZER.estimate(message.reasoningContent());
                for (var call : message.calls()) {
                    tokens += 8 + TOKENIZER.estimate(call.id()) + TOKENIZER.estimate(call.name())
                            + TOKENIZER.estimate(call.arguments());
                }
            }
        }
        for (var tool : tools) {
            tokens += 16 + TOKENIZER.estimate(tool.name()) + TOKENIZER.estimate(tool.description())
                    + TOKENIZER.estimate(tool.schema());
        }
        return (tokens * 5 + 3) / 4 + (tools.isEmpty() ? 0 : toolEnvelope);
    }
}
