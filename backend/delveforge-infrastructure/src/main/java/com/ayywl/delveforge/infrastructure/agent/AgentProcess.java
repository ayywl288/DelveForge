package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 受控 CLI 运输，二进制 stdout 与诊断 stderr 分离；子进程从不继承模型凭据。 */
final class AgentProcess {
    record Result(int exitCode, byte[] bytes, String diagnostic) {
        String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    Result run(List<String> command, Path directory, byte[] input, Duration timeout, int maxBytes,
            BooleanSupplier cancelled, Map<String, String> additions) {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (directory != null) {
                builder.directory(directory.toFile());
            }
            Set<String> allowed = Set.of("PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "HOME", "USERPROFILE",
                    "LOCALAPPDATA", "APPDATA", "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG",
                    "DOCKER_CERT_PATH", "DOCKER_TLS_VERIFY", "COMSPEC", "PATHEXT");
            builder.environment().keySet().removeIf(key -> !allowed.contains(key.toUpperCase(java.util.Locale.ROOT)));
            builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            builder.environment().put("GIT_CONFIG_GLOBAL", nullDevice());
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().putAll(additions);
            process = builder.start();
        } catch (IOException failure) {
            throw new AgentException("ENVIRONMENT_FAILURE", "Cannot start controlled operation", failure);
        }
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicBoolean failed = new AtomicBoolean();
        Thread out = drain(process.getInputStream(), stdout, maxBytes, failed, process);
        Thread err = drain(process.getErrorStream(), stderr, Math.min(maxBytes, 65536), failed, process);
        Thread writer = Thread.ofVirtual().start(() -> {
            try (var stream = process.getOutputStream()) {
                if (input != null) {
                    stream.write(input);
                }
            } catch (IOException failure) {
                failed.set(true);
            }
        });
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    throw new AgentException("CANCELLED", "Agent operation cancelled");
                }
                if (System.nanoTime() > deadline) {
                    throw new AgentException("TIME_LIMIT", "Controlled operation exceeded its time budget");
                }
                if (failed.get()) {
                    throw new AgentException("OUTPUT_LIMIT", "Controlled operation output exceeds its limit");
                }
            }
            writer.join(5000);
            out.join(5000);
            err.join(5000);
            if (failed.get() || writer.isAlive() || out.isAlive() || err.isAlive()) {
                throw new AgentException("OUTPUT_LIMIT", "Controlled operation output could not be captured safely");
            }
            return new Result(process.exitValue(), stdout.toByteArray(), stderr.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AgentException("CANCELLED", "Agent operation interrupted", failure);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static Thread drain(java.io.InputStream input, ByteArrayOutputStream output, int maxBytes,
            AtomicBoolean failed, Process process) {
        return Thread.ofVirtual().start(() -> {
            try (input) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > maxBytes) {
                        failed.set(true);
                        process.destroyForcibly();
                        return;
                    }
                    output.write(buffer, 0, count);
                }
            } catch (IOException failure) {
                failed.set(true);
            }
        });
    }

    private static String nullDevice() {
        return System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null";
    }
}
