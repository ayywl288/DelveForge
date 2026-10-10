package com.ayywl.delveforge.infrastructure.ai;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiMessage;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.ai.AiResponseFormat;
import com.ayywl.delveforge.application.port.ai.AiRole;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * {@link AiGateway} 的 DeepSeek 实现：直接调用 DeepSeek 的 OpenAI 兼容 HTTP API（ADR-0003）。
 *
 * <p>本 Adapter 是 Provider 细节的唯一所在地：Endpoint 路径、鉴权头、请求体形状与响应体
 * 结构都限定在 Infrastructure AI 包内。业务代码只看到 {@link AiGateway} 与 {@link AiRequest}。
 *
 * <p>它不做任何领域判断，也不解析业务语义：返回的仍是模型原始文本，
 * 由 Application 完成解析与校验（RULE-DOM-003）。
 *
 * <p>所有失败都翻译为 {@link AiGatewayException}：网络与 HTTP 层失败、响应体不是合法
 * JSON、生成未完整结束、以及响应里没有可用的 content。SSE 只在完整 stop 与 DONE 后返回。
 * 异常只携带说明与原因链，日志侧不记录 message（见 ADR-0002）；API Key 不写入任何日志。
 */
public class DeepSeekAiGatewayAdapter implements AiGateway {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekAiGatewayAdapter.class);

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String JSON_RESPONSE_TYPE = "json_object";
    private static final String BEARER_PREFIX = "Bearer ";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final DeepSeekProperties properties;

    public DeepSeekAiGatewayAdapter(RestClient restClient,
                                    ObjectMapper objectMapper,
                                    DeepSeekProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String generate(AiRequest request) {
        requireApiKey();
        ReadProgress progress = new ReadProgress();

        try {
            byte[] requestBody = objectMapper.writeValueAsBytes(toRequestBody(request));
            progress.requestBytes = requestBody.length;
            String responseBody = restClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .exchange((ignoredRequest, response) -> readResponse(response, progress));

            String content = properties.streaming() ? responseBody : extractContent(responseBody, progress);
            logProgress("COMPLETE", "PROVIDER_RESPONSE_COMPLETE", "NONE", progress);
            return content;
        } catch (DeepSeekStreamingResponse.Rejected exception) {
            logProgress(exception.phase(), exception.code(), exception.getClass().getName(), progress);
            throw exception;
        } catch (AiGatewayException exception) {
            logProgress("RESPONSE_PARSE", "PROVIDER_RESPONSE_INVALID", exception.getClass().getName(), progress);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            logFailure(exception, progress);
            // 网络与 HTTP 失败只携带固定说明；日志不输出第三方异常文本或正文。
            throw new AiGatewayException("调用 DeepSeek 失败", exception);
        }
    }

    /** 仅旁观固定状态、时间与计数；不记录任何响应头值或正文片段。 */
    private String readResponse(ClientHttpResponse response, ReadProgress progress) throws IOException {
        progress.status = response.getStatusCode().value();
        progress.headersMs = progress.elapsedMs();
        progress.contentLength = response.getHeaders().getContentLength();
        MediaType type = response.getHeaders().getContentType();
        progress.contentType = type == null ? "UNKNOWN"
                : MediaType.APPLICATION_JSON.isCompatibleWith(type) ? "JSON" : "OTHER";
        if (response.getStatusCode().isError()) {
            // exchange 不经过 retrieve 的状态处理；仍拒绝 HTTP 错误，且不读取错误正文或头信息。
            throw new RestClientResponseException("Provider HTTP error", progress.status, "", null, null,
                    StandardCharsets.UTF_8);
        }

        try (InputStream raw = response.getBody()) {
            InputStream body = monitored(raw, progress);
            if (!properties.streaming()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                body.transferTo(bytes);
                return bytes.toString(StandardCharsets.UTF_8);
            }
            if (type == null || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type)) {
                throw new DeepSeekStreamingResponse.Rejected(DeepSeekStreamingResponse.Failure.INVALID);
            }
            progress.contentType = "EVENT_STREAM";
            progress.lastActivity = System.nanoTime();
            Thread idleGuard = Thread.ofVirtual().start(() -> guardIdle(raw, progress));
            try {
                InputStreamReader reader = new InputStreamReader(body, StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
                String result = DeepSeekStreamingResponse.read(reader, objectMapper, properties.maxEventBytes(),
                        progress.stream, progress::elapsedMs);
                if (progress.idleExpired) {
                    throw new IOException("Provider response idle budget expired");
                }
                return result;
            } finally {
                progress.finished = true;
                idleGuard.interrupt();
                try {
                    idleGuard.join(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Provider response reading interrupted", interrupted);
                }
            }
        }
    }

    /** 在缓冲或解码之前限制总字节；任何 SSE 注释同样消耗响应预算。 */
    private InputStream monitored(InputStream body, ReadProgress progress) {
        return new FilterInputStream(body) {
            @Override
            public int read() throws IOException {
                int value = in.read();
                if (value >= 0) {
                    observe(new byte[]{(byte) value}, 0, 1);
                }
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                long remaining = properties.maxResponseBytes() - progress.bodyBytes;
                int count = in.read(bytes, offset, (int) Math.min(length, remaining + 1));
                if (count > 0) {
                    observe(bytes, offset, count);
                }
                return count;
            }

            private void observe(byte[] bytes, int offset, int count) {
                long now = progress.elapsedMs();
                progress.lastActivity = System.nanoTime();
                if (progress.firstByteMs < 0) {
                    progress.firstByteMs = now;
                }
                progress.lastByteMs = now;
                progress.bodyBytes += count;
                for (int index = offset; index < offset + count; index++) {
                    int value = bytes[index] & 0xff;
                    if (value != ' ' && value != '\n' && value != '\r' && value != '\t') {
                        progress.nonWhitespaceBytes++;
                    }
                }
                if (progress.bodyBytes > properties.maxResponseBytes()) {
                    throw new DeepSeekStreamingResponse.Rejected(DeepSeekStreamingResponse.Failure.BODY_LIMIT);
                }
            }
        };
    }

    /** 只关闭本次响应；字节进展重置空闲期限，绝对期限仍由实际 JDK request factory 保证。 */
    private void guardIdle(InputStream body, ReadProgress progress) {
        try {
            while (!progress.finished) {
                long remaining = properties.timeout().toNanos() - (System.nanoTime() - progress.lastActivity);
                if (remaining <= 0) {
                    progress.idleExpired = true;
                    try {
                        body.close();
                    } catch (IOException failure) {
                        progress.closeFailureType = failure.getClass().getName();
                    }
                    return;
                }
                Thread.sleep(Math.min(100, remaining / 1_000_000 + 1));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void logFailure(Throwable exception, ReadProgress progress) {
        boolean httpError = exception instanceof RestClientResponseException;
        String phase = httpError ? "HTTP_STATUS" : progress.status == 0 ? "REQUEST" : "RESPONSE_BODY";
        String code = httpError ? "PROVIDER_HTTP_ERROR"
                : progress.status == 0 ? "PROVIDER_EXCHANGE_FAILED"
                : progress.idleExpired ? "PROVIDER_RESPONSE_IDLE_TIMEOUT" : "PROVIDER_RESPONSE_READ_FAILED";
        StringJoiner types = new StringJoiner(">");
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 8; depth++) {
            types.add(cause.getClass().getName());
            cause = cause.getCause();
        }
        logProgress(phase, code, types.toString(), progress);
    }

    private void logProgress(String phase, String code, String types, ReadProgress progress) {
        log.atLevel(phase.equals("COMPLETE") ? Level.INFO : Level.WARN)
                .log("operation=ai-gateway.generate phase={} code={} elapsedMs={} httpStatus={} exceptionTypes={} "
                        + "requestBytes={} bodyBytes={} nonWhitespaceBytes={} headersMs={} firstByteMs={} "
                        + "lastByteMs={} contentType={} contentLength={} stream={} maxTokens={} events={} "
                        + "contentBytes={} reasoningBytes={} firstContentMs={} firstReasoningMs={} "
                        + "promptTokens={} completionTokens={} reasoningTokens={} closeFailureType={}",
                        phase, code, progress.elapsedMs(), progress.status == 0 ? "UNKNOWN" : progress.status, types,
                        progress.requestBytes, progress.bodyBytes, progress.nonWhitespaceBytes, progress.headersMs,
                        progress.firstByteMs, progress.lastByteMs, progress.contentType, progress.contentLength,
                        properties.streaming(), properties.maxTokens(), progress.stream.events,
                        progress.stream.contentBytes, progress.stream.reasoningBytes, progress.stream.firstContentMs,
                        progress.stream.firstReasoningMs, progress.stream.promptTokens, progress.stream.completionTokens,
                        progress.stream.reasoningTokens, progress.closeFailureType);
    }

    /** 调用内独占的观测状态；时间点相对于本次 Gateway 调用，不由整体 API 时长反推。 */
    private static final class ReadProgress {
        private final long started = System.nanoTime();
        private final DeepSeekStreamingResponse.Stats stream = new DeepSeekStreamingResponse.Stats();
        private volatile long lastActivity;
        private volatile boolean finished;
        private volatile boolean idleExpired;
        private volatile String closeFailureType = "NONE";
        private int status;
        private int requestBytes;
        private long bodyBytes;
        private long nonWhitespaceBytes;
        private long headersMs = -1;
        private long firstByteMs = -1;
        private long lastByteMs = -1;
        private long contentLength = -1;
        private String contentType = "UNKNOWN";

        private long elapsedMs() {
            return (System.nanoTime() - started) / 1_000_000;
        }
    }

    private void requireApiKey() {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new AiGatewayException("未配置 DeepSeek API Key");
        }
    }

    private Map<String, Object> toRequestBody(AiRequest request) {
        List<Map<String, String>> messages = request.messages().stream()
                .map(message -> Map.of(
                        "role", toProviderRole(message.role()),
                        "content", message.content()))
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("messages", messages);
        body.put("max_tokens", properties.maxTokens());
        body.put("stream", properties.streaming());
        if (properties.streaming()) {
            body.put("stream_options", Map.of("include_usage", true));
        }

        // 只在调用方要求结构化输出时声明 JSON 模式；
        // DeepSeek 只支持 json_object，响应结构是否正确仍由调用方校验。
        if (request.responseFormat() == AiResponseFormat.JSON) {
            body.put("response_format", Map.of("type", JSON_RESPONSE_TYPE));
        }
        return body;
    }

    private static String toProviderRole(AiRole role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }

    private String extractContent(String responseBody, ReadProgress progress) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new AiGatewayException("DeepSeek 返回了空响应体");
        }

        JsonNode root = DeepSeekStreamingResponse.parse(objectMapper, responseBody);
        JsonNode choice = root.path("choices").path(0);
        if (!choice.path("finish_reason").asText().equals("stop")) {
            throw new DeepSeekStreamingResponse.Rejected(DeepSeekStreamingResponse.Failure.INCOMPLETE);
        }
        DeepSeekStreamingResponse.usage(root, progress.stream);
        JsonNode content = choice.path("message").path("content");
        JsonNode reasoning = choice.path("message").path("reasoning_content");
        if (reasoning.isTextual()) {
            progress.stream.reasoningBytes = reasoning.asText().getBytes(StandardCharsets.UTF_8).length;
        }
        if (content.isTextual()) {
            progress.stream.contentBytes = content.asText().getBytes(StandardCharsets.UTF_8).length;
        }

        if (!content.isTextual() || content.asText().isBlank()) {
            throw new AiGatewayException("DeepSeek 响应中没有可用的 choices[0].message.content");
        }
        return content.asText();
    }
}
