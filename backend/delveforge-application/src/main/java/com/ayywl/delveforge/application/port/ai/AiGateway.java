package com.ayywl.delveforge.application.port.ai;

/**
 * AI Gateway 出站 Port。
 *
 * <p>业务模块只能通过该接口访问 LLM，不得直接依赖具体 Provider SDK、
 * Provider 专有 API 类型、Model ID 或 Endpoint（RULE-ARCH-008）。
 *
 * <p>该接口只负责“模型如何产生语义候选”。它返回的内容仍是未经校验的原始输出，
 * 不构成合法领域状态，必须由 Application / Domain 完成解析与校验（RULE-DOM-003）：
 *
 * <pre>
 * AI Output
 *     ↓
 * Parse / Validate
 *     ↓
 * Application / Domain Rules
 *     ↓
 * Accepted Domain State
 * </pre>
 *
 * <p>实现由 Infrastructure 层提供（例如后续的 {@code DeepSeekAiGatewayAdapter}）。
 */
public interface AiGateway {

    /**
     * 根据请求内容生成模型输出。
     *
     * @param request 请求消息与期望的响应格式
     * @return 模型返回的原始内容，未经解析与校验
     * @throws AiGatewayException 当外部模型能力调用失败时
     */
    String generate(AiRequest request);
}
