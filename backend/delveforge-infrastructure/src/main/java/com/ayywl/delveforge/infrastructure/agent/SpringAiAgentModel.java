package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.infrastructure.ai.DeepSeekProperties;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

/** 只交换模型消息，不执行工具；禁用框架循环并用不可执行 Callback 防止双重派发。 */
public final class SpringAiAgentModel implements AgentModelPort {
    private final DeepSeekChatModel model;
    private final DeepSeekProperties properties;

    public SpringAiAgentModel(DeepSeekProperties properties, int responseTokens) {
        this.properties = properties;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(properties.timeout()).build());
        factory.setReadTimeout(properties.timeout());
        DeepSeekApi api = DeepSeekApi.builder().baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey() == null || properties.apiKey().isBlank() ? "unconfigured" : properties.apiKey())
                .restClientBuilder(RestClient.builder().requestFactory(factory)).build();
        model = DeepSeekChatModel.builder().deepSeekApi(api)
                .defaultOptions(DeepSeekChatOptions.builder().model(properties.model())
                        .maxTokens(responseTokens).internalToolExecutionEnabled(false).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
    }

    @Override
    public AgentProtocol.Reply call(List<AgentProtocol.Message> history, List<AgentProtocol.ToolSpec> tools) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new AgentException("MODEL_CONFIGURATION", "Configure the model API key outside the sandbox");
        }
        List<Message> messages = new ArrayList<>();
        for (AgentProtocol.Message message : history) {
            switch (message.role()) {
                case "system" -> messages.add(new SystemMessage(message.text()));
                case "user" -> messages.add(new UserMessage(message.text()));
                case "assistant" -> messages.add(AssistantMessage.builder().content(message.text())
                        .toolCalls(message.calls().stream().map(call -> new AssistantMessage.ToolCall(
                                call.id(), "function", call.name(), call.arguments())).toList()).build());
                case "tool" -> messages.add(ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse(message.callId(), message.toolName(), message.text())))
                        .build());
                default -> throw new AgentException("MODEL_PROTOCOL", "Unsupported conversation role");
            }
        }
        List<ToolCallback> callbacks = tools.stream().map(spec -> (ToolCallback) new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return new DefaultToolDefinition(spec.name(), spec.description(), spec.schema());
            }

            @Override
            public String call(String arguments) {
                throw new AgentException("DOUBLE_TOOL_EXECUTION", "Only Application may dispatch Agent tools");
            }
        }).toList();
        ChatResponse response;
        try {
            response = model.call(new Prompt(messages, DeepSeekChatOptions.builder()
                    .toolCallbacks(callbacks).internalToolExecutionEnabled(false).build()));
        } catch (RuntimeException failure) {
            // 不传播提供者错误正文，其中可能包含请求或凭据。
            throw new AgentException("MODEL_TRANSPORT", "Model request failed or timed out", failure);
        }
        if (response == null || response.getResult() == null
                || "length".equalsIgnoreCase(response.getResult().getMetadata().getFinishReason())) {
            throw new AgentException("MODEL_PROTOCOL", "Missing or truncated model response");
        }
        AssistantMessage output = response.getResult().getOutput();
        var usage = response.getMetadata().getUsage();
        Long tokens = usage == null || usage.getTotalTokens() == null || usage.getTotalTokens() <= 0
                ? null : usage.getTotalTokens().longValue();
        return new AgentProtocol.Reply(output.getText() == null ? "" : output.getText(),
                output.getToolCalls().stream().map(call -> new AgentProtocol.ToolCall(
                        call.id(), call.name(), call.arguments())).toList(), tokens);
    }
}
