package com.ayywl.delveforge.application.port.ai;

/**
 * AI Gateway 调用失败。
 *
 * <p>用于在边界处明确表达“外部模型能力调用失败”，避免 Provider 专有异常
 * 直接泄漏到业务代码中。
 */
public class AiGatewayException extends RuntimeException {

    public AiGatewayException(String message) {
        super(message);
    }

    public AiGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
