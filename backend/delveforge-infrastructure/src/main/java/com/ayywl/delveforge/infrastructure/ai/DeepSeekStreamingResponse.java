package com.ayywl.delveforge.infrastructure.ai;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

/** DeepSeek SSE 只在完整 stop 与 DONE 后返回 content；思考增量仅计数，不保留或写入日志。 */
final class DeepSeekStreamingResponse {

    static String read(Reader input, ObjectMapper mapper, int maxEventBytes, Stats stats, LongSupplier elapsed)
            throws IOException {
        BufferedReader reader = new BufferedReader(input);
        StringBuilder event = new StringBuilder();
        StringBuilder content = new StringBuilder();
        int eventBytes = 0;
        boolean stopped = false;
        String line;
        while ((line = reader.readLine()) != null) {
            int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
            if (lineBytes > maxEventBytes) {
                throw new Rejected(Failure.EVENT_LIMIT);
            }
            if (!line.isEmpty()) {
                if (line.startsWith("data:")) {
                    String data = line.substring(5);
                    if (data.startsWith(" ")) {
                        data = data.substring(1);
                    }
                    eventBytes += data.getBytes(StandardCharsets.UTF_8).length + 1;
                    if (eventBytes > maxEventBytes) {
                        throw new Rejected(Failure.EVENT_LIMIT);
                    }
                    event.append(data).append('\n');
                }
                // SSE 注释及 event/id/retry 字段不是 completion 数据，不能进入模型正文。
                continue;
            }
            if (event.isEmpty()) {
                continue;
            }
            String data = event.toString().trim();
            event.setLength(0);
            eventBytes = 0;
            if (data.equals("[DONE]")) {
                if (!stopped || content.toString().isBlank()) {
                    throw new Rejected(Failure.INCOMPLETE);
                }
                return content.toString();
            }
            JsonNode root = parse(mapper, data);
            if (!root.path("object").asText().equals("chat.completion.chunk")
                    || !root.path("choices").isArray()) {
                throw new Rejected(Failure.INVALID);
            }
            stats.events++;
            usage(root, stats);
            JsonNode choices = root.path("choices");
            if (choices.isEmpty()) {
                continue;
            }
            if (choices.size() != 1 || !choices.get(0).path("index").isIntegralNumber()
                    || !choices.get(0).path("index").canConvertToInt()
                    || choices.get(0).path("index").asInt() != 0) {
                throw new Rejected(Failure.INVALID);
            }
            JsonNode choice = choices.get(0);
            JsonNode delta = choice.path("delta");
            if (!delta.isObject()
                    || (delta.hasNonNull("tool_calls") && (!delta.path("tool_calls").isArray()
                        || !delta.path("tool_calls").isEmpty()))
                    || (delta.hasNonNull("role") && (!delta.path("role").isTextual()
                        || !delta.path("role").asText().equals("assistant")))) {
                throw new Rejected(Failure.INVALID);
            }
            String text = optionalText(delta, "content");
            String reasoning = optionalText(delta, "reasoning_content");
            if (stopped && (!text.isEmpty() || !reasoning.isEmpty())) {
                throw new Rejected(Failure.INVALID);
            }
            if (!text.isEmpty()) {
                content.append(text);
                stats.contentBytes += text.getBytes(StandardCharsets.UTF_8).length;
                if (stats.firstContentMs < 0) {
                    stats.firstContentMs = elapsed.getAsLong();
                }
            }
            if (!reasoning.isEmpty()) {
                stats.reasoningBytes += reasoning.getBytes(StandardCharsets.UTF_8).length;
                if (stats.firstReasoningMs < 0) {
                    stats.firstReasoningMs = elapsed.getAsLong();
                }
            }
            if (choice.hasNonNull("finish_reason")) {
                if (stopped || !choice.path("finish_reason").isTextual()) {
                    throw new Rejected(Failure.INVALID);
                }
                if (!choice.path("finish_reason").asText().equals("stop")) {
                    throw new Rejected(Failure.INCOMPLETE);
                }
                stopped = true;
            }
        }
        // EOF、有效 JSON 片段或单独 stop 都不等于一次完整 completion。
        throw new Rejected(Failure.INCOMPLETE);
    }

    static JsonNode parse(ObjectMapper mapper, String data) {
        try (JsonParser parser = mapper.createParser(data)) {
            JsonNode root = mapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw new Rejected(Failure.INVALID);
            }
            return root;
        } catch (IOException failure) {
            throw new Rejected(Failure.INVALID, failure);
        }
    }

    static void usage(JsonNode root, Stats stats) {
        JsonNode usage = root.path("usage");
        long prompt = count(usage.path("prompt_tokens"));
        long completion = count(usage.path("completion_tokens"));
        long reasoning = count(usage.path("completion_tokens_details").path("reasoning_tokens"));
        if (prompt >= 0) {
            stats.promptTokens = prompt;
        }
        if (completion >= 0) {
            stats.completionTokens = completion;
        }
        if (reasoning >= 0) {
            stats.reasoningTokens = reasoning;
        }
    }

    private static long count(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0 ? value.asLong() : -1;
    }

    private static String optionalText(JsonNode delta, String field) {
        JsonNode value = delta.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        if (!value.isTextual()) {
            throw new Rejected(Failure.INVALID);
        }
        return value.asText();
    }

    enum Failure {
        INVALID("PROVIDER_RESPONSE_INVALID"),
        INCOMPLETE("PROVIDER_RESPONSE_INCOMPLETE"),
        BODY_LIMIT("PROVIDER_RESPONSE_LIMIT_EXCEEDED"),
        EVENT_LIMIT("PROVIDER_EVENT_LIMIT_EXCEEDED");

        private final String code;

        Failure(String code) {
            this.code = code;
        }
    }

    static final class Rejected extends AiGatewayException {
        private final Failure failure;

        Rejected(Failure failure) {
            this(failure, null);
        }

        Rejected(Failure failure, Throwable cause) {
            super("Provider response rejected", cause);
            this.failure = failure;
        }

        String code() {
            return failure.code;
        }

        String phase() {
            return failure == Failure.BODY_LIMIT ? "RESPONSE_BODY" : "RESPONSE_PARSE";
        }
    }

    static final class Stats {
        long events;
        long contentBytes;
        long reasoningBytes;
        long firstContentMs = -1;
        long firstReasoningMs = -1;
        long promptTokens = -1;
        long completionTokens = -1;
        long reasoningTokens = -1;
    }
}
