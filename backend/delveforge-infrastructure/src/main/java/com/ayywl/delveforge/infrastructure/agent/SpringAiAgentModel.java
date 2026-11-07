package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentModelFailure;
import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.infrastructure.ai.DeepSeekProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
    private static final ObjectMapper JSON = new ObjectMapper();
    // UTF-8 请求字节上界有意保守，另预留响应额度；这不是精确 token 计数或模型容量声明。
    private static final int MAX_REQUEST_BYTES = 900000;
    private final DeepSeekChatModel model;
    private final DeepSeekProperties properties;
    private final int responseTokens;
    private final ThreadLocal<List<java.util.Map<String, Object>>> diagnostics = ThreadLocal.withInitial(ArrayList::new);

    public SpringAiAgentModel(DeepSeekProperties properties, int responseTokens) {
        this.properties = properties;
        this.responseTokens = responseTokens;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(properties.timeout()).build());
        factory.setReadTimeout(properties.timeout());
        DeepSeekApi api = DeepSeekApi.builder().baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey() == null || properties.apiKey().isBlank() ? "unconfigured" : properties.apiKey())
                .restClientBuilder(RestClient.builder().requestFactory(factory).requestInterceptor((request, body, execution) -> {
                    // 当前消息合同不携带提供者 reasoning_content，显式关闭默认 thinking，避免不完整的工具会话。
                    ObjectNode payload = (ObjectNode) JSON.readTree(body);
                    payload.set("thinking", JSON.createObjectNode().put("type", "disabled"));
                    byte[] assembled = JSON.writeValueAsBytes(payload);
                    if ((long) assembled.length + responseTokens > MAX_REQUEST_BYTES) {
                        throw new AgentException("CONTEXT_LIMIT", "Serialized Agent request exceeds safe transport budget");
                    }
                    for (int attempt = 1; attempt <= 3; attempt++) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new AgentException("CANCELLED", "Model request cancelled");
                        }
                        long started = System.nanoTime();
                        try {
                            var response = execution.execute(request, assembled);
                            int status = response.getStatusCode().value();
                            diagnostics.get().add(java.util.Map.of("category", status < 400 ? "SUCCESS" : "HTTP_ERROR",
                                    "httpStatus", status, "attempt", attempt, "responsePresent", true,
                                    "durationMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
                            if (attempt == 3 || !java.util.Set.of(408, 429, 500, 502, 503, 504).contains(status)) {
                                return response;
                            }
                            long delay = retryDelay(response.getHeaders().getFirst("Retry-After"), attempt);
                            response.close();
                            pause(delay);
                        } catch (java.io.IOException failure) {
                            diagnostics.get().add(java.util.Map.of("category", "TRANSPORT", "attempt", attempt,
                                    "responsePresent", false, "durationMs",
                                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
                            if (attempt == 3 || Thread.currentThread().isInterrupted()) {
                                throw failure;
                            }
                            pause(250L * attempt);
                        }
                    }
                    throw new java.io.IOException("Provider attempts exhausted");
                })).build();
        model = DeepSeekChatModel.builder().deepSeekApi(api)
                .defaultOptions(DeepSeekChatOptions.builder().model(properties.model())
                        .maxTokens(responseTokens).internalToolExecutionEnabled(false).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
    }

    @Override
    public AgentProtocol.Reply call(List<AgentProtocol.Message> history, List<AgentProtocol.ToolSpec> tools) {
        diagnostics.get().clear();
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
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof AgentException rejected
                        && java.util.Set.of("CONTEXT_LIMIT", "CANCELLED").contains(rejected.code())) {
                    throw rejected;
                }
            }
            // 不传播提供者错误正文，其中可能包含请求或凭据。
            throw new AgentModelFailure(AgentModelFailure.Reason.PROVIDER_CALL_FAILED);
        }
        AgentProtocol.Reply reply = decode(response);
        // 失败尝试可能已产生费用但未报告 usage；不能把最终响应用量冒充全部尝试的总量。
        return diagnostics.get().size() > 1
                ? new AgentProtocol.Reply(reply.text(), reply.calls(), null, null) : reply;
    }

    @Override
    public List<java.util.Map<String, Object>> drainDiagnostics() {
        var recorded = List.copyOf(diagnostics.get());
        diagnostics.remove();
        return recorded;
    }

    private static long retryDelay(String value, int attempt) {
        long delay = 250L * attempt;
        if (value != null) {
            try {
                delay = Math.min(2, Math.max(0, Long.parseLong(value))) * 1000L;
            } catch (NumberFormatException ignored) {
                try {
                    delay = java.time.Duration.between(java.time.Instant.now(),
                            java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                                    .toInstant()).toMillis();
                } catch (java.time.format.DateTimeParseException invalidDate) {
                    // 非法提示使用本地短退避，不转发错误正文。
                }
            }
        }
        return Math.max(0, Math.min(2000, delay));
    }

    private static void pause(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AgentException("CANCELLED", "Provider retry cancelled");
        }
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
        Long cached = null;
        if (usage != null && usage.getNativeUsage() instanceof DeepSeekApi.Usage nativeUsage
                && nativeUsage.promptTokensDetails() != null) {
            cached = numerical(nativeUsage.promptTokensDetails().cachedTokens());
        }
        AgentProtocol.Usage reported = tokens == null ? null : new AgentProtocol.Usage(
                numerical(usage.getPromptTokens()), numerical(usage.getCompletionTokens()), tokens, cached, null);
        return new AgentProtocol.Reply(output.getText() == null ? "" : output.getText(), calls, tokens, reported);
    }

    private Long numerical(Integer value) {
        return value == null || value < 0 ? null : value.longValue();
    }
}
