package com.ayywl.delveforge.application.repositoryanalysis.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 验证 Repository 材料选取策略：确定、有界、路径来自真实读取。
 *
 * <p>这些上限与排除项是 Application 层的 MVP 策略，不是领域规则（见
 * {@link RepositoryAnalysisMaterialPolicy}），因此这里锁定的是当前行为，
 * 调整策略时这些断言应当随之更新。
 */
class RepositoryAnalysisMaterialCollectorTest {

    private static final WorkspaceRef WORKSPACE = new WorkspaceRef("E:/projects/legacy-tool");

    private static final String REVISION = "abc123";

    private final RecordingWorkspace workspace = new RecordingWorkspace();

    @Test
    void collectsFilesThatMatchThePolicy() {
        workspace.givenRevision(REVISION, Map.of(
                "README.md", "# tool",
                "src/main/App.java", "class App {}",
                "node_modules/left-pad/index.js", "module.exports = 1",
                "logo.png", "not really an image"));

        List<RepositorySourceFile> material = collect(policy(4, 10, 1000, 10_000));

        assertEquals(
                List.of("README.md", "src/main/App.java"),
                paths(material),
                "生成物目录与二进制文件不进入材料；顺序按相对路径升序");
        assertEquals("# tool", material.get(0).content());
    }

    /**
     * 超过单文件上限的文件被跳过而不是截断：半份内容会让模型基于不完整的信息下结论。
     * 它同时不应该被读取——上限必须约束实际读取量，而不只是进入材料的内容量。
     */
    @Test
    void skipsFilesLargerThanThePerFileLimit() {
        workspace.givenRevision(REVISION, Map.of(
                "big.xml", "x".repeat(50),
                "small.md", "ok"));

        List<RepositorySourceFile> material = collect(policy(4, 10, 10, 10_000));

        assertEquals(List.of("small.md"), paths(material));
        assertEquals(List.of("small.md"), workspace.readPaths(),
                "超限文件不得被读取");
    }

    /**
     * 大量超限文件不应被逐个读完：大小在列目录时就已经知道，取舍因此发生在读取之前。
     */
    @Test
    void doesNotReadAnyFileWhenAllExceedTheLimit() {
        Map<String, String> files = new LinkedHashMap<>();
        for (int index = 0; index < 1_000; index++) {
            files.put("file-" + index + ".txt", "x".repeat(11));
        }
        workspace.givenRevision(REVISION, files);

        assertThrows(RepositoryNotAnalyzableException.class,
                () -> collect(policy(4, 1, 10, 10)));
        assertEquals(List.of(), workspace.readPaths(), "一个超限文件都不应该被读取");
    }

    /**
     * 单个巨大文件同样不应该被完整读进内存：它的大小在读取之前就能判断。
     */
    @Test
    void doesNotReadASingleHugeFile() {
        workspace.givenRevision(REVISION, Map.of(
                "huge.log", "x".repeat(500_000),
                "small.md", "ok"));

        List<RepositorySourceFile> material = collect(policy(4, 10, 1_000, 10_000));

        assertEquals(List.of("small.md"), paths(material));
        assertEquals(List.of("small.md"), workspace.readPaths(),
                "巨大文件不得被完整读取");
    }

    @Test
    void stopsAtTheFileCountLimit() {
        workspace.givenRevision(REVISION, Map.of(
                "a.md", "a", "b.md", "b", "c.md", "c", "d.md", "d"));

        List<RepositorySourceFile> material = collect(policy(4, 2, 100, 10_000));

        assertEquals(List.of("a.md", "b.md"), paths(material),
                "按相对路径升序取前若干个，结果可复现");
    }

    @Test
    void stopsWhenTheTotalLimitWouldBeExceeded() {
        workspace.givenRevision(REVISION, Map.of(
                "a.md", "aaaaaaaa", "b.md", "bbbbbbbb", "c.md", "cccccccc"));

        List<RepositorySourceFile> material = collect(policy(4, 10, 8, 12));

        assertEquals(List.of("a.md"), paths(material),
                "加上下一个文件会超出总上限时停止继续收集");
        assertEquals(List.of("a.md"), workspace.readPaths(),
                "停止之后不再读取后续文件");
    }

    @Test
    void onlyReadsFilesWithinTheConfiguredDepth() {
        workspace.givenRevision(REVISION, Map.of(
                "a.md", "a",
                "one/b.md", "b",
                "one/two/c.md", "c"));

        List<RepositorySourceFile> material = collect(policy(2, 10, 100, 10_000));

        assertEquals(List.of("a.md", "one/b.md"), paths(material));
    }

    /**
     * 只读列目录得到的条目：材料里不会出现没有真实列出的路径。
     */
    @Test
    void readsExactlyThePathsItListed() {
        workspace.givenRevision(REVISION, Map.of("a.md", "a", "dir/b.md", "b"));

        collect(policy(4, 10, 100, 10_000));

        assertEquals(List.of("a.md", "dir/b.md"), workspace.readPaths());
        assertTrue(workspace.readRevisions().stream().allMatch(REVISION::equals),
                "所有读取都使用调用方给的 revision");
    }

    /**
     * 策略下没有任何文件可读时明确失败：空材料不是「分析出空结论」，
     * 而是根本无法分析，因此不能交给下游去问模型。
     */
    @Test
    void failsWhenNothingMatchesThePolicy() {
        workspace.givenRevision(REVISION, Map.of("logo.png", "image"));

        assertThrows(RepositoryNotAnalyzableException.class,
                () -> collect(policy(4, 10, 100, 10_000)));
        assertEquals(List.of(), workspace.readPaths());
    }

    @Test
    void rejectsInvalidPolicy() {
        assertThrows(IllegalArgumentException.class, () -> policy(0, 10, 100, 10_000));
        assertThrows(IllegalArgumentException.class, () -> policy(4, 0, 100, 10_000));
        assertThrows(IllegalArgumentException.class, () -> policy(4, 10, 0, 10_000));
        assertThrows(IllegalArgumentException.class, () -> policy(4, 10, 100, 50));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryAnalysisMaterialPolicy(
                        4, 10, 100, 10_000, null, Set.of()));
    }

    @Test
    void rejectsMissingDependencies() {
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryAnalysisMaterialCollector(
                        null, RepositoryAnalysisMaterialPolicy.mvpDefault()));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryAnalysisMaterialCollector(workspace, null));
    }

    private List<RepositorySourceFile> collect(RepositoryAnalysisMaterialPolicy policy) {
        return new RepositoryAnalysisMaterialCollector(workspace, policy)
                .collect(WORKSPACE, REVISION);
    }

    private static List<String> paths(List<RepositorySourceFile> material) {
        return material.stream().map(RepositorySourceFile::relativePath).toList();
    }

    private static RepositoryAnalysisMaterialPolicy policy(
            int maxDepth, int maxFiles, int maxFileCharacters, int maxTotalCharacters) {
        return new RepositoryAnalysisMaterialPolicy(
                maxDepth, maxFiles, maxFileCharacters, maxTotalCharacters,
                Set.of("node_modules", "target"),
                Set.of(".png"));
    }
}
