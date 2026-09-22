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
 * 验证 Repository 材料选取策略：确定、有界、有代表性。
 *
 * <p>这些上限与选择规则是 Application 层的 MVP 策略，不是领域规则（见
 * {@link RepositoryAnalysisMaterialPolicy}），因此这里锁定的是当前行为，
 * 调整策略时这些断言应当随之更新。
 *
 * <p>其中「深层源码可见」与「浅层文档不挤占源码」两类断言来自真实仓库 Smoke Test 暴露的
 * 问题：当时材料里一个 Java 源文件都没有。
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
                "config/app.yml", "server: 1",
                "pom.xml", "<project/>",
                "node_modules/left-pad/index.js", "module.exports = 1",
                "logo.png", "not really an image"));

        List<RepositorySourceFile> material = collect(policy(10, 1000, 10_000));

        assertEquals(
                List.of("pom.xml", "src/main/App.java", "config/app.yml", "README.md"),
                paths(material),
                "按类别轮转：元数据 → 源码 → 配置 → 文档；生成物目录与二进制文件不进入材料");
    }

    /**
     * 回归：正常项目的包路径很深（{@code src/main/java/com/example/...}），
     * 源码不能因为目录层级而被整体排除。
     *
     * <p>真实仓库 Smoke Test 中，96 个 Java 文件都位于第 7–8 层，旧策略只读前 4 层，
     * 于是一次分析里连一个源文件都没有。
     */
    @Test
    void selectsSourceFilesFromDeepPackageDirectories() {
        workspace.givenRevision(REVISION, Map.of(
                "pom.xml", "<project/>",
                "src/main/java/com/example/product/controller/ShopController.java",
                "class ShopController {}",
                "src/main/java/com/example/product/service/impl/ShopServiceImpl.java",
                "class ShopServiceImpl {}"));

        List<RepositorySourceFile> material = collect(policy(10, 1000, 10_000));

        assertEquals(
                List.of(
                        "pom.xml",
                        "src/main/java/com/example/product/controller/ShopController.java",
                        "src/main/java/com/example/product/service/impl/ShopServiceImpl.java"),
                paths(material),
                "第 7–8 层的源码必须与元数据一起进入材料");
    }

    /**
     * 回归：大量浅层文档不能把源码挤出去——它们属于不同类别，各自排队取。
     */
    @Test
    void doesNotLetShallowDocumentationStarveSourceCode() {
        Map<String, String> files = new LinkedHashMap<>();
        for (int index = 0; index < 50; index++) {
            files.put("docs/" + index + ".md", "# doc " + index);
        }
        files.put("src/main/java/com/example/App.java", "class App {}");
        workspace.givenRevision(REVISION, files);

        List<RepositorySourceFile> material = collect(policy(5, 1000, 10_000));

        assertEquals(5, material.size(), "maxFiles 仍然有效");
        assertTrue(paths(material).contains("src/main/java/com/example/App.java"),
                "存在预算时源码必须被选中，而不是被浅层文档占满: " + paths(material));
        assertEquals(1, paths(material).stream().filter(p -> p.endsWith(".java")).count(),
                "只取一个源码文件：它不是唯一类别，也不能独占预算");
    }

    /**
     * 元数据 / 源码 / 配置 / 文档 / 脚本都能在同一个仓库中一起出现。
     */
    @Test
    void coversMetadataSourceConfigurationDocumentationAndScriptsTogether() {
        workspace.givenRevision(REVISION, Map.of(
                "pom.xml", "<project/>",
                "src/main/java/com/example/App.java", "class App {}",
                "src/main/resources/application.yml", "server: 1",
                "docs/guide.md", "# guide",
                "scripts/run.sh", "echo run"));

        List<RepositorySourceFile> material = collect(policy(10, 1000, 10_000));

        assertEquals(
                List.of(
                        "pom.xml",
                        "src/main/java/com/example/App.java",
                        "src/main/resources/application.yml",
                        "docs/guide.md",
                        "scripts/run.sh"),
                paths(material));
    }

    /**
     * 同一个仓库、同一份策略 → 同样的材料与同样的顺序。
     */
    @Test
    void producesTheSameSelectionForTheSameRepository() {
        workspace.givenRevision(REVISION, Map.of(
                "pom.xml", "<project/>",
                "docs/a.md", "# a",
                "docs/b.md", "# b",
                "src/main/java/com/example/App.java", "class App {}",
                "src/main/resources/application.yml", "server: 1"));

        List<String> first = paths(collect(policy(3, 1000, 10_000)));
        List<String> second = paths(collect(policy(3, 1000, 10_000)));

        assertEquals(first, second);
        assertEquals(List.of("pom.xml", "src/main/java/com/example/App.java",
                "src/main/resources/application.yml"), first);
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

        List<RepositorySourceFile> material = collect(policy(10, 10, 10_000));

        assertEquals(List.of("small.md"), paths(material));
        assertEquals(List.of("small.md"), workspace.readPaths(), "超限文件不得被读取");
    }

    /**
     * 同一类别内出现超限文件时，跳过它并继续取该类别的下一个，而不是放弃整个类别。
     */
    @Test
    void skipsAnOversizedFileAndKeepsTakingFromItsCategory() {
        workspace.givenRevision(REVISION, Map.of(
                "src/main/java/com/example/Huge.java", "x".repeat(50),
                "src/main/java/com/example/Small.java", "tiny"));

        List<RepositorySourceFile> material = collect(policy(10, 10, 10_000));

        assertEquals(List.of("src/main/java/com/example/Small.java"), paths(material));
        assertEquals(List.of("src/main/java/com/example/Small.java"), workspace.readPaths(),
                "超限的源码文件不得被读取");
    }

    @Test
    void stopsAtTheFileCountLimit() {
        workspace.givenRevision(REVISION, Map.of(
                "a.md", "a", "b.md", "b", "c.md", "c", "d.md", "d"));

        List<RepositorySourceFile> material = collect(policy(2, 100, 10_000));

        assertEquals(List.of("a.md", "b.md"), paths(material),
                "同类别内按相对路径升序取前若干个，结果可复现");
    }

    @Test
    void stopsWhenTheTotalLimitWouldBeExceeded() {
        workspace.givenRevision(REVISION, Map.of(
                "a.md", "aaaaaaaa", "b.md", "bbbbbbbb", "c.md", "cccccccc"));

        List<RepositorySourceFile> material = collect(policy(10, 8, 12));

        assertEquals(List.of("a.md"), paths(material),
                "加上下一个文件会超出总上限时停止继续收集");
        assertEquals(List.of("a.md"), workspace.readPaths());
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
                () -> collect(policy(1, 10, 10)));
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

        List<RepositorySourceFile> material = collect(policy(10, 1_000, 10_000));

        assertEquals(List.of("small.md"), paths(material));
        assertEquals(List.of("small.md"), workspace.readPaths(), "巨大文件不得被完整读取");
    }

    /**
     * 只读列目录得到的条目：材料里不会出现没有真实列出的路径；
     * 每次读取都带着调用方给的 revision。
     */
    @Test
    void readsExactlyTheListedPathsAtTheGivenRevision() {
        workspace.givenRevision(REVISION, Map.of(
                "pom.xml", "<project/>", "src/main/java/com/example/App.java", "class App {}"));

        collect(policy(10, 100, 10_000));

        assertEquals(List.of("pom.xml", "src/main/java/com/example/App.java"),
                workspace.readPaths());
        assertTrue(workspace.readRevisions().stream().allMatch(REVISION::equals),
                "所有读取都使用调用方给的 revision");
        assertTrue(workspace.listedRevisions().stream().allMatch(REVISION::equals));
    }

    /**
     * 策略下没有任何文件可读时明确失败：空材料不是「分析出空结论」，
     * 而是根本无法分析，因此不能交给下游去问模型。
     */
    @Test
    void failsWhenNothingMatchesThePolicy() {
        workspace.givenRevision(REVISION, Map.of("logo.png", "image"));

        assertThrows(RepositoryNotAnalyzableException.class,
                () -> collect(policy(10, 100, 10_000)));
        assertEquals(List.of(), workspace.readPaths());
    }

    @Test
    void rejectsInvalidPolicy() {
        assertThrows(IllegalArgumentException.class, () -> policy(0, 100, 10_000));
        assertThrows(IllegalArgumentException.class, () -> policy(10, 0, 10_000));
        assertThrows(IllegalArgumentException.class, () -> policy(10, 100, 50));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryAnalysisMaterialPolicy(10, 100, 10_000, null, Set.of()));
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
            int maxFiles, int maxFileBytes, int maxTotalBytes) {
        return new RepositoryAnalysisMaterialPolicy(
                maxFiles, maxFileBytes, maxTotalBytes,
                Set.of("node_modules", "target"),
                Set.of(".png"));
    }
}
