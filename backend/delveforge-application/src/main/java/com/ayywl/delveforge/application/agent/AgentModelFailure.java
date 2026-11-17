package com.ayywl.delveforge.application.agent;

/** 模型边界的有限诊断：只携带固定枚举、存在性和计数，不携带消息、参数或 Provider 自由文本。 */
public final class AgentModelFailure extends AgentException {
    public enum Reason {
        OUTPUT_MISSING, RESPONSE_TRUNCATED, TOOL_ID_INVALID, TOOL_CALL_STRUCTURE_INVALID,
        REASONING_MISSING, CONVERSATION_ROLE_UNSUPPORTED, PROVIDER_CALL_FAILED
    }

    public enum FinishReason {
        LENGTH
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
}
