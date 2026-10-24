package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentModelFailure;
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
    private final int responseTokens;

    public SpringAiAgentModel(DeepSeekProperties properties, int responseTokens) {
        this.properties = properties;
        this.responseTokens = responseTokens;
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
                default -> throw new AgentModelFailure(AgentModelFailure.Reason.CONVERSATION_ROLE_UNSUPPORTED);
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
            throw new AgentModelFailure(AgentModelFailure.Reason.PROVIDER_CALL_FAILED);
        }
        return decode(response);
    }

    AgentProtocol.Reply decode(ChatResponse response) {
        var result = response == null ? null : response.getResult();
        var output = result == null ? null : result.getOutput();
        var usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        Long tokens = usage == null || usage.getTotalTokens() == null || usage.getTotalTokens() <= 0
                ? null : usage.getTotalTokens().longValue();
        var finish = AgentModelFailure.FinishReason.from(result == null || result.getMetadata() == null
                ? null : result.getMetadata().getFinishReason());
        var observed = new AgentModelFailure.Response(response != null, result != null, output != null, finish,
                output == null || output.getToolCalls() == null ? null : output.getToolCalls().size(), responseTokens, tokens);
        if (response == null) {
            throw new AgentModelFailure(AgentModelFailure.Reason.RESPONSE_MISSING, observed);
        }
        if (result == null) {
            throw new AgentModelFailure(AgentModelFailure.Reason.RESULT_MISSING, observed);
        }
        // 截断的工具参数不能进入 Application；只保留实际返回的有限元数据。
        if (finish == AgentModelFailure.FinishReason.LENGTH) {
            throw new AgentModelFailure(AgentModelFailure.Reason.RESPONSE_TRUNCATED, observed);
        }
        if (output == null) {
            throw new AgentModelFailure(AgentModelFailure.Reason.OUTPUT_MISSING, observed);
        }
        if (output.getToolCalls() == null) {
            throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_CALL_STRUCTURE_INVALID, observed);
        }
        List<AgentProtocol.ToolCall> calls = new ArrayList<>();
        for (AssistantMessage.ToolCall call : output.getToolCalls()) {
            if (call == null) {
                throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_CALL_STRUCTURE_INVALID, observed);
            }
            if (!"function".equals(call.type())) {
                throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_CALL_TYPE_INVALID, observed);
            }
            if (call.id() == null || call.id().isBlank() || call.id().length() > 200) {
                throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_ID_INVALID, observed);
            }
            if (call.name() == null || call.name().isBlank()) {
                throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_NAME_INVALID, observed);
            }
            if (call.arguments() == null) {
                throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_ARGUMENTS_INVALID, observed);
            }
            calls.add(new AgentProtocol.ToolCall(call.id(), call.name(), call.arguments()));
        }
        return new AgentProtocol.Reply(output.getText() == null ? "" : output.getText(), calls, tokens);
    }
}
