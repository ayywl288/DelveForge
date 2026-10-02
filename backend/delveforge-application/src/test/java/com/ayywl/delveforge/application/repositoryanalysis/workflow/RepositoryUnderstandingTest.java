package com.ayywl.delveforge.application.repositoryanalysis.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.workspace.WorkspaceEntry;
import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapBuilder;
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryMaterialBudget;
import com.ayywl.delveforge.application.repositoryanalysis.region.RegionHierarchyNotReducibleException;
import com.ayywl.delveforge.application.repositoryanalysis.region.RegionRecursionBudget;
import com.ayywl.delveforge.application.repositoryanalysis.region.ScoutCallBudget;
import com.ayywl.delveforge.application.repositoryanalysis.region.ScoutCallBudgetExceededException;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryScoutExtraction;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryScoutInputs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * 验证理解链路：Map → Scout（flat 或分层）→ 规划 → 读取，以及它的流程前置条件。
 *
 * <p>AI 与 Workspace 都在 Port 边界用替身替代（AGENTS.md §10.2、§10.6），
 * 因此不依赖 Spring 容器、不访问网络、不读取真实文件系统。
 *
 * <p>两条 Scout 路径都在这里验证：小仓库走 flat，超出预算走分层；两者之后汇入同一份
 * 读取规划与材料预算。
 */
class RepositoryUnderstandingTest {

    private static final WorkspaceRef WORKSPACE = new WorkspaceRef("E:/projects/demo");

    private static final String REVISION = "aaaa1111";

    private static final String POM = "pom.xml";

    private static final String APP = "src/main/App.java";

    private static final String OTHER = "src/main/Other.java";

    private static final RepositoryMaterialBudget GENEROUS_FOUNDATION =
            new RepositoryMaterialBudget(10, 1_000, 10_000);

    private static final RepositoryMaterialBudget GENEROUS_TARGETED =
            new RepositoryMaterialBudget(10, 1_000, 10_000);

    /** 目录按路径升序：RF-1 = pom.xml，RF-2 = App.java，RF-3 = Other.java。 */
    private static final String SCOUT_RESPONSE = """
            { "focusAreas": [
                { "label": "入口", "fileRefs": ["RF-2"] },
                { "label": "其余", "fileRefs": ["RF-3"] },
                { "label": "再来一次", "fileRefs": ["RF-2"] } ] }
            """;

    private final StubAiGateway aiGateway = new StubAiGateway();

    private final UnderstandingWorkspace workspace = new UnderstandingWorkspace();

    /** 分层场景的替身：Region Scout、File Scout 与最终分析都由它扮演。 */
    private final ScoutPathGateway gateway = new ScoutPathGateway(workspace::readCount);

    // ---------------------------------------------------------------------
    // flat 路径：目录装得下就走原有行为，完全旁路分层
    // ---------------------------------------------------------------------

    @Test
    void readsMaterialForThePlannedFiles() {
        seedSources();
        aiGateway.respondAll(SCOUT_RESPONSE);

        List<RepositorySourceFile> material = understanding().understand(WORKSPACE, REVISION);

        assertTrue(pathsOf(material).contains(APP), "Scout 指出的源码应当被读进来: " + pathsOf(material));
        assertTrue(pathsOf(material).contains(POM), "基础材料应当被读进来: " + pathsOf(material));
        assertTrue(pathsOf(material).contains(OTHER));
        assertEquals(1, aiGateway.callCount(), "理解阶段只调用一次模型——就是 File Scout");
    }

    /**
     * flat 路径只调用 File Scout：目录装得下时**不会**因为分层机制的存在而多问一次
     * Region Scout（ADR-0005：小仓库完全旁路分层）。
     */
    @Test
    void bypassesRegionScoutEntirelyWhenTheFlatCatalogFits() {
        seedSources();
        aiGateway.respondAll(SCOUT_RESPONSE);

        understanding().understand(WORKSPACE, REVISION);

        assertEquals(1, aiGateway.callCount());
        for (AiRequest request : aiGateway.requests()) {
            assertTrue(userMessage(request).contains("fileCatalog"),
                    "flat 路径发出的只应是 File Scout 请求");
        }
    }

    /**
     * 恰好等于上限的目录照常走 flat：上限是「最多多少」，不是「必须小于多少」。
     */
    @Test
    void acceptsACatalogExactlyAtTheLimit() {
        seedSources();
        aiGateway.respondAll(SCOUT_RESPONSE);
        int exact = catalogPayloadBytes(workspace);

        List<RepositorySourceFile> material =
                understandingWithCatalogLimit(exact).understand(WORKSPACE, REVISION);

        assertEquals(1, aiGateway.callCount(), "恰好等于上限时应当照常走 flat 路径");
        assertTrue(pathsOf(material).contains(APP),
                "Scout 指出的源码应当照常被读进来: " + pathsOf(material));
    }

    /**
     * Scout 拿到的是**全部**源码候选，而不是某一批。
     */
    @Test
    void scoutReceivesEveryScoutSourceDescriptor() {
        workspace.given(POM, "<project/>");
        workspace.given(APP, "class App {}");
        workspace.given(OTHER, "class Other {}");
        aiGateway.respondAll(SCOUT_RESPONSE);

        understanding().understand(WORKSPACE, REVISION);

        String catalog = userMessage(aiGateway.lastRequest());
        assertTrue(catalog.contains(APP), "源码候选必须全部进入 Scout 目录");
        assertTrue(catalog.contains(OTHER));
        assertTrue(!catalog.contains(POM), "基础材料不走 Scout 这条通道");
    }

    /**
     * 同一个 revision 从头用到尾：建 Map、读取都用它，而且**不解析 HEAD**。
     *
     * <p>替身在这三件事上会直接失败，因此本用例同时证明理解链路没有自己去找 revision。
     */
    @Test
    void usesTheGivenRevisionForEveryOperation() {
        seedSources();
        aiGateway.respondAll(SCOUT_RESPONSE);

        understanding().understand(WORKSPACE, REVISION);

        assertEquals(List.of(REVISION), workspace.listedRevisions());
        assertTrue(workspace.readRevisions().stream().allMatch(REVISION::equals),
                "所有读取都必须带着同一个 revision: " + workspace.readRevisions());
        assertEquals(0, workspace.headRevisionCalls());
    }

    // ---------------------------------------------------------------------
    // 分层路径：flat 目录超出预算
    // ---------------------------------------------------------------------

    /**
     * 目录超出上限时不再失败，而是走分层：Region Scout 先缩范围，每个终态分支各跑一次
     * File Scout，结果合并后照常进入读取。
     */
    @Test
    void entersHierarchicalScoutWhenTheFlatCatalogIsOversized() {
        seedBranches();

        List<RepositorySourceFile> material =
                hierarchicalUnderstanding(flatCatalogBytes() - 1)
                        .understand(WORKSPACE, REVISION);

        assertEquals(1, gateway.regionCalls(), "根层只需一次 Region Scout 就能缩到两条分支");
        assertEquals(2, gateway.fileCalls(), "每个终态分支各一次 File Scout");
        assertEquals(List.of(POM, "alpha/A2.java", "beta/B1.java", "alpha/A1.java", "beta/B2.java"),
                pathsOf(material));
    }

    /**
     * 保序轮转合并出来的顺序就是定向源码的**考虑顺序**：读取按它进行，不被重排。
     *
     * <pre>
     * alpha：A2 A1        ← File Scout 给的分支内优先级
     * beta ：B1 B2
     * 合并：A2 B1 A1 B2   ← 第一轮取每支第一个，第二轮取每支第二个
     * </pre>
     */
    @Test
    void keepsTheRoundRobinOrderAsTheTargetedConsiderationOrder() {
        seedBranches();

        hierarchicalUnderstanding(flatCatalogBytes() - 1).understand(WORKSPACE, REVISION);

        assertEquals(
                List.of(POM, "alpha/A2.java", "beta/B1.java", "alpha/A1.java", "beta/B2.java"),
                workspace.readPaths(),
                "轮转顺序必须原样成为定向源码的考虑顺序");
    }

    /**
     * 分层路径的每一次读取都固定在最初解析出的 revision 上，也不会去解析 HEAD。
     */
    @Test
    void keepsTheResolvedRevisionForEveryHierarchicalRead() {
        seedBranches();

        hierarchicalUnderstanding(flatCatalogBytes() - 1).understand(WORKSPACE, REVISION);

        // 列目录可能有多次（测试自己算过一次字节上限），但每一次都必须带着同一个 revision
        assertTrue(workspace.listedRevisions().stream().allMatch(REVISION::equals),
                "所有列目录都必须带着同一个 revision: " + workspace.listedRevisions());
        assertTrue(workspace.readRevisions().stream().allMatch(REVISION::equals),
                "所有读取都必须带着同一个 revision: " + workspace.readRevisions());
        assertEquals(0, workspace.headRevisionCalls(), "理解链路不得解析 HEAD");
    }

    /**
     * Map、Region Scout、File Scout 三个阶段都不读源码：读取只发生在规划选出文件之后。
     *
     * <p>替身在**每次模型调用之前**记下当时已经读过几个文件，因此「哪一步开始读」是被观测到的
     * 事实，而不是靠时序推测。
     */
    @Test
    void readsNoSourceBeforeThePlanSelectsIt() {
        seedBranches();

        hierarchicalUnderstanding(flatCatalogBytes() - 1).understand(WORKSPACE, REVISION);

        int scoutCalls = gateway.scoutCalls();
        assertEquals(3, scoutCalls);
        assertTrue(gateway.readsBeforeCall().subList(0, scoutCalls).stream().allMatch(count -> count == 0),
                "Scout 阶段不得读取任何源码: " + gateway.readsBeforeCall());
    }

    /**
     * 送给 File Scout 的每一份目录都在上限之内——包括分层路径下每个分支的目录。
     *
     * <p>这条是分层真正的意义所在：把「交给模型的文件目录不会超过这个上限」从「flat 目录
     * 恰好没超」变成「两条路径都成立」。
     */
    @Test
    void neverSendsAFileCatalogLargerThanTheLimit() {
        seedBranches();
        int limit = flatCatalogBytes() - 1;

        hierarchicalUnderstanding(limit).understand(WORKSPACE, REVISION);

        for (AiRequest request : gateway.requests()) {
            String message = userMessage(request);
            if (!message.contains("fileCatalog")) {
                continue;
            }
            int bytes = message.getBytes(StandardCharsets.UTF_8).length;
            assertTrue(bytes <= limit, "File Scout 目录载荷 " + bytes + " 超过了上限 " + limit);
        }
    }

    /**
     * 合并后的候选照常受**既有材料预算**约束：定向源码通道读多少仍由它决定。
     */
    @Test
    void appliesTheExistingTargetedBudgetToTheMergedCandidates() {
        seedBranches();

        RepositoryUnderstanding understanding = UnderstandingFixtures.understanding(
                gateway, workspace,
                new RepositoryMaterialBudget(5, 1_000, 10_000),
                new RepositoryMaterialBudget(2, 1_000, 10_000),
                flatCatalogBytes() - 1);

        List<RepositorySourceFile> material = understanding.understand(WORKSPACE, REVISION);

        assertEquals(List.of(POM, "alpha/A2.java", "beta/B1.java"), pathsOf(material),
                "定向源码预算 maxFiles=2：只读轮转顺序里的前两个");
    }

    /**
     * 分层失败**不回退**：不会把超限的 flat 目录直接发给 File Scout，也不读取任何文件。
     */
    @Test
    void doesNotFallBackToTheFlatCatalogWhenHierarchicalScoutFails() {
        seedBranches();
        gateway.answerRegionWithUnknownReference();

        assertThrows(AiGatewayException.class,
                () -> hierarchicalUnderstanding(flatCatalogBytes() - 1)
                        .understand(WORKSPACE, REVISION));

        assertEquals(1, gateway.regionCalls(), "只有那一次失败的 Region Scout");
        assertEquals(0, gateway.fileCalls(), "失败之后不得再调用 File Scout");
        assertTrue(workspace.readPaths().isEmpty(), "失败时不读取任何文件");
    }

    /**
     * 结构上无法再缩小时失败关闭：不做截断，也不退回 flat。
     *
     * <p>一个扁平目录（没有子目录可分）超出预算，就是「分层也解决不了」的形状。
     */
    @Test
    void failsClosedWhenTheHierarchyCannotBeReduced() {
        for (int index = 1; index <= 8; index++) {
            workspace.given("flat/F" + index + ".java", "class F" + index + " {}");
        }
        workspace.given(POM, "<project/>");
        // 根层只有 flat/ 一个子目录；缩到它之后就再没有更细的结构可分。
        gateway.respondAll(List.of(List.of("flat")), List.of());

        RepositoryNotAnalyzableException failure = assertThrows(
                RepositoryNotAnalyzableException.class,
                () -> hierarchicalUnderstanding(flatCatalogBytes() - 1)
                        .understand(WORKSPACE, REVISION));

        assertEquals(RegionHierarchyNotReducibleException.class, failure.getCause().getClass(),
                "原始守卫必须留在 cause 里，便于定位是哪一条挡住了分析");
        assertEquals(0, gateway.fileCalls(), "不可再分时不调用 File Scout");
        assertEquals(1, gateway.regionCalls(), "只在根层问过一次");
        assertTrue(workspace.readPaths().isEmpty());
    }

    /**
     * 整次分析的 Scout 调用总数**在导航阶段就生效**：不是等分支 File Scout 那一步才算。
     *
     * <p>结构需要两轮下降（{@code pkg} 那一层装不下，得再往下走一层），总预算只给 1。
     * 第二次 Region 调用必须在触达模型之前被挡下——否则超限之后的失败收不回已经付出的调用。
     */
    @Test
    void stopsAtTheTotalScoutBudgetWhileStillNavigating() {
        workspace.given(POM, "<project/>");
        workspace.given("pkg/a/A1.java", "class A1 {}");
        workspace.given("pkg/a/A2.java", "class A2 {}");
        workspace.given("pkg/b/B1.java", "class B1 {}");
        workspace.given("pkg/b/B2.java", "class B2 {}");
        gateway.respondAll(List.of(List.of("pkg")), List.of());

        RepositoryUnderstanding understanding = UnderstandingFixtures.understanding(
                gateway, workspace,
                GENEROUS_FOUNDATION, GENEROUS_TARGETED,
                flatCatalogBytes() - 1,
                // Region 自己的上限很宽：卡住的是整次分析的总数
                new RegionRecursionBudget(8, 12),
                new ScoutCallBudget(1));

        RepositoryNotAnalyzableException failure = assertThrows(
                RepositoryNotAnalyzableException.class,
                () -> understanding.understand(WORKSPACE, REVISION));

        assertEquals(ScoutCallBudgetExceededException.class, failure.getCause().getClass());
        assertEquals(1, gateway.regionCalls(), "总预算用尽的调用不得触达模型");
        assertEquals(0, gateway.fileCalls(), "导航没走完，不应进入分支阶段");
        assertTrue(workspace.readPaths().isEmpty());
    }

    // ---------------------------------------------------------------------
    // 前置条件：都在调用模型之前失败
    // ---------------------------------------------------------------------

    /**
     * 没有源码候选就直接失败，不进行一次注定失败的调用。
     */
    @Test
    void rejectsARepositoryWithoutSourceCandidatesBeforeCallingTheModel() {
        workspace.given(POM, "<project/>");
        workspace.given("README.md", "# demo");

        assertThrows(RepositoryNotAnalyzableException.class,
                () -> understanding().understand(WORKSPACE, REVISION));

        assertEquals(0, aiGateway.callCount(), "没有源码候选时不调用 Scout");
        assertTrue(workspace.readPaths().isEmpty(), "也不读取任何文件");
    }

    /**
     * 目录载荷按 **UTF-8 字节**衡量，不是字符数。
     *
     * <p>把同一个位置上的两个 ASCII 字符换成两个汉字：字符数不变，字节数增加 4。
     * 载荷的增长量必须跟着字节数走——按字符数衡量会得到 0。
     */
    @Test
    void measuresCatalogPayloadInUtf8BytesNotCharacters() {
        workspace.given("src/main/AA.java", "class A {}");
        int ascii = catalogPayloadBytes(workspace);

        UnderstandingWorkspace multibyte = new UnderstandingWorkspace();
        multibyte.given("src/main/中中.java", "class A {}");
        int chinese = catalogPayloadBytes(multibyte);

        int pathByteGrowth = "中中.java".getBytes(StandardCharsets.UTF_8).length
                - "AA.java".getBytes(StandardCharsets.UTF_8).length;

        assertEquals(pathByteGrowth, chinese - ascii,
                "目录载荷应当按 UTF-8 字节增长，而不是按字符数增长");
    }

    /**
     * 计划一个都没选中时直接失败，不去读取，也不调用分析模型。
     */
    @Test
    void rejectsAnEmptyReadPlan() {
        seedSources();
        aiGateway.respondAll(SCOUT_RESPONSE);

        // 两条通道的单文件上限都小于任何一个候选，因此规划阶段全部被跳过
        RepositoryMaterialBudget tiny = new RepositoryMaterialBudget(10, 1, 1);
        RepositoryUnderstanding understanding = UnderstandingFixtures.understanding(
                aiGateway, workspace, tiny, tiny, 65_536);

        assertThrows(RepositoryNotAnalyzableException.class,
                () -> understanding.understand(WORKSPACE, REVISION));

        assertEquals(1, aiGateway.callCount(), "Scout 已经调用过了，但不会再调用分析模型");
        assertTrue(workspace.readPaths().isEmpty(), "计划为空时不读取任何文件");
    }

    /**
     * 计划选中了文件，但实际内容在执行期全部被剔除时同样失败。
     *
     * <p>这里让 Workspace 报出的 blob 尺寸小于真实内容——正是「规划依据 metadata、
     * 而 metadata 不一定等于真实内容」这一情形。
     */
    @Test
    void rejectsEmptyMaterialAfterExecutionTimeChecks() {
        workspace.given(POM, "<project/>");
        workspace.given(APP, "class App {}");
        workspace.given(OTHER, "class Other {}");
        workspace.givenReportedSize(POM, 1);
        workspace.givenReportedSize(APP, 1);
        workspace.givenReportedSize(OTHER, 1);
        aiGateway.respondAll(SCOUT_RESPONSE);

        RepositoryUnderstanding understanding = understandingWithBudgets(
                new RepositoryMaterialBudget(10, 5, 100),
                new RepositoryMaterialBudget(10, 5, 100));

        assertThrows(RepositoryNotAnalyzableException.class,
                () -> understanding.understand(WORKSPACE, REVISION));
    }

    // ---------------------------------------------------------------------
    // 失败传播
    // ---------------------------------------------------------------------

    @Test
    void propagatesScoutFailure() {
        seedSources();
        aiGateway.failOnCall(1, new AiGatewayException("模型不可用"));

        assertThrows(AiGatewayException.class,
                () -> understanding().understand(WORKSPACE, REVISION));
        assertTrue(workspace.readPaths().isEmpty(), "Scout 失败时不读取任何文件");
    }

    @Test
    void propagatesMalformedScoutOutput() {
        seedSources();
        aiGateway.respondAll("{}");

        assertThrows(AiGatewayException.class,
                () -> understanding().understand(WORKSPACE, REVISION));
    }

    /**
     * Scout 引用了本次没有提供的编号时整次失败，不做部分补救。
     */
    @Test
    void propagatesReferenceValidationFailure() {
        seedSources();
        aiGateway.respondAll("""
                { "focusAreas": [
                    { "label": "a", "fileRefs": ["RF-99"] },
                    { "label": "b", "fileRefs": ["RF-2"] },
                    { "label": "c", "fileRefs": ["RF-3"] } ] }
                """);

        assertThrows(AiGatewayException.class,
                () -> understanding().understand(WORKSPACE, REVISION));
    }

    @Test
    void propagatesReadFailure() {
        seedSources();
        aiGateway.respondAll(SCOUT_RESPONSE);
        workspace.failReadsWith(new WorkspaceException("读取失败"));

        assertThrows(WorkspaceException.class,
                () -> understanding().understand(WORKSPACE, REVISION));
    }

    // ---------------------------------------------------------------------
    // 参数
    // ---------------------------------------------------------------------

    @Test
    void rejectsNonPositiveCatalogLimit() {
        assertThrows(IllegalArgumentException.class, () -> UnderstandingFixtures.understanding(
                aiGateway, workspace, GENEROUS_FOUNDATION, GENEROUS_TARGETED, 0));
    }

    // ---------------------------------------------------------------------
    // 辅助：flat 场景
    // ---------------------------------------------------------------------

    private void seedSources() {
        workspace.given(POM, "<project/>");
        workspace.given(APP, "class App {}");
        workspace.given(OTHER, "class Other {}");
    }

    private RepositoryUnderstanding understanding() {
        return understandingWithBudgets(GENEROUS_FOUNDATION, GENEROUS_TARGETED);
    }

    /**
     * 某份夹具下 Scout 目录载荷的 UTF-8 字节数。
     *
     * <p>走与生产路径相同的入口量：用同一份夹具、同一个 revision 建 Map，再取它的源码候选。
     * 这样边界用例比较的就是「恰好等于即将发出的那一份载荷」，而不是一个拍出来的常量——
     * 常量会随着夹具或目录格式变化而悄悄失准。
     */
    private int catalogPayloadBytes(UnderstandingWorkspace target) {
        RepositoryMap map = new RepositoryMapBuilder(target).build(WORKSPACE, REVISION);
        return new RepositoryScoutExtraction(aiGateway, new ObjectMapper())
                .catalogPayloadBytes(RepositoryScoutInputs.of(map));
    }

    /**
     * 另一条上限下的链路。用 {@link StubAiGateway}：这些用例走 flat 路径，
     * 只需要按顺序的固定响应。
     */
    private RepositoryUnderstanding understandingWithCatalogLimit(int maxCatalogBytes) {
        return UnderstandingFixtures.understanding(aiGateway, workspace,
                GENEROUS_FOUNDATION, GENEROUS_TARGETED, maxCatalogBytes);
    }

    /** 走分层路径的链路：Scout 响应按请求内容编排。 */
    private RepositoryUnderstanding hierarchicalUnderstanding(int maxCatalogBytes) {
        return UnderstandingFixtures.understanding(gateway, workspace,
                GENEROUS_FOUNDATION, GENEROUS_TARGETED, maxCatalogBytes);
    }

    private RepositoryUnderstanding understandingWithBudgets(
            RepositoryMaterialBudget foundation, RepositoryMaterialBudget targetedSource) {
        return UnderstandingFixtures.understanding(
                aiGateway, workspace, foundation, targetedSource, 65_536);
    }

    private static List<String> pathsOf(List<RepositorySourceFile> material) {
        return material.stream().map(RepositorySourceFile::relativePath).toList();
    }

    private static String userMessage(AiRequest request) {
        return request.messages().get(request.messages().size() - 1).content();
    }

    // ---------------------------------------------------------------------
    // 辅助：分层场景
    // ---------------------------------------------------------------------

    /**
     * 两条各自装得下的分支，外加一份基础材料。
     *
     * <pre>
     * alpha/A1.java  alpha/A2.java     一条分支
     * beta/B1.java   beta/B2.java      另一条分支
     * pom.xml                          基础材料（FOUNDATION）
     * </pre>
     *
     * <p>根层只有 {@code alpha} 与 {@code beta} 两个子目录，因此一次 Region Scout 就能缩到
     * 两条终态分支；每支各一次 File Scout。
     */
    private void seedBranches() {
        workspace.given(POM, "<project>spring-boot</project>");
        workspace.given("alpha/A1.java", "class A1 {}");
        workspace.given("alpha/A2.java", "class A2 {}");
        workspace.given("beta/B1.java", "class B1 {}");
        workspace.given("beta/B2.java", "class B2 {}");

        gateway.respondAll(
                List.of(List.of("alpha", "beta")),
                List.of(
                        areas("alpha/A2.java", "alpha/A1.java"),
                        areas("beta/B1.java", "beta/B2.java")));
    }

    /**
     * 一组的 File Scout 响应：每个文件各占一个区域（优先级顺序即给的顺序），
     * 再补一个重复区域凑够解析器要求的最少区域数。
     *
     * <pre>
     * { focusAreas: [ {路径1}, {路径2}, {路径1} ] }  →  组内顺序 = 路径1, 路径2
     * </pre>
     */
    private static List<List<String>> areas(String first, String second) {
        return List.of(List.of(first), List.of(second), List.of(first));
    }

    /**
     * flat 路径下这份夹具会发出的目录载荷字节数，也就是「分层与否」的判据。
     *
     * <p>取它减一作为上限：整份目录超出、而每条分支都装得下。
     */
    private int flatCatalogBytes() {
        return catalogPayloadBytes(workspace);
    }

    // ---------------------------------------------------------------------
    // 替身
    // ---------------------------------------------------------------------

    /** AI Gateway 替身：按调用顺序返回预设内容，可指定某一次调用失败。 */
    private static final class StubAiGateway implements AiGateway {

        private final List<AiRequest> requests = new ArrayList<>();

        private final List<String> responses = new ArrayList<>();

        private int failOnCall = -1;

        private RuntimeException failure;

        void respondAll(String... rawResponses) {
            responses.clear();
            responses.addAll(List.of(rawResponses));
            failOnCall = -1;
            failure = null;
        }

        void failOnCall(int callNumber, RuntimeException exception) {
            this.failOnCall = callNumber;
            this.failure = exception;
        }

        List<AiRequest> requests() {
            return List.copyOf(requests);
        }

        AiRequest lastRequest() {
            return requests.get(requests.size() - 1);
        }

        int callCount() {
            return requests.size();
        }

        @Override
        public String generate(AiRequest request) {
            requests.add(request);
            if (requests.size() == failOnCall) {
                throw failure;
            }
            if (responses.isEmpty()) {
                throw new AiGatewayException("替身没有更多预设响应");
            }
            return responses.remove(0);
        }
    }

    /**
     * 提供列目录与读取的替身。
     *
     * <p>解析 HEAD 会直接失败：理解链路的 revision 必须由调用方给出。
     */
    private static final class UnderstandingWorkspace implements WorkspaceReadPort {

        private final Map<String, String> files = new LinkedHashMap<>();

        private final Map<String, Long> reportedSizes = new LinkedHashMap<>();

        private final List<String> listedRevisions = new ArrayList<>();

        private final List<String> readRevisions = new ArrayList<>();

        private final List<String> readPaths = new ArrayList<>();

        private RuntimeException failure;

        void given(String relativePath, String content) {
            files.put(relativePath, content);
        }

        /** 让列目录报出一个与真实内容不同的尺寸，用来模拟 metadata 与内容不一致。 */
        void givenReportedSize(String relativePath, long size) {
            reportedSizes.put(relativePath, size);
        }

        void failReadsWith(RuntimeException exception) {
            this.failure = exception;
        }

        List<String> listedRevisions() {
            return List.copyOf(listedRevisions);
        }

        List<String> readRevisions() {
            return List.copyOf(readRevisions);
        }

        List<String> readPaths() {
            return List.copyOf(readPaths);
        }

        int readCount() {
            return readPaths.size();
        }

        int headRevisionCalls() {
            return 0;
        }

        @Override
        public boolean isReadableRepository(WorkspaceRef workspace) {
            throw new AssertionError("理解链路不判断仓库可读性");
        }

        @Override
        public List<WorkspaceEntry> listEntries(
                WorkspaceRef workspace, String revision, String relativePath, int maxDepth) {
            listedRevisions.add(revision);
            List<WorkspaceEntry> entries = new ArrayList<>();
            for (Map.Entry<String, String> file : files.entrySet()) {
                long size = reportedSizes.getOrDefault(
                        file.getKey(),
                        (long) file.getValue().getBytes(StandardCharsets.UTF_8).length);
                entries.add(new WorkspaceEntry(file.getKey(), false, size));
            }
            return List.copyOf(entries);
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
            throw new AssertionError("理解链路不得解析 HEAD");
        }
    }
}
