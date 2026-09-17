package com.ayywl.delveforge.infrastructure.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
 * @param timeout 单次调用的读超时
 */
@ConfigurationProperties("delveforge.ai.deepseek")
public record DeepSeekProperties(
        String baseUrl,
        String apiKey,
        String model,
        Duration timeout) {

    public DeepSeekProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("必须配置 delveforge.ai.deepseek.base-url");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("必须配置 delveforge.ai.deepseek.model");
        }
        if (timeout == null) {
            throw new IllegalArgumentException("必须配置 delveforge.ai.deepseek.timeout");
        }
    }
}
