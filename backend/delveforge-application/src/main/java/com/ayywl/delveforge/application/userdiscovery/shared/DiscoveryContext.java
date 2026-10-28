package com.ayywl.delveforge.application.userdiscovery.shared;

import java.util.List;

/** 仅在请求内帮助理解指代和跳过意愿；客户端文本不是事实、Evidence 或授权。 */
public final class DiscoveryContext {

    private DiscoveryContext() {
    }

    public record Message(String role, String text) {
    }

    public static List<Message> validate(List<Message> messages) {
        if (messages == null) {
            return List.of();
        }
        if (messages.size() > 8 || messages.stream().anyMatch(message -> message == null || message.role() == null
                || !List.of("user", "agent").contains(message.role())
                || message.text() == null || message.text().isBlank() || message.text().length() > 2048)) {
            throw new IllegalArgumentException("recentConversation 最多八条，每条为 user/agent 和不超过 2048 字符的正文");
        }
        return List.copyOf(messages);
    }
}
