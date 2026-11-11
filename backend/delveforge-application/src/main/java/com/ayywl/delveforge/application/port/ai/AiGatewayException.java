package com.ayywl.delveforge.application.port.ai;

/**
 * AI Gateway 调用失败。
 *
 * <p>用于在边界处明确表达“外部模型能力调用失败”，避免 Provider 专有异常
 * 直接泄漏到业务代码中。
 */
public class AiGatewayException extends RuntimeException {

    public enum Category { UNAVAILABLE, TRANSPORT, HTTP, EMPTY_OUTPUT, INCOMPLETE_OUTPUT, INVALID_OUTPUT, OUTPUT_LIMIT }

    private final Category category;

    public AiGatewayException(String message) {
        this(message, null, Category.UNAVAILABLE);
    }

    public AiGatewayException(String message, Throwable cause) {
        this(message, cause, Category.UNAVAILABLE);
    }

    public AiGatewayException(String message, Throwable cause, Category category) {
        super(message, cause);
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
