package com.ayywl.delveforge.infrastructure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * DeepSeek Provider 配置。
 *
 * <p>Provider 专有的 Endpoint、Model 与凭据属于配置，不得硬编码进业务代码
 * （RULE-ARCH-008、AGENTS.md §8.9）。默认值写在 {@code application.yml}，代码不兜底。
 *
 * <p>API Key 只能由外部配置或环境变量提供，不进入源码仓库。
 * 缺失时仍可完成装配，调用时才失败——这样不含凭据的环境（例如测试）也能启动。
 *
 * @param baseUrl DeepSeek API 基地址，OpenAI 兼容
 * @param apiKey  API Key；只从外部配置 / 环境变量读取
 * @param model   模型 ID
 * @param timeout 连接超时；非流式总读取期限，流式读取的空闲期限
 * @param streaming 是否在 Provider 边界内部读取 SSE；Port 仍只返回完整文本
 * @param streamTimeout 流式响应的绝对期限，不被 keep-alive 或增量延长
 * @param maxTokens Provider 总生成 token 上限，包含其思考输出
 * @param maxResponseBytes HTTP 响应总字节上限
 * @param maxEventBytes 单个 SSE 事件字节上限
 */
@ConfigurationProperties("delveforge.ai.deepseek")
public record DeepSeekProperties(
        String baseUrl,
        String apiKey,
        String model,
        Duration timeout,
        boolean streaming,
        Duration streamTimeout,
        int maxTokens,
        int maxResponseBytes,
        int maxEventBytes) {

    public DeepSeekProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("必须配置 delveforge.ai.deepseek.base-url");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("必须配置 delveforge.ai.deepseek.model");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("必须配置 delveforge.ai.deepseek.timeout");
        }
        if (streamTimeout == null || streamTimeout.isZero() || streamTimeout.isNegative()
                || streamTimeout.compareTo(timeout) < 0) {
            throw new IllegalArgumentException("流式绝对期限不得小于连接 / 空闲期限");
        }
        if (maxTokens <= 0 || maxResponseBytes <= 0 || maxEventBytes <= 0 || maxEventBytes > maxResponseBytes) {
            throw new IllegalArgumentException("Provider 输出及响应预算必须为正且事件不得超过总响应预算");
        }
    }
}
