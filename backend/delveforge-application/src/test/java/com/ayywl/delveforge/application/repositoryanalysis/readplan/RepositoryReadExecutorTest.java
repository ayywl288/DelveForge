package com.ayywl.delveforge.application.repositoryanalysis.readplan;

import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.BLOG_CONTROLLER;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.MVC_CONFIG;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.POM;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.REVISION;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.SCHEMA;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.SHOP_ENTITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.workspace.WorkspaceEntry;
import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证按计划真实读取，以及执行期按**实际内容**重新核对尺寸。
 *
 * <p>规划依据的是 blob metadata，而 metadata 不一定等于真实内容的字节数。因此这里刻意让
 * 两者可以不一致：计划里的尺寸来自夹具，实际读到的内容长度由测试指定。
 */
class RepositoryReadExecutorTest {

    private static final WorkspaceRef WORKSPACE = new WorkspaceRef("E:/projects/demo");

    private final RepositoryMap map = ReadPlanFixtures.map();

    private final ContentWorkspace workspace = new ContentWorkspace();

    @Test
    void readsEveryPlannedFileInPlanOrder() {
        workspace.given(path(MVC_CONFIG), content(10));
        workspace.given(path(POM), content(20));

        RepositoryReadResult result = execute(generous(), generous(),
                List.of(MVC_CONFIG, POM), List.of());

        assertEquals(List.of(path(MVC_CONFIG), path(POM)), pathsOf(result.material()));
        assertTrue(result.skipped().isEmpty());
    }

    /**
     * 实际内容超过单文件上限时剔除它，并继续处理后面的文件。
     */
    @Test
    void skipsContentThatExceedsTheLimitAndKeepsReading() {
        workspace.given(path(MVC_CONFIG), content(500));
        workspace.given(path(POM), content(10));

        RepositoryReadResult result = execute(
                new RepositoryMaterialBudget(10, 100, 10_000),
                new RepositoryMaterialBudget(10, 100, 10_000),
                List.of(MVC_CONFIG, POM), List.of());

        assertEquals(List.of(path(POM)), pathsOf(result.material()));
        assertEquals(List.of(path(MVC_CONFIG)), skippedPaths(result));
        assertEquals(RepositoryReadSkipReason.SELECTED_BUT_TOO_LARGE,
                result.skipped().get(0).reason());
        assertEquals(1, result.tooLargeCount());
    }

    /**
     * 实际内容放不进剩余总量时剔除它，同一条通道里更小的文件仍然会被读。
     */
    @Test
    void skipsContentThatDoesNotFitAndStillTakesSmallerOnes() {
        workspace.given(path(MVC_CONFIG), content(60));
        workspace.given(path(POM), content(60));
        workspace.given(path(SCHEMA), content(20));

        RepositoryReadResult result = execute(
                new RepositoryMaterialBudget(10, 100, 100),
                new RepositoryMaterialBudget(10, 100, 100),
                List.of(MVC_CONFIG, POM, SCHEMA), List.of());

        assertEquals(List.of(path(MVC_CONFIG), path(SCHEMA)), pathsOf(result.material()));
        assertEquals(List.of(path(POM)), skippedPaths(result));
        assertEquals(1, result.totalBudgetExceededCount());
    }

    /**
     * 尺寸按**真实内容的 UTF-8 字节**算，不是字符数。
     *
     * <p>这段内容有 30 个字符，却占 90 个字节。按字符数衡量会把它判成 30，于是放行——
     * 而它实际是上限的两倍还多。因此下面的上限取 89：字节口径拒绝它，字符口径接受它。
     */
    @Test
    void measuresContentInUtf8BytesNotCharacters() {
        workspace.given(path(MVC_CONFIG), chineseContent(30));

        RepositoryReadResult result = execute(
                new RepositoryMaterialBudget(10, 89, 10_000),
                new RepositoryMaterialBudget(10, 89, 10_000),
                List.of(MVC_CONFIG), List.of());

        assertTrue(result.material().isEmpty(),
                "90 字节的内容不得在 89 字节的上限下被读入");
        assertEquals(List.of(path(MVC_CONFIG)), skippedPaths(result));
        assertEquals(1, result.tooLargeCount());
    }

    /**
     * 恰好等于上限的内容是可以读的：上限是「最多多少」，不是「必须小于多少」。
     */
    @Test
    void acceptsContentExactlyAtTheByteLimit() {
        workspace.given(path(MVC_CONFIG), chineseContent(30));

        RepositoryReadResult result = execute(
                new RepositoryMaterialBudget(10, 90, 10_000),
                new RepositoryMaterialBudget(10, 90, 10_000),
                List.of(MVC_CONFIG), List.of());

        assertEquals(List.of(path(MVC_CONFIG)), pathsOf(result.material()));
        assertTrue(result.skipped().isEmpty());
    }

    /**
     * 总预算同样按 UTF-8 字节累计：两份 90 字节的内容放不进 150 字节的总量。
     *
     * <p>按字符累计会得到 30 + 30 = 60，两份都会被读进来。
     */
    @Test
    void accountsForTheTotalBudgetInUtf8Bytes() {
        workspace.given(path(MVC_CONFIG), chineseContent(30));
        workspace.given(path(POM), chineseContent(30));

        RepositoryReadResult result = execute(
                new RepositoryMaterialBudget(10, 150, 150),
                new RepositoryMaterialBudget(10, 150, 150),
                List.of(MVC_CONFIG, POM), List.of());

        assertEquals(List.of(path(MVC_CONFIG)), pathsOf(result.material()));
        assertEquals(List.of(path(POM)), skippedPaths(result));
        assertEquals(1, result.totalBudgetExceededCount());
    }

    /**
     * 两条通道各记各的账：Foundation 用满自己的总量不会让定向源码少读。
     */
    @Test
    void budgetsAreIndependentPerLane() {
        workspace.given(path(MVC_CONFIG), content(100));
        workspace.given(path(POM), content(100));
        workspace.given(path(BLOG_CONTROLLER), content(100));

        RepositoryReadResult result = execute(
                new RepositoryMaterialBudget(10, 100, 100),
                new RepositoryMaterialBudget(10, 100, 100),
                List.of(MVC_CONFIG, POM),
                List.of(BLOG_CONTROLLER));

        assertEquals(List.of(path(MVC_CONFIG)), pathsOf(result.material()).subList(0, 1));
        assertTrue(pathsOf(result.material()).contains(path(BLOG_CONTROLLER)),
                "定向源码应当按自己的预算读出，不受 Foundation 影响: " + pathsOf(result.material()));
        assertEquals(1, result.totalBudgetExceededCount());
    }

    /**
     * 总预算耗尽时不会向另一条通道借：即使另一条通道还有余额，这条通道也不再读。
     */
    @Test
    void neverBorrowsBudgetFromTheOtherLane() {
        workspace.given(path(MVC_CONFIG), content(100));
        workspace.given(path(POM), content(100));
        workspace.given(path(SCHEMA), content(100));
        workspace.given(path(BLOG_CONTROLLER), content(100));

        RepositoryReadResult result = execute(
                new RepositoryMaterialBudget(10, 150, 150),
                new RepositoryMaterialBudget(10, 1_000, 1_000),
                List.of(MVC_CONFIG, POM, SCHEMA),
                List.of(BLOG_CONTROLLER));

        assertEquals(List.of(path(MVC_CONFIG), path(BLOG_CONTROLLER)), pathsOf(result.material()),
                "Foundation 超预算的候选不得占用定向源码的余额");
        assertEquals(2, result.skipped().size());
    }

    /**
     * 只读计划里的文件，不多读一个，也不少读一个。
     */
    @Test
    void readsExactlyThePlannedFiles() {
        workspace.given(path(MVC_CONFIG), content(10));
        workspace.given(path(BLOG_CONTROLLER), content(10));
        // 在 Workspace 里存在、但不在计划里
        workspace.given(path(SHOP_ENTITY), content(10));

        execute(generous(), generous(), List.of(MVC_CONFIG), List.of(BLOG_CONTROLLER));

        assertEquals(List.of(path(MVC_CONFIG), path(BLOG_CONTROLLER)), workspace.readPaths(),
                "执行器没有选择权，只能读计划里的文件");
        assertTrue(workspace.readRevisions().stream().allMatch(REVISION::equals),
                "所有读取都必须带着同一个 revision: " + workspace.readRevisions());
    }

    /**
     * 读取失败**不是**可以静默跳过的候选：它意味着环境出了问题，整次分析应当失败。
     */
    @Test
    void propagatesReadFailureInsteadOfSkipping() {
        workspace.given(path(MVC_CONFIG), content(10));
        workspace.failWith(new WorkspaceException("读取失败"));

        assertThrows(WorkspaceException.class,
                () -> execute(generous(), generous(), List.of(MVC_CONFIG), List.of()));
    }

    @Test
    void rejectsNullArguments() {
        RepositoryReadPlan plan = RepositoryReadPlan.of(REVISION, List.of(), List.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> executor().execute(null, WORKSPACE));
        assertThrows(IllegalArgumentException.class, () -> executor().execute(plan, null));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryReadExecutor(null, generous(), generous()));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryReadExecutor(workspace, null, generous()));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryReadExecutor(workspace, generous(), null));
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    private static RepositoryMaterialBudget generous() {
        return new RepositoryMaterialBudget(10, 1_000, 10_000);
    }

    private RepositoryReadExecutor executor() {
        return new RepositoryReadExecutor(workspace, generous(), generous());
    }

    /** 编号对应的真实路径——路径来自夹具的 Map，不是硬编码的字符串。 */
    private String path(int position) {
        return ReadPlanFixtures.entry(map, position).relativePath();
    }

    private RepositoryReadResult execute(RepositoryMaterialBudget foundationBudget,
                                         RepositoryMaterialBudget targetedBudget,
                                         List<Integer> foundationPositions,
                                         List<Integer> targetedPositions) {
        RepositoryReadPlan plan = RepositoryReadPlan.of(
                REVISION,
                entriesOf(foundationPositions),
                entriesOf(targetedPositions),
                List.of());
        return new RepositoryReadExecutor(workspace, foundationBudget, targetedBudget)
                .execute(plan, WORKSPACE);
    }

    private List<RepositoryMapEntry> entriesOf(List<Integer> positions) {
        List<RepositoryMapEntry> entries = new ArrayList<>(positions.size());
        for (int position : positions) {
            entries.add(ReadPlanFixtures.entry(map, position));
        }
        return List.copyOf(entries);
    }

    /** 恰好 {@code utf8Bytes} 个字节的 ASCII 内容。 */
    private static String content(int utf8Bytes) {
        return "x".repeat(utf8Bytes);
    }

    /**
     * {@code characters} 个汉字的内容：每个汉字占 3 个 UTF-8 字节。
     *
     * <p>字符数与字节数在这里刻意不同，用来把「按字节」与「按字符」两种口径分开。
     */
    private static String chineseContent(int characters) {
        return "中".repeat(characters);
    }

    private static List<String> pathsOf(List<RepositorySourceFile> material) {
        return material.stream().map(RepositorySourceFile::relativePath).toList();
    }

    private static List<String> skippedPaths(RepositoryReadResult result) {
        return result.skipped().stream()
                .map(candidate -> candidate.entry().relativePath())
                .toList();
    }

    /**
     * 只提供按路径读取的替身。
     *
     * <p>列目录与解析 HEAD 会直接失败——执行器本来就只该读计划里的文件，
     * 若它去做了别的事，测试会当场发现。
     */
    private static final class ContentWorkspace implements WorkspaceReadPort {

        private final Map<String, String> files = new LinkedHashMap<>();

        private final List<String> readPaths = new ArrayList<>();

        private final List<String> readRevisions = new ArrayList<>();

        private RuntimeException failure;

        void given(String relativePath, String content) {
            files.put(relativePath, content);
        }

        void failWith(RuntimeException exception) {
            this.failure = exception;
        }

        List<String> readPaths() {
            return List.copyOf(readPaths);
        }

        List<String> readRevisions() {
            return List.copyOf(readRevisions);
        }

        @Override
        public boolean isReadableRepository(WorkspaceRef workspace) {
            throw new AssertionError("执行读取计划不需要判断仓库可读性");
        }

        @Override
        public List<WorkspaceEntry> listEntries(
                WorkspaceRef workspace, String revision, String relativePath, int maxDepth) {
            throw new AssertionError("执行读取计划不需要列目录");
        }

        @Override
        public String readFile(WorkspaceRef workspace, String revision, String relativePath) {
            readRevisions.add(revision);
            readPaths.add(relativePath);
            if (failure != null) {
                throw failure;
            }
            String content = files.get(relativePath);
            if (content == null) {
                throw new WorkspaceException("文件不存在: " + relativePath);
            }
            return content;
        }

        @Override
        public String headRevision(WorkspaceRef workspace) {
            throw new AssertionError("执行读取计划不需要解析 HEAD");
        }
    }
}
