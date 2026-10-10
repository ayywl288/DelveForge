package com.ayywl.delveforge.infrastructure.ai;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiMessage;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.ai.AiResponseFormat;
import com.ayywl.delveforge.application.port.ai.AiRole;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * {@link AiGateway} 的 DeepSeek 实现：直接调用 DeepSeek 的 OpenAI 兼容 HTTP API（ADR-0003）。
 *
 * <p>本 Adapter 是 Provider 细节的唯一所在地：Endpoint 路径、鉴权头、请求体形状与响应体
 * 结构都限定在这个类里。业务代码只看到 {@link AiGateway} 与 {@link AiRequest}。
 *
 * <p>它不做任何领域判断，也不解析业务语义：返回的仍是模型原始文本，
 * 由 Application 完成解析与校验（RULE-DOM-003）。
 *
 * <p>所有失败都翻译为 {@link AiGatewayException}：网络与 HTTP 层失败、响应体不是合法
 * JSON、以及响应里没有可用的 {@code choices[0].message.content}。
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

            String content = extractContent(responseBody);
            logProgress("COMPLETE", "PROVIDER_RESPONSE_COMPLETE", "NONE", progress);
            return content;
        } catch (AiGatewayException exception) {
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

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream body = response.getBody()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = body.read(buffer)) != -1) {
                if (count == 0) {
                    continue;
                }
                long now = progress.elapsedMs();
                if (progress.firstByteMs < 0) {
                    progress.firstByteMs = now;
                }
                progress.lastByteMs = now;
                progress.bodyBytes += count;
                for (int index = 0; index < count; index++) {
                    int value = buffer[index] & 0xff;
                    if (value != ' ' && value != '\n' && value != '\r' && value != '\t') {
                        progress.nonWhitespaceBytes++;
                    }
                }
                bytes.write(buffer, 0, count);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private void logFailure(Throwable exception, ReadProgress progress) {
        boolean httpError = exception instanceof RestClientResponseException;
        String phase = httpError ? "HTTP_STATUS" : progress.status == 0 ? "REQUEST" : "RESPONSE_BODY";
        String code = httpError ? "PROVIDER_HTTP_ERROR"
                : progress.status == 0 ? "PROVIDER_EXCHANGE_FAILED" : "PROVIDER_RESPONSE_READ_FAILED";
        StringJoiner types = new StringJoiner(">");
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 8; depth++) {
            types.add(cause.getClass().getName());
            cause = cause.getCause();
        }
        logProgress(phase, code, types.toString(), progress);
    }

    private void logProgress(String phase, String code, String types, ReadProgress progress) {
        log.info("operation=ai-gateway.generate phase={} code={} elapsedMs={} httpStatus={} exceptionTypes={} "
                        + "requestBytes={} bodyBytes={} nonWhitespaceBytes={} headersMs={} firstByteMs={} "
                        + "lastByteMs={} contentType={} contentLength={} stream=false maxTokens=PROVIDER_DEFAULT",
                phase, code, progress.elapsedMs(), progress.status == 0 ? "UNKNOWN" : progress.status, types,
                progress.requestBytes, progress.bodyBytes, progress.nonWhitespaceBytes, progress.headersMs,
                progress.firstByteMs, progress.lastByteMs, progress.contentType, progress.contentLength);
    }

    /** 调用内独占的观测状态；时间点相对于本次 Gateway 调用，不由整体 API 时长反推。 */
    private static final class ReadProgress {
        private final long started = System.nanoTime();
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

    private String extractContent(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new AiGatewayException("DeepSeek 返回了空响应体");
        }

        JsonNode content;
        try {
            content = objectMapper.readTree(responseBody)
                    .path("choices").path(0).path("message").path("content");
        } catch (JsonProcessingException exception) {
            throw new AiGatewayException("DeepSeek 返回的响应体不是合法 JSON", exception);
        }

        if (!content.isTextual() || content.asText().isBlank()) {
            throw new AiGatewayException("DeepSeek 响应中没有可用的 choices[0].message.content");
        }
        return content.asText();
    }
}
