package com.ayywl.delveforge.application.repositoryanalysis.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryScoutExtraction;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryScoutInputs;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 分层导航：递归、分支优先级、直接文件、预算守卫与失败关闭。 */
class RepositoryRegionNavigatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final RegionNavigationLimits CALL_LIMITS =
            new RegionNavigationLimits(65_536, 6);

    private static final RegionRecursionBudget ROOMY_BUDGET =
            new RegionRecursionBudget(8, 12);

    /** 宽裕的 Scout 调用总数：这些用例验证的是别的守卫，不应被总数挡住。 */
    private static final ScoutCallBudget ROOMY_SCOUT_CALLS = new ScoutCallBudget(18);

    private static RepositoryRegionScoutExtraction extraction(AiGateway gateway) {
        return extraction(gateway, CALL_LIMITS);
    }

    private static RepositoryRegionScoutExtraction extraction(AiGateway gateway,
                                                              RegionNavigationLimits limits) {
        return new RepositoryRegionScoutExtraction(gateway, MAPPER, limits);
    }

    private static RepositoryRegionNavigator navigator(AiGateway gateway,
                                                       int maxFileCatalogBytes,
                                                       RegionRecursionBudget budget) {
        return navigator(extraction(gateway), maxFileCatalogBytes, budget, ROOMY_SCOUT_CALLS);
    }

    private static RepositoryRegionNavigator navigator(AiGateway gateway,
                                                       int maxFileCatalogBytes,
                                                       RegionRecursionBudget budget,
                                                       ScoutCallBudget scoutCallBudget) {
        return navigator(extraction(gateway), maxFileCatalogBytes, budget, scoutCallBudget);
    }

    private static RepositoryRegionNavigator navigator(RepositoryRegionScoutExtraction scoutExtraction,
                                                       int maxFileCatalogBytes,
                                                       RegionRecursionBudget budget) {
        return navigator(scoutExtraction, maxFileCatalogBytes, budget, ROOMY_SCOUT_CALLS);
    }

    private static RepositoryRegionNavigator navigator(RepositoryRegionScoutExtraction scoutExtraction,
                                                       int maxFileCatalogBytes,
                                                       RegionRecursionBudget budget,
                                                       ScoutCallBudget scoutCallBudget) {
        return new RepositoryRegionNavigator(scoutExtraction,
                RegionNavigationFixtures.payload(), maxFileCatalogBytes, budget, scoutCallBudget);
    }

    private static List<String> groupPrefixes(RepositoryRegionNavigation navigation) {
        return navigation.terminalFileGroups().stream()
                .map(RepositoryTerminalFileGroup::pathPrefix)
                .toList();
    }

    /** 便于断言的展开形式：每组里各文件的相对路径。 */
    private static List<List<String>> groupsAsPaths(RepositoryRegionNavigation navigation) {
        return navigation.terminalFileGroups().stream()
                .map(group -> group.sourceFiles().stream()
                        .map(RepositoryMapEntry::relativePath).toList())
                .toList();
    }

    // ---------------------------------------------------------------- 缩减

    @Test
    void wholeSetFitsWithoutAnyRegionScout() {
        RepositoryMap map = RegionNavigationFixtures.map();
        int all = RegionNavigationFixtures.catalogBytes(map,
                RegionNavigationFixtures.ROOT_MAIN, RegionNavigationFixtures.PKG_AAA_A,
                RegionNavigationFixtures.PKG_AAA_B, RegionNavigationFixtures.PKG_AAA_C,
                RegionNavigationFixtures.PKG_BBB_D, RegionNavigationFixtures.PKG_TOP,
                RegionNavigationFixtures.TOOL_E);
        RegionNavigationFixtures.ScriptedScoutGateway gateway =
                new RegionNavigationFixtures.ScriptedScoutGateway(List.of());

        RepositoryRegionNavigation navigation =
                navigator(gateway, all, ROOMY_BUDGET).navigate(map);

        assertEquals(List.of(""), groupPrefixes(navigation), "整份装得下时只有一个根层组");
        assertEquals(7, navigation.totalSourceFiles());
        assertEquals(0, gateway.calls(), "小仓库完全旁路 Region Scout");
        assertEquals(0, navigation.regionScoutCalls());
    }

    @Test
    void reducesOneLevelWhenChildRegionsFit() {
        RepositoryMap map = RegionNavigationFixtures.map();
        int limit = RegionNavigationFixtures.catalogBytes(map, RegionNavigationFixtures.PKG_FILES);
        RegionNavigationFixtures.ScriptedScoutGateway gateway =
                new RegionNavigationFixtures.ScriptedScoutGateway(List.of(List.of(1, 2)));

        RepositoryRegionNavigation navigation = navigator(gateway, limit, ROOMY_BUDGET).navigate(map);

        assertEquals(List.of("", "pkg", "tool"), groupPrefixes(navigation));
        assertEquals(List.of(RegionNavigationFixtures.ROOT_MAIN),
                groupsAsPaths(navigation).get(0), "根目录直属文件单独成组");
        assertEquals(List.of(RegionNavigationFixtures.PKG_AAA_A,
                        RegionNavigationFixtures.PKG_AAA_B,
                        RegionNavigationFixtures.PKG_AAA_C,
                        RegionNavigationFixtures.PKG_BBB_D,
                        RegionNavigationFixtures.PKG_TOP),
                groupsAsPaths(navigation).get(1));
        assertEquals(List.of(RegionNavigationFixtures.TOOL_E), groupsAsPaths(navigation).get(2));
        assertEquals(7, navigation.totalSourceFiles(), "分解不得丢文件");
        assertEquals(1, navigation.regionScoutCalls());
    }

    @Test
    void recursesUntilEachBranchFitsAndKeepsSelectedBranchOrder() {
        RepositoryMap map = RegionNavigationFixtures.map();
        int limit = RegionNavigationFixtures.catalogBytes(
                map, RegionNavigationFixtures.PKG_AAA_FILES);
        RegionNavigationFixtures.ScriptedScoutGateway gateway =
                new RegionNavigationFixtures.ScriptedScoutGateway(
                        List.of(List.of(1, 2), List.of(1)));

        RepositoryRegionNavigation navigation = navigator(gateway, limit, ROOMY_BUDGET).navigate(map);

        assertEquals(List.of("", "pkg", "pkg/aaa", "tool"), groupPrefixes(navigation),
                "结构遍历顺序：根目录直属组在前，被选中的分支之间保持 Region Scout 的返回顺序"
                        + "（pkg 整支走完才轮到 tool）");
        assertEquals(List.of(List.of(RegionNavigationFixtures.ROOT_MAIN),
                        List.of(RegionNavigationFixtures.PKG_TOP),
                        List.of(RegionNavigationFixtures.PKG_AAA_A,
                                RegionNavigationFixtures.PKG_AAA_B,
                                RegionNavigationFixtures.PKG_AAA_C),
                        List.of(RegionNavigationFixtures.TOOL_E)),
                groupsAsPaths(navigation));
        // 被保留的是「被选中分支上的文件」：main.py + pkg/top.java + pkg/aaa 三个 + tool/E.java。
        // pkg/bbb/D.java 没有被保留，是因为 Region Scout 没有选中 pkg/bbb —— 那是取舍，不是丢失。
        assertEquals(6, navigation.totalSourceFiles());
        assertEquals(1 + 1 + 3 + 1, navigation.totalSourceFiles());
        assertEquals(2, navigation.regionScoutCalls());

        for (RepositoryTerminalFileGroup group : navigation.terminalFileGroups()) {
            assertTrue(group.fileCatalogBytes() <= limit,
                    "每个终态组都必须装得进预算: " + group.pathPrefix());
        }
    }

    @Test
    void branchPriorityFollowsTheRegionScoutOrder() {
        RepositoryMap map = RegionNavigationFixtures.map();
        int limit = RegionNavigationFixtures.catalogBytes(map, RegionNavigationFixtures.PKG_FILES);
        // 反序选择：先 tool 再 pkg
        RegionNavigationFixtures.ScriptedScoutGateway gateway =
                new RegionNavigationFixtures.ScriptedScoutGateway(List.of(List.of(2, 1)));

        RepositoryRegionNavigation navigation = navigator(gateway, limit, ROOMY_BUDGET).navigate(map);

        assertEquals(List.of("", "tool", "pkg"), groupPrefixes(navigation),
                "支优先级由 Region Scout 的返回顺序决定");
    }

    @Test
    void everyTerminalGroupIsASubsetOfTheMap() {
        RepositoryMap map = RegionNavigationFixtures.map();
        int limit = RegionNavigationFixtures.catalogBytes(
                map, RegionNavigationFixtures.PKG_AAA_FILES);
        RegionNavigationFixtures.ScriptedScoutGateway gateway =
                new RegionNavigationFixtures.ScriptedScoutGateway(
                        List.of(List.of(1, 2), List.of(1)));

        RepositoryRegionNavigation navigation = navigator(gateway, limit, ROOMY_BUDGET).navigate(map);

        List<RepositoryMapEntry> all = map.entries();
        for (RepositoryTerminalFileGroup group : navigation.terminalFileGroups()) {
            assertTrue(all.containsAll(group.sourceFiles()),
                    "终态组必须是这张 Map 描述符的子集: " + group.pathPrefix());
        }
    }

    @Test
    void navigationResultIsDeterministic() {
        RepositoryMap map = RegionNavigationFixtures.map();
        int limit = RegionNavigationFixtures.catalogBytes(
                map, RegionNavigationFixtures.PKG_AAA_FILES);

        RepositoryRegionNavigation first = navigator(
                new RegionNavigationFixtures.ScriptedScoutGateway(
                        List.of(List.of(1, 2), List.of(1))), limit, ROOMY_BUDGET).navigate(map);
        RepositoryRegionNavigation second = navigator(
                new RegionNavigationFixtures.ScriptedScoutGateway(
                        List.of(List.of(1, 2), List.of(1))), limit, ROOMY_BUDGET).navigate(map);

        assertEquals(groupPrefixes(first), groupPrefixes(second));
        assertEquals(groupsAsPaths(first), groupsAsPaths(second));
        assertEquals(first.regionScoutCalls(), second.regionScoutCalls());
    }

    // ---------------------------------------------------------------- 守卫

    @Test
    void failsClosedWhenPerBranchRoundsAreExhausted() {
        RepositoryMap map = RegionNavigationFixtures.map();
        int limit = RegionNavigationFixtures.catalogBytes(
                map, RegionNavigationFixtures.PKG_AAA_FILES);
        RegionNavigationFixtures.ScriptedScoutGateway gateway =
                new RegionNavigationFixtures.ScriptedScoutGateway(
                        List.of(List.of(1, 2), List.of(1)));

        RegionNavigationBudgetExceededException failure = assertThrows(
                RegionNavigationBudgetExceededException.class,
                () -> navigator(gateway, limit, new RegionRecursionBudget(1, 12)).navigate(map));

        assertTrue(failure.getMessage()
                .contains(RegionNavigationBudgetExceededException.MAX_REGION_SCOUT_ROUNDS_EXCEEDED));
        assertEquals(1, gateway.calls(), "第一轮已经发生，第二轮被守卫挡下");
    }

    /**
     * 总调用数被**宽度**耗尽：两个分支各自都需要下钻，第三次调用超限。
     *
     * <p>单分支轮数在这里远远没用完——它管的是深度，总调用数管的是「深度 × 宽度」。
     */
    @Test
    void failsClosedWhenTotalScoutCallsAreExhausted() {
        RepositoryMap map = RegionNavigationFixtures.twoBranchesMap();
        int limit = RegionNavigationFixtures.catalogBytes(
                map, RegionNavigationFixtures.ALPHA_X_FILES);
        RegionNavigationFixtures.ScriptedScoutGateway gateway =
                new RegionNavigationFixtures.ScriptedScoutGateway(
                        List.of(List.of(1, 2), List.of(1)));

        RegionNavigationBudgetExceededException failure = assertThrows(
                RegionNavigationBudgetExceededException.class,
                () -> navigator(gateway, limit, new RegionRecursionBudget(8, 2)).navigate(map));

        assertTrue(failure.getMessage()
                .contains(RegionNavigationBudgetExceededException.MAX_REGION_SCOUT_CALLS_EXCEEDED));
        assertEquals(2, gateway.calls(), "前两次调用已经发生，第三次被总上限挡下");
    }

    @Test
    void budgetFailureReturnsNoPartialResult() {
        RepositoryMap map = RegionNavigationFixtures.twoBranchesMap();
        int limit = RegionNavigationFixtures.catalogBytes(
                map, RegionNavigationFixtures.ALPHA_X_FILES);
        RegionNavigationFixtures.ScriptedScoutGateway gateway =
                new RegionNavigationFixtures.ScriptedScoutGateway(
                        List.of(List.of(1, 2), List.of(1)));

        // 已经走出来的部分（alpha 与 alpha/x 两组）不得被交出去：失败即抛，没有返回值。
        assertThrows(RegionNavigationBudgetExceededException.class,
                () -> navigator(gateway, limit, new RegionRecursionBudget(8, 2)).navigate(map));
        assertEquals(2, gateway.requests().size());
    }

    // ---------------------------------------------------------------- 不可再分

    @Test
    void failsClosedWhenAFlatDirectoryCannotBeReduced() {
        RepositoryMap map = RegionNavigationFixtures.flatMap();
        int limit = RegionNavigationFixtures.catalogBytes(map, "flat/F1.java");
        RegionNavigationFixtures.ScriptedScoutGateway gateway =
                new RegionNavigationFixtures.ScriptedScoutGateway(List.of(List.of(1)));

        RegionHierarchyNotReducibleException failure = assertThrows(
                RegionHierarchyNotReducibleException.class,
                () -> navigator(gateway, limit, ROOMY_BUDGET).navigate(map));

        assertTrue(failure.getMessage()
                .contains(RegionHierarchyNotReducibleException.HIERARCHY_NOT_REDUCIBLE));
        assertTrue(failure.getMessage().contains("flat"));
    }

    @Test
    void failsClosedWhenDirectFilesAloneExceedTheBudget() {
        RepositoryMap map = RegionNavigationFixtures.directFilesMap();
        int limit = RegionNavigationFixtures.catalogBytes(map, "pkg/sub/A.java");
        RegionNavigationFixtures.ScriptedScoutGateway gateway =
                new RegionNavigationFixtures.ScriptedScoutGateway(List.of(List.of(1)));

        RegionHierarchyNotReducibleException failure = assertThrows(
                RegionHierarchyNotReducibleException.class,
                () -> navigator(gateway, limit, ROOMY_BUDGET).navigate(map));

        assertTrue(failure.getMessage()
                .contains(RegionHierarchyNotReducibleException.HIERARCHY_NOT_REDUCIBLE));
        assertTrue(failure.getMessage().contains("pkg"),
                "失败信息应指向那个直属文件装不下的节点");
    }

    // ---------------------------------------------------------------- 总 Scout 预算

    /**
     * 整次分析的 Scout 调用总数在**导航阶段**就生效，而不是等分支 File Scout 那一步。
     *
     * <p>结构需要两轮下降（第二轮要调一次 Region Scout），总预算只有 1：
     * 第二次调用必须在触达模型之前被挡下。否则超限之后的失败收不回已经付出的 Region 调用。
     */
    @Test
    void stopsAtTheTotalScoutBudgetBeforeTheRegionCallThatWouldExceedIt() {
        RepositoryMap map = RegionNavigationFixtures.map();
        int limit = RegionNavigationFixtures.catalogBytes(
                map, RegionNavigationFixtures.PKG_AAA_FILES);
        RegionNavigationFixtures.ScriptedScoutGateway gateway =
                new RegionNavigationFixtures.ScriptedScoutGateway(
                        List.of(List.of(1, 2), List.of(1)));

        // 结构上需要 2 次 Region 调用，但整次分析只允许 1 次
        ScoutCallBudgetExceededException failure = assertThrows(
                ScoutCallBudgetExceededException.class,
                () -> navigator(gateway, limit, ROOMY_BUDGET, new ScoutCallBudget(1))
                        .navigate(map));

        assertTrue(failure.getMessage()
                        .contains(ScoutCallBudgetExceededException.MAX_TOTAL_SCOUT_CALLS_EXCEEDED),
                "失败原因应当带上稳定的标识: " + failure.getMessage());
        assertEquals(1, gateway.calls(), "被挡下的那一次不得触达模型");
    }

    /**
     * 总预算足够时照常走完：这条守卫不会因为「存在」就误伤正常导航。
     */
    @Test
    void completesWhenTheTotalScoutBudgetIsExactlyEnough() {
        RepositoryMap map = RegionNavigationFixtures.map();
        int limit = RegionNavigationFixtures.catalogBytes(
                map, RegionNavigationFixtures.PKG_AAA_FILES);
        RegionNavigationFixtures.ScriptedScoutGateway gateway =
                new RegionNavigationFixtures.ScriptedScoutGateway(
                        List.of(List.of(1, 2), List.of(1)));

        RepositoryRegionNavigation navigation =
                navigator(gateway, limit, ROOMY_BUDGET, new ScoutCallBudget(2)).navigate(map);

        assertEquals(2, navigation.regionScoutCalls());
        assertEquals(2, gateway.calls());
    }

    // ---------------------------------------------------------------- 传递失败

    @Test
    void propagatesRegionCatalogOverflow() {
        RepositoryMap map = RegionNavigationFixtures.map();
        int limit = RegionNavigationFixtures.catalogBytes(
                map, RegionNavigationFixtures.PKG_AAA_FILES);
        RegionNavigationFixtures.ScriptedScoutGateway gateway =
                new RegionNavigationFixtures.ScriptedScoutGateway(List.of(List.of(1)));

        RepositoryRegionScoutExtraction tooTight = extraction(gateway,
                new RegionNavigationLimits(1, 6));

        assertThrows(RepositoryRegionCatalogTooLargeException.class,
                () -> navigator(tooTight, limit, ROOMY_BUDGET).navigate(map));
    }

    @Test
    void propagatesInvalidRegionReferences() {
        RepositoryMap map = RegionNavigationFixtures.map();
        int limit = RegionNavigationFixtures.catalogBytes(
                map, RegionNavigationFixtures.PKG_AAA_FILES);
        AiGateway badReferences = request ->
                "{\"regionRefs\":[\"RR-00000000000000000000000000000000-1\"]}";

        assertThrows(AiGatewayException.class,
                () -> navigator(badReferences, limit, ROOMY_BUDGET).navigate(map));
    }

    // ---------------------------------------------------------------- 与 File Scout 同源

    @Test
    void measurementMatchesTheProductionFileScoutCatalog() {
        RepositoryMap map = RegionNavigationFixtures.map();

        int viaFixture = RegionNavigationFixtures.catalogBytes(map,
                RegionNavigationFixtures.ROOT_MAIN, RegionNavigationFixtures.PKG_AAA_A,
                RegionNavigationFixtures.PKG_AAA_B, RegionNavigationFixtures.PKG_AAA_C,
                RegionNavigationFixtures.PKG_BBB_D, RegionNavigationFixtures.PKG_TOP,
                RegionNavigationFixtures.TOOL_E);

        RepositoryScoutExtraction fileScout = new RepositoryScoutExtraction(
                new RegionNavigationFixtures.ScriptedScoutGateway(List.of()), MAPPER);
        int viaFileScout = fileScout.catalogPayloadBytes(RepositoryScoutInputs.of(map));

        assertEquals(viaFileScout, viaFixture,
                "导航的停止条件必须与 File Scout 实际发出的载荷是同一种度量");
    }

    // ---------------------------------------------------------------- 参数

    @Test
    void rejectsInvalidConstructionAndNullMap() {
        RegionNavigationFixtures.ScriptedScoutGateway gateway =
                new RegionNavigationFixtures.ScriptedScoutGateway(List.of());

        assertThrows(IllegalArgumentException.class, () -> new RepositoryRegionNavigator(
                null, RegionNavigationFixtures.payload(), 100, ROOMY_BUDGET, ROOMY_SCOUT_CALLS));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryRegionNavigator(
                extraction(gateway), null, 100, ROOMY_BUDGET, ROOMY_SCOUT_CALLS));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryRegionNavigator(
                extraction(gateway), RegionNavigationFixtures.payload(), 0, ROOMY_BUDGET,
                ROOMY_SCOUT_CALLS));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryRegionNavigator(
                extraction(gateway), RegionNavigationFixtures.payload(), 100, null,
                ROOMY_SCOUT_CALLS));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryRegionNavigator(
                extraction(gateway), RegionNavigationFixtures.payload(), 100, ROOMY_BUDGET,
                null));

        RepositoryRegionNavigator navigator =
                navigator(gateway, 100, ROOMY_BUDGET);
        assertThrows(IllegalArgumentException.class, () -> navigator.navigate(null));
    }
}
