package com.ayywl.delveforge.application.port.ai;

/**
 * AI 消息角色。
 */
public enum AiRole {

    /** 系统指令与上下文约束。 */
    SYSTEM,

    /** 用户输入。 */
    USER,

    /** 模型此前的回复。 */
    ASSISTANT
}
