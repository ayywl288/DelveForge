package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentModelFailure;
import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.port.ai.AiRequest.Thinking;
import com.ayywl.delveforge.infrastructure.ai.DeepSeekProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage;
import org.springframework.ai.deepseek.api.DeepSeekApi.FunctionTool;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

/** 只交换模型消息，不执行工具；直接使用 Spring AI 的消息 API，Application 独占工具派发。 */
public final class SpringAiAgentModel implements AgentModelPort {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DeepSeekApi api;
    private final DeepSeekProperties properties;
    private final int responseTokens;
    private final Thinking thinking;
    private final ThreadLocal<List<java.util.Map<String, Object>>> diagnostics = ThreadLocal.withInitial(ArrayList::new);

    public SpringAiAgentModel(DeepSeekProperties properties, int responseTokens) {
        this(properties, responseTokens, Thinking.HIGH);
    }

    public SpringAiAgentModel(DeepSeekProperties properties, int responseTokens, Thinking thinking) {
        if (thinking == null || thinking == Thinking.DEFAULT) {
            throw new IllegalArgumentException("Coding Agent requires an explicit thinking mode");
        }
        this.thinking = thinking;
        this.properties = properties;
        this.responseTokens = responseTokens;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(properties.timeout()).build());
        factory.setReadTimeout(properties.timeout());
        api = DeepSeekApi.builder().baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey() == null || properties.apiKey().isBlank() ? "unconfigured" : properties.apiKey())
                .restClientBuilder(RestClient.builder().requestFactory(factory).requestInterceptor((request, body, execution) -> {
                    ObjectNode payload = (ObjectNode) JSON.readTree(body);
                    boolean enabled = thinking != Thinking.DISABLED;
                    payload.putObject("thinking").put("type", enabled ? "enabled" : "disabled");
                    if (enabled) {
                        payload.put("reasoning_effort", thinking.name().toLowerCase(java.util.Locale.ROOT));
                    }
                    byte[] assembled = JSON.writeValueAsBytes(payload);
                    if (assembled.length > properties.maxRequestBytes()) {
                        throw new AgentException("REQUEST_BYTES_LIMIT", "Serialized Agent request exceeds safe transport budget");
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
                                if (status >= 400) {
                                    response.close();
                                    throw new AgentModelFailure(AgentModelFailure.Reason.PROVIDER_CALL_FAILED);
                                }
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
    }

    @Override
    public AgentProtocol.Reply call(List<AgentProtocol.Message> history, List<AgentProtocol.ToolSpec> tools) {
        diagnostics.get().clear();
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new AgentException("MODEL_CONFIGURATION", "Configure the model API key outside the sandbox");
        }
        // ChatModel 1.1.8 转换 Assistant 时丢弃 reasoningContent；底层消息 DTO 保留该字段。
        boolean replay = thinking != Thinking.DISABLED && !tools.isEmpty();
        var messages = history.stream().map(message -> providerMessage(message, replay)).toList();
        var definitions = tools.stream().map(spec -> new FunctionTool(
                new FunctionTool.Function(spec.description(), spec.name(), spec.schema()))).toList();
        var request = new DeepSeekApi.ChatCompletionRequest(messages, properties.model(), null, responseTokens,
                null, null, null, false, null, null, null, null, definitions.isEmpty() ? null : definitions, null);
        DeepSeekApi.ChatCompletion response;
        try {
            response = api.chatCompletionEntity(request).getBody();
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof AgentModelFailure protocol) {
                    throw protocol;
                }
                if (cause instanceof AgentException rejected
                        && java.util.Set.of("REQUEST_BYTES_LIMIT", "CANCELLED").contains(rejected.code())) {
                    throw rejected;
                }
            }
            // 不传播提供者错误正文，其中可能包含请求或凭据。
            throw new AgentModelFailure(AgentModelFailure.Reason.PROVIDER_CALL_FAILED);
        }
        AgentProtocol.Reply reply = decode(response);
        if (thinking != Thinking.DISABLED && !tools.isEmpty() && reply.reasoningContent() == null) {
            throw new AgentModelFailure(AgentModelFailure.Reason.REASONING_MISSING);
        }
        // 失败尝试可能已产生费用但未报告 usage；不能把最终响应用量冒充全部尝试的总量。
        return diagnostics.get().size() > 1
                ? new AgentProtocol.Reply(reply.text(), reply.calls(), null, reply.usage(), reply.reasoningContent()) : reply;
    }

    private ChatCompletionMessage providerMessage(AgentProtocol.Message message, boolean replay) {
        var role = switch (message.role()) {
            case "assistant" -> ChatCompletionMessage.Role.ASSISTANT;
            case "tool" -> ChatCompletionMessage.Role.TOOL;
            case "system" -> ChatCompletionMessage.Role.SYSTEM;
            case "user" -> ChatCompletionMessage.Role.USER;
            default -> throw new AgentModelFailure(AgentModelFailure.Reason.CONVERSATION_ROLE_UNSUPPORTED);
        };
        String reasoning = role == ChatCompletionMessage.Role.ASSISTANT && replay ? message.reasoningContent() : null;
        if (role == ChatCompletionMessage.Role.ASSISTANT && replay && reasoning == null) {
            throw new AgentModelFailure(AgentModelFailure.Reason.REASONING_MISSING);
        }
        var calls = message.calls().stream().map(call -> new ChatCompletionMessage.ToolCall(call.id(), "function",
                new ChatCompletionMessage.ChatCompletionFunction(call.name(), call.arguments()))).toList();
        return new ChatCompletionMessage(message.text(), role, message.toolName(), message.callId(),
                calls.isEmpty() ? null : calls, null, reasoning);
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

    AgentProtocol.Reply decode(DeepSeekApi.ChatCompletion response) {
        var result = response == null || response.choices() == null || response.choices().isEmpty()
                ? null : response.choices().getFirst();
        var output = result == null ? null : result.message();
        var usage = response == null ? null : response.usage();
        Long tokens = usage == null || usage.totalTokens() == null || usage.totalTokens() <= 0
                ? null : usage.totalTokens().longValue();
        var finish = AgentModelFailure.FinishReason.from(result == null || result.finishReason() == null
                ? null : result.finishReason().name());
        var toolCalls = output == null ? null : output.toolCalls();
        Integer toolCount = output == null ? null : Integer.valueOf(toolCalls == null ? 0 : toolCalls.size());
        var observed = new AgentModelFailure.Response(response != null, result != null, output != null, finish,
                toolCount, responseTokens, tokens);
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
        if (output.rawContent() != null && !(output.rawContent() instanceof String)) {
            throw new AgentModelFailure(AgentModelFailure.Reason.OUTPUT_INVALID, observed);
        }
        if (output.role() != ChatCompletionMessage.Role.ASSISTANT
                || finish != AgentModelFailure.FinishReason.STOP && finish != AgentModelFailure.FinishReason.TOOL_CALLS
                || (finish == AgentModelFailure.FinishReason.TOOL_CALLS) != (toolCalls != null && !toolCalls.isEmpty())) {
            throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_CALL_STRUCTURE_INVALID, observed);
        }
        List<AgentProtocol.ToolCall> calls = new ArrayList<>();
        var callIds = new java.util.HashSet<String>();
        for (var call : toolCalls == null ? List.<ChatCompletionMessage.ToolCall>of() : toolCalls) {
            if (call == null || call.function() == null) {
                throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_CALL_STRUCTURE_INVALID, observed);
            }
            if (!"function".equals(call.type())) {
                throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_CALL_TYPE_INVALID, observed);
            }
            if (call.id() == null || call.id().isBlank() || call.id().length() > 200 || !callIds.add(call.id())) {
                throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_ID_INVALID, observed);
            }
            if (call.function().name() == null || call.function().name().isBlank()) {
                throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_NAME_INVALID, observed);
            }
            if (call.function().arguments() == null) {
                throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_ARGUMENTS_INVALID, observed);
            }
            calls.add(new AgentProtocol.ToolCall(call.id(), call.function().name(), call.function().arguments()));
        }
        Long cached = usage == null || usage.promptTokensDetails() == null ? null
                : numerical(usage.promptTokensDetails().cachedTokens());
        AgentProtocol.Usage reported = tokens == null ? null : new AgentProtocol.Usage(
                numerical(usage.promptTokens()), numerical(usage.completionTokens()), tokens, cached, null);
        return new AgentProtocol.Reply(output.content() == null ? "" : output.content(), calls, tokens, reported,
                thinking == Thinking.DISABLED ? null : output.reasoningContent());
    }

    private Long numerical(Integer value) {
        return value == null || value < 0 ? null : value.longValue();
    }
}
