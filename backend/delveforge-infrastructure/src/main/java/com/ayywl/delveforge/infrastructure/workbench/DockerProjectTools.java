package com.ayywl.delveforge.infrastructure.workbench;

import com.ayywl.delveforge.application.workbench.ProjectTools;
import com.ayywl.delveforge.application.workbench.WorkbenchRejection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 宿主执行已签入仓库的工具管理程序。发布锁保护整份全局版本清单。 */
public final class DockerProjectTools implements ProjectTools {
    private final Path root;
    private final Path helper;
    private final ObjectMapper json;

    public DockerProjectTools(Path root, ObjectMapper json) {
        this.root = root.toAbsolutePath().normalize();
        this.json = json;
        Path repository = Path.of("").toAbsolutePath();
        Path file = Path.of("runtime/workbench/tool-images/manage.mjs");
        while (repository != null && !Files.isRegularFile(repository.resolve(file))) {
            repository = repository.getParent();
        }
        this.helper = repository == null ? file.toAbsolutePath() : repository.resolve(file);
    }

    @Override
    public Map<String, Object> inventory(String project) {
        if (!Files.isRegularFile(root.resolve("runtime-catalog.json"))) {
            return Map.of("initialized", false, "latest", 0);
        }
        return invoke(project, "inventory", "", "");
    }

    @Override
    public synchronized Map<String, Object> preview(String project, String name) {
        return invoke(project, "preview", name, "");
    }

    @Override
    public synchronized Map<String, Object> publish(String project, String name, String plan) {
        if (plan == null || !plan.matches("[a-f0-9]{64}")) {
            throw new WorkbenchRejection(WorkbenchRejection.Kind.INVALID, "请先核对要推广的软件包");
        }
        try (var channel = FileChannel.open(root.resolve("tool-images.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); var lock = channel.tryLock()) {
            if (lock == null) {
                throw new WorkbenchRejection(WorkbenchRejection.Kind.BUSY, "正在发布另一个全局工具版本");
            }
            var result = new LinkedHashMap<>(invoke(project, "publish", name, plan));
            var release = json.valueToTree(result.remove("release"));
            String prior = (String) result.remove("prior");
            Path registry = root.resolve("tool-images.json");
            ArrayNode history = Files.exists(registry) ? (ArrayNode) json.readTree(registry.toFile()).path("releases")
                    : json.createArrayNode();
            String active = release.path("base").asText();
            int version = 0;
            for (var entry : history) {
                version = Math.max(version, entry.path("version").asInt());
                if (entry.path("base").equals(release.path("base"))) {
                    active = entry.path("image").asText();
                }
            }
            if (!active.equals(prior) || release.path("version").asInt() != version + 1) {
                throw new WorkbenchRejection(WorkbenchRejection.Kind.BUSY, "全局版本已变化，请重新预览");
            }
            history.add(release);
            Path pending = Files.createTempFile(root, "tool-images-", ".tmp");
            try {
                json.writeValue(pending.toFile(), Map.of("releases", history));
                Files.move(pending, registry, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(pending);
            }
            return result;
        } catch (IOException failure) {
            throw new IllegalStateException("无法取得全局镜像发布锁", failure);
        }
    }

    private Map<String, Object> invoke(String project, String action, String name, String plan) {
        if (project == null || !project.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                || name == null || (!action.equals("inventory") && !name.matches("[a-z0-9][a-z0-9+.-]{1,127}"))) {
            throw new WorkbenchRejection(WorkbenchRejection.Kind.INVALID, "项目或软件包标识无效");
        }
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile(root, "tool-result-", ".json");
            var builder = new ProcessBuilder("node", helper.toString(), root.toString(), project, action, name, plan);
            builder.environment().remove("DEEPSEEK_API_KEY");
            process = builder.redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(output.toFile()).start();
            if (!process.waitFor(12, TimeUnit.MINUTES) || Files.size(output) > 2_000_000) {
                throw new IllegalStateException("工具镜像操作未在限时内完成");
            }
            var result = json.readValue(Files.readString(output, StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Object>>() {});
            if (process.exitValue() != 0 || result.containsKey("error")) {
                throw new WorkbenchRejection(WorkbenchRejection.Kind.BUSY,
                        String.valueOf(result.getOrDefault("error", "镜像操作未完成")));
            }
            return result;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("镜像操作被中断", failure);
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取本机工具镜像服务", failure);
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            if (output != null) {
                output.toFile().delete();
            }
        }
    }
}
