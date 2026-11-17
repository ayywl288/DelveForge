package com.ayywl.delveforge.infrastructure.workbench.model;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.workbench.WorkbenchModels.Frozen;

import com.anthropic.models.messages.OutputConfig;
import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import okhttp3.OkHttpClient;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatModel;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 原生 ChatModel 只返回提议。四个工具的实际执行仍由 ConversationAgent 准入。 */
final class ProviderChatModels {
    private final Duration timeout;
    private final JdkClientHttpRequestFactory deepSeekHttp;
    private final RetryTemplate singleAttempt = new RetryTemplate(RetryPolicy.withMaxRetries(0));

    ProviderChatModels(Duration timeout) {
        this.timeout = timeout;
        deepSeekHttp = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build());
        deepSeekHttp.setReadTimeout(timeout);
    }

    ChatResponse call(Frozen frozen, String endpoint, String key, ModelTransport wire, Prompt prompt,
            List<AgentProtocol.ToolSpec> tools) {
        var family = ProviderFamily.named(frozen.choice().provider());
        String model = frozen.choice().model();
        String effort = frozen.choice().effort();
        int output = frozen.budget().responseTokens();
        var definitions = tools.stream().map(DefinitionOnly::new).map(ToolCallback.class::cast).toList();
        return switch (family) {
            case OPENAI -> {
                var options = OpenAiResponsesChatOptions.builder().baseUrl(endpoint).apiKey(key)
                        .model(model).maxOutputTokens(output).maxRetries(0).timeout(timeout)
                        .strict(false).toolCallbacks(definitions);
                if (!"default".equals(effort)) {
                    options.reasoningEffort("off".equals(effort) ? "none" : effort);
                }
                yield OpenAiResponsesChatModel.builder().options(options.build())
                        .httpClientBuilderCustomizer(b -> b.interceptor(c -> wire.send(c.request())))
                        .build().call(prompt);
            }
            case ANTHROPIC -> {
                var options = AnthropicChatOptions.builder().baseUrl(endpoint).apiKey(key)
                        .model(model).maxTokens(output).maxRetries(0).timeout(timeout).toolCallbacks(definitions);
                if ("off".equals(effort)) {
                    options.thinkingDisabled();
                } else if (!"default".equals(effort)) {
                    options.thinkingAdaptive().effort(OutputConfig.Effort.of(effort));
                }
                yield AnthropicChatModel.builder().options(options.build())
                        .httpClientBuilderCustomizer(b -> b.interceptor(c -> wire.send(c.request())))
                        .build().call(prompt);
            }
            case GOOGLE -> {
                var options = GoogleGenAiChatOptions.builder().model(model).maxOutputTokens(output)
                        .toolCallbacks(definitions);
                if ("off".equals(effort)) {
                    options.thinkingBudget(0);
                } else if (!"default".equals(effort)) {
                    options.thinkingLevel(GoogleGenAiThinkingLevel.valueOf(effort.toUpperCase(Locale.ROOT)));
                }
                var http = HttpOptions.builder().baseUrl(endpoint).timeout(Math.toIntExact(timeout.toMillis()))
                        .retryOptions(HttpRetryOptions.builder().attempts(1)).build();
                var transport = new OkHttpClient.Builder().addInterceptor(c -> wire.send(c.request())).build();
                try (var client = Client.builder().vertexAI(false).apiKey(key).httpOptions(http)
                        .clientOptions(ClientOptions.builder().customHttpClient(transport).build()).build()) {
                    yield GoogleGenAiChatModel.builder().genAiClient(client).options(options.build())
                            .retryTemplate(singleAttempt).build().call(prompt);
                }
            }
            case DEEPSEEK -> {
                var options = DeepSeekChatOptions.builder().model(model).maxTokens(output).toolCallbacks(definitions);
                switch (effort) {
                    case "off" -> options.disableThinking();
                    case "low" -> options.enableThinking();
                    case "high" -> options.enableThinking().reasoningEffortHigh();
                    case "max" -> options.enableThinking().reasoningEffortMax();
                    case "default" -> { }
                    default -> throw ProviderFamily.invalid("不支持的 DeepSeek 思考强度");
                }
                var api = DeepSeekApi.builder().baseUrl(endpoint).apiKey(key)
                        .restClientBuilder(RestClient.builder().requestFactory(deepSeekHttp)
                                .requestInterceptor(wire.deepSeek())).build();
                yield DeepSeekChatModel.builder().deepSeekApi(api).options(options.build())
                        .retryTemplate(singleAttempt).build().call(prompt);
            }
            case ZAI, QWEN, KIMI, OPENROUTER -> {
                var options = OpenAiChatOptions.builder().baseUrl(endpoint).apiKey(key).model(model).maxTokens(output)
                        .maxRetries(0).timeout(timeout).strict(false).toolCallbacks(definitions)
                        .replayReasoningContent(!"off".equals(effort));
                Map<String, Object> extra = new java.util.LinkedHashMap<>();
                if (family == ProviderFamily.OPENROUTER) {
                    extra.put("provider", Map.of("require_parameters", true, "allow_fallbacks", false));
                    if (!"default".equals(effort)) {
                        extra.put("reasoning", Map.of("effort", "off".equals(effort) ? "none" : effort));
                    }
                } else if (!"default".equals(effort)) {
                    if (family == ProviderFamily.KIMI && model.startsWith("kimi-k3")) {
                        options.reasoningEffort(effort);
                    } else if (family == ProviderFamily.QWEN) {
                        extra.put("enable_thinking", "on".equals(effort));
                    } else {
                        extra.put("thinking", Map.of("type", "on".equals(effort) ? "enabled" : "disabled"));
                    }
                }
                options.extraBody(extra);
                yield OpenAiChatModel.builder().options(options.build())
                        .httpClientBuilderCustomizer(b -> b.interceptor(c -> wire.send(c.request())))
                        .build().call(prompt);
            }
        };
    }

    private record DefinitionOnly(ToolDefinition getToolDefinition) implements ToolCallback {
        DefinitionOnly(AgentProtocol.ToolSpec spec) {
            this(ToolDefinition.builder().name(spec.name()).description(spec.description()).inputSchema(spec.schema()).build());
        }

        @Override
        public String call(String input) {
            throw new IllegalStateException("工具仅能经工作台准入和执行");
        }
    }
}
