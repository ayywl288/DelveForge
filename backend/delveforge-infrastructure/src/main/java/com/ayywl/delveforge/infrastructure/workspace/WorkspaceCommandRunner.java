package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.port.workspace.WorkspaceException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Workspace 内部的有界进程执行；调用方只使用已解析的固定参数，不输出源码或 stderr。 */
final class WorkspaceCommandRunner {

    record Result(int exitCode, String output) {
        boolean succeeded() {
            return exitCode == 0;
        }
    }

    static Result run(Path directory, List<String> command, Duration timeout, int maxOutputBytes) {
        return run(directory, command, null, timeout, maxOutputBytes);
    }

    static Result runWithIndex(Path directory, List<String> command, Path index, Duration timeout, int maxOutputBytes) {
        if (!index.toAbsolutePath().normalize().getParent()
                .equals(directory.resolve(".git").toAbsolutePath().normalize())
                || !index.getFileName().toString().startsWith("delveforge-index-")) {
            throw new WorkspaceException("Controlled index ownership mismatch");
        }
        return run(directory, command, index, timeout, maxOutputBytes);
    }

    private static Result run(Path directory, List<String> command, Path index, Duration timeout, int maxOutputBytes) {
        Process process;
        try {
            java.util.ArrayList<String> resolved = new java.util.ArrayList<>(command);
            if (resolved.getFirst().equals("git")) {
                resolved.addAll(1, List.of("-c", "core.longpaths=true"));
            }
            ProcessBuilder builder = new ProcessBuilder(resolved)
                    .directory(directory.toFile())
                    .redirectErrorStream(true);
            // 外部 Git 定位变量不能把受控路径重定向到原仓库或另一个 index。
            isolateEnvironment(builder.environment());
            if (resolved.getFirst().equals("git")) {
                // 内部固定值在清除外部 GIT_* 后设置；系统属性不属于受版本证明的仓库来源。
                builder.environment().put("GIT_ATTR_NOSYSTEM", "1");
            }
            if (index != null) {
                // 仅内部拥有的临时 index 可覆盖定位；不接受外部环境提供的 Git 路径。
                builder.environment().put("GIT_INDEX_FILE", index.toString());
            }
            process = builder.start();
            process.getOutputStream().close();
        } catch (IOException failure) {
            throw new WorkspaceException("Cannot start controlled Workspace operation", failure);
        }

        AtomicBoolean overflow = new AtomicBoolean();
        AtomicBoolean readFailed = new AtomicBoolean();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var stream = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (output.size() + count > maxOutputBytes) {
                        overflow.set(true);
                        stop(process);
                        break;
                    }
                    output.write(buffer, 0, count);
                }
            } catch (IOException failure) {
                readFailed.set(true);
            }
        });

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                stop(process);
                throw new WorkspaceException("Controlled Workspace operation exceeded its time budget");
            }
            reader.join(5000);
            if (reader.isAlive() || overflow.get() || readFailed.get()) {
                stop(process);
                throw new WorkspaceException("Controlled Workspace operation exceeded its output budget or could not be read");
            }
            return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException failure) {
            try {
                stop(process);
            } finally {
                Thread.currentThread().interrupt();
            }
            throw new WorkspaceException("Controlled Workspace operation interrupted", failure);
        }
    }

    static void stop(Process process) {
        // 此处只运行受控 Git。退出后 PID 可能被复用，不能再据它重新发现并终止“后代”。
        if (!process.isAlive()) {
            return;
        }
        java.util.ArrayList<ProcessHandle> owned = new java.util.ArrayList<>(process.descendants().toList());
        if (!process.isAlive()) {
            return;
        }
        if (owned.stream().anyMatch(handle -> handle.pid() == ProcessHandle.current().pid())) {
            throw new WorkspaceException("Controlled process ancestry cannot be proven");
        }
        owned.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        // destroyForcibly 是异步的；只等待本次进程树，避免 Windows 目录锁仍被持有时清理。
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            boolean exited = false;
            while (!exited) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new WorkspaceException("Controlled Workspace process termination is not confirmed");
                }
                try {
                    exited = process.waitFor(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
            for (ProcessHandle handle : owned) {
                while (handle.isAlive()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new WorkspaceException("Controlled Workspace process termination is not confirmed");
                    }
                    try {
                        handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException failure) {
                        interrupted = true;
                    } catch (ExecutionException | TimeoutException failure) {
                        throw new WorkspaceException("Controlled Workspace process termination is not confirmed", failure);
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 删除可注入 Git / Maven / JVM 定位或选择的外部变量，不接受环境替代固定命令意图。 */
    static void isolateEnvironment(java.util.Map<String, String> environment) {
        removeProviderCredentials(environment);
        environment.keySet().removeIf(key -> {
            String upper = key.toUpperCase(java.util.Locale.ROOT);
            return upper.startsWith("GIT_") || upper.startsWith("MAVEN_")
                    || upper.equals("MVNW_REPOURL") || upper.equals("MVNW_USERNAME") || upper.equals("MVNW_PASSWORD")
                    || upper.equals("JAVA_TOOL_OPTIONS") || upper.equals("_JAVA_OPTIONS")
                    || upper.equals("JDK_JAVA_OPTIONS");
        });
        // 启动脚本不得通过用户 mavenrc 重新注入测试选择或 JVM 属性。
        environment.put("MAVEN_SKIP_RC", "true");
    }

    /** Provider 凭据只供应用调用模型；环境过滤不代表宿主文件系统沙箱。 */
    static void removeProviderCredentials(java.util.Map<String, String> environment) {
        environment.keySet().removeIf(key -> {
            String canonical = key.replace("_", "").replace("-", "").replace(".", "")
                    .toUpperCase(java.util.Locale.ROOT);
            // Spring 的聚合 JSON 也可能携带凭据，不能作为完整配置透传给仓库进程。
            return canonical.equals("DEEPSEEKAPIKEY") || canonical.equals("DELVEFORGEAIDEEPSEEKAPIKEY")
                    || canonical.equals("SPRINGAPPLICATIONJSON");
        });
    }
}
