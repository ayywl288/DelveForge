package com.ayywl.delveforge.infrastructure.workbench.model;

import com.ayywl.delveforge.application.agent.AgentContextBudget;
import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentModelFailure;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.workbench.ConversationAgent;
import com.ayywl.delveforge.application.workbench.ConversationContext;
import com.ayywl.delveforge.application.workbench.ExecutionEnvironment;
import com.ayywl.delveforge.application.workbench.Workbench;
import com.ayywl.delveforge.application.workbench.WorkbenchModels;
import com.ayywl.delveforge.application.workbench.WorkbenchModels.Choice;
import com.ayywl.delveforge.application.workbench.WorkbenchModels.Frozen;
import com.ayywl.delveforge.application.workbench.WorkbenchRejection;
import com.ayywl.delveforge.domain.workbench.RunState;
import com.ayywl.delveforge.infrastructure.workbench.ProjectWorkcopies;
import com.ayywl.delveforge.infrastructure.workbench.SqliteWorkbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.sqlite.SQLiteDataSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class ProviderProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<JsonNode> requests = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();
    private final List<String> auth = new ArrayList<>();
    private final List<String> responses = new ArrayList<>();
    private HttpServer server;
    private int status = 200;

    @BeforeEach
    void listen() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody()));
            paths.add(exchange.getRequestURI().getPath());
            auth.add(String.join("/", List.of("Authorization", "x-api-key", "x-goog-api-key").stream()
                    .map(h -> String.valueOf(exchange.getRequestHeaders().getFirst(h))).toList()));
            String body = responses.get(Math.min(requests.size() - 1, responses.size() - 1));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static Choice choice(ProviderFamily f) {
        return switch (f) {
            case OPENAI -> new Choice(f.id(), "gpt-5.4", "xhigh");
            case ANTHROPIC -> new Choice(f.id(), "claude-opus-4-6", "max");
            case GOOGLE -> new Choice(f.id(), "gemini-3.1-pro-preview", "high");
            case DEEPSEEK -> new Choice(f.id(), "deepseek-flash", "max");
            case ZAI -> new Choice(f.id(), "glm-5", "on");
            case QWEN -> new Choice(f.id(), "qwen3.5-plus", "off");
            case KIMI -> new Choice(f.id(), "kimi-k2.6", "on");
            case OPENROUTER -> new Choice(f.id(), "openai/gpt-5.4", "high");
        };
    }

    private WorkbenchChatModel adapter(ProviderFamily f) {
        return adapter(f, choice(f));
    }

    private WorkbenchChatModel adapter(ProviderFamily f, Choice choice) {
        var config = new Frozen(choice, f.protocol, f.defaultPreset(), "", "synthetic-ref",
                new AgentContextBudget(32000, 2048, .9), "contract");
        var timeout = Duration.ofSeconds(10);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new WorkbenchChatModel(config, base, () -> "test-account", new ProviderChatModels(timeout),
                ModelTransport.client(timeout), 500000, 500000);
    }

    private static String reply(ProviderFamily family, boolean tool) {
        String arguments = "{\\\"path\\\":\\\"README.md\\\"}";
        if (family == ProviderFamily.OPENAI) {
            String parts = tool ? """
                    [{"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":"private-encrypted"},
                     {"id":"fc_1","type":"function_call","call_id":"call_1","name":"Read","arguments":"%s","status":"completed"}]
                    """.formatted(arguments) : """
                    [{"id":"msg_1","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"OK","annotations":[]}]}]
                    """;
            return """
                    {"id":"resp_1","object":"response","created_at":1,"status":"completed","model":"gpt-5.4",
                     "output":%s,"usage":{"input_tokens":11,"output_tokens":6,"total_tokens":17}}
                    """.formatted(parts);
        }
        if (family == ProviderFamily.ANTHROPIC) {
            String parts = tool ? """
                    [{"type":"thinking","thinking":"private-thought","signature":"private-signature"},
                     {"type":"tool_use","id":"call_1","name":"Read","input":{"path":"README.md"}}]
                    """ : "[{\"type\":\"text\",\"text\":\"OK\"}]";
            return """
                    {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-4-6","content":%s,
                     "stop_reason":"%s","usage":{"input_tokens":11,"output_tokens":6}}
                    """.formatted(parts, tool ? "tool_use" : "end_turn");
        }
        if (family == ProviderFamily.GOOGLE) {
            String parts = tool ? """
                    [{"thoughtSignature":"c2lnbmVk","functionCall":{"id":"call_1","name":"Read","args":{"path":"README.md"}}}]
                    """ : "[{\"text\":\"OK\"}]";
            return """
                    {"responseId":"gemini-1","modelVersion":"gemini-3.1-pro-preview",
                     "candidates":[{"index":0,"finishReason":"STOP","content":{"role":"model","parts":%s}}],
                     "usageMetadata":{"promptTokenCount":11,"candidatesTokenCount":6,"totalTokenCount":17}}
                    """.formatted(parts);
        }
        String call = tool ? ",\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"Read\",\"arguments\":\"" + arguments + "\"}}]" : "";
        String details = family == ProviderFamily.OPENROUTER
                ? ",\"reasoning_details\":[{\"type\":\"reasoning.encrypted\",\"data\":\"private-router-block\",\"index\":0}]" : "";
        return """
                {"id":"chat-1","object":"chat.completion","created":1,"model":"fixture","choices":[{"index":0,
                 "finish_reason":"%s","message":{"role":"assistant","content":"%s","reasoning_content":"private-thought"%s%s}}],
                 "usage":{"prompt_tokens":11,"completion_tokens":6,"total_tokens":17,"prompt_tokens_details":{"cached_tokens":3}}}
                """.formatted(tool ? "tool_calls" : "stop", tool ? "" : "OK", call, details);
    }

    @Test
    void workbenchExecutesTheNativeProposalOnceAndReplaysItsDurableReceipt(@TempDir Path root) throws Exception {
        var family = ProviderFamily.ANTHROPIC;
        var selected = choice(family);
        responses.add(reply(family, true));
        responses.add(reply(family, false));
        var db = new SQLiteDataSource();
        db.setUrl("jdbc:sqlite:" + root.resolve("control.db"));
        Flyway.configure().dataSource(db).locations("classpath:db/workbench").load().migrate();
        var models = new SpringWorkbenchModels(db, JSON, root, selected, new AgentContextBudget(32000, 2048, .9),
                Duration.ofSeconds(5), 500000, 500000, name -> name.equals("ANTHROPIC_API_KEY") ? "test-account" : null);
        var store = new SqliteWorkbench(db, JSON, 2, models);
        var files = new ProjectWorkcopies(root.resolve("projects"));
        var executions = new AtomicInteger();
        var guest = new ExecutionEnvironment() {
            @Override
            public Prepared prepare(Workbench.Run run, Workbench.Call call, String capability) {
                return new Prepared("test-container", "generation", "test-exec");
            }

            @Override
            public void execute(Workbench.Call call, BiConsumer<String, byte[]> capture) {
                executions.incrementAndGet();
                capture.accept("stdout", "observed file content".getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public End reconcile(Workbench.Call call, boolean stop) {
                return new End(true, 0, "test exit");
            }
        };
        var binding = mock(WorkbenchModels.class);
        when(binding.bind(models.freeze(selected))).thenReturn(new WorkbenchModels.Binding(
                adapter(family), new WorkbenchTokenEstimate()));
        try (var agent = new ConversationAgent(store, files, guest, binding, JSON, 6)) {
            String project = agent.project("test", null);
            String conversation = store.conversation(project, "Read a file");
            String run = agent.receive(conversation, "request-1", "Read README.md").runId();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (store.run(run).state() == RunState.RUNNING && System.nanoTime() < deadline) {
                Thread.sleep(15);
            }
            assertEquals(RunState.COMPLETED, store.run(run).state());
            assertEquals(1, executions.get());
            assertEquals(2, requests.size());
            assertTrue(requests.get(1).toString().contains("observed file content"));
            assertTrue(requests.get(1).toString().contains("private-signature"));
            var rows = store.timeline(conversation);
            assertEquals(List.of("user", "assistant", "tool", "assistant"),
                    rows.stream().map(row -> row.get("role")).toList());
            assertEquals("call_1", rows.get(2).get("provider_id"));
            assertFalse(rows.toString().contains("private-signature"));
            assertEquals(2, ((Number) store.runs(conversation).getFirst().get("requests")).intValue());
        }
    }

    @Test
    void concurrentWorkersSendOnlyTheirFrozenChoiceAndConversation(@TempDir Path root) throws Exception {
        var deepseek = choice(ProviderFamily.DEEPSEEK);
        var qwen = choice(ProviderFamily.QWEN);
        responses.add(reply(ProviderFamily.DEEPSEEK, false));
        var db = new SQLiteDataSource();
        db.setUrl("jdbc:sqlite:" + root.resolve("control.db"));
        Flyway.configure().dataSource(db).locations("classpath:db/workbench").load().migrate();
        var models = new SpringWorkbenchModels(db, JSON, root, deepseek, new AgentContextBudget(32000, 2048, .9),
                Duration.ofSeconds(5), 500000, 500000, name -> "test-account");
        var store = new SqliteWorkbench(db, JSON, 2, models);
        var files = new ProjectWorkcopies(root.resolve("projects"));
        var together = new CountDownLatch(2);
        var bindings = mock(WorkbenchModels.class);
        when(bindings.bind(any())).thenAnswer(invocation -> {
            Frozen frozen = invocation.getArgument(0);
            together.countDown();
            assertTrue(together.await(8, TimeUnit.SECONDS), "两个运行应同时持有执行位");
            return new WorkbenchModels.Binding(adapter(ProviderFamily.named(frozen.choice().provider()),
                    frozen.choice()), new WorkbenchTokenEstimate());
        });
        try (var agent = new ConversationAgent(store, files, mock(ExecutionEnvironment.class), bindings, JSON, 3)) {
            String a = store.conversation(agent.project("First", null), "A");
            String b = store.conversation(agent.project("Second", null), "B");
            store.selectModel(b, qwen);
            String first = agent.receive(a, "a", "request-one").runId();
            String second = agent.receive(b, "b", "request-two").runId();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            for (String run : List.of(first, second)) {
                while (store.run(run).state() == RunState.RUNNING && System.nanoTime() < deadline) {
                    Thread.sleep(15);
                }
                assertEquals(RunState.COMPLETED, store.run(run).state());
            }
            assertEquals(deepseek, store.runModel(first).choice());
            assertEquals(qwen, store.runModel(second).choice());
            assertEquals(2, requests.size());
            for (var request : requests) {
                boolean firstModel = deepseek.model().equals(request.path("model").asText());
                assertEquals(firstModel ? "request-one" : "request-two", request.at("/messages/1/content").asText());
                assertFalse(request.toString().contains(firstModel ? "request-two" : "request-one"));
            }
        }
    }

    @Test
    void failedToolProbeDisablesOnlyThatCredentialModelAndEffort(@TempDir Path root) {
        var selected = choice(ProviderFamily.QWEN);
        var db = new SQLiteDataSource();
        db.setUrl("jdbc:sqlite:" + root.resolve("control.db"));
        Flyway.configure().dataSource(db).locations("classpath:db/workbench").load().migrate();
        var models = spy(new SpringWorkbenchModels(db, JSON, root, selected, new AgentContextBudget(32000, 2048, .9),
                Duration.ofSeconds(5), 500000, 500000, name -> "test-account"));
        doReturn(new WorkbenchModels.Binding(adapter(ProviderFamily.QWEN), new WorkbenchTokenEstimate()))
                .when(models).bind(any());
        responses.add(reply(ProviderFamily.QWEN, false));
        assertThrows(WorkbenchRejection.class, () -> models.validate(selected));
        assertThrows(WorkbenchRejection.class, () -> models.freeze(selected));
        assertEquals("on", models.freeze(new Choice("qwen", selected.model(), "on")).choice().effort());
        String ping = reply(ProviderFamily.QWEN, true).replace("Read", "Ping")
                .replace("{\\\"path\\\":\\\"README.md\\\"}", "{}");
        responses.add(ping);
        responses.add(reply(ProviderFamily.QWEN, false));
        models.validate(selected);
        assertEquals(selected, models.freeze(selected).choice());
    }

    @ParameterizedTest
    @EnumSource(ProviderFamily.class)
    void roundTripsNativeProposalsAndReceiptsWithoutExecutingTools(ProviderFamily family) throws Exception {
        responses.add(reply(family, true));
        responses.add(reply(family, false));
        var history = new ArrayList<AgentProtocol.Message>();
        history.add(AgentProtocol.Message.text("user", "read"));
        var first = adapter(family).call(history, ConversationContext.TOOLS);
        assertEquals(1, requests.size());
        assertEquals(1, first.calls().size());
        var call = first.calls().getFirst();
        assertEquals("call_1", call.id());
        assertEquals("Read", call.name());
        assertEquals("README.md", JSON.readTree(call.arguments()).path("path").asText());
        assertEquals(17L, first.tokens());
        var saved = JSON.readValue(JSON.writeValueAsBytes(first.stored()), AgentProtocol.StoredMessage.class);
        history.add(new AgentProtocol.Message("assistant", first.text(), first.calls(), null, null, null, saved));
        history.add(new AgentProtocol.Message("tool", "{\"result\":\"receipt-evidence\"}", List.of(), call.id(), call.name()));
        assertEquals("OK", adapter(family).call(history, ConversationContext.TOOLS).text());
        var request = requests.getFirst();
        String back = requests.get(1).toString();
        assertTrue(auth.stream().allMatch(h -> h.contains("test-account")));
        assertTrue(back.contains("receipt-evidence") && back.contains("call_1"));
        assertFalse(JSON.writeValueAsString(first).contains("private-"));
        switch (family) {
            case OPENAI -> {
                assertTrue(paths.getFirst().endsWith("/responses"));
                assertEquals("xhigh", request.at("/reasoning/effort").asText());
                assertFalse(request.path("store").asBoolean());
                assertTrue(back.contains("private-encrypted"));
            }
            case ANTHROPIC -> {
                assertTrue(paths.getFirst().endsWith("/messages"));
                assertEquals("max", request.at("/output_config/effort").asText());
                assertTrue(back.contains("private-signature"));
            }
            case GOOGLE -> {
                assertTrue(paths.getFirst().endsWith(":generateContent"));
                assertEquals("HIGH", request.at("/generationConfig/thinkingConfig/thinkingLevel").asText());
                assertTrue(back.contains("c2lnbmVk"));
                assertTrue(back.contains("\"functionResponse\"") && back.contains("\"id\":\"call_1\""));
            }
            case DEEPSEEK -> {
                assertEquals(3L, first.usage().cacheHitTokens());
                assertEquals("max", request.path("reasoning_effort").asText());
                assertEquals("private-thought", requests.get(1).at("/messages/1/reasoning_content").asText());
            }
            case ZAI, KIMI -> {
                assertEquals("enabled", request.at("/thinking/type").asText());
                assertEquals("private-thought", requests.get(1).at("/messages/1/reasoning_content").asText());
            }
            case QWEN -> assertTrue(request.has("enable_thinking") && !request.path("enable_thinking").asBoolean());
            case OPENROUTER -> {
                assertEquals("high", request.at("/reasoning/effort").asText());
                assertTrue(back.contains("private-router-block"));
            }
        }
    }

    @Test
    void newerDeepSeekAndKimiEffortsUseTheLiteralWireValue() {
        for (ProviderFamily family : List.of(ProviderFamily.DEEPSEEK, ProviderFamily.KIMI)) {
            var choice = new Choice(family.id(), family == ProviderFamily.KIMI ? "kimi-k3.1-pro" : "deepseek-flash", "low");
            var model = adapter(family, choice);
            responses.add(reply(family, false));
            model.call(List.of(AgentProtocol.Message.text("user", "ok")), List.of());
            var body = requests.getLast();
            assertEquals("low", body.path("reasoning_effort").asText());
            assertEquals(family == ProviderFamily.DEEPSEEK, body.has("thinking"));
        }
    }

    @Test
    void missingGeminiIdsRemainAbsentOnTheWire() {
        responses.add(reply(ProviderFamily.GOOGLE, true).replace("\"id\":\"call_1\",", ""));
        responses.add(reply(ProviderFamily.GOOGLE, false));
        var model = adapter(ProviderFamily.GOOGLE);
        var messages = new ArrayList<AgentProtocol.Message>();
        messages.add(AgentProtocol.Message.text("user", "read"));
        var first = model.call(messages, ConversationContext.TOOLS);
        var local = first.calls().getFirst().id();
        assertFalse(local.isBlank());
        messages.add(first.assistantMessage(first.text()));
        messages.add(new AgentProtocol.Message("tool", "{}", List.of(), local, "Read"));
        model.call(messages, ConversationContext.TOOLS);
        assertFalse(requests.getLast().toString().contains(local));
    }

    @Test
    void retryNeedsNewAdmissionAndErrorsDoNotEchoRawBody() {
        responses.add("{\"error\":{\"message\":\"private-error-body\"}}");
        status = 503;
        var adapter = adapter(ProviderFamily.ZAI);
        var granted = new AtomicInteger();
        var messages = List.of(AgentProtocol.Message.text("user", "read"));
        var failure = assertThrows(AgentException.class, () -> adapter.call(messages, ConversationContext.TOOLS,
                () -> granted.incrementAndGet() == 1));
        assertEquals("REQUEST_NOT_ADMITTED", failure.code());
        assertEquals(1, requests.size());
        status = 401;
        assertEquals("MODEL_AUTHORIZATION", assertThrows(AgentException.class,
                () -> adapter.call(messages, ConversationContext.TOOLS)).code());
        assertFalse(adapter.drainDiagnostics().toString().contains("private"));
        status = 402;
        assertEquals("MODEL_BILLING", assertThrows(AgentException.class,
                () -> adapter.call(messages, ConversationContext.TOOLS)).code());
        status = 200;
        responses.clear();
        responses.add(reply(ProviderFamily.ZAI, true).replace("\"finish_reason\":\"tool_calls\"", "\"finish_reason\":\"length\""));
        assertEquals(AgentModelFailure.Reason.RESPONSE_TRUNCATED,
                assertThrows(AgentModelFailure.class, () -> adapter.call(messages, ConversationContext.TOOLS)).reason());
    }
}
