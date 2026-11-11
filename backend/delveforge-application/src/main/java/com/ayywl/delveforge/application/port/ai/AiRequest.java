package com.ayywl.delveforge.application.port.ai;

import java.util.List;

/**
 * 一次 AI 生成请求。
 *
 * <p>以消息列表表达上下文，因此同时支持 User Discovery 的多轮对话
 * 与其他流程的单轮结构化生成。
 *
 * @param thinking        当前工作负载的思考选项；DEFAULT 保持提供者的既有行为
 * @param messages       按时间顺序排列的消息，不得为空
 * @param responseFormat 期望的响应格式
 * @param discoveryStage 现有 Discovery 阶段的输出预算身份；与是否启用思考相互独立
 */
public record AiRequest(List<AiMessage> messages, AiResponseFormat responseFormat, Thinking thinking,
        List<Integer> discoveryTurnStarts, DiscoveryStage discoveryStage) {

    public AiRequest(List<AiMessage> messages, AiResponseFormat responseFormat, Thinking thinking) {
        this(messages, responseFormat, thinking, null, null);
    }

    public enum Thinking {
        DEFAULT, DISABLED, LOW, HIGH, MAX
    }

    public enum DiscoveryStage {
        EXTRACTION, DIALOGUE
    }

    public AiRequest(List<AiMessage> messages, AiResponseFormat responseFormat) {
        this(messages, responseFormat, Thinking.DEFAULT);
    }

    public AiRequest {
        java.util.Objects.requireNonNull(thinking);
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("AiRequest 至少需要一条消息");
        }
        if (responseFormat == null) {
            throw new IllegalArgumentException("AiRequest 必须指定 responseFormat");
        }
        messages = List.copyOf(messages);
        if ((discoveryTurnStarts == null) != (discoveryStage == null)) {
            throw new IllegalArgumentException("Discovery 轮次边界与阶段必须同时指定");
        }
        if (discoveryTurnStarts != null) {
            discoveryTurnStarts = List.copyOf(discoveryTurnStarts);
            int last = 0;
            for (int start : discoveryTurnStarts) {
                if (start <= last || start >= messages.size() - 1 || last == 0 && start != 1) {
                    throw new IllegalArgumentException("历史轮次边界无效");
                }
                last = start;
            }
            if (last == 0 && messages.size() != 2) {
                throw new IllegalArgumentException("历史轮次缺少请求身份");
            }
        }
    }

    /**
     * 创建单轮请求。
     */
    public static AiRequest of(AiRole role, String content, AiResponseFormat responseFormat) {
        return new AiRequest(List.of(new AiMessage(role, content)), responseFormat);
    }
}
