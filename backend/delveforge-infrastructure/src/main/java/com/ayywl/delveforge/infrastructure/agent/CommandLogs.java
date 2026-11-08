package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;

/** 有界、净化的 Run 日志；仅平台写入，项目容器从不挂载此目录。 */
final class CommandLogs {
    static final int STREAM_BYTES = 262144;
    static final int RUN_BYTES = 16777216;
    private static final ObjectMapper JSON = new ObjectMapper();

    private CommandLogs() {
    }

    static String readable(String text, RepositorySecretPolicy secrets) {
        String plain = text.replaceAll("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)", "")
                .replaceAll("\u001B\\[[0-?]*[ -/]*[@-~]", "")
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "")
                .replace("\r\n", "\n").replace('\r', '\n');
        return secrets.sanitizeOutput(plain);
    }

    static String preview(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        int half = Math.max(0, (limit - 20) / 2);
        return text.substring(0, half) + "\n[… read_log …]\n" + text.substring(text.length() - half);
    }

    static boolean save(Path run, ObjectNode receipt) {
        Path logs = run.resolve("command-logs");
        try {
            if (Files.isSymbolicLink(logs)) {
                throw new AgentException("RESOURCE_OWNERSHIP", "Unsafe log storage");
            }
            Files.createDirectories(logs);
            Path file = logs.resolve(receipt.path("invocationId").asText() + ".json");
            if (Files.isSymbolicLink(file)) {
                throw new AgentException("RESOURCE_OWNERSHIP", "Unsafe log storage");
            }
            long total = 0;
            try (var files = Files.list(logs)) {
                for (Path item : files.toList()) {
                    if (!item.equals(file)) {
                        total += Files.size(item);
                    }
                }
            }
            byte[] bytes = receipt.toString().getBytes(StandardCharsets.UTF_8);
            if (total + bytes.length > RUN_BYTES) {
                return false;
            }
            Files.write(file, bytes);
            return true;
        } catch (IOException failure) {
            throw new AgentException("COMMAND_LOG_STORAGE", "Cannot retain sanitized command diagnostics", failure);
        }
    }

    static String read(Path run, String invocation, String stream, int offset, int limit) {
        if (invocation == null || !invocation.matches("[0-9a-f]{8}-[0-9a-f-]{27}")
                || !Set.of("stdout", "stderr", "command").contains(stream) || offset < 0 || limit < 1 || limit > 8000) {
            throw new AgentException("INVALID_ARGUMENTS", "Invalid log identity or range");
        }
        Path logs = run.resolve("command-logs");
        Path file = logs.resolve(invocation + ".json");
        try {
            if (Files.isSymbolicLink(logs) || Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > RUN_BYTES) {
                throw new AgentException("INVALID_ARGUMENTS", "Retained log is unavailable");
            }
            JsonNode receipt = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String value = receipt.path(stream).asText();
            int start = Math.min(offset, value.length());
            int end = Math.min(start + limit, value.length());
            ObjectNode result = JSON.createObjectNode().put("invocationId", invocation).put("stream", stream)
                    .put("offset", start).put("nextOffset", end).put("totalChars", value.length())
                    .put("hasMore", end < value.length()).put("logDiscarded", receipt.path("logDiscarded").asBoolean())
                    .put("content", value.substring(start, end));
            return JSON.createObjectNode().put("ok", true).set("result", result).toString();
        } catch (IOException failure) {
            throw new AgentException("COMMAND_LOG_STORAGE", "Cannot read retained command log", failure);
        }
    }
}
