package com.ayywl.delveforge.infrastructure.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 宿主控制目录持有每个项目的不可变镜像、容器 ID、网络代次和网关地址。 */
final class ProjectRuntime {
    private final Path root;
    private final ObjectMapper json;
    private final Path script;
    private final int cpu;
    private final int memory;
    private final int pids;

    ProjectRuntime(Path root, ObjectMapper json, int cpu, int memory, int pids) {
        this.root = root.toAbsolutePath().normalize();
        this.json = json;
        this.cpu = cpu;
        this.memory = memory;
        this.pids = pids;
        Path repo = Path.of("").toAbsolutePath();
        Path relative = Path.of("runtime/workbench/Initialize-Environment.ps1");
        while (repo != null && !Files.isRegularFile(repo.resolve(relative))) {
            repo = repo.getParent();
        }
        this.script = repo == null ? relative.toAbsolutePath() : repo.resolve(relative);
    }

    Path directory(String project) {
        if (!UUID.fromString(project).toString().equals(project)) {
            throw new IllegalArgumentException("项目标识无效");
        }
        return root.resolve("environments").resolve(project);
    }

    JsonNode catalog() {
        try {
            return json.readTree(root.resolve("runtime-catalog.json").toFile());
        } catch (IOException failure) {
            throw new IllegalStateException("请先运行工作台启动脚本以准备受信镜像", failure);
        }
    }

    DockerDevelopmentEnvironment.Settings registered(String project) {
        Path file = directory(project).resolve("environment.json");
        if (Files.notExists(file)) {
            return null;
        }
        try {
            var settings = json.readValue(file.toFile(), DockerDevelopmentEnvironment.Settings.class);
            var catalog = catalog();
            Path copy = root.resolve("projects").resolve(project);
            String owner = catalog.path("owner").asText();
            String namespace = owner + "-p" + project.replace("-", "");
            if (!owner.matches("df-wb-[0-9a-f]{12}") || !owner.equals(settings.installation())
                    || !catalog.path("endpoint").asText().equals(settings.endpoint())
                    || !project.equals(settings.project()) || !settings.owner().matches(namespace + "-g[0-9a-f]{12}")
                    || !settings.container().equals(settings.owner() + "-dev")
                    || !settings.stateVolume().equals(namespace + "-state")
                    || !copy.toRealPath().equals(copy) || !copy.equals(Path.of(settings.parent()).toRealPath())) {
                throw new IllegalStateException("项目环境身份或工作副本位置已变更");
            }
            return settings;
        } catch (IOException failure) {
            throw new IllegalStateException("项目环境登记无法读取", failure);
        }
    }

    void provision(String project, String action) {
        Path control = directory(project);
        List<String> command = new ArrayList<>(List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", script.toString(), "-DataDirectory", root.toString(),
                "-ProjectId", project, "-Action", action, "-CpuLimit", Integer.toString(cpu),
                "-MemoryMiB", Integer.toString(memory), "-PidLimit", Integer.toString(pids)));
        Path ticket = null;
        Process child = null;
        try {
            Files.createDirectories(control);
            if (!"Ensure".equals(action)) {
                String nonce = UUID.randomUUID().toString();
                ticket = control.resolve("maintenance-" + nonce);
                Files.writeString(ticket, action, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                command.addAll(List.of("-MaintenanceTicket", nonce));
            }
            var start = new ProcessBuilder(command).redirectErrorStream(true);
            start.environment().remove("DEEPSEEK_API_KEY");
            child = start.redirectOutput(control.resolve("provision.log").toFile()).start();
            if (!child.waitFor(180, TimeUnit.SECONDS) || child.exitValue() != 0) {
                throw new IllegalStateException("项目环境未就绪，请检查本机 Docker 和该项目的环境登记");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("项目环境准备被中断", failure);
        } catch (IOException failure) {
            throw new IllegalStateException("无法启动项目环境准备程序", failure);
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
            }
            if (ticket != null) {
                try {
                    Files.deleteIfExists(ticket);
                } catch (IOException failure) {
                    throw new IllegalStateException("无法撤销本地环境维护凭据", failure);
                }
            }
        }
    }
}
