package com.ayywl.delveforge.infrastructure.workbench.model;

import com.ayywl.delveforge.application.agent.AgentModelFailure;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.workbench.WorkbenchModels.Choice;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.util.JacksonUtils;
import tools.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** 使用 Spring AI 的多态序列化器保留顺序、签名、未知部件与属性。公开投影单独持久化。 */
final class SpringMessages {
    private static final TypeReference<List<MessagePart>> PARTS = new TypeReference<>() {};

    static AgentProtocol.StoredMessage store(Choice choice, List<MessagePart> parts) {
        String json = JacksonUtils.getDefaultJsonMapper().writerFor(PARTS).writeValueAsString(parts);
        return new AgentProtocol.StoredMessage(choice.provider(), choice.model(), json);
    }

    static List<MessagePart> parts(AgentProtocol.StoredMessage stored) {
        return JacksonUtils.getDefaultJsonMapper().readValue(stored.partsJson(), PARTS);
    }

    static boolean replayable(Choice choice, AgentProtocol.Message message) {
        if (!"assistant".equals(message.role())) {
            return true;
        }
        boolean requiresThinking = !"off".equals(choice.effort()) && (choice.provider().equals("deepseek")
                || choice.provider().equals("kimi") && !message.calls().isEmpty());
        var stored = message.stored();
        if (stored == null) {
            if (!choice.provider().equals("deepseek") && !message.calls().isEmpty()) {
                return false;
            }
            return !requiresThinking || message.reasoningContent() != null && !message.reasoningContent().isBlank();
        }
        if (!sameProducer(choice, stored)) {
            return message.calls().isEmpty() && !requiresThinking;
        }
        return !requiresThinking || parts(stored).stream().anyMatch(part -> part instanceof ReasoningPart reasoning
                && reasoning.text() != null && !reasoning.text().isBlank());
    }

    static boolean sameProducer(Choice choice, AgentProtocol.StoredMessage stored) {
        return stored.provider().equals(choice.provider()) && stored.model().equals(choice.model());
    }

    static List<Message> history(Choice choice, List<AgentProtocol.Message> history, boolean tools) {
        var result = new ArrayList<Message>();
        var receipts = new ArrayList<ToolResponseMessage.ToolResponse>();
        for (var message : history) {
            if ("tool".equals(message.role())) {
                receipts.add(new ToolResponseMessage.ToolResponse(message.callId(), message.toolName(), message.text()));
                continue;
            }
            flush(result, receipts);
            switch (message.role()) {
                case "user" -> result.add(new UserMessage(message.text()));
                case "system" -> result.add(new SystemMessage(message.text()));
                case "assistant" -> {
                    if (tools && !replayable(choice, message)) {
                        throw new AgentModelFailure(AgentModelFailure.Reason.REASONING_MISSING);
                    }
                    result.add(assistant(choice, message));
                }
                default -> throw new AgentModelFailure(AgentModelFailure.Reason.CONVERSATION_ROLE_UNSUPPORTED);
            }
        }
        flush(result, receipts);
        return result;
    }

    private static void flush(List<Message> result, List<ToolResponseMessage.ToolResponse> receipts) {
        if (!receipts.isEmpty()) {
            result.add(ToolResponseMessage.builder().responses(List.copyOf(receipts)).build());
            receipts.clear();
        }
    }

    private static AssistantMessage assistant(Choice choice, AgentProtocol.Message message) {
        var stored = message.stored();
        if (stored != null && sameProducer(choice, stored)) {
            var original = parts(stored);
            var calls = original.stream().filter(ToolCallPart.class::isInstance).map(ToolCallPart.class::cast).toList();
            if (calls.size() != message.calls().size()) {
                throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_CALL_STRUCTURE_INVALID);
            }
            for (int i = 0; i < calls.size(); i++) {
                var nativeCall = calls.get(i).toolCall();
                var savedCall = message.calls().get(i);
                boolean localId = "google".equals(choice.provider()) && (nativeCall.id() == null || nativeCall.id().isBlank());
                if (!localId && !savedCall.id().equals(nativeCall.id()) || !savedCall.name().equals(nativeCall.name())
                        || !savedCall.arguments().equals(nativeCall.arguments())) {
                    throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_CALL_STRUCTURE_INVALID);
                }
            }
            return AssistantMessage.builder().parts(original).build();
        }
        var builder = AssistantMessage.builder();
        if (stored == null && "deepseek".equals(choice.provider()) && !"off".equals(choice.effort())
                && message.reasoningContent() != null) {
            builder.part(ReasoningPart.of(message.reasoningContent()));
        }
        if (message.text() != null && !message.text().isEmpty()) {
            builder.part(TextPart.of(message.text()));
        }
        for (var call : message.calls()) {
            builder.part(ToolCallPart.of(new AssistantMessage.ToolCall(call.id(), "function", call.name(), call.arguments())));
        }
        return builder.build();
    }

    static List<AgentProtocol.ToolCall> calls(AssistantMessage output, boolean gemini) {
        var result = new ArrayList<AgentProtocol.ToolCall>();
        var seen = new HashSet<String>();
        for (var call : output.getToolCalls()) {
            String id = call.id();
            if (gemini && (id == null || id.isBlank())) {
                // Gemini 允许省略 ID。此 ID 只配对本地回执，保留的原始部件仍然无 ID。
                id = "local-" + java.util.UUID.randomUUID();
            }
            if (id == null || id.isBlank() || id.length() > 200 || !seen.add(id)) {
                throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_ID_INVALID);
            }
            if (!"function".equals(call.type()) || call.name() == null || call.name().isBlank()
                    || call.arguments() == null) {
                throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_CALL_STRUCTURE_INVALID);
            }
            result.add(new AgentProtocol.ToolCall(id, call.name(), call.arguments()));
        }
        return List.copyOf(result);
    }
}
