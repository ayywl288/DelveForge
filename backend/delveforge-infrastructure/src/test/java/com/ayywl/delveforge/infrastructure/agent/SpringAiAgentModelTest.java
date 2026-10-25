package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentModelFailure;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentTools;
import com.ayywl.delveforge.infrastructure.ai.DeepSeekProperties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringAiAgentModelTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private SpringAiAgentModel model;
    private String body;
    private int status;

    @BeforeEach
    void setup() throws Exception {
        body = "{\"choices\":[]}";
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        Duration timeout = Duration.ofSeconds(3);
        model = new SpringAiAgentModel(new DeepSeekProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                "fixture-only-key", "fixture-model", timeout, false, timeout, 65536, 1048576, 65536), 8192);
    }

    @AfterEach
    void close() {
        server.stop(0);
    }

    private List<AgentProtocol.Message> history() {
        return List.of(AgentProtocol.Message.text("system", "synthetic instruction"),
                AgentProtocol.Message.text("user", "synthetic task"));
    }

    private String response(String finish, List<Map<String, Object>> calls, String text, boolean usage) throws Exception {
        Map<String, Object> message = Map.of("role", "assistant", "content", text, "tool_calls", calls);
        Map<String, Object> result = new java.util.LinkedHashMap<>(Map.of("id", "fixture-response", "model", "fixture-model",
                "choices", List.of(Map.of("index", 0, "finish_reason", finish, "message", message))));
        if (usage) {
            result.put("usage", Map.of("prompt_tokens", 20, "completion_tokens", 8192, "total_tokens", 8212));
        }
        return json.writeValueAsString(result);
    }

    private Map<String, Object> call(String id, String name, String arguments) {
        return Map.of("id", id, "type", "function", "function", Map.of("name", name, "arguments", arguments));
    }

    @Test
    void agentExplicitlyUsesNonThinkingProtocolWithoutChangingModelOrResponseLimit() throws Exception {
        body = response("tool_calls", List.of(call("read", "read_file", "{\"path\":\"src/A.java\"}")), "", true);
        model.call(history(), AgentTools.definitions());
        JsonNode request = requests.getFirst();
        assertEquals("disabled", request.path("thinking").path("type").asText());
        assertEquals("fixture-model", request.path("model").asText());
        assertEquals(8192, request.path("max_tokens").asInt());
    }

    @Test
    void realSpringAiConversionRejectsLengthBeforeAnyIncompleteToolCanReturn() throws Exception {
        body = response("length", List.of(call("incomplete", "apply_patch", "{\"patch\":\"private-partial")),
                "private-model-output", true);
        var failure = assertThrows(AgentModelFailure.class, () -> model.call(history(), AgentTools.definitions()));
        assertEquals(AgentModelFailure.Reason.RESPONSE_TRUNCATED, failure.reason());
        assertEquals(AgentModelFailure.FinishReason.LENGTH, failure.response().finishReason());
        assertEquals(8212L, failure.response().reportedTokens());
        assertEquals(1, failure.response().toolCallCount());
        assertEquals(8192, failure.response().responseTokenLimit());
        assertEquals(1, requests.size());
        assertEquals(8192, requests.getFirst().path("max_tokens").asInt());
        String diagnostic = json.writeValueAsString(failure.diagnostic(11, false));
        assertFalse(diagnostic.contains("private"));
        assertFalse(failure.getMessage().contains("private"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"choices\":[]}", "{}"})
    void emptyProviderChoicesAreAResponseStageFailure(String value) {
        body = value;
        var failure = assertThrows(AgentModelFailure.class, () -> model.call(history(), AgentTools.definitions()));
        assertEquals(AgentModelFailure.Reason.RESULT_MISSING, failure.reason());
        assertTrue(failure.response().responsePresent());
        assertFalse(failure.response().resultPresent());
        assertNull(failure.response().reportedTokens());
        assertEquals(1, requests.size());
    }

    @Test
    void missingResponseAndMissingOutputHaveDifferentSafeReasons() {
        assertEquals(AgentModelFailure.Reason.RESPONSE_MISSING,
                assertThrows(AgentModelFailure.class, () -> model.decode(null)).reason());
        Generation generation = mock(Generation.class);
        when(generation.getMetadata()).thenReturn(ChatGenerationMetadata.builder().finishReason("stop").build());
        assertEquals(AgentModelFailure.Reason.OUTPUT_MISSING, assertThrows(AgentModelFailure.class,
                () -> model.decode(new ChatResponse(List.of(generation)))).reason());
        assertTrue(requests.isEmpty());
    }

    @Test
    void invalidIdentityAndToolStructureDoNotReturnExecutableReplies() throws Exception {
        body = response("tool_calls", List.of(call("", "apply_patch", "{\"patch\":\"private\"}")), "", false);
        assertEquals(AgentModelFailure.Reason.TOOL_ID_INVALID,
                assertThrows(AgentModelFailure.class, () -> model.call(history(), AgentTools.definitions())).reason());
        var message = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("one", "other", "read_file", "{}"))).build();
        var failure = assertThrows(AgentModelFailure.class, () -> model.decode(new ChatResponse(List.of(new Generation(message)))));
        assertEquals(AgentModelFailure.Reason.TOOL_CALL_TYPE_INVALID, failure.reason());
        assertFalse(failure.getMessage().contains("other"));
    }

    @Test
    void unsupportedConversationRoleIsRejectedBeforeProviderRequest() {
        var failure = assertThrows(AgentModelFailure.class, () -> model.call(
                List.of(AgentProtocol.Message.text("private-role", "private-history")), AgentTools.definitions()));
        assertEquals(AgentModelFailure.Reason.CONVERSATION_ROLE_UNSUPPORTED, failure.reason());
        assertTrue(requests.isEmpty());
        assertFalse(failure.getMessage().contains("private"));
    }

    @Test
    void providerFailureRetainsTransportClassificationWithoutBody() {
        status = 500;
        body = "{\"error\":{\"message\":\"private-provider-error\"}}";
        var failure = assertThrows(AgentModelFailure.class, () -> model.call(history(), AgentTools.definitions()));
        assertEquals("MODEL_TRANSPORT", failure.code());
        assertEquals(AgentModelFailure.Reason.PROVIDER_CALL_FAILED, failure.reason());
        assertNull(failure.response());
        assertFalse(failure.getMessage().contains("private"));
        assertEquals(1, requests.size());
    }

    @Test
    void sourceRefusalContinuationPreservesToolIdentitiesOrderingAndJsonHistory() throws Exception {
        body = response("tool_calls", List.of(call("apply-one", "apply_patch", "{\"patch\":\"synthetic-one\"}"),
                call("apply-two", "apply_patch", "{\"patch\":\"synthetic-two\"}")), "", true);
        var history = new ArrayList<>(history());
        var first = model.call(history, AgentTools.definitions());
        assertEquals(List.of("apply-one", "apply-two"), first.calls().stream().map(AgentProtocol.ToolCall::id).toList());
        history.add(new AgentProtocol.Message("assistant", first.text(), first.calls(), null, null));
        history.add(new AgentProtocol.Message("tool", "{\"ok\":true,\"result\":{\"classification\":\"PATCH_APPLIED\"}}",
                List.of(), "apply-one", "apply_patch"));
        history.add(new AgentProtocol.Message("tool", "{\"ok\":false,\"classification\":\"POLICY_REJECTED\","
                + "\"policyRule\":\"SOURCE_CREDENTIALS\",\"policyStage\":\"PATCH_SOURCE\",\"mutationNotStarted\":true,"
                + "\"alternativeAllowed\":true,\"instruction\":\"选择其他安全实现路径\"}", List.of(), "apply-two", "apply_patch"));
        body = response("stop", List.of(), "{\"status\":\"completed\"}", false);
        var continuation = model.call(history, AgentTools.definitions());
        assertTrue(continuation.calls().isEmpty());
        assertNull(continuation.tokens());
        var messages = requests.getLast().path("messages");
        assertEquals(List.of("system", "user", "assistant", "tool", "tool"),
                java.util.stream.StreamSupport.stream(messages.spliterator(), false).map(m -> m.path("role").asText()).toList());
        assertEquals("apply-one", messages.get(2).path("tool_calls").get(0).path("id").asText());
        assertEquals("apply-two", messages.get(2).path("tool_calls").get(1).path("id").asText());
        assertEquals("apply-one", messages.get(3).path("tool_call_id").asText());
        assertEquals("apply-two", messages.get(4).path("tool_call_id").asText());
        assertEquals("SOURCE_CREDENTIALS", json.readTree(messages.get(4).path("content").asText()).path("policyRule").asText());
        assertEquals(2, requests.size());
    }

    @Test
    void untrustedFinishReasonNeverEntersFailureDiagnostics() throws Exception {
        var message = AssistantMessage.builder().content("private-output").toolCalls(List.of(
                new AssistantMessage.ToolCall("", "function", "apply_patch", "private-patch"))).build();
        var response = new ChatResponse(List.of(new Generation(message,
                ChatGenerationMetadata.builder().finishReason("private-finish-reason").build())),
                ChatResponseMetadata.builder().usage(new DefaultUsage(2, 3, 5)).build());
        var failure = assertThrows(AgentModelFailure.class, () -> model.decode(response));
        assertEquals(AgentModelFailure.FinishReason.OTHER, failure.response().finishReason());
        assertFalse(json.writeValueAsString(failure.diagnostic(1, false)).contains("private"));
    }
}
