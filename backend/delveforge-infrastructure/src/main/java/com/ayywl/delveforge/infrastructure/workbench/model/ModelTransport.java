package com.ayywl.delveforge.infrastructure.workbench.model;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.workbench.WorkbenchModels.Choice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.messages.part.UnknownPart;
import org.springframework.http.client.ClientHttpRequestInterceptor;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** SDK 负责组装协议。宿主限制大小、重定向与物理重试，并补足两个 SDK 未映射的字段。 */
final class ModelTransport {
    static final class HttpFailure extends RuntimeException {
        final int status;
        final String retryAfter;

        HttpFailure(int status, String retryAfter) {
            super("HTTP " + status);
            this.status = status;
            this.retryAfter = retryAfter;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaType JSON_MEDIA = MediaType.get("application/json");
    private final Choice choice;
    private final List<AgentProtocol.Message> history;
    private final OkHttpClient client;
    private final int requestLimit;
    private final int responseLimit;
    private String routerDetails;
    private final java.util.function.BooleanSupplier admission;
    boolean sent;
    int status;

    ModelTransport(Choice choice, List<AgentProtocol.Message> history, OkHttpClient client,
            int requestLimit, int responseLimit, java.util.function.BooleanSupplier admission) {
        this.admission = admission;
        this.choice = choice;
        this.history = history;
        this.client = client;
        this.requestLimit = requestLimit;
        this.responseLimit = responseLimit;
    }

    static OkHttpClient client(Duration timeout) {
        return new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).callTimeout(timeout).readTimeout(timeout).build();
    }

    Response send(Request request) throws IOException {
        if (request.body() == null || request.body().contentLength() > requestLimit) {
            throw size();
        }
        var buffer = new Buffer();
        request.body().writeTo(buffer);
        if (buffer.size() > requestLimit) {
            throw size();
        }
        byte[] body = buffer.readByteArray();
        body = switch (choice.provider()) {
            case "openrouter" -> routerRequest(body);
            case "google" -> googleRequest(body);
            default -> body;
        };
        if (body.length > requestLimit) {
            throw size();
        }
        Request outgoing = request.newBuilder().method(request.method(), RequestBody.create(body, JSON_MEDIA)).build();
        admit();
        try (Response response = client.newCall(outgoing).execute()) {
            status = response.code();
            if (!response.isSuccessful()) {
                throw failure(status, response.header("Retry-After"), response.peekBody(16384).bytes());
            }
            ResponseBody received = response.body();
            if (received == null || received.contentLength() > responseLimit) {
                throw new AgentException("MODEL_RESPONSE_LIMIT", "模型响应超出容量");
            }
            byte[] bytes = received.byteStream().readNBytes(responseLimit + 1);
            if (bytes.length > responseLimit) {
                throw new AgentException("MODEL_RESPONSE_LIMIT", "模型响应超出容量");
            }
            if ("openrouter".equals(choice.provider())) {
                var details = JSON.readTree(bytes).at("/choices/0/message/reasoning_details");
                routerDetails = details.isArray() ? details.toString() : null;
            }
            return response.newBuilder().body(ResponseBody.create(bytes, received.contentType())).build();
        }
    }

    private byte[] routerRequest(byte[] body) throws IOException {
        var root = JSON.readTree(body);
        var previous = history.stream().filter(m -> m.role().equals("assistant")).iterator();
        for (var message : root.path("messages")) {
            if ("assistant".equals(message.path("role").asText())) {
                var stored = previous.next().stored();
                if (stored != null && SpringMessages.sameProducer(choice, stored)) {
                    for (var part : SpringMessages.parts(stored)) {
                        if (part instanceof UnknownPart unknown && "openrouter".equals(unknown.provider())
                                && "reasoning_details".equals(unknown.kind())) {
                            ((ObjectNode) message).set("reasoning_details", JSON.readTree(unknown.rawJson()));
                        }
                    }
                }
            }
        }
        return JSON.writeValueAsBytes(root);
    }

    UnknownPart routerPart() {
        return routerDetails == null ? null
                : new UnknownPart("openrouter", "reasoning_details", routerDetails, null, Map.of());
    }

    /** Spring AI 2.1 RC2 在 Gemini 的 functionResponse 中省略 ID；可选 ID 仅从原始调用取得。 */
    private byte[] googleRequest(byte[] body) throws IOException {
        var originalIds = new HashMap<String, String>();
        var results = new ArrayList<String>();
        for (var message : history) {
            if ("assistant".equals(message.role()) && message.stored() != null) {
                var original = SpringMessages.parts(message.stored()).stream()
                        .filter(ToolCallPart.class::isInstance).map(ToolCallPart.class::cast).toList();
                for (int i = 0; i < message.calls().size(); i++) {
                    originalIds.put(message.calls().get(i).id(), original.get(i).toolCall().id());
                }
            } else if ("tool".equals(message.role())) {
                results.add(originalIds.get(message.callId()));
            }
        }
        if (results.isEmpty()) {
            return body;
        }
        var root = JSON.readTree(body);
        int index = 0;
        for (var content : root.path("contents")) {
            for (var part : content.path("parts")) {
                if (part.get("functionResponse") instanceof ObjectNode receipt) {
                    String id = results.get(index++);
                    if (id != null && !id.isBlank()) {
                        receipt.put("id", id);
                    }
                }
            }
        }
        if (index != results.size()) {
            throw new AgentException("MODEL_RECEIPT_PAIRING", "工具回执未完整重放");
        }
        return JSON.writeValueAsBytes(root);
    }

    ClientHttpRequestInterceptor deepSeek() {
        return (request, body, next) -> {
            if ("low".equals(choice.effort())) {
                // RC2 的原生枚举只有 HIGH/MAX；DeepSeek 的新 low 值使用相同的官方字段。
                var payload = (ObjectNode) JSON.readTree(body);
                payload.put("reasoning_effort", "low");
                body = JSON.writeValueAsBytes(payload);
            }
            if (body.length > requestLimit) {
                throw size();
            }
            admit();
            var response = next.execute(request, body);
            status = response.getStatusCode().value();
            if (status < 200 || status >= 300) {
                try (response) {
                    throw failure(status, response.getHeaders().getFirst("Retry-After"),
                            response.getBody().readNBytes(16384));
                }
            }
            try (response) {
                byte[] bytes = response.getBody().readNBytes(responseLimit + 1);
                if (bytes.length > responseLimit) {
                    throw new AgentException("MODEL_RESPONSE_LIMIT", "模型响应超出容量");
                }
                var headers = org.springframework.http.HttpHeaders.readOnlyHttpHeaders(response.getHeaders());
                var code = response.getStatusCode();
                String label = response.getStatusText();
                return new org.springframework.http.client.ClientHttpResponse() {
                    @Override
                    public org.springframework.http.HttpStatusCode getStatusCode() {
                        return code;
                    }

                    @Override
                    public String getStatusText() {
                        return label;
                    }

                    @Override
                    public org.springframework.http.HttpHeaders getHeaders() {
                        return headers;
                    }

                    @Override
                    public java.io.InputStream getBody() {
                        return new java.io.ByteArrayInputStream(bytes);
                    }

                    @Override
                    public void close() {
                        // 原始网络响应已关闭，此视图只持有内存数据。
                    }
                };
            }
        };
    }

    private RuntimeException failure(int status, String delay, byte[] body) {
        if (status == 413) {
            return new AgentException("MODEL_CONTEXT_LENGTH", "提供方上下文容量不足");
        }
        if ((status == 400 || status == 422 || status == 429) && body.length > 0) {
            try {
                JsonNode decoded = JSON.readTree(body);
                JsonNode error = decoded == null ? JSON.getNodeFactory().missingNode() : decoded.path("error");
                String code = error.path("code").asText();
                String parameter = error.path("param").asText();
                if (status == 429 && ("insufficient_quota".equals(code)
                        || "zai".equals(choice.provider()) && "1113".equals(code))) {
                    return new AgentException("MODEL_BILLING", "提供方账户额度不足");
                }
                if (status == 400 && "zai".equals(choice.provider()) && "1211".equals(code)) {
                    return new AgentException("MODEL_NOT_AVAILABLE", "当前端点没有此模型");
                }
                // 仅接受能力字段的明确拒绝。工具 schema、历史消息和通用 400 均不足以禁用模型。
                if (status != 429 && (Set.of("tools_not_supported", "unsupported_reasoning_effort").contains(code)
                        || Set.of("unsupported_parameter", "unsupported_value").contains(code)
                        && Set.of("tools", "reasoning", "reasoning.effort", "reasoning_effort", "thinking",
                                "thinking.type", "enable_thinking").contains(parameter))) {
                    return new AgentException("MODEL_CAPABILITY_UNSUPPORTED", "提供方明确拒绝所选能力");
                }
                if ("context_length_exceeded".equals(code)
                        || "context_length_exceeded".equals(error.path("type").asText())) {
                    return new AgentException("MODEL_CONTEXT_LENGTH", "提供方上下文容量不足");
                }
            } catch (IOException ignored) {
                // 错误正文不进入异常和诊断。
            }
        }
        return new HttpFailure(status, delay);
    }

    private void admit() {
        if (Thread.currentThread().isInterrupted()) {
            throw new AgentException("CANCELLED", "模型请求已取消");
        }
        if (sent || !admission.getAsBoolean()) {
            throw new AgentException("REQUEST_NOT_ADMITTED", "模型请求未获准入");
        }
        sent = true;
    }

    private static AgentException size() {
        return new AgentException("REQUEST_BYTES_LIMIT", "模型请求超过传输限制");
    }
}
