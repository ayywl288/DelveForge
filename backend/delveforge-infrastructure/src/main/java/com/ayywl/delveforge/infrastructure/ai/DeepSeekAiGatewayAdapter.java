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
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
        long started = System.nanoTime();
        int[] responseStatus = {0};

        try {
            String responseBody = restClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toRequestBody(request))
                    .retrieve()
                    .onStatus(status -> {
                        // 只旁观响应状态；返回 false 保留默认 HTTP 错误处理，不接受错误响应。
                        responseStatus[0] = status.value();
                        return false;
                    }, (ignoredRequest, ignoredResponse) -> {})
                    .body(String.class);

            return extractContent(responseBody);
        } catch (AiGatewayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            logFailure(exception, responseStatus[0], started);
            // 网络、HTTP 状态码与反序列化失败都在这里统一翻译，避免第三方异常类型泄漏出去。
            throw new AiGatewayException("调用 DeepSeek 失败", exception);
        }
    }

    /** 未收到状态时不推断响应阶段；原因链只取类型，限制深度且不传 Throwable 给日志。 */
    private void logFailure(RuntimeException exception, int status, long started) {
        boolean httpError = exception instanceof RestClientResponseException;
        String phase = httpError ? "HTTP_STATUS" : status == 0 ? "REQUEST" : "RESPONSE_BODY";
        String code = httpError ? "PROVIDER_HTTP_ERROR"
                : status == 0 ? "PROVIDER_EXCHANGE_FAILED" : "PROVIDER_RESPONSE_READ_FAILED";
        StringJoiner types = new StringJoiner(">");
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 8; depth++) {
            types.add(cause.getClass().getName());
            cause = cause.getCause();
        }
        log.warn("operation=ai-gateway.generate phase={} code={} elapsedMs={} httpStatus={} exceptionTypes={}",
                phase, code, (System.nanoTime() - started) / 1_000_000,
                status == 0 ? "UNKNOWN" : Integer.toString(status), types.toString());
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
