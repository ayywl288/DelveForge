package com.ayywl.delveforge.application.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/** Application 的模型与工具协议，不向领域暴露 Spring AI 或 Docker 类型。 */
public final class AgentProtocol {
    private AgentProtocol() {
    }

    public record ToolCall(String id, String name, String arguments) {
    }

    /** 仅供宿主重放；序列化格式属于模型适配器，不可进入公开历史。 */
    public record StoredMessage(String provider, String model, String partsJson, String prefixHash) {
        public StoredMessage(String provider, String model, String partsJson) {
            this(provider, model, partsJson, null);
        }

        @Override
        public String toString() {
            return "Stored model message[" + provider + "]";
        }
    }

    /** reasoningContent 仅供当前 Run 向模型重放，排除在通用 JSON 和日志表示之外。 */
    public record Message(String role, String text, List<ToolCall> calls, String callId, String toolName,
            @JsonIgnore String reasoningContent, @JsonIgnore StoredMessage stored) {
        public Message(String role, String text, List<ToolCall> calls, String callId, String toolName,
                String reasoningContent) {
            this(role, text, calls, callId, toolName, reasoningContent, null);
        }

        public Message(String role, String text, List<ToolCall> calls, String callId, String toolName) {
            this(role, text, calls, callId, toolName, null);
        }

        @Override
        public String toString() {
            return "Agent message[role=" + role + ", calls=" + calls.size() + "]";
        }

        public Message {
            calls = calls == null ? List.of() : List.copyOf(calls);
        }

        public static Message text(String role, String text) {
            return new Message(role, text, List.of(), null, null);
        }
    }

    public record Usage(Long promptTokens, Long completionTokens, Long totalTokens,
            Long cacheHitTokens, Long cacheMissTokens) {
    }

    public record Reply(String text, List<ToolCall> calls, Long tokens, Usage usage,
            @JsonIgnore String reasoningContent, @JsonIgnore StoredMessage stored) {
        public Reply(String text, List<ToolCall> calls, Long tokens, Usage usage, String reasoningContent) {
            this(text, calls, tokens, usage, reasoningContent, null);
        }

        public Reply(String text, List<ToolCall> calls, Long tokens, Usage usage) {
            this(text, calls, tokens, usage, null);
        }

        public Message assistantMessage(String visibleText) {
            return new Message("assistant", visibleText, calls, null, null, reasoningContent, stored);
        }

        @Override
        public String toString() {
            return "Agent reply[calls=" + calls.size() + ", tokens=" + tokens + "]";
        }

        public Reply(String text, List<ToolCall> calls, Long tokens) {
            this(text, calls, tokens, null);
        }

        public Reply {
            calls = List.copyOf(calls);
        }
    }

    public record ToolSpec(String name, String description, String schema) {
    }
}
