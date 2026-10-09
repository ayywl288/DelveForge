package com.ayywl.delveforge.infrastructure.ai;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiMessage;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.ai.AiResponseFormat;
import com.ayywl.delveforge.application.port.ai.AiRole;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 验证 DeepSeek Adapter 与真实 HTTP 客户端的集成：请求形状、鉴权头、响应解析与失败翻译。
 *
 * <p>使用 {@link MockRestServiceServer} 拦截 HTTP 层，因此不需要真实 DeepSeek 端点，
 * 也不产生真实 LLM 调用（AGENTS.md §10.6）。被替换的只是网络边界——
 * 请求构造与响应解析都是 Adapter 的真实代码。
 */
@ExtendWith(OutputCaptureExtension.class)
class DeepSeekAiGatewayAdapterTest {

    private static final String BASE_URL = "https://api.deepseek.example";
    private static final String API_KEY = "test-api-key";
    private static final String MODEL = "deepseek-flash";

    private final RestClient.Builder restClientBuilder = RestClient.builder().baseUrl(BASE_URL);

    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(restClientBuilder).build();

    private final DeepSeekAiGatewayAdapter adapter = new DeepSeekAiGatewayAdapter(
            restClientBuilder.build(),
            new ObjectMapper(),
            new DeepSeekProperties(BASE_URL, API_KEY, MODEL, Duration.ofSeconds(5)));

    @Test
    void sendsMessagesWithJsonResponseFormatAndReturnsContent() throws Exception {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("系统指令"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("本轮用户输入"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andRespond(withSuccess(chatCompletion("{\"interests\":[\"兴趣\"]}"),
                        MediaType.APPLICATION_JSON));

        String content = adapter.generate(new AiRequest(
                List.of(
                        new AiMessage(AiRole.SYSTEM, "系统指令"),
                        new AiMessage(AiRole.USER, "本轮用户输入")),
                AiResponseFormat.JSON));

        assertEquals("{\"interests\":[\"兴趣\"]}", content);
        server.verify();
    }

    /**
     * 未要求结构化输出时不声明 JSON 模式：Port 的 responseFormat 语义要如实传下去。
     */
    @Test
    void omitsResponseFormatForTextRequests() throws Exception {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andExpect(jsonPath("$.response_format").doesNotExist())
                .andRespond(withSuccess(chatCompletion("纯文本回答"), MediaType.APPLICATION_JSON));

        String content = adapter.generate(new AiRequest(
                List.of(new AiMessage(AiRole.USER, "问题")), AiResponseFormat.TEXT));

        assertEquals("纯文本回答", content);
        server.verify();
    }

    /**
     * 缺少 API Key 时不发起请求，直接失败。
     */
    @Test
    void failsWithoutApiKeyAndDoesNotCallTheProvider() {
        DeepSeekAiGatewayAdapter withoutKey = new DeepSeekAiGatewayAdapter(
                restClientBuilder.build(),
                new ObjectMapper(),
                new DeepSeekProperties(BASE_URL, "  ", MODEL, Duration.ofSeconds(5)));

        assertThrows(AiGatewayException.class, () -> withoutKey.generate(jsonRequest()));

        server.verify();
    }

    @Test
    void failsOnProviderErrorStatus(CapturedOutput output) {
        server.expect(requestTo(BASE_URL + "/chat/completions")).andRespond(withServerError());

        assertThrows(AiGatewayException.class, () -> adapter.generate(jsonRequest()));
        assertTrue(output.getAll().contains("phase=HTTP_STATUS code=PROVIDER_HTTP_ERROR"));
        assertTrue(output.getAll().contains("httpStatus=500"));
        server.verify();
    }

    @Test
    void failsWhenResponseBodyIsNotJson() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

        assertThrows(AiGatewayException.class, () -> adapter.generate(jsonRequest()));
    }

    @Test
    void failsWhenMessageContentIsMissing() {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{}}]}", MediaType.APPLICATION_JSON));

        assertThrows(AiGatewayException.class, () -> adapter.generate(jsonRequest()));
    }

    @Test
    void failsWhenMessageContentIsBlank() throws Exception {
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess(chatCompletion("   "), MediaType.APPLICATION_JSON));

        assertThrows(AiGatewayException.class, () -> adapter.generate(jsonRequest()));
    }

    @Test
    void recordsResponseReadFailureWithoutLoggingRuntimeText(CapturedOutput output) {
        String sentinel = "private-provider-prompt-source-credential";
        server.expect(requestTo(BASE_URL + "/chat/completions")).andRespond(request -> {
            MockClientHttpResponse response = new MockClientHttpResponse(new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException(sentinel);
                }
            }, HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response;
        });

        assertThrows(AiGatewayException.class, () -> adapter.generate(new AiRequest(
                List.of(new AiMessage(AiRole.USER, sentinel)), AiResponseFormat.JSON)));
        String logs = output.getAll();
        assertTrue(logs.contains("phase=RESPONSE_BODY code=PROVIDER_RESPONSE_READ_FAILED"));
        assertTrue(logs.contains("httpStatus=200"));
        assertTrue(logs.matches("(?s).*elapsedMs=\\d+.*"));
        assertTrue(logs.contains("java.io.IOException"));
        assertFalse(logs.contains(sentinel));
        assertFalse(logs.contains(API_KEY));
        assertFalse(logs.contains("Authorization"));
        server.verify();
    }

    @Test
    void recordsExchangeFailureWithoutInventingAResponseStatus(CapturedOutput output) {
        String sentinel = "private-exchange-error";
        server.expect(requestTo(BASE_URL + "/chat/completions")).andRespond(request -> {
            throw new IOException(sentinel);
        });

        assertThrows(AiGatewayException.class, () -> adapter.generate(jsonRequest()));
        String logs = output.getAll();
        assertTrue(logs.contains("phase=REQUEST code=PROVIDER_EXCHANGE_FAILED"));
        assertTrue(logs.contains("httpStatus=UNKNOWN"));
        assertTrue(logs.contains("java.io.IOException"));
        assertFalse(logs.contains(sentinel));
        assertFalse(logs.contains(API_KEY));
        server.verify();
    }

    /** 使用实际配置的 JDK HTTP 客户端；期限关闭流可以表现为普通 IOException，不能仅靠类型推断超时。 */
    @Test
    void diagnosesRealJdkBodyReadFailureWithKnownStatus(CapturedOutput output) throws Exception {
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        provider.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write("{\"choices\":[".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        provider.start();
        try {
            var actual = new DeepSeekAiGatewayConfiguration().deepSeekAiGateway(new DeepSeekProperties(
                    "http://127.0.0.1:" + provider.getAddress().getPort(), API_KEY, MODEL,
                    Duration.ofMillis(1500)));
            assertThrows(AiGatewayException.class, () -> actual.generate(jsonRequest()));
            String logs = output.getAll();
            assertTrue(logs.contains("phase=RESPONSE_BODY code=PROVIDER_RESPONSE_READ_FAILED"));
            assertTrue(logs.contains("httpStatus=200"));
            assertTrue(logs.contains("java.io.IOException"));
            assertFalse(logs.contains(API_KEY));
        } finally {
            release.countDown();
            provider.stop(0);
        }
    }

    private static AiRequest jsonRequest() {
        return new AiRequest(
                List.of(new AiMessage(AiRole.USER, "问题")), AiResponseFormat.JSON);
    }

    private static String chatCompletion(String content) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("choices").addObject().putObject("message").put("content", content);
        return objectMapper.writeValueAsString(root);
    }
}
