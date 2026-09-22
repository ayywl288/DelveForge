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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** 测试仓库的建立与清理（绝对路径：Adapter 只接受绝对位置）。 */
    private static final GitTestRepositories REPOSITORIES = new GitTestRepositories();

    private static final String README_CONTENT = "# legacy tool\n";

    private static final String APP_CONTENT = "public class App {}\n";

    private static final String APP_TEST_CONTENT = "public class AppTest {}\n";

    private static final String DOC_CONTENT = "# 说明\n\n这是一个遗留工具。\n";

    /** 用于「断言与 revision 无关」的场景：位置本身就读不了时，读操作会在它之前失败。 */
    private static final String ANY_REVISION = "0".repeat(40);

    private final GitWorkspaceAdapter adapter = new GitWorkspaceAdapter();

    @AfterAll
    static void deleteTestRepositories() throws IOException {
        REPOSITORIES.deleteAll();
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

        GitTestRepositories.writeFile(fixture.path(), "README.md", "# 第二个版本\n");
        GitTestRepositories.runGit(fixture.path(), "add", "-A");
        GitTestRepositories.commit(fixture.path(), "second");

        String afterCommit = adapter.headRevision(workspace);
        assertEquals(GitTestRepositories.runGit(fixture.path(), "rev-parse", "HEAD").trim(), afterCommit);
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

        GitTestRepositories.writeFile(fixture.path(), "README.md", "# 尚未提交的修改\n");
        GitTestRepositories.writeFile(fixture.path(), "untracked.java", "class Untracked {}\n");
        GitTestRepositories.writeFile(fixture.path(), "ignored.txt", "ignored content\n");

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

        GitTestRepositories.writeFile(fixture.path(), "README.md", "# 后来的版本\n");
        GitTestRepositories.writeFile(fixture.path(), "added.txt", "之后新增的文件\n");
        GitTestRepositories.runGit(fixture.path(), "add", "-A");
        GitTestRepositories.commit(fixture.path(), "later");

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
        Path notARepository = REPOSITORIES.directory("plain-directory");
        Files.createDirectories(notARepository);
        GitTestRepositories.writeFile(notARepository, "README.md", README_CONTENT);
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
                new WorkspaceRef(REPOSITORIES.directory("does-not-exist").toString());

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
        Path repository = REPOSITORIES.createEmpty("empty");
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
        GitTestRepositories.writeFile(fixture.path().getParent(), "outside.txt", "仓库外的内容\n");
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

    /**
     * 相对路径不是一个可用的位置：可读性检查回答「不能读」，而不是断言调用方写错了参数。
     *
     * <p>调用方通常只是拿着一个已登记资产的 location 来问，因此这里不能抛异常——
     * 那会让「资产位置不可分析」在接口层变成「请求不合法」。
     * 读取操作仍然要求可解析的位置，那里照旧拒绝。
     */
    @Test
    void reportsRelativeWorkspaceLocationAsNotReadable() {
        WorkspaceRef relative = new WorkspaceRef("relative/repository");

        assertFalse(adapter.isReadableRepository(relative));
        assertThrows(IllegalArgumentException.class, () -> adapter.headRevision(relative));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.listEntries(relative, "0".repeat(40), "", 1));
    }

    /**
     * 只读边界：所有读取调用前后，源 Repository 的文件内容、大小、修改时间都必须不变，
     * 包括 {@code .git} 内部状态。
     */
    @Test
    void doesNotModifySourceRepository() throws Exception {
        Fixture fixture = createRepository("read-only");
        WorkspaceRef workspace = workspaceRef(fixture);
        Map<String, String> before = GitTestRepositories.snapshot(fixture.path());

        adapter.isReadableRepository(workspace);
        adapter.headRevision(workspace);
        adapter.listEntries(workspace, fixture.revision(), "", 2);
        adapter.listEntries(workspace, fixture.revision(), "src", 1);
        adapter.readFile(workspace, fixture.revision(), "README.md");

        assertEquals(before, GitTestRepositories.snapshot(fixture.path()),
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
        GitTestRepositories.runGit(fixture.path(), "add", "-A");
        GitTestRepositories.commit(fixture.path(), "binary asset");

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
        Map<String, String> files = new LinkedHashMap<>();
        files.put("README.md", README_CONTENT);
        files.put("src/main/App.java", APP_CONTENT);
        files.put("src/test/AppTest.java", APP_TEST_CONTENT);
        files.put("docs/说明.md", DOC_CONTENT);
        files.put(".gitignore", "ignored.txt\n");

        Path repository = REPOSITORIES.createCommitted(name, files);
        return new Fixture(repository, GitTestRepositories.headRevision(repository));
    }
}
