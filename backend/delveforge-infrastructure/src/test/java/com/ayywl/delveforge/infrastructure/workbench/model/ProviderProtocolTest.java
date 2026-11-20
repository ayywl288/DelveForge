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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.messages.part.UnknownPart;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    @ParameterizedTest
    @EnumSource(ProviderFamily.class)
    void connectionTestUsesAuthenticatedReadOnlyRequest(ProviderFamily family) {
        String path = switch (family) {
            case GOOGLE -> "/v1beta/models";
            case ANTHROPIC -> "/v1/models";
            case DEEPSEEK -> "/user/balance";
            case KIMI -> "/users/me/balance";
            case OPENROUTER -> "/key";
            default -> "/models";
        };
        responses.add(switch (family) {
            case GOOGLE -> "{\"models\":[]}";
            case KIMI, OPENROUTER -> "{\"data\":{}}";
            case DEEPSEEK -> "{\"is_available\":true}";
            default -> "{\"data\":[]}";
        });
        var result = ProviderConnection.test(ModelTransport.client(Duration.ofSeconds(3)), family,
                "http://127.0.0.1:" + server.getAddress().getPort(), "unit-test-key");
        assertTrue(result.latencyMs() >= 0);
        assertEquals(List.of(path), paths);
        assertEquals(List.of(switch (family) {
            case GOOGLE -> "null/null/unit-test-key";
            case ANTHROPIC -> "null/unit-test-key/null";
            default -> "Bearer unit-test-key/null/null";
        }), auth);
    }

    @ParameterizedTest
    @CsvSource({"401,invalid,认证", "403,forbidden,权限", "429,rate_limit,限流",
            "429,insufficient_quota,额度", "402,billing,余额", "503,unavailable,503"})
    void connectionFailuresRemainDistinct(int httpStatus, String code, String expected) {
        status = httpStatus;
        responses.add("{\"error\":{\"code\":\"" + code + "\"}}");
        var failure = assertThrows(WorkbenchRejection.class, () -> ProviderConnection.test(
                ModelTransport.client(Duration.ofSeconds(3)), ProviderFamily.OPENAI,
                "http://127.0.0.1:" + server.getAddress().getPort(), "unit-test-key"));
        assertTrue(failure.getMessage().contains(expected));
    }

    @BeforeEach
    void listen() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            requests.add(request);
            paths.add(exchange.getRequestURI().getPath());
            auth.add(String.join("/", List.of("Authorization", "x-api-key", "x-goog-api-key").stream()
                    .map(h -> String.valueOf(exchange.getRequestHeaders().getFirst(h))).toList()));
            String body = responses.get(Math.min(requests.size() - 1, responses.size() - 1));
            if (body.contains("RECEIPT_FROM_REQUEST")) {
                JsonNode messages = request.has("input") ? request.path("input") : request.path("messages");
                JsonNode result = messages.get(messages.size() - 1);
                String output = result.path(request.has("input") ? "output" : "content").asText();
                body = body.replace("RECEIPT_FROM_REQUEST", JSON.readTree(output).path("receipt").asText());
            }
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
                adapter(family), new WorkbenchTokenEstimate(family)));
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
                    frozen.choice()), new WorkbenchTokenEstimate(ProviderFamily.named(frozen.choice().provider())));
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
    void oneMissedInstructionDoesNotDisableTheChoiceAndASecondProbeCanVerifyIt(@TempDir Path root) {
        var selected = choice(ProviderFamily.QWEN);
        var db = new SQLiteDataSource();
        db.setUrl("jdbc:sqlite:" + root.resolve("control.db"));
        Flyway.configure().dataSource(db).locations("classpath:db/workbench").load().migrate();
        var models = spy(new SpringWorkbenchModels(db, JSON, root, selected, new AgentContextBudget(32000, 2048, .9),
                Duration.ofSeconds(5), 500000, 500000, name -> "test-account"));
        doReturn(new WorkbenchModels.Binding(adapter(ProviderFamily.QWEN), new WorkbenchTokenEstimate(ProviderFamily.QWEN)))
                .when(models).bind(any());
        responses.add(reply(ProviderFamily.QWEN, false));
        assertThrows(WorkbenchRejection.class, () -> models.validate(selected));
        assertEquals(selected, models.freeze(selected).choice());
        assertEquals("on", models.freeze(new Choice("qwen", selected.model(), "on")).choice().effort());
        String ping = reply(ProviderFamily.QWEN, true).replace("Read", "Ping")
                .replace("{\\\"path\\\":\\\"README.md\\\"}", "{}");
        responses.add(ping);
        responses.add(reply(ProviderFamily.QWEN, false).replace("\"OK\"", "\"RECEIPT_FROM_REQUEST\""));
        models.validate(selected);
        assertEquals(selected, models.freeze(selected).choice());
        responses.add(reply(ProviderFamily.QWEN, false));
        assertThrows(WorkbenchRejection.class, () -> models.validate(selected));
        assertEquals(1, new JdbcTemplate(db).queryForObject(
                "SELECT compatible FROM model_validation", Integer.class));
        responses.add(ping);
        responses.add(reply(ProviderFamily.QWEN, false));
        assertThrows(WorkbenchRejection.class, () -> models.validate(selected));
        assertEquals(selected, models.freeze(selected).choice());
        responses.add(ping.replace("\"id\":\"call_1\"", "\"id\":\"\""));
        assertThrows(WorkbenchRejection.class, () -> models.validate(selected));
        assertEquals(selected, models.freeze(selected).choice());
    }

    @ParameterizedTest
    @CsvSource({
            "400,unsupported_parameter,tools,true",
            "422,unsupported_value,reasoning.effort,true",
            "400,unsupported_parameter,tools[0].function.parameters,false",
            "400,invalid_request_error,reasoning.effort,false",
            "401,invalid_api_key,tools,false", "403,permission_denied,tools,false",
            "404,model_not_found,model,false", "402,billing,model,false",
            "429,insufficient_quota,tools,false", "429,rate_limit,tools,false", "503,unavailable,tools,false"
    })
    void onlyExplicitCapabilityEvidenceBlocksTheExactCombination(int httpStatus, String code, String parameter,
            boolean rejected, @TempDir Path root) {
        var selected = choice(ProviderFamily.OPENAI);
        var db = new SQLiteDataSource();
        db.setUrl("jdbc:sqlite:" + root.resolve("control.db"));
        Flyway.configure().dataSource(db).locations("classpath:db/workbench").load().migrate();
        var models = spy(new SpringWorkbenchModels(db, JSON, root, selected, new AgentContextBudget(32000, 2048, .9),
                Duration.ofSeconds(5), 500000, 500000, name -> "fixture-key"));
        doReturn(new WorkbenchModels.Binding(adapter(ProviderFamily.OPENAI), new WorkbenchTokenEstimate(ProviderFamily.OPENAI)))
                .when(models).bind(any());
        status = httpStatus;
        responses.add("{\"error\":{\"code\":\"" + code + "\",\"param\":\"" + parameter + "\",\"message\":\"private\"}}");
        var failure = assertThrows(WorkbenchRejection.class, () -> models.validate(selected));
        assertFalse(failure.getMessage().contains("private"));
        if (rejected) {
            assertThrows(WorkbenchRejection.class, () -> models.freeze(selected));
        } else {
            assertEquals(selected, models.freeze(selected).choice());
        }
        assertEquals("low", models.freeze(new Choice("openai", selected.model(), "low")).choice().effort());
        assertEquals(rejected ? 1 : 0, new JdbcTemplate(db).queryForObject(
                "SELECT count(*) FROM model_validation", Integer.class));
        if (rejected) {
            status = 200;
            responses.add(reply(ProviderFamily.OPENAI, true).replace("Read", "Ping")
                    .replace("{\\\"path\\\":\\\"README.md\\\"}", "{}"));
            responses.add(reply(ProviderFamily.OPENAI, false).replace("\"OK\"", "\"RECEIPT_FROM_REQUEST\""));
            models.validate(selected);
            assertEquals(selected, models.freeze(selected).choice());
        }
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

    @ParameterizedTest
    @EnumSource(value = ProviderFamily.class, names = {"GOOGLE", "OPENROUTER"})
    void textualBatchHasNoTransportIdsOrPrivateSlotsBeforeANativeContinuation(ProviderFamily family) {
        var target = adapter(family);
        var prefix = List.of(AgentProtocol.Message.text("user", "核对两个回执"));
        var legacy = new AgentProtocol.Message("assistant", "旧调用", List.of(
                new AgentProtocol.ToolCall("old", "Bash", "invalid")), null, null, "private-legacy");
        var records = new ArrayList<>(List.of(new Workbench.Entry(1, legacy),
                new Workbench.Entry(2, new AgentProtocol.Message("tool", "exit 17", List.of(), "old", "Bash"))));
        var first = target.projectHistory(prefix, records);
        responses.add(reply(family, true));
        responses.add(reply(family, false));
        var next = target.call(first, ConversationContext.TOOLS);
        records.add(new Workbench.Entry(3, next.assistantMessage(next.text()), true, null));
        records.add(new Workbench.Entry(4,
                new AgentProtocol.Message("tool", "{\"value\":\"current receipt\"}", List.of(), "call_1", "Read")));
        target.call(target.projectHistory(prefix, records), ConversationContext.TOOLS);
        String wire = requests.getLast().toString();
        assertTrue(wire.contains("exit 17") && wire.contains("current receipt"));
        assertFalse(wire.contains("private-legacy"));
        if (family == ProviderFamily.OPENROUTER) {
            assertTrue(requests.getLast().at("/messages/2/reasoning_details/0/data").asText().contains("private-router"));
        } else {
            assertEquals("call_1", requests.getLast().findValue("functionResponse").path("id").asText());
            assertTrue(wire.contains("c2lnbmVk"));
        }
    }

    @Test
    void orderedMultiCallPartsAndReversedReceiptsSurviveAnAnthropicHandoff() {
        responses.add(reply(ProviderFamily.ANTHROPIC, false));
        var source = choice(ProviderFamily.OPENAI);
        var first = new AssistantMessage.ToolCall("first", "function", "Read", "{\"path\":\"one.md\"}");
        var second = new AssistantMessage.ToolCall("second", "function", "Read", "{\"path\":\"two.md\"}");
        var stored = SpringMessages.store(source, List.of(TextPart.of("before-call"), ToolCallPart.of(first),
                new UnknownPart("openai", "future", "{\"private-unknown\":1}", null, Map.of()),
                TextPart.of("between-calls"), ToolCallPart.of(second)));
        var calls = List.of(new AgentProtocol.ToolCall("first", "Read", first.arguments()),
                new AgentProtocol.ToolCall("second", "Read", second.arguments()));
        var assistant = new AgentProtocol.Message("assistant", "before-call\nbetween-calls", calls, null, null, null, stored);
        var rows = List.of(new Workbench.Entry(3, assistant), new Workbench.Entry(4,
                        new AgentProtocol.Message("tool", "failed with exit 17", List.of(), "second", "Read")),
                new Workbench.Entry(5, new AgentProtocol.Message("tool", "first result", List.of(), "first", "Read")));
        var model = adapter(ProviderFamily.ANTHROPIC);
        var projected = model.projectHistory(List.of(AgentProtocol.Message.text("user", "inspect")), rows);
        var parts = SpringMessages.parts(projected.get(1).stored());
        assertEquals(List.of(TextPart.of("before-call"), ToolCallPart.of(first),
                TextPart.of("between-calls"), ToolCallPart.of(second)), parts);
        model.call(projected, ConversationContext.TOOLS);
        var sent = requests.getLast().path("messages");
        assertFalse(sent.toString().contains("private-unknown"));
        var blocks = sent.get(1).path("content");
        assertEquals("before-call", blocks.get(0).path("text").asText());
        assertEquals("first", blocks.get(1).path("id").asText());
        assertEquals("between-calls", blocks.get(2).path("text").asText());
        assertEquals("second", blocks.get(3).path("id").asText());
        var results = sent.get(2).path("content");
        assertEquals("second", results.get(0).path("tool_use_id").asText());
        assertTrue(results.get(0).toString().contains("failed with exit 17"));
        assertEquals("first", results.get(1).path("tool_use_id").asText());
    }

    @ParameterizedTest
    @CsvSource({"OPENAI,ANTHROPIC", "ANTHROPIC,OPENAI", "OPENAI,GOOGLE", "ANTHROPIC,OPENROUTER"})
    void handoffSendsOnlyPairedPublicPartsOnTheTargetWire(ProviderFamily source, ProviderFamily target) {
        responses.add(reply(source, true).replace("call_1", "foreign:1"));
        responses.add(reply(target, false));
        var start = List.of(AgentProtocol.Message.text("user", "inspect README"));
        var turn = adapter(source).call(start, ConversationContext.TOOLS);
        var receipt = new AgentProtocol.Message("tool", "{\"result\":\"receipt-evidence\"}", List.of(), "foreign:1", "Read");
        var rows = List.of(new Workbench.Entry(21, turn.assistantMessage(turn.text())),
                new Workbench.Entry(22, receipt), new Workbench.Entry(23, AgentProtocol.Message.text("user", "continue")));
        var destination = adapter(target);
        var projected = destination.projectHistory(start, rows);
        assertEquals("df_hist_21_0", projected.get(1).calls().getFirst().id());
        assertEquals(projected.get(1).calls().getFirst().id(), projected.get(2).callId());
        assertEquals("foreign:1", receipt.callId());
        assertEquals(projected, destination.projectHistory(start, rows));
        destination.call(projected, ConversationContext.TOOLS);
        var sent = requests.getLast();
        String wire = sent.toString();
        assertFalse(wire.contains("private-"));
        assertFalse(wire.contains("foreign:1"));
        assertTrue(wire.contains("README.md") && wire.contains("receipt-evidence"));
        assertTrue(wire.indexOf("README.md") < wire.indexOf("receipt-evidence"));
        assertTrue(wire.indexOf("receipt-evidence") < wire.indexOf("continue"));
        assertEquals(2, wire.split("df_hist_21_0", -1).length - 1);
        switch (target) {
            case OPENAI -> {
                assertTrue(wire.contains("\"function_call\""));
                assertTrue(wire.contains("\"function_call_output\""));
            }
            case ANTHROPIC -> {
                assertTrue(wire.contains("\"tool_use\""));
                assertTrue(wire.contains("\"tool_result\""));
            }
            case GOOGLE -> assertTrue(wire.contains("\"functionCall\"") && wire.contains("\"functionResponse\""));
            case OPENROUTER -> assertEquals("tool", sent.at("/messages/2/role").asText());
            default -> throw new AssertionError(target);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"off", "default", "high"})
    void foreignDeepSeekHistoryPreservesExecutionFactsWithoutInjectingToolMessages(String effort) {
        var source = ProviderFamily.ANTHROPIC;
        responses.add(reply(source, true));
        responses.add(reply(ProviderFamily.DEEPSEEK, false));
        var prefix = List.of(AgentProtocol.Message.text("user", "inspect README"));
        var turn = adapter(source).call(prefix, ConversationContext.TOOLS);
        var receipt = new AgentProtocol.Message("tool",
                "{\"timedOut\":true,\"displayTruncated\":true,\"stderr\":\"last observed chunk\"}",
                List.of(), "call_1", "Read");
        var rows = List.of(new Workbench.Entry(21, turn.assistantMessage(turn.text())),
                new Workbench.Entry(22, receipt, false, new Workbench.Receipt("inv-1", "FINISHED", null, true)),
                new Workbench.Entry(23, AgentProtocol.Message.text("user", "continue")));
        var target = adapter(ProviderFamily.DEEPSEEK, new Choice("deepseek", "deepseek-flash", effort));
        target.call(target.projectHistory(prefix, rows), ConversationContext.TOOLS);
        var sent = requests.getLast();
        var messages = sent.path("messages");
        assertEquals(3, messages.size());
        assertEquals("continue", messages.get(2).path("content").asText());
        assertEquals("user", messages.get(1).path("role").asText());
        String evidence = messages.get(1).path("content").asText();
        for (String fact : List.of("[#21]", "[#22]", "README.md", "call_1 Read", "inv-1", "退出码 未知",
                "输出缺口 true", "timedOut", "displayTruncated", "last observed chunk", "df-workbench output inv-1")) {
            assertTrue(evidence.contains(fact), fact);
        }
        assertFalse(messages.toString().contains("private-") || messages.toString().contains("reasoning_content")
                || messages.toString().contains("tool_calls"));
        if ("default".equals(effort)) {
            assertFalse(sent.has("thinking"));
        } else {
            assertEquals("off".equals(effort) ? "disabled" : "enabled", sent.at("/thinking/type").asText());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProviderFamily.class, names = {"ANTHROPIC", "DEEPSEEK", "OPENAI", "OPENROUTER", "GOOGLE"})
    void nativeProjectionRequiresTheOriginalPrefixAndFrozenConfig(ProviderFamily family) {
        responses.add(reply(family, true));
        responses.add(reply(family, false));
        var target = adapter(family);
        var prefix = List.of(AgentProtocol.Message.text("user", "read"));
        var turn = target.call(prefix, ConversationContext.TOOLS);
        var assistant = turn.assistantMessage(turn.text());
        var result = new Workbench.Entry(9, new AgentProtocol.Message("tool", "{}", List.of(), "call_1", "Read"));
        var batch = List.of(new Workbench.Entry(8, assistant, true, null), result);
        var nativeView = target.projectHistory(prefix, batch);
        assertEquals(assistant, nativeView.get(1));
        target.call(nativeView, ConversationContext.TOOLS);
        String marker = family == ProviderFamily.GOOGLE ? "c2lnbmVk" : "private-";
        assertTrue(requests.getLast().toString().contains(marker));
        var changedPrefix = target.projectHistory(List.of(AgentProtocol.Message.text("system", "checkpoint")), batch);
        var changedConfig = target.projectHistory(prefix, List.of(new Workbench.Entry(8, assistant, false, null), result));
        for (var view : List.of(changedPrefix, changedConfig)) {
            assertFalse(view.stream().anyMatch(m -> m.stored() != null && m.stored().partsJson().contains(marker)));
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
    void finalDeepSeekTextWithoutNewReasoningCompletesButIsNotReplayedAsToolHistory() {
        var model = adapter(ProviderFamily.DEEPSEEK);
        String privateField = ",\"reasoning_content\":\"private-thought\"";
        responses.add(reply(ProviderFamily.DEEPSEEK, false).replace(privateField, ""));
        var messages = List.of(AgentProtocol.Message.text("user", "ok"));
        var last = model.call(messages, ConversationContext.TOOLS);
        assertEquals("OK", last.text());
        var view = model.projectHistory(messages,
                List.of(new Workbench.Entry(3, last.assistantMessage(last.text()), true, null)));
        assertEquals("user", view.getLast().role());
        assertTrue(view.getLast().text().contains("OK"));
        responses.add(reply(ProviderFamily.DEEPSEEK, true).replace(privateField, ""));
        assertEquals(AgentModelFailure.Reason.REASONING_MISSING,
                assertThrows(AgentModelFailure.class, () -> model.call(messages, ConversationContext.TOOLS)).reason());
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
        responses.clear();
        responses.add("{\"error\":{\"code\":\"1113\"}}");
        status = 429;
        assertEquals("MODEL_BILLING", assertThrows(AgentException.class,
                () -> adapter.call(messages, ConversationContext.TOOLS)).code());
        responses.clear();
        responses.add("{\"error\":{\"code\":\"1211\"}}");
        status = 400;
        assertEquals("MODEL_NOT_AVAILABLE", assertThrows(AgentException.class,
                () -> adapter.call(messages, ConversationContext.TOOLS)).code());
        status = 200;
        responses.clear();
        responses.add(reply(ProviderFamily.ZAI, true).replace("\"finish_reason\":\"tool_calls\"", "\"finish_reason\":\"length\""));
        assertEquals(AgentModelFailure.Reason.RESPONSE_TRUNCATED,
                assertThrows(AgentModelFailure.class, () -> adapter.call(messages, ConversationContext.TOOLS)).reason());
    }
}
