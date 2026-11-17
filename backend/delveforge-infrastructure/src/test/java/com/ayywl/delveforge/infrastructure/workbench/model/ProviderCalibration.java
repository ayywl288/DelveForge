package com.ayywl.delveforge.infrastructure.workbench.model;

import com.ayywl.delveforge.application.agent.AgentContextBudget;
import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentModelFailure;
import com.ayywl.delveforge.application.agent.AgentProtocol.Message;
import com.ayywl.delveforge.application.agent.AgentProtocol.Reply;
import com.ayywl.delveforge.application.agent.AgentProtocol.ToolCall;
import com.ayywl.delveforge.application.agent.AgentProtocol.ToolSpec;
import com.ayywl.delveforge.application.workbench.ConversationContext;
import com.ayywl.delveforge.application.workbench.WorkbenchModels.Choice;
import com.ayywl.delveforge.application.workbench.WorkbenchModels.Frozen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;

import java.io.Console;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** 手动运行的真实 API 夹具。只输出计数与验证事实，临时服务仅在回环接收带随机票据的本进程请求。 */
public final class ProviderCalibration implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final List<ToolSpec> PING = List.of(new ToolSpec("Ping", "返回测试回执",
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"));
    private static final String CODE = "验证输入长度并保留原始记录。\nint limit = Math.min(input.size(), 3072);\n";
    private final WorkbenchTokenEstimate counter;
    private final ProviderFamily family;
    private final Choice choice;
    private final String upstream;
    private final String ticket = UUID.randomUUID().toString();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final HttpServer server;
    private final WorkbenchChatModel model;
    private final String key;
    private volatile JsonNode sent;
    private volatile JsonNode received;
    private volatile boolean countOnly;
    private volatile int status;
    private final ObjectNode report = JSON.createObjectNode();

    private ProviderCalibration(ProviderFamily family, String key) throws IOException {
        this.family = family;
        counter = new WorkbenchTokenEstimate(family);
        this.key = key;
        choice = switch (family) {
            case DEEPSEEK -> new Choice("deepseek", "deepseek-flash", "high");
            case OPENAI -> new Choice("openai", "gpt-5.4-nano", "high");
            case ZAI -> new Choice("zai", "glm-4.7-flash", "on");
            default -> throw new IllegalArgumentException("选择 deepseek、openai 或 zai");
        };
        String preset = family == ProviderFamily.ZAI ? "china" : family.defaultPreset();
        upstream = family.endpoint(preset, "");
        var frozen = new Frozen(choice, family.protocol, preset, "", "fixture",
                new AgentContextBudget(100000, 1024, .9), "probe");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::exchange);
        server.start();
        model = new WorkbenchChatModel(frozen, "http://127.0.0.1:" + server.getAddress().getPort(), () -> ticket,
                new ProviderChatModels(TIMEOUT), ModelTransport.client(TIMEOUT), 2000000, 1000000);
        report.put("provider", family.id()).put("model", choice.model()).put("effort", choice.effort())
                .put("preset", preset).put("tool", false).put("reasoningReplay", false).put("receipt", false);
        report.putArray("samples");
    }

    private void exchange(HttpExchange exchange) throws IOException {
        try (exchange) {
            String route = family == ProviderFamily.OPENAI ? "/responses" : "/chat/completions";
            if (!exchange.getRequestURI().getPath().equals(route) || !"POST".equals(exchange.getRequestMethod())
                    || !("Bearer " + ticket).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            sent = JSON.readTree(body);
            if (countOnly) {
                // 仅捕获 SDK 序列化的输入；记录后由计数入口单独发送，绝不生成回答。
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            var request = HttpRequest.newBuilder(URI.create(upstream + route)).timeout(TIMEOUT)
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            status = response.statusCode();
            received = JSON.readTree(response.body());
            byte[] result = response.body();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, result.length);
            exchange.getResponseBody().write(result);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("计数请求中断");
        }
    }

    private ObjectNode measure(String name, List<Message> messages, List<ToolSpec> tools, long input, String source) {
        require(input > 0, "INPUT_USAGE_MISSING");
        long estimate = counter.countPromptTokens(messages, tools);
        return report.withArray("samples").addObject().put("case", name).put("source", source).put("tools", tools.size())
                .put("estimate", estimate).put("input", input).put("errorTokens", estimate - input);
    }

    private Reply generate(String name, List<Message> history, List<ToolSpec> tools) {
        Reply reply = callOnce(history, tools);
        require(reply.usage() != null && reply.usage().promptTokens() != null, "USAGE_MISSING");
        ObjectNode sample = measure(name, history, tools, reply.usage().promptTokens(), "usage");
        sample.put("output", reply.usage().completionTokens()).put("total", reply.usage().totalTokens());
        sample.put("cachedInput", reply.usage().cacheHitTokens());
        report.put("responseModel", received.path("model").asText());
        return reply;
    }

    private Reply callOnce(List<Message> history, List<ToolSpec> tools) {
        boolean[] sentOnce = { false };
        status = 0;
        received = null;
        return model.call(history, tools, () -> {
            if (sentOnce[0]) {
                return false;
            }
            sentOnce[0] = true;
            return true;
        });
    }

    private void count(String name, List<Message> history, List<ToolSpec> tools) {
        countOnly = true;
        sent = null;
        try {
            callOnce(history, tools);
        } catch (AgentException end) {
            if (sent == null || !"MODEL_OPTIONS_REJECTED".equals(end.code())) {
                throw end;
            }
        } finally {
            countOnly = false;
        }
        ObjectNode input = sent.deepCopy();
        input.retain("model", "input", "tools", "instructions", "reasoning", "tool_choice", "parallel_tool_calls");
        try {
            var request = HttpRequest.newBuilder(URI.create(upstream + "/responses/input_tokens")).timeout(TIMEOUT)
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(input))).build();
            var count = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            status = count.statusCode();
            received = JSON.readTree(count.body());
            require(status == 200, "COUNT_HTTP_" + status);
            measure(name, history, tools, received.path("input_tokens").asLong(), "responses/input_tokens");
        } catch (IOException e) {
            throw new AgentException("COUNT_TRANSPORT", "计数请求未完成");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException("CANCELLED", "计数请求中断");
        }
    }

    private void run() {
        var history = new ArrayList<Message>();
        history.add(Message.text("user", "请判断 137×23 与 17×19 的差是否为 7 的倍数。如果是，请调用一次无参数 Ping。"
                + "如果不是，请答 NO。收到工具结果后，仅原样回复 receipt 字段的值。"));
        var first = generate("ping", history, PING);
        require(first.calls().size() == 1, "MISSED_TOOL");
        var call = first.calls().getFirst();
        require("Ping".equals(call.name()) && call.arguments().matches("\\s*\\{\\s*}\\s*"), "INVALID_PING");
        report.put("tool", true);
        boolean responses = family == ProviderFamily.OPENAI;
        String material = responses ? encrypted(received.path("output"))
                : received.at("/choices/0/message/reasoning_content").asText("");
        require(!material.isBlank(), "REASONING_ABSENT");
        history.add(first.assistantMessage(first.text()));
        String receipt = "DF-" + UUID.randomUUID().toString().substring(0, 8);
        history.add(new Message("tool", "{\"receipt\":\"" + receipt + "\"}", List.of(), call.id(), "Ping"));
        var last = receipt(history);
        String replay = responses ? encrypted(sent.path("input")) : sent.at("/messages/1/reasoning_content").asText("");
        require(material.equals(replay), "REASONING_REPLAY_MISMATCH");
        report.put("reasoningReplay", true);
        require(last.calls().isEmpty() && receipt.equals(last.text().strip()), "RECEIPT_MISMATCH");
        report.put("receipt", true);

        if (family != ProviderFamily.OPENAI) {
            usageSamples();
            return;
        }
        count("short-english", List.of(Message.text("user", "Return the build status.")), List.of());
        count("chinese-code", List.of(Message.text("user", CODE.repeat(100))), List.of());
        count("tool-schemas", List.of(Message.text("user", "Inspect the workcopy.")), ConversationContext.TOOLS);
        var batch = new ArrayList<>(history);
        batch.set(2, new Message("tool", "line: build completed\n".repeat(600), List.of(), call.id(), "Ping"));
        count("large-tool-result", batch, PING);
        countHistories();
    }

    private void countHistories() {
        // 多批次使用独立的合成调用 ID，不复制厂商推理项的身份。
        var rounds = new ArrayList<Message>();
        rounds.add(Message.text("user", "Inspect two files."));
        for (int i = 0; i < 2; i++) {
            var invocation = new AssistantMessage.ToolCall("call_probe_" + i,
                    "function", "Read", "{\"path\":\"example" + i + ".txt\"}");
            var stored = SpringMessages.store(choice, List.of(ToolCallPart.of(invocation)));
            var tool = new ToolCall(invocation.id(), "Read", invocation.arguments());
            rounds.add(new Message("assistant", "", List.of(tool), null, null, null, stored));
            rounds.add(new Message("tool", "sample " + i, List.of(), tool.id(), tool.name()));
        }
        count("two-tool-rounds", rounds, ConversationContext.TOOLS);
        var foreign = SpringMessages.store(new Choice("zai", "glm-4.7-flash", "on"), List.of(
                ReasoningPart.of("synthetic prior reasoning"), TextPart.of("README was read.")));
        count("cross-provider-visible-continuation", List.of(Message.text("user", "Read the project notes."),
                new Message("assistant", "README was read.", List.of(), null, null, null, foreign),
                Message.text("user", "Continue.")), ConversationContext.TOOLS);
        count("large-chinese-code", List.of(Message.text("user", CODE.repeat(2200))), List.of());
    }

    private void usageSamples() {
        var brief = List.of(Message.text("user", "Reply OK. No tool is needed."));
        generate("short-english", brief, List.of());
        generate("tool-schemas", brief, ConversationContext.TOOLS);
        generate("chinese-code", List.of(Message.text("user", CODE.repeat(100) + "只回复 OK。")), List.of());
    }

    private Reply receipt(List<Message> history) {
        try {
            return generate("reasoning-receipt", history, PING);
        } catch (AgentException limited) {
            if (family != ProviderFamily.ZAI || status != 429) {
                throw limited;
            }
            report.put("receiptRetriedAfter429", true);
            try {
                Thread.sleep(Duration.ofSeconds(8));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw limited;
            }
            return generate("reasoning-receipt", history, PING);
        }
    }

    private static String encrypted(JsonNode items) {
        for (JsonNode item : items) {
            if ("reasoning".equals(item.path("type").asText())) {
                return item.path("encrypted_content").asText("");
            }
        }
        return "";
    }

    private static void require(boolean passed, String reason) {
        if (!passed) {
            throw new AgentException(reason, reason);
        }
    }

    public static void main(String[] args) throws Exception {
        var family = ProviderFamily.named(args[0]);
        String key = System.getenv(family.environment);
        if (key == null) {
            Console console = System.console();
            if (console == null) {
                throw new IllegalStateException("请在终端内输入凭据或设置提供方环境变量");
            }
            char[] secret = console.readPassword("%s API Key: ", family.label);
            key = new String(secret);
            Arrays.fill(secret, '\0');
        }
        try (var probe = new ProviderCalibration(family, key)) {
            probe.report.put("mode", args.length == 2 ? args[1] : "round-trip-and-calibration");
            boolean passed = false;
            try {
                if (args.length == 2 && "counts".equals(args[1]) && family == ProviderFamily.OPENAI) {
                    probe.countHistories();
                } else if (args.length == 2 && "usage".equals(args[1]) && family != ProviderFamily.OPENAI) {
                    probe.usageSamples();
                } else {
                    probe.run();
                }
                passed = true;
            } catch (AgentException failure) {
                probe.report.put("failure", failure.code()).put("httpStatus", probe.status);
                if (failure instanceof AgentModelFailure protocol) {
                    probe.report.put("protocolReason", protocol.reason().name());
                }
                if (probe.received != null) {
                    if (probe.status == 200) {
                        JsonNode message = probe.received.at("/choices/0/message");
                        probe.report.put("responseModel", probe.received.path("model").asText());
                        probe.report.put("finish", probe.received.at("/choices/0/finish_reason").asText());
                        probe.report.put("wireToolCalls", message.path("tool_calls").size());
                        probe.report.put("wireReasoning", !message.path("reasoning_content").asText("").isBlank());
                        probe.report.put("wireArgumentsEmpty", message.at("/tool_calls/0/function/arguments").asText("").isBlank());
                    }
                    String code = probe.received.at("/error/code").asText("");
                    if (code.matches("[a-zA-Z0-9_]{1,32}")) {
                        probe.report.put("providerCode", code);
                    }
                }
            }
            System.out.println(JSON.writeValueAsString(probe.report));
            if (!passed) {
                throw new IllegalStateException("探针未完成，见上方脱敏结果");
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        http.close();
    }
}
