package com.ayywl.delveforge.application.agent;

import com.ayywl.delveforge.domain.agent.AgentRunState;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;

/** Application 的模型与工具协议，不向领域暴露 Spring AI 或 Docker 类型。 */
public final class AgentProtocol {
    private AgentProtocol() {
    }

    public record ToolCall(String id, String name, String arguments) {
    }

    /** reasoningContent 仅供当前 Run 向模型重放，排除在通用 JSON 和日志表示之外。 */
    public record Message(String role, String text, List<ToolCall> calls, String callId, String toolName,
            @JsonIgnore String reasoningContent) {
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

    public record Reply(String text, List<ToolCall> calls, Long tokens, Usage usage, @JsonIgnore String reasoningContent) {
        public Reply(String text, List<ToolCall> calls, Long tokens, Usage usage) {
            this(text, calls, tokens, usage, null);
        }

        public Message assistantMessage(String visibleText) {
            return new Message("assistant", visibleText, calls, null, null, reasoningContent);
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

    public record Run(String id, String planId, String stepId, String workingCopyId, String baseline,
            AgentRunState state, Instant startedAt, Instant endedAt, String reason, String candidateId,
            boolean cancelRequested, boolean retained, int modelCalls, int toolCalls, Long tokens) {
    }

    public record Event(long sequence, String runId, String kind, String summary, Instant at) {
    }

    public record Limits(int maxContextChars,
            int maxOutputBytes, int maxPatchBytes, int maxFileBytes, int maxCandidateBytes,
            int maxFiles, int checkSeconds) {
        public Limits {
            if (maxContextChars < 4096
                    || maxOutputBytes < 1024 || maxPatchBytes < 1024 || maxFileBytes < 1024
                    || maxCandidateBytes < maxPatchBytes || maxFiles < 1
                    || checkSeconds < 1) {
                throw new IllegalArgumentException("Invalid Agent limits");
            }
        }

        public static Limits defaults() {
            // 累计用量与总时长不作停止门槛；技术材料、单次操作仍有界。
            return new Limits(500000, 24000, 131072, 262144, 2097152, 10000, 180);
        }
    }
}
