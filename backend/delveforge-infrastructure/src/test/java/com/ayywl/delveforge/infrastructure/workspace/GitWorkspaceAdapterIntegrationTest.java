package com.ayywl.delveforge.infrastructure.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.workspace.WorkspaceEntry;
import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * 验证 {@link GitWorkspaceAdapter} 与真实 Git Repository 的集成。
 *
 * <p>测试用的 Repository 由真实 {@code git} 命令创建与提交，不是磁盘上手工摆出来的
 * 目录结构（AGENTS.md §10.3）：真正的 Git 才能证明该 Adapter 读的是 commit tree，
 * 而不是「长得像 Git」的文件布局。
 *
 * <p>与 Adapter 自身一样，本测试依赖运行环境能找到 {@code git} 可执行文件；
 * Repository 建在 {@code target/test-repositories} 下，属于构建产物，不会被提交。
 */
class GitWorkspaceAdapterIntegrationTest {

    /** Adapter 只接受绝对位置，因此测试仓库也用绝对路径定位。 */
    private static final Path BASE_DIRECTORY =
            Path.of("target", "test-repositories", UUID.randomUUID().toString()).toAbsolutePath();

    private static final String README_CONTENT = "# legacy tool\n";

    private static final String APP_CONTENT = "public class App {}\n";

    private static final String APP_TEST_CONTENT = "public class AppTest {}\n";

    private static final String DOC_CONTENT = "# 说明\n\n这是一个遗留工具。\n";

    /** 用于「断言与 revision 无关」的场景：位置本身就读不了时，读操作会在它之前失败。 */
    private static final String ANY_REVISION = "0".repeat(40);

    private final GitWorkspaceAdapter adapter = new GitWorkspaceAdapter();

    /**
     * 删除本次测试建立的 Repository。
     *
     * <p>必须显式清理：git 在 Windows 上把对象文件标记为只读，把它们留在 {@code target} 下
     * 会让下一次 {@code mvn clean} 删不掉 target 而失败。清理范围严格限定在本测试自己的
     * 目录内，删除前先去掉只读属性。
     */
    @AfterAll
    static void deleteTestRepositories() throws IOException {
        deleteRecursively(BASE_DIRECTORY);
    }

    @Test
    void readsCommittedTreeOfARealRepository() throws Exception {
        Fixture fixture = createRepository("basic");
        WorkspaceRef workspace = workspaceRef(fixture);

        assertTrue(adapter.isReadableRepository(workspace));
        assertEquals(fixture.revision(), adapter.headRevision(workspace));

        List<WorkspaceEntry> rootDepthOne = adapter.listEntries(workspace, fixture.revision(), "", 1);
        assertEquals(List.of(".gitignore", "README.md", "docs", "src"), paths(rootDepthOne));
        assertTrue(entry(rootDepthOne, "src").directory());
        assertFalse(entry(rootDepthOne, "README.md").directory());

        assertEquals(List.of(".gitignore", "README.md", "docs", "docs/说明.md", "src", "src/main",
                        "src/test"),
                paths(adapter.listEntries(workspace, fixture.revision(), "", 2)));
        assertEquals(List.of("src/main", "src/test"), paths(adapter.listEntries(workspace, fixture.revision(), "src", 1)));

        assertEquals(README_CONTENT, adapter.readFile(workspace, fixture.revision(), "README.md"));
        assertEquals(APP_CONTENT, adapter.readFile(workspace, fixture.revision(), "src/main/App.java"));
        assertEquals(DOC_CONTENT, adapter.readFile(workspace, fixture.revision(), "docs/说明.md"),
                "非 ASCII 路径必须能原样解析，不被转义形式破坏");
    }

    /**
     * Repository Analysis 记录 analyzedRevision 时依赖该值稳定且对应当前提交。
     */
    @Test
    void reportsCurrentCommittedRevisionStably() throws Exception {
        Fixture fixture = createRepository("revision");
        WorkspaceRef workspace = workspaceRef(fixture);

        String first = adapter.headRevision(workspace);
        String second = adapter.headRevision(workspace);

        assertEquals(first, second, "同一状态下重复读取必须得到同一个 revision");
        assertEquals(fixture.revision(), first);

        writeFile(fixture.path(), "README.md", "# 第二个版本\n");
        runGit(fixture.path(), "add", "-A");
        commit(fixture.path(), "second");

        String afterCommit = adapter.headRevision(workspace);
        assertEquals(runGit(fixture.path(), "rev-parse", "HEAD").trim(), afterCommit);
        assertNotEquals(first, afterCommit, "新 commit 之后 revision 必须随之变化");
    }

    /**
     * 读取的是该 revision 的已提交内容：未提交修改、untracked 与被忽略的文件都不进入结果。
     *
     * <p>若读取工作区，{@code RepositoryProfile.analyzedRevision} 就会声称分析了一个
     * 它其实没有描述的版本。
     */
    @Test
    void readsCommittedContentRatherThanWorkingTree() throws Exception {
        Fixture fixture = createRepository("dirty");
        WorkspaceRef workspace = workspaceRef(fixture);

        writeFile(fixture.path(), "README.md", "# 尚未提交的修改\n");
        writeFile(fixture.path(), "untracked.java", "class Untracked {}\n");
        writeFile(fixture.path(), "ignored.txt", "ignored content\n");

        assertEquals(fixture.revision(), adapter.headRevision(workspace), "未提交修改不构成新的 revision");
        assertEquals(README_CONTENT, adapter.readFile(workspace, fixture.revision(), "README.md"),
                "必须读到已提交的内容，而不是磁盘上被改过的内容");

        List<String> rootEntries = paths(adapter.listEntries(workspace, fixture.revision(), "", 1));
        assertEquals(List.of(".gitignore", "README.md", "docs", "src"), rootEntries,
                "untracked 与被忽略的文件不得出现在已提交内容的列表中");
    }

    /**
     * 回归：一次分析必须固定在一个 revision 上。
     *
     * <p>解析出 revision A 之后仓库又产生了提交 B，此时带着 A 的读取必须仍然返回 A 的内容。
     * 若读取去重新解析 HEAD，这里会读到 B 的内容与 B 新增的条目——Profile 就会声称
     * 描述的是 A，实际描述的却是 B。
     */
    @Test
    void readsOnlyTheRequestedRevisionEvenAfterHeadMoves() throws Exception {
        Fixture fixture = createRepository("pinned-revision");
        WorkspaceRef workspace = workspaceRef(fixture);

        String analyzedRevision = adapter.headRevision(workspace);

        writeFile(fixture.path(), "README.md", "# 后来的版本\n");
        writeFile(fixture.path(), "added.txt", "之后新增的文件\n");
        runGit(fixture.path(), "add", "-A");
        commit(fixture.path(), "later");

        assertNotEquals(analyzedRevision, adapter.headRevision(workspace), "测试前提：HEAD 已经移动");

        assertEquals(README_CONTENT, adapter.readFile(workspace, analyzedRevision, "README.md"),
                "带着 A 读取必须返回 A 的内容，而不是后来提交的 B");
        assertFalse(
                paths(adapter.listEntries(workspace, analyzedRevision, "", 1)).contains("added.txt"),
                "B 新增的条目不得出现在 A 的目录结构中");

        assertEquals("# 后来的版本\n",
                adapter.readFile(workspace, adapter.headRevision(workspace), "README.md"),
                "两个版本的内容确实不同，上面的断言不是碰巧成立");
    }

    /**
     * revision 必须是已解析的完整 commit id：可移动的引用名（HEAD / 分支名）与缩写的 id
     * 都被拒绝，因为它们在不同时刻会指向不同 commit。
     */
    @Test
    void rejectsMutableOrAbbreviatedRevisionIdentifiers() throws Exception {
        Fixture fixture = createRepository("revision-identifiers");
        WorkspaceRef workspace = workspaceRef(fixture);
        String abbreviated = fixture.revision().substring(0, 7);

        assertThrows(IllegalArgumentException.class,
                () -> adapter.readFile(workspace, "HEAD", "README.md"));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.listEntries(workspace, "HEAD", "", 1));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.readFile(workspace, "main", "README.md"));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.readFile(workspace, abbreviated, "README.md"));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.readFile(workspace, "0".repeat(40), "README.md"));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.readFile(workspace, null, "README.md"));

        assertEquals(README_CONTENT, adapter.readFile(workspace, fixture.revision(), "README.md"),
                "完整的 commit id 仍然可用");
    }

    @Test
    void rejectsLocationThatIsNotAGitRepository() throws Exception {
        Path notARepository = BASE_DIRECTORY.resolve("plain-directory");
        Files.createDirectories(notARepository);
        writeFile(notARepository, "README.md", README_CONTENT);
        WorkspaceRef workspace = new WorkspaceRef(notARepository.toString());

        assertFalse(adapter.isReadableRepository(workspace));

        assertThrows(WorkspaceException.class, () -> adapter.headRevision(workspace));
        // 本测试只关心「位置不可读」，断言与传入的 revision 无关
        assertThrows(WorkspaceException.class, () -> adapter.listEntries(workspace, ANY_REVISION, "", 1));
        assertThrows(WorkspaceException.class, () -> adapter.readFile(workspace, ANY_REVISION, "README.md"));
    }

    @Test
    void rejectsMissingLocation() {
        WorkspaceRef workspace =
                new WorkspaceRef(BASE_DIRECTORY.resolve("does-not-exist").toString());

        assertFalse(adapter.isReadableRepository(workspace));
        assertThrows(WorkspaceException.class, () -> adapter.headRevision(workspace));
    }

    /**
     * 指向 Repository 内部的子目录不算一个 Repository：否则读取范围会与用户指定的位置
     * 不一致，而分析结果仍然会声称分析的是那个位置。
     */
    @Test
    void rejectsSubdirectoryInsideRepository() throws Exception {
        Fixture fixture = createRepository("subdirectory");
        WorkspaceRef subdirectory = new WorkspaceRef(fixture.path().resolve("src").toString());

        assertFalse(adapter.isReadableRepository(subdirectory));
        assertThrows(WorkspaceException.class, () -> adapter.headRevision(subdirectory));
    }

    /**
     * 空仓库仍然是可读取的 Repository，但没有 commit 就无法确定 analyzedRevision，
     * 必须明确失败，而不是发明一个特殊 revision。
     */
    @Test
    void failsClearlyForRepositoryWithoutAnyCommit() throws Exception {
        Path repository = BASE_DIRECTORY.resolve("empty");
        Files.createDirectories(repository);
        runGit(repository, "init", "-q", "--initial-branch=main");
        WorkspaceRef workspace = new WorkspaceRef(repository.toString());

        assertTrue(adapter.isReadableRepository(workspace));

        assertThrows(WorkspaceException.class, () -> adapter.headRevision(workspace));
    }

    /**
     * 路径不得越过 Repository 边界：仓库外的文件即使真实存在也不能通过 Workspace 读到。
     */
    @Test
    void refusesPathsOutsideRepositoryBoundary() throws Exception {
        Fixture fixture = createRepository("boundary");
        WorkspaceRef workspace = workspaceRef(fixture);

        Path outside = fixture.path().getParent().resolve("outside.txt");
        writeFile(fixture.path().getParent(), "outside.txt", "仓库外的内容\n");
        assertTrue(Files.exists(outside), "测试前提：仓库外确实存在这个文件");

        assertThrows(IllegalArgumentException.class, () -> adapter.readFile(workspace, fixture.revision(), "../outside.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.readFile(workspace, fixture.revision(), "src/../../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> adapter.readFile(workspace, fixture.revision(), "/etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.readFile(workspace, fixture.revision(), "C:\\Windows\\win.ini"));
        assertThrows(IllegalArgumentException.class, () -> adapter.listEntries(workspace, fixture.revision(), "../", 1));
    }

    @Test
    void rejectsUnknownOrDirectoryPathsInsideRepository() throws Exception {
        Fixture fixture = createRepository("unknown-paths");
        WorkspaceRef workspace = workspaceRef(fixture);

        assertThrows(WorkspaceException.class, () -> adapter.readFile(workspace, fixture.revision(), "missing.txt"));
        assertThrows(WorkspaceException.class, () -> adapter.readFile(workspace, fixture.revision(), "src"),
                "目录不是文件");

        assertThrows(IllegalArgumentException.class, () -> adapter.listEntries(workspace, fixture.revision(), "", 0));
        assertThrows(IllegalArgumentException.class, () -> adapter.listEntries(workspace, fixture.revision(), "", -1));
    }

    @Test
    void rejectsRelativeWorkspaceLocation() {
        WorkspaceRef relative = new WorkspaceRef("relative/repository");

        assertThrows(IllegalArgumentException.class, () -> adapter.isReadableRepository(relative));
        assertThrows(IllegalArgumentException.class, () -> adapter.headRevision(relative));
    }

    /**
     * 只读边界：所有读取调用前后，源 Repository 的文件内容、大小、修改时间都必须不变，
     * 包括 {@code .git} 内部状态。
     */
    @Test
    void doesNotModifySourceRepository() throws Exception {
        Fixture fixture = createRepository("read-only");
        WorkspaceRef workspace = workspaceRef(fixture);
        Map<String, String> before = snapshot(fixture.path());

        adapter.isReadableRepository(workspace);
        adapter.headRevision(workspace);
        adapter.listEntries(workspace, fixture.revision(), "", 2);
        adapter.listEntries(workspace, fixture.revision(), "src", 1);
        adapter.readFile(workspace, fixture.revision(), "README.md");

        assertEquals(before, snapshot(fixture.path()),
                "Repository Analysis 必须保持只读：源码与 Git 状态都不得被修改");
    }

    /**
     * 非 UTF-8 / 二进制内容不得让读取崩溃：Port 返回 {@code String}，因此按 UTF-8
     * 宽松解码（无法解码的字节被替换），而不是抛出异常中断整个分析流程。
     */
    @Test
    void readsContentThatIsNotValidUtf8() throws Exception {
        Fixture fixture = createRepository("binary");
        WorkspaceRef workspace = workspaceRef(fixture);

        byte[] invalidUtf8 = {(byte) 0xFF, (byte) 0xFE, 0x00, 0x01, (byte) 0x80};
        Files.write(fixture.path().resolve("assets.bin"), invalidUtf8);
        runGit(fixture.path(), "add", "-A");
        commit(fixture.path(), "binary asset");

        // 该文件是在初次提交之后加进来的，因此必须用新的 revision 读取
        String content =
                adapter.readFile(workspace, adapter.headRevision(workspace), "assets.bin");

        assertFalse(content.isEmpty());
    }

    @Test
    void listsNoEntriesWhenStartPathDoesNotExist() throws Exception {
        Fixture fixture = createRepository("missing-start");

        assertEquals(List.of(), adapter.listEntries(workspaceRef(fixture), fixture.revision(), "absent", 1));
    }

    private record Fixture(Path path, String revision) {
    }

    private static WorkspaceRef workspaceRef(Fixture fixture) {
        return new WorkspaceRef(fixture.path().toString());
    }

    private static List<String> paths(List<WorkspaceEntry> entries) {
        return entries.stream().map(WorkspaceEntry::relativePath).toList();
    }

    private static WorkspaceEntry entry(List<WorkspaceEntry> entries, String relativePath) {
        return entries.stream()
                .filter(item -> item.relativePath().equals(relativePath))
                .findFirst()
                .orElseThrow();
    }

    /** 建一个真实 Git Repository 并提交一次，返回位置与提交后的 revision。 */
    private static Fixture createRepository(String name) throws Exception {
        Path repository = BASE_DIRECTORY.resolve(name);
        Files.createDirectories(repository);
        runGit(repository, "init", "-q", "--initial-branch=main");

        writeFile(repository, "README.md", README_CONTENT);
        writeFile(repository, "src/main/App.java", APP_CONTENT);
        writeFile(repository, "src/test/AppTest.java", APP_TEST_CONTENT);
        writeFile(repository, "docs/说明.md", DOC_CONTENT);
        writeFile(repository, ".gitignore", "ignored.txt\n");

        runGit(repository, "add", "-A");
        commit(repository, "initial");

        return new Fixture(repository, runGit(repository, "rev-parse", "HEAD").trim());
    }

    /**
     * 递归删除测试自己建立的 Repository。
     *
     * <p>先去掉只读属性再删：git 在 Windows 上把 {@code .git/objects} 下的文件标为只读，
     * 直接删会失败，残留物会让后续 {@code mvn clean} 删不掉 target。
     * {@code setWritable(true)} 在 Windows 上对应清除只读属性，在其他平台则设置写位。
     */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            // 逆序：先删文件与深层目录，最后才删目录本身
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        }
    }

    private static void writeFile(Path root, String relativePath, String content) throws IOException {
        Path target = root.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static void commit(Path repository, String message) throws Exception {
        runGit(repository, "-c", "user.email=test@delveforge.local",
                "-c", "user.name=DelveForge Test", "commit", "-q", "-m", message);
    }

    /**
     * 仓库全部文件的内容、大小与修改时间。
     *
     * <p>修改时间也参与比较：只比较内容会漏掉「被重写但内容相同」的写操作。
     */
    private static Map<String, String> snapshot(Path root) throws IOException {
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

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256 实现", e);
        }
    }

    /** 在测试里驱动真实的 git 命令，用于建立 fixture 与核对结果。 */
    private static String runGit(Path repository, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(arguments));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git 命令失败: " + command + "\n" + output);
        }
        return output;
    }
}
