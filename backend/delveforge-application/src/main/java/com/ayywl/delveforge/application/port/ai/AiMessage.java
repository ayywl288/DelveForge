package com.ayywl.delveforge.application.port.ai;

/**
 * 一次 AI 请求中的单条消息。
 *
 * @param role    消息角色
 * @param content 消息内容
 */
public record AiMessage(AiRole role, String content) {

    public AiMessage {
        if (role == null) {
            throw new IllegalArgumentException("AiMessage 必须指定 role");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("AiMessage 的 content 不能为空");
        }
    }
}
