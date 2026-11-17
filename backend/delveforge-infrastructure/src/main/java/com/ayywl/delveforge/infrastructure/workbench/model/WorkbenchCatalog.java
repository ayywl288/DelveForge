package com.ayywl.delveforge.infrastructure.workbench.model;

import com.ayywl.delveforge.application.workbench.WorkbenchModels.Model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 目录刷新只访问固定公共 URL。网络失败时沿用上次成功写入的目录。 */
final class WorkbenchCatalog {
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private final ObjectMapper json;
    private final Path cache;
    private final Map<String, Model> builtin;
    private volatile Map<String, Model> current;
    private Instant nextRefresh = Instant.MIN;

    WorkbenchCatalog(Path root, ObjectMapper json) {
        this.json = json;
        cache = root.resolve("models-dev.json");
        try (var input = WorkbenchCatalog.class.getResourceAsStream("/workbench-models.json")) {
            builtin = parse(input.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("内置模型目录无法加载", e);
        }
        current = builtin;
        if (Files.isRegularFile(cache)) {
            try (var input = Files.newInputStream(cache)) {
                var cached = parse(bounded(input));
                if (cached.isEmpty()) {
                    throw new IOException("缓存缺少可用模型");
                }
                current = cached;
                nextRefresh = Files.getLastModifiedTime(cache).toInstant().plus(Duration.ofHours(24));
            } catch (IOException | RuntimeException invalidCache) {
                current = builtin;
            }
        }
    }

    synchronized void refreshIfDue() {
        if (Instant.now().isBefore(nextRefresh)) {
            return;
        }
        nextRefresh = Instant.now().plus(Duration.ofHours(1));
        Thread.startVirtualThread(this::refresh);
    }

    private void refresh() {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()) {
            var request = HttpRequest.newBuilder(URI.create("https://models.dev/api.json"))
                    .timeout(Duration.ofSeconds(20)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                if (response.statusCode() != 200) {
                    return;
                }
                byte[] bytes = bounded(input);
                var models = parse(bytes);
                if (models.size() < builtin.size()) {
                    return;
                }
                Path temp = Files.createTempFile(cache.getParent(), "models-", ".tmp");
                try {
                    Files.write(temp, bytes);
                    Files.move(temp, cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temp);
                }
                current = models;
                synchronized (this) {
                    nextRefresh = Instant.now().plus(Duration.ofHours(24));
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException offline) {
            // 目录是可选的发现服务；失败后保留上次内容并在下次到期时重试。
        }
    }

    private static byte[] bounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            throw new IOException("模型目录超出上限");
        }
        return bytes;
    }

    Map<String, Model> parse(byte[] bytes) throws IOException {
        JsonNode tree = json.readTree(bytes);
        var result = new LinkedHashMap<String, Model>();
        for (var family : ProviderFamily.values()) {
            for (String source : family.endpoints.keySet().stream().map(family::catalogSource).distinct().toList()) {
                var entries = tree.path(source).path("models");
                for (var model : entries) {
                    String id = model.path("id").asText("");
                    var limits = model.path("limit");
                    int context = limits.path("context").asInt();
                    int output = limits.path("output").asInt();
                    if (!model.path("tool_call").asBoolean() || !text(model.path("modalities").path("input"))
                            || !text(model.path("modalities").path("output")) || context < 1024 || output < 1
                            || "deprecated".equals(model.path("status").asText())
                            || !id.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,180}") || id.contains("..")
                            || id.matches(".*(realtime|computer-use|deep-research|audio-preview).*")
                            || family == ProviderFamily.GOOGLE && id.matches(".*(-live|-image|nano-banana).*")
                            || family == ProviderFamily.OPENROUTER && id.startsWith("openrouter/")) {
                        continue;
                    }
                    boolean reasoning = model.path("reasoning").asBoolean();
                    result.put(source + "/" + id, new Model(family.id(), id, model.path("name").asText(id),
                            family.efforts(id, reasoning), context, limits.path("input").asInt(context), output,
                            reasoning, model.path("last_updated").asText("models.dev"), "catalog", List.of(), List.of()));
                }
            }
        }
        return Map.copyOf(result);
    }

    private static boolean text(JsonNode modes) {
        for (JsonNode value : modes) {
            if ("text".equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    Model require(String source, String id) {
        var model = current.get(source + "/" + id);
        if (model == null) {
            throw ProviderFamily.invalid("此模型未声明支持工作台的文本和工具协议");
        }
        return model;
    }

    List<Model> models(String source) {
        String prefix = source + "/";
        return current.entrySet().stream().filter(e -> e.getKey().startsWith(prefix)).map(Map.Entry::getValue).toList();
    }
}
