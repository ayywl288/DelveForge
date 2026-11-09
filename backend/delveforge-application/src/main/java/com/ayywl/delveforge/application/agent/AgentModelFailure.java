package com.ayywl.delveforge.application.agent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 模型边界的有限诊断：只携带固定枚举、存在性和计数，不携带消息、参数或 Provider 自由文本。 */
public final class AgentModelFailure extends AgentException {
    public enum Reason {
        RESPONSE_MISSING, RESULT_MISSING, OUTPUT_MISSING, OUTPUT_INVALID, RESPONSE_TRUNCATED,
        TOOL_ID_INVALID, TOOL_NAME_INVALID, TOOL_ARGUMENTS_INVALID, TOOL_ARGUMENTS_LIMIT,
        TOOL_CALL_TYPE_INVALID, TOOL_CALL_STRUCTURE_INVALID,
        REASONING_MISSING, CONVERSATION_ROLE_UNSUPPORTED, COMPLETION_FORMAT_INVALID, PROVIDER_CALL_FAILED
    }

    public enum FinishReason {
        STOP, TOOL_CALLS, LENGTH, CONTENT_FILTER, MISSING, OTHER;

        public static FinishReason from(String value) {
            return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
                case "stop" -> STOP;
                case "tool_calls" -> TOOL_CALLS;
                case "length" -> LENGTH;
                case "content_filter" -> CONTENT_FILTER;
                case "" -> MISSING;
                default -> OTHER;
            };
        }
    }

    // reportedTokens 仅是失败响应实际报告的用量；不并入正常返回调用的 Run.tokens，缺失保持 null。
    public record Response(boolean responsePresent, boolean resultPresent, boolean outputPresent,
            FinishReason finishReason, Integer toolCallCount, int responseTokenLimit, Long reportedTokens) {
    }

    private final Reason reason;
    private final Response response;

    public AgentModelFailure(Reason reason) {
        this(reason, null);
    }

    public AgentModelFailure(Reason reason, Response response) {
        super(reason == Reason.PROVIDER_CALL_FAILED ? "MODEL_TRANSPORT" : "MODEL_PROTOCOL",
                "Agent model failure: " + reason.name());
        this.reason = reason;
        this.response = response;
    }

    public Reason reason() {
        return reason;
    }

    public Response response() {
        return response;
    }

    public Map<String, Object> diagnostic(int request, boolean callReturned) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("metadataVersion", 1);
        fields.put("classification", code());
        fields.put("reason", reason.name());
        fields.put("request", request);
        fields.put("callReturned", callReturned);
        fields.put("stage", switch (reason) {
            case CONVERSATION_ROLE_UNSUPPORTED -> "CONVERSATION";
            case COMPLETION_FORMAT_INVALID -> "COMPLETION";
            case PROVIDER_CALL_FAILED -> "PROVIDER_CALL";
            case TOOL_ID_INVALID, TOOL_NAME_INVALID, TOOL_ARGUMENTS_INVALID, TOOL_ARGUMENTS_LIMIT,
                    TOOL_CALL_TYPE_INVALID, TOOL_CALL_STRUCTURE_INVALID -> "TOOL_CALL";
            default -> "RESPONSE";
        });
        if (response != null) {
            fields.put("responsePresent", response.responsePresent());
            fields.put("resultPresent", response.resultPresent());
            fields.put("outputPresent", response.outputPresent());
            fields.put("finishReason", response.finishReason().name());
            fields.put("responseTokenLimit", response.responseTokenLimit());
            if (response.toolCallCount() != null) {
                fields.put("toolCallCount", response.toolCallCount());
            }
            if (response.reportedTokens() != null) {
                fields.put("reportedTokens", response.reportedTokens());
            }
        }
        return fields;
    }
}
