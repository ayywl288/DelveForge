package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentModelFailure;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentTools;
import com.ayywl.delveforge.application.port.ai.AiRequest.Thinking;
import com.ayywl.delveforge.infrastructure.ai.DeepSeekProperties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpServer;
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

class SpringAiAgentModelTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private SpringAiAgentModel model;
    private String body;
    private int status;
    private int transientFailures;
    private String retryAfter;

    @BeforeEach
    void setup() throws Exception {
        body = "{\"choices\":[]}";
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            boolean transientFailure = requests.size() <= transientFailures;
            byte[] bytes = (transientFailure ? "{\"error\":{\"message\":\"private-error\"}}" : body).getBytes(StandardCharsets.UTF_8);
            if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(transientFailure ? 503 : status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        Duration timeout = Duration.ofSeconds(3);
        model = new SpringAiAgentModel(new DeepSeekProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                "fixture-only-key", "fixture-model", timeout, false, timeout, 65536, 1048576, 65536, 900000), 65536);
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
        Map<String, Object> message = Map.of("role", "assistant", "content", text, "tool_calls", calls,
                "reasoning_content", "synthetic-private-" + finish);
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
    void agentUsesThinkingHighWithConfiguredModelAndReserve() throws Exception {
        body = response("tool_calls", List.of(call("read", "read_file", "{\"path\":\"src/A.java\"}")), "", true);
        model.call(history(), AgentTools.definitions());
        JsonNode request = requests.getFirst();
        assertEquals("enabled", request.path("thinking").path("type").asText());
        assertEquals("high", request.path("reasoning_effort").asText());
        assertEquals("fixture-model", request.path("model").asText());
        assertEquals(65536, request.path("max_tokens").asInt());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = Thinking.class, names = {"DISABLED", "MAX"})
    void configuredModesControlBothWireParametersAndReplay(Thinking mode) throws Exception {
        Duration timeout = Duration.ofSeconds(3);
        model = new SpringAiAgentModel(new DeepSeekProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                "fixture-only-key", "fixture-model", timeout, false, timeout, 65536, 1048576, 65536, 900000), 32768, mode);
        var history = new ArrayList<>(history());
        history.add(new AgentProtocol.Message("assistant", "previous", List.of(), null, null, "private-prior"));
        history.add(AgentProtocol.Message.text("user", "continue"));
        body = response("stop", List.of(), "done", true);
        var reply = model.call(history, AgentTools.definitions());
        var request = requests.getFirst();
        assertEquals(32768, request.required("max_tokens").asInt());
        assertEquals(mode == Thinking.DISABLED ? "disabled" : "enabled", request.at("/thinking/type").asText());
        assertEquals(mode == Thinking.MAX, request.has("reasoning_effort"));
        assertEquals(mode == Thinking.MAX, request.at("/messages/2").has("reasoning_content"));
        assertEquals(mode == Thinking.MAX, reply.reasoningContent() != null);
        if (mode == Thinking.MAX) {
            assertEquals("max", request.required("reasoning_effort").asText());
        }
    }

    @Test
    void malformedContentAndAmbiguousCallResultsAreRejectedBeforeDispatch() throws Exception {
        var payload = json.readTree(response("tool_calls", List.of(call("same", "read_file", "{}"),
                call("same", "edit_file", "{}")), "", false));
        body = payload.toString();
        assertEquals(AgentModelFailure.Reason.TOOL_ID_INVALID, assertThrows(AgentModelFailure.class,
                () -> model.call(history(), AgentTools.definitions())).reason());
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.at("/choices/0/message"))
                .putObject("content").put("private", "untrusted");
        body = payload.toString();
        var failure = assertThrows(AgentModelFailure.class, () -> model.call(history(), AgentTools.definitions()));
        assertEquals(AgentModelFailure.Reason.OUTPUT_INVALID, failure.reason());
        assertFalse(failure.getMessage().contains("private"));
    }

    @Test
    void missingAssistantReasoningCannotDispatchOrBeReconstructed() throws Exception {
        var payload = json.readTree(response("tool_calls", List.of(call("edit", "edit_file", "{}")), "", false));
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.at("/choices/0/message")).remove("reasoning_content");
        body = payload.toString();
        assertEquals(AgentModelFailure.Reason.REASONING_MISSING, assertThrows(AgentModelFailure.class,
                () -> model.call(history(), AgentTools.definitions())).reason());
        var history = new ArrayList<>(history());
        history.add(AgentProtocol.Message.text("assistant", "synthetic"));
        assertEquals(AgentModelFailure.Reason.REASONING_MISSING, assertThrows(AgentModelFailure.class,
                () -> model.call(history, AgentTools.definitions())).reason());
        assertEquals(1, requests.size());
    }

    @Test
    void returnsOnlyNumericalUsageAndSupportedNativeCacheMetadata() throws Exception {
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(
                response("stop", List.of(), "completed", true));
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("usage"))
                .putObject("prompt_tokens_details").put("cached_tokens", 10);
        body = payload.toString();
        var messages = List.of(AgentProtocol.Message.text("user", "Fix the failing test."));
        var reply = model.call(messages, List.of());
        try (var counter = new DeepSeekTokenCounterAdapter("deepseek-flash", Thinking.HIGH)) {
            long estimate = counter.countPromptTokens(messages, List.of());
            assertTrue(estimate > 9);
        }
        assertEquals(20L, reply.usage().promptTokens());
        assertEquals(8192L, reply.usage().completionTokens());
        assertEquals(8212L, reply.usage().totalTokens());
        assertEquals(10L, reply.usage().cacheHitTokens());
        assertNull(reply.usage().cacheMissTokens());
        assertFalse(json.writeValueAsString(reply.usage()).contains("completed"));
    }

    @Test
    void moderateHistoryAboveOldBoundaryStillReachesProvider() throws Exception {
        body = response("stop", List.of(), "completed", false);
        model.call(List.of(AgentProtocol.Message.text("system", "x".repeat(200000))), AgentTools.definitions());
        assertEquals(1, requests.size());
    }

    @Test
    void serializedEscapingAndToolDefinitionsAreCheckedBeforeNetworkSubmission() {
        var escaped = List.of(AgentProtocol.Message.text("system", "\u0001".repeat(200000)));
        var first = assertThrows(AgentException.class, () -> model.call(escaped, AgentTools.definitions()));
        assertEquals("REQUEST_BYTES_LIMIT", first.code());
        var second = assertThrows(AgentException.class, () -> model.call(history(), List.of(
                new AgentProtocol.ToolSpec("large", "x".repeat(910000), "{}"))));
        assertEquals("REQUEST_BYTES_LIMIT", second.code());
        var expanded = new ArrayList<>(history());
        expanded.add(AgentProtocol.Message.text("system", "x".repeat(910000)));
        assertEquals("REQUEST_BYTES_LIMIT", assertThrows(AgentException.class,
                () -> model.call(expanded, AgentTools.definitions())).code());
        assertTrue(requests.isEmpty());
    }

    @Test
    void responseReserveDoesNotCountTowardSerializedRequestBytes() throws Exception {
        var timeout = Duration.ofSeconds(3);
        var properties = new DeepSeekProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                "fixture-only-key", "fixture-model", timeout, false, timeout, 65536, 1048576, 65536, 2048);
        body = response("stop", List.of(), "completed", true);
        new SpringAiAgentModel(properties, 8192).call(history(), List.of());
        assertEquals(1, requests.size());
    }

    @Test
    void realSpringAiConversionRejectsLengthBeforeAnyIncompleteToolCanReturn() throws Exception {
        body = response("length", List.of(call("incomplete", "edit_file", "{\"patch\":\"private-partial")),
                "private-model-output", true);
        var failure = assertThrows(AgentModelFailure.class, () -> model.call(history(), AgentTools.definitions()));
        assertEquals(AgentModelFailure.Reason.RESPONSE_TRUNCATED, failure.reason());
        assertEquals(AgentModelFailure.FinishReason.LENGTH, failure.response().finishReason());
        assertEquals(8212L, failure.response().reportedTokens());
        assertEquals(1, failure.response().toolCallCount());
        assertEquals(65536, failure.response().responseTokenLimit());
        assertEquals(1, requests.size());
        assertEquals(65536, requests.getFirst().path("max_tokens").asInt());
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
    void missingResponseAndMissingOutputHaveDifferentSafeReasons() throws Exception {
        assertEquals(AgentModelFailure.Reason.RESPONSE_MISSING,
                assertThrows(AgentModelFailure.class, () -> model.decode(null)).reason());
        body = "{\"choices\":[{\"finish_reason\":\"stop\"}]}";
        assertEquals(AgentModelFailure.Reason.OUTPUT_MISSING, assertThrows(AgentModelFailure.class,
                () -> model.call(history(), AgentTools.definitions())).reason());
    }

    @Test
    void invalidIdentityAndToolStructureDoNotReturnExecutableReplies() throws Exception {
        body = response("tool_calls", List.of(call("", "edit_file", "{\"patch\":\"private\"}")), "", false);
        assertEquals(AgentModelFailure.Reason.TOOL_ID_INVALID,
                assertThrows(AgentModelFailure.class, () -> model.call(history(), AgentTools.definitions())).reason());
        body = response("tool_calls", List.of(Map.of("id", "one", "type", "other", "function",
                Map.of("name", "read_file", "arguments", "{}"))), "", false);
        var failure = assertThrows(AgentModelFailure.class, () -> model.call(history(), AgentTools.definitions()));
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
        assertEquals(3, requests.size());
        assertEquals(3, model.drainDiagnostics().size());
    }

    @Test
    void transientRequestRetryPreservesExactHistoryAndUnknownUsage() throws Exception {
        transientFailures = 1;
        retryAfter = "999999999999999999";
        body = response("stop", List.of(), "completed", true);
        var recorded = new ArrayList<>(history());
        recorded.add(new AgentProtocol.Message("assistant", "", List.of(
                new AgentProtocol.ToolCall("edit-once", "edit_file", "{}")), null, null, "synthetic-private-retry"));
        recorded.add(new AgentProtocol.Message("tool", "{\"ok\":true}", List.of(), "edit-once", "edit_file"));
        long start = System.nanoTime();
        var reply = model.call(recorded, AgentTools.definitions());
        assertEquals(2, requests.size());
        assertEquals(requests.get(0), requests.get(1));
        assertEquals(4, requests.get(1).path("messages").size());
        assertEquals("synthetic-private-retry", requests.get(1).at("/messages/2/reasoning_content").asText());
        assertEquals("synthetic-private-stop", reply.reasoningContent());
        assertNull(reply.tokens());
        assertEquals(20L, reply.usage().promptTokens());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 6);
        var diagnostics = model.drainDiagnostics();
        assertEquals(503, diagnostics.get(0).get("httpStatus"));
        assertEquals(200, diagnostics.get(1).get("httpStatus"));
        assertFalse(diagnostics.toString().contains("private"));
        assertTrue(model.drainDiagnostics().isEmpty());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {400, 401, 402, 403})
    void invalidAuthenticationBillingOrRequestIsNotRetried(int code) {
        status = code;
        body = "{\"error\":{\"message\":\"private-error\"}}";
        assertThrows(AgentModelFailure.class, () -> model.call(history(), AgentTools.definitions()));
        assertEquals(1, requests.size());
        assertEquals(code, model.drainDiagnostics().getFirst().get("httpStatus"));
    }

    @Test
    void cancellationDuringRetryBackoffStopsFurtherRequests() throws Exception {
        status = 503;
        retryAfter = "2";
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try {
                model.call(history(), AgentTools.definitions());
            } catch (Throwable caught) {
                failure.set(caught);
            }
        });
        worker.start();
        for (int wait = 0; requests.isEmpty() && wait < 100; wait++) Thread.sleep(10);
        Thread.sleep(100);
        worker.interrupt();
        worker.join(3000);
        assertFalse(worker.isAlive());
        assertEquals("CANCELLED", ((AgentException) failure.get()).code());
        assertEquals(1, requests.size());
    }

    @Test
    void sourceRefusalContinuationPreservesToolIdentitiesOrderingAndJsonHistory() throws Exception {
        body = response("tool_calls", List.of(call("apply-one", "edit_file", "{\"patch\":\"synthetic-one\"}"),
                call("apply-two", "edit_file", "{\"patch\":\"synthetic-two\"}")), "", true);
        var history = new ArrayList<>(history());
        var first = model.call(history, AgentTools.definitions());
        assertEquals(List.of("apply-one", "apply-two"), first.calls().stream().map(AgentProtocol.ToolCall::id).toList());
        history.add(first.assistantMessage(first.text()));
        history.add(new AgentProtocol.Message("tool", "{\"ok\":true,\"result\":{\"classification\":\"PATCH_APPLIED\"}}",
                List.of(), "apply-one", "edit_file"));
        history.add(new AgentProtocol.Message("tool", "{\"ok\":false,\"classification\":\"POLICY_REJECTED\","
                + "\"policyRule\":\"SOURCE_CREDENTIALS\",\"policyStage\":\"PATCH_SOURCE\",\"mutationNotStarted\":true,"
                + "\"alternativeAllowed\":true,\"instruction\":\"选择其他安全实现路径\"}", List.of(), "apply-two", "edit_file"));
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
        assertEquals("synthetic-private-tool_calls", messages.get(2).path("reasoning_content").asText());
        history.add(continuation.assistantMessage(continuation.text()));
        history.add(AgentProtocol.Message.text("user", "Completion rejected"));
        body = response("tool_calls", List.of(call("read-next", "read_file", "{}")), "", false);
        var second = model.call(history, AgentTools.definitions());
        history.add(second.assistantMessage(second.text()));
        history.add(new AgentProtocol.Message("tool", "{}", List.of(), "read-next", "read_file"));
        body = response("stop", List.of(), "done", true);
        model.call(history, AgentTools.definitions());
        messages = requests.getLast().path("messages");
        assertEquals("synthetic-private-stop", messages.get(5).path("reasoning_content").asText());
        assertEquals("synthetic-private-tool_calls", messages.get(7).path("reasoning_content").asText());
        assertEquals("read-next", messages.get(8).path("tool_call_id").asText());
        assertFalse(model.drainDiagnostics().toString().contains("synthetic-private"));
        assertFalse(second.toString().contains("synthetic-private"));
        assertFalse(json.writeValueAsString(second).contains("synthetic-private"));
        assertFalse(json.writeValueAsString(second.assistantMessage("")).contains("synthetic-private"));
    }

    @Test
    void incompleteFinishDoesNotReturnExecutableCallsOrLeakBody() throws Exception {
        body = response("content_filter", List.of(call("private-id", "edit_file", "private-patch")), "private-output", true);
        var failure = assertThrows(AgentModelFailure.class, () -> model.call(history(), AgentTools.definitions()));
        assertEquals(AgentModelFailure.FinishReason.CONTENT_FILTER, failure.response().finishReason());
        assertFalse(json.writeValueAsString(failure.diagnostic(1, false)).contains("private"));
    }
}
