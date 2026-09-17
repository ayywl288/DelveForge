/**
 * AI Gateway 的 Provider Adapter 实现。
 *
 * <p>Provider 相关的 Model ID、Endpoint、凭据与参数属于配置，不得硬编码（RULE-ARCH-008）。
 *
 * <p>当前只有一个 Adapter：{@code DeepSeekAiGatewayAdapter}，直接调用 DeepSeek 的
 * OpenAI 兼容 HTTP API。不引入 Spring AI / Spring AI Alibaba，理由与重新评估条件见
 * {@code docs/decisions/0003-first-ai-adapter-uses-deepseek-http-api.md}。
 *
 * <p>Provider 细节（Endpoint 路径、鉴权头、请求体与响应体形状）只允许出现在本包内：
 * 业务模块看到的始终是 {@code com.ayywl.delveforge.application.port.ai} 的抽象。
 */
package com.ayywl.delveforge.infrastructure.ai;
