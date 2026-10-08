package com.ayywl.delveforge.application.port.ai;

/**
 * 已收到的模型内容违反输出格式、字段或引用契约；不表示 Provider 调用或环境失败。
 * 调用方可据此决定有界重问，但每次输出仍须完整校验。
 */
public final class AiOutputContractViolationException extends AiGatewayException {

    public AiOutputContractViolationException(String message) {
        super(message);
    }

    public AiOutputContractViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
