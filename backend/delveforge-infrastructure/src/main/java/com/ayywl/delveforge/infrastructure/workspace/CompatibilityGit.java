package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.port.workspace.WorkspaceException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 准备策略专用的有界 Git plumbing；不执行项目脚本，不记录 blob 或 Git 自由文本。 */
final class CompatibilityGit {
    private CompatibilityGit() {
    }

    static byte[] run(Path root, byte[] input, Map<String, String> environment, String... arguments) {
        int limit = arguments[0].equals("cat-file") ? 16777216 : 8388608;
        List<String> command = new ArrayList<>(List.of("git", "--no-optional-locks", "-c", "core.longpaths=true",
                "-c", "core.autocrlf=false", "-c", "core.fsmonitor=false", "-c", "core.hooksPath=",
                "-c", "core.attributesFile=", "-c", "core.excludesFile=", "-c", "core.ignoreCase=false",
                "-c", "diff.external=", "-C", root.toString()));
        command.addAll(List.of(arguments));
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
            WorkspaceCommandRunner.isolateEnvironment(builder.environment());
            builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            builder.environment().put("GIT_CONFIG_GLOBAL", System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
            builder.environment().putAll(environment);
            builder.environment().put("GIT_ATTR_NOSYSTEM", "1");
            process = builder.start();
        } catch (IOException failure) {
            throw new WorkspaceException("Cannot start compatibility Git inspection", failure);
        }
        AtomicReference<byte[]> output = new AtomicReference<>();
        AtomicReference<IOException> failed = new AtomicReference<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var stream = process.getInputStream()) {
                byte[] bytes = stream.readNBytes(limit + 1);
                output.set(bytes);
                if (bytes.length > limit) {
                    process.destroyForcibly();
                }
            } catch (IOException failure) {
                failed.set(failure);
            }
        });
        Thread writer = Thread.ofVirtual().start(() -> {
            try (var stream = process.getOutputStream()) {
                if (input != null) {
                    stream.write(input);
                }
            } catch (IOException failure) {
                failed.set(failure);
            }
        });
        try {
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                throw new WorkspaceException("Compatibility Git operation exceeded its time budget");
            }
            reader.join(5000);
            writer.join(5000);
            boolean expectedMiss = arguments[0].equals("check-ignore") && process.exitValue() == 1;
            if (process.exitValue() != 0 && !expectedMiss || failed.get() != null || output.get() == null
                    || output.get().length > limit || reader.isAlive() || writer.isAlive()) {
                throw new WorkspaceException("Cannot establish bounded Git compatibility evidence");
            }
            return output.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new WorkspaceException("Repository preparation was interrupted", failure);
        } finally {
            WorkspaceCommandRunner.stop(process);
        }
    }

    static String text(Path root, byte[] input, Map<String, String> environment, String... arguments) {
        return new String(run(root, input, environment, arguments), StandardCharsets.UTF_8).strip();
    }
}
