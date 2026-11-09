package com.ayywl.delveforge.application.userdiscovery.shared;

import com.ayywl.delveforge.application.port.ai.AiMessage;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.ai.AiResponseFormat;
import com.ayywl.delveforge.application.port.ai.AiRole;

import java.util.ArrayList;
import java.util.List;

/** 仅在请求内帮助理解指代和跳过意愿；客户端文本不是事实、Evidence 或授权。 */
public final class DiscoveryContext {

    private DiscoveryContext() {
    }

    public record Message(String role, String text, String requestId) {
        public Message(String role, String text) {
            this(role, text, null);
        }
    }

    /** 稳定前缀只包含已接受的原文；可变事实、最新输入与修正要求位于最后一条消息。 */
    public static AiRequest request(String system, List<Message> history, String current, AiRequest.Thinking thinking) {
        List<AiMessage> messages = new ArrayList<>();
        List<Integer> turns = new ArrayList<>();
        messages.add(new AiMessage(AiRole.SYSTEM, system));
        String previous = null;
        for (Message message : history) {
            if (message.requestId() == null || !message.requestId().equals(previous)) {
                turns.add(messages.size());
            }
            previous = message.requestId();
            messages.add(new AiMessage("user".equals(message.role()) ? AiRole.USER : AiRole.ASSISTANT, message.text()));
        }
        messages.add(new AiMessage(AiRole.USER, current));
        return new AiRequest(messages, AiResponseFormat.JSON, thinking, turns);
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
