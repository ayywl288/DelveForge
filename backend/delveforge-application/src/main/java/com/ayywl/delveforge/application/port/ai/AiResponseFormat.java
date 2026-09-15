package com.ayywl.delveforge.application.port.ai;

/**
 * 期望的 AI 响应格式。
 *
 * <p>该枚举只表达业务侧期望，不涉及任何 Provider 专有的响应模式参数，
 * 具体参数映射由 Infrastructure 的 Adapter 完成。
 */
public enum AiResponseFormat {

    /** 自然语言文本，用于对话与自由文本生成。 */
    TEXT,

    /** 结构化内容，由 Application 负责解析与校验。 */
    JSON
}
