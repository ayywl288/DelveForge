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
 */
public record AiRequest(List<AiMessage> messages, AiResponseFormat responseFormat, Thinking thinking) {

    public enum Thinking {
        DEFAULT, DISABLED, LOW, HIGH, MAX
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
    }

    /**
     * 创建单轮请求。
     */
    public static AiRequest of(AiRole role, String content, AiResponseFormat responseFormat) {
        return new AiRequest(List.of(new AiMessage(role, content)), responseFormat);
    }
}
