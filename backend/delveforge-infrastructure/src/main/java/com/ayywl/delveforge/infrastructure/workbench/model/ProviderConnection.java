package com.ayywl.delveforge.infrastructure.workbench.model;

import com.ayywl.delveforge.application.workbench.WorkbenchModels.Connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 普通连接测试只读账户或模型目录。它不写工具兼容性记录。 */
final class ProviderConnection {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ProviderConnection() {}

    static Connection test(OkHttpClient client, ProviderFamily family, String endpoint, String key) {
        String path = switch (family) {
            case ANTHROPIC -> "/v1/models?limit=1";
            case GOOGLE -> "/v1beta/models?pageSize=1";
            case OPENROUTER -> "/key";
            case DEEPSEEK -> "/user/balance";
            case KIMI -> "/users/me/balance";
            case QWEN -> endpoint.contains(".maas.aliyuncs.com/")
                    ? "/api/v1/models?page_size=1" : "/models";
            default -> "/models";
        };
        if (family == ProviderFamily.QWEN && path.startsWith("/api/")) {
            endpoint = endpoint.substring(0, endpoint.indexOf("/compatible-mode"));
        }
        var builder = new Request.Builder().url(endpoint + path);
        switch (family) {
            case ANTHROPIC -> builder.header("x-api-key", key).header("anthropic-version", "2023-06-01");
            case GOOGLE -> builder.header("x-goog-api-key", key);
            default -> builder.header("Authorization", "Bearer " + key);
        }
        var call = client.newCall(builder.get().build());
        call.timeout().timeout(12, TimeUnit.SECONDS);
        long start = System.nanoTime();
        try (var response = call.execute()) {
            if (!response.isSuccessful()) {
                throw ProviderFamily.invalid(reason(response.code(), response.peekBody(16_384).string()));
            }
            var body = JSON.readTree(response.peekBody(2 * 1024 * 1024).bytes());
            boolean valid = body != null && switch (family) {
                case OPENROUTER, KIMI -> body.path("data").isObject();
                case DEEPSEEK -> body.path("is_available").isBoolean();
                case GOOGLE -> body.path("models").isArray();
                case QWEN -> body.path("data").isArray() || body.path("output").path("models").isArray()
                        || body.path("models").isArray();
                default -> body.path("data").isArray();
            };
            if (body != null && (family == ProviderFamily.DEEPSEEK && body.path("is_available").isBoolean()
                    && !body.path("is_available").asBoolean() || family == ProviderFamily.KIMI
                    && body.at("/data/available_balance").isNumber() && body.at("/data/available_balance").asDouble() <= 0)) {
                throw ProviderFamily.invalid("提供方账户余额或额度不足");
            }
            if (!valid) {
                throw ProviderFamily.invalid("端点未返回可识别的连接测试结果");
            }
            return new Connection(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw ProviderFamily.invalid("端点未返回可识别的连接测试结果");
        } catch (IOException unavailable) {
            throw ProviderFamily.invalid("网络连接失败或超时");
        }
    }

    private static String reason(int status, String body) {
        JsonNode error = JSON.getNodeFactory().missingNode();
        try {
            var decoded = JSON.readTree(body);
            if (decoded != null) {
                error = decoded.has("error") ? decoded.path("error") : decoded;
            }
        } catch (IOException ignored) {
            // 提供方的错误正文可能是 HTML，用户只看到经过归类的结果。
        }
        String code = error.path("code").asText();
        if (status == 402 || Set.of("insufficient_quota", "1113", "Arrearage", "InsufficientBalance").contains(code)) {
            return "提供方账户余额或额度不足";
        }
        if (status == 400 && error.path("details").findValuesAsText("reason").contains("API_KEY_INVALID")) {
            return "API Key 未通过认证";
        }
        return switch (status) {
            case 401 -> "API Key 未通过认证";
            case 403 -> "账户没有访问此端点的权限";
            case 429 -> "请求被限流，请稍后重试";
            default -> "连接测试未通过（HTTP " + status + "）";
        };
    }
}
