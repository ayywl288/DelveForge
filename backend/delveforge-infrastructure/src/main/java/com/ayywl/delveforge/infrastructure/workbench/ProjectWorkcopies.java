package com.ayywl.delveforge.infrastructure.workbench;

import com.ayywl.delveforge.application.workbench.ProjectFiles;
import com.ayywl.delveforge.application.workbench.WorkbenchRejection;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 先在挂载目录外构造独立副本，核实源内容与固定的 Git 基线后原子发布。 */
public final class ProjectWorkcopies implements ProjectFiles {
    private static final Set<String> GENERATED =
            Set.of(
                    "node_modules",
                    "target",
                    "dist",
                    ".vite",
                    ".pnpm-store",
                    "__pycache__",
                    ".venv",
                    ".gradle");
    private final Path parent;
    private final Path staging;

    public ProjectWorkcopies(Path parent) throws IOException {
        this.parent = Files.createDirectories(parent).toRealPath();
        this.staging =
                Files.createDirectories(
                                this.parent.resolveSibling(this.parent.getFileName() + "-import"))
                        .toRealPath();
    }

    @Override
    public String prepare(String id, String source) {
        UUID.fromString(id);
        Path destination = parent.resolve(id);
        Path temp = staging.resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectory(temp);
            if (source != null && !source.isBlank()) {
                Path requested = Path.of(source).toAbsolutePath().normalize();
                rejectLink(requested);
                Path src = requested.toRealPath();
                if (!Files.isDirectory(src)
                        || src.getParent() == null
                        || related(src, parent)
                        || related(src, staging)) {
                    throw new IOException("源目录与工作副本或控制目录重叠");
                }
                if (Files.isSameFile(src, parent.getParent())
                        || src.startsWith(parent.getParent())
                                && Files.exists(parent.getParent().resolve("control.db"))) {
                    throw new IOException("请选择控制目录以外的源项目");
                }
                copyCoherent(src, temp);
            }
            if (!Files.exists(temp.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                // 阻止 Git 从空项目或普通目录向上发现宿主程序自己的仓库。
                git(temp, true, "init", "--initial-branch", "main");
            }
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
            return destination.toString();
        } catch (IOException e) {
            throw new WorkbenchRejection(
                    WorkbenchRejection.Kind.INVALID, "建立工作副本失败：" + e.getMessage());
        } finally {
            if (Files.exists(temp)) {
                removeStage(temp);
            }
        }
    }

    private static boolean related(Path a, Path b) {
        return a.startsWith(b) || b.startsWith(a);
    }

    @Override
    public void open(String id) {
        try {
            UUID.fromString(id);
            Path directory = parent.resolve(id);
            rejectLink(directory);
            if (!directory.toRealPath().getParent().equals(parent)) {
                throw new IOException("目录已移动");
            }
            new ProcessBuilder("explorer.exe", directory.toString()).start();
        } catch (IOException | IllegalArgumentException e) {
            throw new WorkbenchRejection(WorkbenchRejection.Kind.MISSING, "无法打开指定项目工作副本");
        }
    }

    private record Head(String ref, String commit) {}

    private record Manifest(Map<String, String> files, Set<String> directories) {}

    private void copyCoherent(Path src, Path tmp) throws IOException {
        boolean repository = Files.exists(src.resolve(".git"), LinkOption.NOFOLLOW_LINKS);
        if (repository) {
            rejectLink(src.resolve(".git"));
        }
        Head head = repository ? head(src) : new Head("", "");
        Set<String> tracked = new java.util.HashSet<>();
        if (!head.commit().isEmpty()) {
            for (String entry : git(src, true, "ls-tree", "-rz", head.commit()).split("\u0000")) {
                if (entry.isEmpty()) {
                    continue;
                }
                int tab = entry.indexOf('\t');
                if (tab < 0 || entry.startsWith("160000 ") || entry.startsWith("120000 ")) {
                    throw new IOException("无法独立导入含 Git 子模块或符号链接的项目");
                }
                tracked.add(entry.substring(tab + 1));
            }
        }
        Manifest before = snapshot(src, tracked);
        if (repository) {
            if (head.commit().isEmpty()) {
                git(
                        tmp,
                        true,
                        "init",
                        "--initial-branch",
                        head.ref().substring("refs/heads/".length()));
                git(tmp, true, "read-tree", "--empty");
            } else {
                git(
                        tmp.getParent(),
                        true,
                        "clone",
                        "--no-local",
                        "--no-checkout",
                        "--",
                        src.toString(),
                        tmp.toString());
                git(tmp, true, "remote", "remove", "origin");
                if (head.ref().isEmpty()) {
                    git(tmp, true, "update-ref", "--no-deref", "HEAD", head.commit());
                } else {
                    git(tmp, true, "update-ref", head.ref(), head.commit());
                    git(tmp, true, "symbolic-ref", "HEAD", head.ref());
                }
                git(tmp, true, "read-tree", head.commit());
            }
            if (!head.equals(head(tmp))
                    || Files.exists(tmp.resolve(".git/objects/info/alternates"))) {
                throw new IOException("副本的 Git 基线或对象存储不独立");
            }
        }
        for (String dir : before.directories()) {
            Files.createDirectories(tmp.resolve(dir));
        }
        for (String rel : before.files().keySet()) {
            Path from = src.resolve(rel);
            rejectLink(from);
            Files.copy(from, tmp.resolve(rel), LinkOption.NOFOLLOW_LINKS);
        }
        if (repository != Files.exists(src.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
                || repository && !head.equals(head(src))
                || !before.equals(snapshot(src, tracked))
                || !before.equals(snapshot(tmp, tracked))) {
            throw new IOException("导入期间源项目已变动，请重试");
        }
    }

    private static Head head(Path path) throws IOException {
        String ref = git(path, false, "symbolic-ref", "-q", "HEAD").strip();
        String commit = git(path, false, "rev-parse", "--verify", "HEAD^{commit}").strip();
        if (commit.isEmpty()
                && (!ref.startsWith("refs/heads/") || !git(path, false, "show-ref").isBlank())) {
            throw new IOException("Git HEAD 无法固定");
        }
        return new Head(ref, commit);
    }

    private static Manifest snapshot(Path root, Set<String> tracked) throws IOException {
        Map<String, String> files = new HashMap<>();
        Set<String> dirs = new java.util.HashSet<>();
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes attr)
                            throws IOException {
                        if (p.equals(root)) {
                            return FileVisitResult.CONTINUE;
                        }
                        String name = p.getFileName().toString();
                        if (name.equalsIgnoreCase(".git")) {
                            if (!p.getParent().equals(root)) {
                                throw new IOException("无法独立导入嵌套 Git 仓库：" + root.relativize(p));
                            }
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        rejectLink(p);
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        if (GENERATED.contains(name)
                                && tracked.stream().noneMatch(t -> t.startsWith(rel + "/"))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        dirs.add(rel);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path p, BasicFileAttributes attr)
                            throws IOException {
                        if (p.getFileName().toString().equalsIgnoreCase(".git")) {
                            if (!p.getParent().equals(root)) {
                                throw new IOException("无法独立导入嵌套 Git 工作树：" + root.relativize(p));
                            }
                            return FileVisitResult.CONTINUE;
                        }
                        rejectLink(p);
                        if (!attr.isRegularFile()) {
                            throw new IOException("不支持的文件：" + root.relativize(p));
                        }
                        try (var input =
                                new DigestInputStream(
                                        Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS),
                                        digest())) {
                            input.transferTo(java.io.OutputStream.nullOutputStream());
                            files.put(
                                    root.relativize(p).toString().replace('\\', '/'),
                                    HexFormat.of().formatHex(input.getMessageDigest().digest()));
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        return new Manifest(files, dirs);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void rejectLink(Path p) throws IOException {
        if (Files.isSymbolicLink(p)
                || System.getProperty("os.name").startsWith("Windows")
                        && (((Number)
                                                        Files.getAttribute(
                                                                p,
                                                                "dos:attributes",
                                                                LinkOption.NOFOLLOW_LINKS))
                                                .intValue()
                                        & 1024)
                                != 0) {
            throw new IOException("无法导入符号链接或目录联接：" + p.getFileName());
        }
    }

    private static String git(Path cwd, boolean required, String... args) throws IOException {
        var command =
                new java.util.ArrayList<>(
                        List.of(
                                "git",
                                "--no-optional-locks",
                                "-c",
                                "core.hooksPath="
                                        + (System.getProperty("os.name").startsWith("Windows")
                                                ? "NUL"
                                                : "/dev/null"),
                                "-C",
                                cwd.toString()));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command);
        // 防止继承调用者的 Git 工作树、索引、外部 helper 或配置覆盖。
        builder.environment()
                .keySet()
                .removeIf(k -> k.toUpperCase(java.util.Locale.ROOT).startsWith("GIT_"));
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment()
                .put(
                        "GIT_CONFIG_GLOBAL",
                        System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        var proc = builder.start();
        try {
            var out =
                    java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> {
                                try (var in = proc.getInputStream()) {
                                    return in.readAllBytes();
                                } catch (IOException e) {
                                    throw new java.io.UncheckedIOException(e);
                                }
                            });
            if (!proc.waitFor(120, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("Git " + args[0] + " 超时");
            }
            if (proc.exitValue() != 0) {
                if (!required) {
                    return "";
                }
                throw new IOException("Git " + args[0] + " 退出码 " + proc.exitValue());
            }
            return new String(out.join(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new IOException("Git 操作被中断", e);
        }
    }

    private void removeStage(Path tmp) {
        if (!tmp.toAbsolutePath().normalize().getParent().equals(staging)) {
            throw new IllegalArgumentException("临时目录超出范围");
        }
        try {
            Files.walkFileTree(
                    tmp,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path p, BasicFileAttributes attr)
                                throws IOException {
                            if (!Files.isSymbolicLink(p)
                                    && System.getProperty("os.name").startsWith("Windows")) {
                                Files.setAttribute(
                                        p, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
                            }
                            Files.delete(p);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path p, IOException e)
                                throws IOException {
                            if (e != null) {
                                throw e;
                            }
                            Files.delete(p);
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("请检查未发布的临时目录 " + tmp, e);
        }
    }
}
