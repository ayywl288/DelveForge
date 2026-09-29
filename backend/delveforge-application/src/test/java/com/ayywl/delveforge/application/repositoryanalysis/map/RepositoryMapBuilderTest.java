package com.ayywl.delveforge.application.repositoryanalysis.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 验证从完整已提交树建立 Repository Map。
 *
 * <p>这里的替身会在任何内容读取或 HEAD 解析时直接失败，因此「Map 的构建只看元数据」
 * 不是靠断言调用次数间接证明，而是任何一次越界都会让测试立刻红。
 */
class RepositoryMapBuilderTest {

    private static final WorkspaceRef WORKSPACE = new WorkspaceRef("E:/projects/demo");

    private static final String REVISION = "18e6b63cf218eca717cd00ecf4e3e0a12dccf5b4";

    @Test
    void buildsTheCompleteTreeInPathOrder() {
        RepositoryMap map = builderFor(realisticTree()).build(WORKSPACE, REVISION);

        assertEquals(List.of(
                        ".gitignore",
                        "docs/report.md",
                        "pom.xml",
                        "src/main/java/com/hmdp/HmDianPingApplication.java",
                        "src/main/java/com/hmdp/config/MvcConfig.java",
                        "src/main/java/com/hmdp/controller/ShopController.java",
                        "src/main/java/com/hmdp/service/impl/ShopServiceImpl.java",
                        "src/main/resources/application.yaml",
                        "src/test/java/com/hmdp/ShopTest.java"),
                pathsOf(map));
    }

    /**
     * 目录项不进入 Map：它是容器，不是材料。
     */
    @Test
    void excludesDirectoryEntries() {
        RecordingWorkspace workspace = new RecordingWorkspace()
                .givenDirectory("src")
                .givenDirectory("src/main")
                .givenFile("src/main/pom.xml", 1_024);

        RepositoryMap map = new RepositoryMapBuilder(workspace).build(WORKSPACE, REVISION);

        assertEquals(List.of("src/main/pom.xml"), pathsOf(map));
    }

    /**
     * Map 的构建不得读取任何文件内容。
     *
     * <p>这是本阶段成立的前提而非优化：一旦为了分类去读内容，「让整棵树可见」就退化成
     * 另一次有界采样。替身在这里会抛错，因此本用例同时证明了「没有读取」与「不会误读」。
     */
    @Test
    void neverReadsFileContents() {
        RecordingWorkspace workspace = realisticTree();

        RepositoryMap map = new RepositoryMapBuilder(workspace).build(WORKSPACE, REVISION);

        assertTrue(workspace.readFileRequests().isEmpty(),
                "构建 Map 时读取了文件内容: " + workspace.readFileRequests());
        assertFalse(map.isEmpty());
    }

    /**
     * Map 的构建不得自己解析 HEAD：revision 由调用方给出，并且就是 Map 记录下来的那个。
     */
    @Test
    void neverResolvesHeadAndKeepsTheGivenRevision() {
        RecordingWorkspace workspace = realisticTree();

        RepositoryMap map = new RepositoryMapBuilder(workspace).build(WORKSPACE, REVISION);

        assertEquals(0, workspace.headRevisionRequests(), "构建 Map 时解析了 HEAD");
        assertEquals(REVISION, map.analyzedRevision());
        assertEquals(List.of(REVISION), workspace.listedRevisions(),
                "列目录必须带着调用方给出的 revision，且只发生一次");
    }

    /**
     * 引用按位置分配，并且能被解析回正确的描述符。
     */
    @Test
    void assignsReferencesByPositionAndResolvesThem() {
        RepositoryMap map = builderFor(realisticTree()).build(WORKSPACE, REVISION);

        for (int index = 0; index < map.size(); index++) {
            RepositoryMapEntry entry = map.entries().get(index);
            RepositoryFileReference expected = RepositoryFileReference.of(index + 1);

            assertEquals(expected, entry.reference());
            assertEquals(Optional.of(entry), map.find(expected),
                    "引用必须解析回它自己的描述符: " + expected.value());
        }
        assertEquals(Optional.of("src/main/java/com/hmdp/controller/ShopController.java"),
                map.find(RepositoryFileReference.of(6)).map(RepositoryMapEntry::relativePath));
    }

    /**
     * 不属于本次 Map 的引用一律解析不到。
     *
     * <p>「越界即可判定地失败」是引用这个设计存在的全部理由，因此它是本测试的重点。
     */
    @Test
    void doesNotResolveReferencesThatAreNotInThisMap() {
        RepositoryMap map = builderFor(realisticTree()).build(WORKSPACE, REVISION);

        assertTrue(map.find(RepositoryFileReference.of(map.size() + 1)).isEmpty(),
                "超出范围的引用不得被解析");
        assertTrue(map.find(new RepositoryFileReference("RF-abc")).isEmpty(),
                "格式类似但不在本次 Map 中的引用不得被解析");
        assertTrue(map.find(null).isEmpty());
    }

    /**
     * 同一棵树、同一个 revision，两次构建得到逐项相同的 Map。
     *
     * <p>顺序本身也是 Map 的一部分：如果顺序会变，{@code RF-3} 会在两次分析之间指向
     * 不同的文件，引用也就不再是一个可判定的指针。
     */
    @Test
    void isDeterministicForTheSameTree() {
        RepositoryMap first = builderFor(realisticTree()).build(WORKSPACE, REVISION);
        RepositoryMap second = builderFor(realisticTree()).build(WORKSPACE, REVISION);

        assertEquals(first.analyzedRevision(), second.analyzedRevision());
        assertEquals(first.entries(), second.entries());
    }

    /**
     * 顺序由构建端确定，不依赖 Adapter 的返回顺序。
     *
     * <p>同一批条目以不同插入顺序交给替身，得到的 Map 必须完全一致。
     */
    @Test
    void ordersEntriesItselfRegardlessOfAdapterOrder() {
        RecordingWorkspace forward = new RecordingWorkspace()
                .givenFile("b.txt", 10)
                .givenFile("a/b.txt", 20)
                .givenFile("a.txt", 30);
        RecordingWorkspace reversed = new RecordingWorkspace()
                .givenFile("a.txt", 30)
                .givenFile("a/b.txt", 20)
                .givenFile("b.txt", 10);

        RepositoryMap first = new RepositoryMapBuilder(forward).build(WORKSPACE, REVISION);
        RepositoryMap second = new RepositoryMapBuilder(reversed).build(WORKSPACE, REVISION);

        assertEquals(List.of("a.txt", "a/b.txt", "b.txt"), pathsOf(first));
        assertEquals(first.entries(), second.entries());
    }

    /**
     * 空树得到空 Map：空是一份诚实的描述，不是失败。
     *
     * <p>「没有可分析材料时不该去问模型」属于读取阶段的判断，由材料收集器继续承担。
     */
    @Test
    void returnsAnEmptyMapForAnEmptyTree() {
        RepositoryMap map = builderFor(new RecordingWorkspace()).build(WORKSPACE, REVISION);

        assertTrue(map.isEmpty());
        assertEquals(0, map.size());
        assertEquals(REVISION, map.analyzedRevision());
    }

    @Test
    void classifiesEntriesWhileBuilding() {
        RepositoryMap map = builderFor(realisticTree()).build(WORKSPACE, REVISION);

        RepositoryMapEntry config = map.find(RepositoryFileReference.of(5)).orElseThrow();
        assertEquals(RepositoryMaterialKind.SOURCE_CODE, config.materialKind());
        assertEquals(List.of(RepositoryRoleHint.CONFIG_BOOTSTRAP), config.roleHints());

        RepositoryMapEntry service = map.find(RepositoryFileReference.of(7)).orElseThrow();
        assertEquals(RepositoryMaterialKind.SOURCE_CODE, service.materialKind());
        assertEquals(List.of(RepositoryRoleHint.APPLICATION_SERVICE), service.roleHints());

        assertEquals(RepositoryMaterialKind.BUILD_METADATA,
                map.entries().get(2).materialKind());
    }

    @Test
    void exposesCandidateLanes() {
        RepositoryMap map = builderFor(realisticTree()).build(WORKSPACE, REVISION);

        assertEquals(List.of(
                        ".gitignore",
                        "docs/report.md",
                        "pom.xml",
                        "src/main/java/com/hmdp/HmDianPingApplication.java",
                        "src/main/java/com/hmdp/config/MvcConfig.java",
                        "src/main/resources/application.yaml"),
                pathsOf(map.entriesIn(RepositoryCandidateLane.FOUNDATION)));

        assertEquals(List.of(
                        "src/main/java/com/hmdp/controller/ShopController.java",
                        "src/main/java/com/hmdp/service/impl/ShopServiceImpl.java"),
                pathsOf(map.entriesIn(RepositoryCandidateLane.SCOUT_SOURCE)));

        assertEquals(List.of("src/test/java/com/hmdp/ShopTest.java"),
                pathsOf(map.entriesIn(RepositoryCandidateLane.NONE)));
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(IllegalArgumentException.class, () -> new RepositoryMapBuilder(null));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryMapBuilder(new RecordingWorkspace()).build(null, REVISION));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryMapBuilder(new RecordingWorkspace()).build(WORKSPACE, null));
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    /** 一份覆盖各类材料的代表性树，插入顺序刻意打乱。 */
    private static RecordingWorkspace realisticTree() {
        return new RecordingWorkspace()
                .givenDirectory("src")
                .givenFile("pom.xml", 4_096)
                .givenFile("src/main/resources/application.yaml", 2_048)
                .givenFile("src/main/java/com/hmdp/controller/ShopController.java", 3_000)
                .givenFile("src/main/java/com/hmdp/service/impl/ShopServiceImpl.java", 12_000)
                .givenFile("src/main/java/com/hmdp/config/MvcConfig.java", 1_500)
                .givenFile("src/main/java/com/hmdp/HmDianPingApplication.java", 900)
                .givenFile("src/test/java/com/hmdp/ShopTest.java", 2_200)
                .givenFile("docs/report.md", 5_000)
                .givenFile(".gitignore", 120);
    }

    private static RepositoryMapBuilder builderFor(RecordingWorkspace workspace) {
        return new RepositoryMapBuilder(workspace);
    }

    private static List<String> pathsOf(RepositoryMap map) {
        return map.entries().stream().map(RepositoryMapEntry::relativePath).toList();
    }

    private static List<String> pathsOf(List<RepositoryMapEntry> entries) {
        return entries.stream().map(RepositoryMapEntry::relativePath).toList();
    }
}
