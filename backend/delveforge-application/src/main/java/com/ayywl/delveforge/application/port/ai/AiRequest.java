package com.ayywl.delveforge.application.port.ai;

import java.util.List;

/**
 * 一次 AI 生成请求。
 *
 * <p>以消息列表表达上下文，因此同时支持 User Discovery 的多轮对话
 * 与其他流程的单轮结构化生成。
 *
 * @param messages       按时间顺序排列的消息，不得为空
 * @param responseFormat 期望的响应格式
 */
public record AiRequest(List<AiMessage> messages, AiResponseFormat responseFormat) {

    public AiRequest {
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
