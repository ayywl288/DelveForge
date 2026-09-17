package com.ayywl.delveforge.infrastructure.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiMessage;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.ai.AiResponseFormat;
import com.ayywl.delveforge.application.port.ai.AiRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 验证 DeepSeek Adapter 与真实 HTTP 客户端的集成：请求形状、鉴权头、响应解析与失败翻译。
 *
 * <p>使用 {@link MockRestServiceServer} 拦截 HTTP 层，因此不需要真实 DeepSeek 端点，
 * 也不产生真实 LLM 调用（AGENTS.md §10.6）。被替换的只是网络边界——
 * 请求构造与响应解析都是 Adapter 的真实代码。
 */
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
    void failsOnProviderErrorStatus() {
        server.expect(requestTo(BASE_URL + "/chat/completions")).andRespond(withServerError());

        assertThrows(AiGatewayException.class, () -> adapter.generate(jsonRequest()));
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
