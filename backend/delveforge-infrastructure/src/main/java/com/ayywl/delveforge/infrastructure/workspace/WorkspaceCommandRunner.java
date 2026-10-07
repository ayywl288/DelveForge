package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Workspace 内部的有界进程执行；调用方只使用已解析的固定参数，不输出源码或 stderr。 */
final class WorkspaceCommandRunner {
    record Result(int exitCode, String output) {
        boolean succeeded() { return exitCode == 0; }
    }

    static Result run(Path directory, List<String> command, Duration timeout, int maxOutputBytes) {
        return run(directory, command, null, timeout, maxOutputBytes);
    }

    static Result runWithIndex(Path directory, List<String> command, Path index, Duration timeout, int maxOutputBytes) {
        if (!index.toAbsolutePath().normalize().getParent().equals(directory.resolve(".git").toAbsolutePath().normalize())
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
            ProcessBuilder builder = new ProcessBuilder(resolved).directory(directory.toFile()).redirectErrorStream(true);
            // 外部 Git 定位变量不能把受控路径重定向到原仓库或另一个 index。
            builder.environment().keySet().removeIf(key -> key.startsWith("GIT_") || key.startsWith("MAVEN_")
                    || key.equals("MVNW_REPOURL") || key.equals("MVNW_USERNAME") || key.equals("MVNW_PASSWORD"));
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
            stop(process);
            Thread.currentThread().interrupt();
            throw new WorkspaceException("Controlled Workspace operation interrupted", failure);
        }
    }

    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
