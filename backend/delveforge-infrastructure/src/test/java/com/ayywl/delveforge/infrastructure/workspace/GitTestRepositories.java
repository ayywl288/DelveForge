package com.ayywl.delveforge.infrastructure.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 测试用真实 Git Repository 的建立、检查与清理。
 *
 * <p>它属于测试支持代码，只存在于 Infrastructure 的测试源中：测试需要的是真实
 * {@code git} 命令产生的仓库，而不是磁盘上手工摆出来的目录结构（AGENTS.md §10.3）。
 * 多个测试类都要建立这样的仓库，因此集中在这里，避免各自复制一份。
 *
 * <p>与 Adapter 自身一样，本类依赖运行环境能找到 {@code git} 可执行文件。
 * 仓库建在 {@code target/test-repositories} 下，属于构建产物，不会被提交。
 */
public final class GitTestRepositories {

    private final Path baseDirectory;

    /**
     * 每个实例使用独立目录，避免测试类之间互相影响。
     *
     * <p>路径必须是绝对的：Workspace Adapter 只接受绝对位置。
     */
    public GitTestRepositories() {
        this.baseDirectory =
                Path.of("target", "test-repositories", UUID.randomUUID().toString()).toAbsolutePath();
    }

    /** 本实例目录下的一个位置；只返回路径，不创建任何内容。 */
    public Path directory(String name) {
        return baseDirectory.resolve(name);
    }

    /** 建一个空的 Repository（只 init，没有 commit）。 */
    public Path createEmpty(String name) throws Exception {
        Path repository = baseDirectory.resolve(name);
        Files.createDirectories(repository);
        runGit(repository, "init", "-q", "--initial-branch=main");
        return repository;
    }

    /** 建一个 Repository，写入给定文件并提交一次。 */
    public Path createCommitted(String name, Map<String, String> files) throws Exception {
        Path repository = createEmpty(name);
        for (Map.Entry<String, String> file : files.entrySet()) {
            writeFile(repository, file.getKey(), file.getValue());
        }
        runGit(repository, "add", "-A");
        commit(repository, "initial");
        return repository;
    }

    /** 当前提交的 revision。 */
    public static String headRevision(Path repository) throws Exception {
        return runGit(repository, "rev-parse", "HEAD").trim();
    }

    public static void writeFile(Path root, String relativePath, String content) throws IOException {
        Path target = root.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    public static void commit(Path repository, String message) throws Exception {
        runGit(repository, "-c", "user.email=test@delveforge.local",
                "-c", "user.name=DelveForge Test", "commit", "-q", "-m", message);
    }

    /**
     * 仓库全部文件的内容、大小与修改时间。
     *
     * <p>修改时间也参与比较：只比较内容会漏掉「被重写但内容相同」的写操作。
     */
    public static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> state = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                state.put(root.relativize(path).toString(),
                        sha256(path) + "|" + Files.size(path) + "|"
                                + Files.getLastModifiedTime(path).toMillis());
            }
        }
        return state;
    }

    /**
     * 删除本实例建立的全部 Repository。
     *
     * <p>必须显式清理：git 在 Windows 上把对象文件标记为只读，把它们留在 {@code target} 下
     * 会让下一次 {@code mvn clean} 删不掉 target 而失败。删除前先去掉只读属性——
     * {@code setWritable(true)} 在 Windows 上对应清除只读属性，在其他平台则设置写位。
     */
    public void deleteAll() throws IOException {
        if (!Files.exists(baseDirectory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(baseDirectory)) {
            // 逆序：先删文件与深层目录，最后才删目录本身
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        }
    }

    /** 在测试里驱动真实的 git 命令，用于建立 fixture 与核对结果。 */
    public static String runGit(Path repository, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(arguments));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git 命令失败: " + command + "\n" + output);
        }
        return output;
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256 实现", e);
        }
    }
}
