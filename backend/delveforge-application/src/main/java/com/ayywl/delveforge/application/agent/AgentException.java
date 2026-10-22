package com.ayywl.delveforge.application.agent;

/** 分类不依赖提供者自由文本；接口层只映射这个业务类型。 */
public class AgentException extends RuntimeException {
    private final String code;

    public AgentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AgentException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
