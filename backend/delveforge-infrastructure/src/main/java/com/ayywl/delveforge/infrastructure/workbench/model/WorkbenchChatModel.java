package com.ayywl.delveforge.infrastructure.workbench.model;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentModelFailure;
import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.workbench.WorkbenchModels.Frozen;

import okhttp3.OkHttpClient;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.api.DeepSeekApi;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 每个实例只绑定一个不可变配置。每个物理尝试重新检查凭据引用和 Run 额度。 */
final class WorkbenchChatModel implements AgentModelPort {
    private final Frozen config;
    private final String endpoint;
    private final Supplier<String> key;
    private final ProviderChatModels models;
    private final OkHttpClient http;
    private final int requestBytes;
    private final int responseBytes;
    private final ThreadLocal<List<Map<String, Object>>> attempts = ThreadLocal.withInitial(ArrayList::new);

    WorkbenchChatModel(Frozen config, String endpoint, Supplier<String> key, ProviderChatModels models,
            OkHttpClient http, int requestBytes, int responseBytes) {
        this.config = config;
        this.endpoint = endpoint;
        this.key = key;
        this.models = models;
        this.http = http;
        this.requestBytes = requestBytes;
        this.responseBytes = responseBytes;
    }

    @Override
    public Identity identity() {
        var origin = java.net.URI.create(endpoint);
        return new Identity(ProviderFamily.named(config.choice().provider()).label,
                origin.getScheme() + "://" + origin.getAuthority(), config.choice().model(), config.choice().effort());
    }

    @Override
    public boolean canReplayWithTools(AgentProtocol.Message message) {
        return SpringMessages.replayable(config.choice(), message);
    }

    @Override
    public AgentProtocol.Reply call(List<AgentProtocol.Message> history, List<AgentProtocol.ToolSpec> tools) {
        return call(history, tools, () -> true);
    }

    @Override
    public AgentProtocol.Reply call(List<AgentProtocol.Message> history, List<AgentProtocol.ToolSpec> tools,
            BooleanSupplier admit) {
        attempts.get().clear();
        var prompt = new Prompt(SpringMessages.history(config.choice(), history, !tools.isEmpty()));
        for (int i = 1; i <= 3; i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new AgentException("CANCELLED", "模型请求已取消");
            }
            String credential = key.get();
            var wire = new ModelTransport(config.choice(), history, http, requestBytes, responseBytes, admit);
            long start = System.nanoTime();
            try {
                var response = models.call(config, endpoint, credential, wire, prompt, tools);
                var reply = decode(response, wire, !tools.isEmpty());
                return i == 1 ? reply : new AgentProtocol.Reply(reply.text(), reply.calls(), null,
                        reply.usage(), null, reply.stored());
            } catch (RuntimeException failure) {
                Throwable cause = failure;
                boolean transport = false;
                ModelTransport.HttpFailure status = null;
                while (cause != null) {
                    if (cause instanceof AgentException known) {
                        throw known;
                    }
                    if (cause instanceof ModelTransport.HttpFailure rejected) {
                        status = rejected;
                    }
                    transport |= cause instanceof IOException;
                    cause = cause.getCause();
                }
                int code = status == null ? wire.status : status.status;
                if (i < 3 && (transport && code == 0 || Set.of(408, 429, 500, 502, 503, 504).contains(code))) {
                    waitToRetry(i, status == null ? null : status.retryAfter);
                    continue;
                }
                throw switch (code) {
                    case 401 -> new AgentException("MODEL_AUTHORIZATION", "提供方未接受凭据");
                    case 403 -> new AgentException("MODEL_ACCESS_DENIED", "账户没有访问权限");
                    case 402 -> new AgentException("MODEL_BILLING", "提供方账户余额或付款权限不足");
                    case 404 -> new AgentException("MODEL_NOT_AVAILABLE", "当前端点没有此模型");
                    case 429 -> new AgentException("MODEL_RATE_LIMIT", "提供方限流或余额不足");
                    case 400, 422 -> new AgentException("MODEL_OPTIONS_REJECTED", "提供方拒绝当前模型、协议或参数");
                    default -> new AgentModelFailure(AgentModelFailure.Reason.PROVIDER_CALL_FAILED);
                };
            } finally {
                if (wire.sent) {
                    recordAttempt(wire, i, start);
                }
            }
        }
        throw new AgentModelFailure(AgentModelFailure.Reason.PROVIDER_CALL_FAILED);
    }

    private void recordAttempt(ModelTransport wire, int attempt, long start) {
        var facts = new java.util.LinkedHashMap<String, Object>();
        facts.put("attempt", attempt);
        facts.put("category", wire.status == 0 ? "TRANSPORT" : wire.status < 300 ? "SUCCESS" : "HTTP_ERROR");
        facts.put("httpStatus", wire.status == 0 ? null : wire.status);
        facts.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        attempts.get().add(facts);
    }

    private AgentProtocol.Reply decode(ChatResponse response, ModelTransport wire, boolean tools) {
        var generation = response == null ? null : response.getResult();
        if (generation == null || generation.getOutput() == null) {
            throw new AgentModelFailure(AgentModelFailure.Reason.OUTPUT_MISSING);
        }
        var output = generation.getOutput();
        var usage = response.getMetadata().getUsage();
        Long total = usage == null || usage.getTotalTokens() == null || usage.getTotalTokens() <= 0
                ? null : usage.getTotalTokens().longValue();
        boolean gemini = config.choice().provider().equals("google");
        String reason = generation.getMetadata().getFinishReason();
        String finish = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (Set.of("length", "max_tokens", "max_output_tokens").contains(finish)) {
            throw new AgentModelFailure(AgentModelFailure.Reason.RESPONSE_TRUNCATED,
                    new AgentModelFailure.Response(true, true, true, AgentModelFailure.FinishReason.LENGTH,
                            output.getToolCalls().size(), config.budget().responseTokens(), total));
        }
        boolean called = output.hasToolCalls();
        if (!(called ? Set.of("tool_calls", "tool_use").contains(finish) || gemini && "stop".equals(finish)
                : Set.of("stop", "end_turn", "stop_sequence").contains(finish))) {
            throw new AgentModelFailure(AgentModelFailure.Reason.TOOL_CALL_STRUCTURE_INVALID);
        }
        var calls = SpringMessages.calls(output, gemini);
        var parts = new ArrayList<MessagePart>(output.getParts());
        if (wire.routerPart() != null) {
            parts.add(wire.routerPart());
        }
        var stored = SpringMessages.store(config.choice(), parts);
        String text = output.getText() == null ? "" : output.getText();
        var reported = total == null ? null : new AgentProtocol.Usage(
                number(usage.getPromptTokens()), number(usage.getCompletionTokens()), total, cachedInput(usage), null);
        var reply = new AgentProtocol.Reply(text, calls, total, reported, null, stored);
        // 最终答复可以没有新的推理文本。以后重建上下文时仍由原重放规则选择安全后缀。
        if (tools && !calls.isEmpty() && !canReplayWithTools(reply.assistantMessage(text))) {
            throw new AgentModelFailure(AgentModelFailure.Reason.REASONING_MISSING);
        }
        return reply;
    }

    private static Long cachedInput(Usage usage) {
        Long cached = usage.getCacheReadInputTokens();
        if (cached == null && usage.getNativeUsage() instanceof DeepSeekApi.Usage nativeUsage
                && nativeUsage.promptTokensDetails() != null) {
            return number(nativeUsage.promptTokensDetails().cachedTokens());
        }
        return cached;
    }

    private static Long number(Integer value) {
        return value == null ? null : value.longValue();
    }

    private static void waitToRetry(int attempt, String hint) {
        long ms = 250L * attempt;
        if (hint != null) {
            try {
                ms = Math.min(2, Long.parseLong(hint)) * 1000;
            } catch (NumberFormatException invalidSeconds) {
                try {
                    ms = Duration.between(Instant.now(), ZonedDateTime.parse(hint,
                            DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis();
                } catch (java.time.format.DateTimeParseException invalidDate) {
                    // 无有效提示时沿用短退避。
                }
            }
        }
        try {
            Thread.sleep(Math.clamp(ms, 0, 2000));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AgentException("CANCELLED", "模型请求已取消");
        }
    }

    @Override
    public List<Map<String, Object>> drainDiagnostics() {
        var facts = List.copyOf(attempts.get());
        attempts.remove();
        return facts;
    }
}
