package com.ayywl.delveforge.infrastructure.ai;

import com.ayywl.delveforge.application.port.ai.AiGateway;

import com.ayywl.delveforge.application.port.ai.AiRequest.Thinking;
import com.ayywl.delveforge.infrastructure.agent.DeepSeekTokenCounterAdapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * DeepSeek AI Gateway 的装配。
 *
 * <p>配置由消费它的模块自己声明并启用（ARCHITECTURE.md §6.2）。
 * Provider 专有的 Endpoint、Model 与凭据全部来自 {@link DeepSeekProperties}，
 * 不在这里硬编码。
 *
 * <p>Adapter 使用自己的 {@link ObjectMapper}：它解析的是 Provider 固定的线上格式，
 * 不应受应用其它部分对 Jackson 的配置影响。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeepSeekProperties.class)
public class DeepSeekAiGatewayConfiguration {

    @Bean
    @Lazy
    public DeepSeekTokenCounterAdapter agentTokenCounter(DeepSeekProperties properties, Environment environment) {
        return new DeepSeekTokenCounterAdapter(properties.model(),
                environment.getProperty("delveforge.agent.thinking", Thinking.class, Thinking.HIGH));
    }

    @Bean
    public AiGateway deepSeekAiGateway(DeepSeekProperties properties, ObjectProvider<DeepSeekTokenCounterAdapter> counter) {
        return gateway(properties, counter::getObject);
    }

    static AiGateway gateway(DeepSeekProperties properties, java.util.function.Supplier<DeepSeekTokenCounterAdapter> counter) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        // JDK factory 的期限覆盖完整读取；SSE 另在 Adapter 监督空闲时间，不能让增量延长总期限。
        requestFactory.setReadTimeout(properties.streaming() ? properties.streamTimeout() : properties.timeout());

        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();

        return new DeepSeekAiGatewayAdapter(restClient, new ObjectMapper(), properties, counter);
    }
}
