package com.ayywl.delveforge.application.repositoryanalysis.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryScoutExtraction;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 分支本地 File Scout 执行与保序轮转合并。 */
class RepositoryBranchScoutRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final RepositoryMap MAP = BranchScoutFixtures.map();

    /** 配置里的 Region 守卫（单分支 8 轮 / Region 调用 12 次）。 */
    private static final RegionRecursionBudget REGION_BUDGET = new RegionRecursionBudget(8, 12);

    private static RepositoryBranchScoutRunner runner(AiGateway gateway, int maxTotalCalls) {
        return runner(gateway, REGION_BUDGET, maxTotalCalls);
    }

    private static RepositoryBranchScoutRunner runner(AiGateway gateway,
                                                      RegionRecursionBudget regionBudget,
                                                      int maxTotalCalls) {
        return new RepositoryBranchScoutRunner(
                new RepositoryScoutExtraction(gateway, MAPPER),
                regionBudget,
                new ScoutCallBudget(maxTotalCalls));
    }

    /** 每个终态组一个文件，用来精确构造 Region + File 的调用数组合。 */
    private static final List<String> ONE_FILE_PER_GROUP = List.of(
            BranchScoutFixtures.A1, BranchScoutFixtures.A2, BranchScoutFixtures.A3,
            BranchScoutFixtures.B1, BranchScoutFixtures.B2,
            BranchScoutFixtures.C1, BranchScoutFixtures.C2);

    private static RepositoryRegionNavigation oneFilePerGroup(int regionScoutCalls,
                                                              int groupCount) {
        RepositoryTerminalFileGroup[] groups = new RepositoryTerminalFileGroup[groupCount];
        for (int i = 0; i < groupCount; i++) {
            groups[i] = BranchScoutFixtures.group(MAP, "g" + i, ONE_FILE_PER_GROUP.get(i));
        }
        return BranchScoutFixtures.navigation(regionScoutCalls, groups);
    }

    private static List<List<List<String>>> oneFilePerGroupScript(int groupCount) {
        List<List<List<String>>> script = new java.util.ArrayList<>();
        for (int i = 0; i < groupCount; i++) {
            String path = ONE_FILE_PER_GROUP.get(i);
            script.add(List.of(List.of(path), List.of(path), List.of(path)));
        }
        return script;
    }

    private static List<String> paths(RepositoryFileCandidates candidates) {
        return candidates.orderedFiles().stream()
                .map(RepositoryMapEntry::relativePath)
                .toList();
    }

    /** A(3) / B(2) / C(3) 三支：任务书里那个轮转例子。 */
    private static List<List<List<String>>> threeBranchScript() {
        return List.of(
                List.of(List.of(BranchScoutFixtures.A1),
                        List.of(BranchScoutFixtures.A2),
                        List.of(BranchScoutFixtures.A3)),
                List.of(List.of(BranchScoutFixtures.B1),
                        List.of(BranchScoutFixtures.B2),
                        List.of(BranchScoutFixtures.B1)),
                List.of(List.of(BranchScoutFixtures.C1),
                        List.of(BranchScoutFixtures.C2),
                        List.of(BranchScoutFixtures.C3)));
    }

    private static RepositoryRegionNavigation threeBranches() {
        return BranchScoutFixtures.navigation(0,
                BranchScoutFixtures.group(MAP, "pkg/a",
                        BranchScoutFixtures.A1, BranchScoutFixtures.A2, BranchScoutFixtures.A3),
                BranchScoutFixtures.group(MAP, "pkg/b",
                        BranchScoutFixtures.B1, BranchScoutFixtures.B2),
                BranchScoutFixtures.group(MAP, "pkg/c",
                        BranchScoutFixtures.C1, BranchScoutFixtures.C2, BranchScoutFixtures.C3));
    }

    // ---------------------------------------------------------------- 执行

    @Test
    void oneTerminalGroupRunsExactlyOneFileScout() {
        BranchScoutFixtures.ScriptedFileScoutGateway gateway =
                new BranchScoutFixtures.ScriptedFileScoutGateway(List.of(
                        List.of(List.of(BranchScoutFixtures.A1),
                                List.of(BranchScoutFixtures.A2),
                                List.of(BranchScoutFixtures.A3))));

        RepositoryFileCandidates candidates = runner(gateway, 18).run(
                BranchScoutFixtures.navigation(0, BranchScoutFixtures.group(MAP, "pkg/a",
                        BranchScoutFixtures.A1, BranchScoutFixtures.A2, BranchScoutFixtures.A3)));

        assertEquals(1, gateway.calls());
        assertEquals(List.of(BranchScoutFixtures.A1, BranchScoutFixtures.A2, BranchScoutFixtures.A3),
                paths(candidates));
        assertEquals(1, candidates.fileScoutCalls());
        assertEquals(0, candidates.regionScoutCalls());
    }

    @Test
    void multipleTerminalGroupsRunIndependentFileScoutsAndMergeByRoundRobin() {
        BranchScoutFixtures.ScriptedFileScoutGateway gateway =
                new BranchScoutFixtures.ScriptedFileScoutGateway(threeBranchScript());

        RepositoryFileCandidates candidates = runner(gateway, 18).run(threeBranches());

        assertEquals(3, gateway.calls(), "每个终态组各一次 File Scout");
        assertEquals(List.of(
                        BranchScoutFixtures.A1, BranchScoutFixtures.B1, BranchScoutFixtures.C1,
                        BranchScoutFixtures.A2, BranchScoutFixtures.B2, BranchScoutFixtures.C2,
                        BranchScoutFixtures.A3, BranchScoutFixtures.C3),
                paths(candidates), "不均匀分支的保序轮转：A1 B1 C1 A2 B2 C2 A3 C3");
        assertEquals(3, candidates.fileScoutCalls());
        assertEquals(8, candidates.size());
    }

    @Test
    void branchLocalOrderFollowsFileScoutAreaOrder() {
        // b 支的两个区域反序给出：区域顺序即优先级，展平时必须照此。
        BranchScoutFixtures.ScriptedFileScoutGateway gateway =
                new BranchScoutFixtures.ScriptedFileScoutGateway(List.of(
                        List.of(List.of(BranchScoutFixtures.A1),
                                List.of(BranchScoutFixtures.A2),
                                List.of(BranchScoutFixtures.A3)),
                        List.of(List.of(BranchScoutFixtures.B2),
                                List.of(BranchScoutFixtures.B1),
                                List.of(BranchScoutFixtures.B2)),
                        List.of(List.of(BranchScoutFixtures.C1),
                                List.of(BranchScoutFixtures.C2),
                                List.of(BranchScoutFixtures.C3))));

        RepositoryFileCandidates candidates = runner(gateway, 18).run(threeBranches());

        assertEquals(List.of(
                        BranchScoutFixtures.A1, BranchScoutFixtures.B2, BranchScoutFixtures.C1,
                        BranchScoutFixtures.A2, BranchScoutFixtures.B1, BranchScoutFixtures.C2,
                        BranchScoutFixtures.A3, BranchScoutFixtures.C3),
                paths(candidates), "区域顺序即组内优先级：b 支先 B2 后 B1");
    }

    @Test
    void resultIsDeterministicForDeterministicResponses() {
        RepositoryFileCandidates first = runner(
                new BranchScoutFixtures.ScriptedFileScoutGateway(threeBranchScript()), 18)
                .run(threeBranches());
        RepositoryFileCandidates second = runner(
                new BranchScoutFixtures.ScriptedFileScoutGateway(threeBranchScript()), 18)
                .run(threeBranches());

        assertEquals(paths(first), paths(second));
        assertEquals(first.totalScoutCalls(), second.totalScoutCalls());
    }

    // ---------------------------------------------------------------- 引用

    @Test
    void eachBranchCallSeesItsOwnFreshClosedReferenceNamespace() {
        BranchScoutFixtures.ScriptedFileScoutGateway gateway =
                new BranchScoutFixtures.ScriptedFileScoutGateway(threeBranchScript());

        runner(gateway, 18).run(threeBranches());

        assertEquals(List.of("RF-1", "RF-2", "RF-3"), gateway.referencesOf(0));
        assertEquals(List.of("RF-1", "RF-2"), gateway.referencesOf(1),
                "每次分支调用都从头分配编号：原 Map 的编号不是新调用的身份");
        assertEquals(List.of(BranchScoutFixtures.B1, BranchScoutFixtures.B2),
                gateway.pathsOf(1), "b 支只看到自己的两个文件");
        assertEquals(List.of(BranchScoutFixtures.C1, BranchScoutFixtures.C2, BranchScoutFixtures.C3),
                gateway.pathsOf(2));
    }

    @Test
    void rejectsReferencesOutsideTheBranchCatalog() {
        // b 支只有两个文件（RF-1 / RF-2）；RF-3 超出它的闭集。
        BranchScoutFixtures.ScriptedFileScoutGateway gateway =
                new BranchScoutFixtures.ScriptedFileScoutGateway(List.of(
                        List.of(List.of(BranchScoutFixtures.A1),
                                List.of(BranchScoutFixtures.A2),
                                List.of(BranchScoutFixtures.A3)),
                        List.of(List.of("RF-3"), List.of(BranchScoutFixtures.B1),
                                List.of(BranchScoutFixtures.B2)),
                        List.of(List.of(BranchScoutFixtures.C1),
                                List.of(BranchScoutFixtures.C2),
                                List.of(BranchScoutFixtures.C3))));

        assertThrows(AiGatewayException.class,
                () -> runner(gateway, 18).run(threeBranches()));
    }

    /**
     * 编号不携带调用身份：两组各有第一个文件时，两边都是 {@code RF-1}，
     * 而且都在**各自的目录**里成功解析——只是解出的文件不同。
     *
     * <p>隔离来自「每次调用绑定自己那份目录」，不是来自编号互不相同。这条测试把
     * 这个区别钉住，免得把「同名编号」当成漏洞、或把「名字不同」当成隔离。
     */
    @Test
    void bothBranchesCanUseRfOneAndEachResolvesToItsOwnFile() {
        BranchScoutFixtures.ScriptedFileScoutGateway gateway =
                new BranchScoutFixtures.ScriptedFileScoutGateway(List.of(
                        List.of(List.of("RF-1"), List.of(BranchScoutFixtures.A1),
                                List.of(BranchScoutFixtures.A2)),
                        List.of(List.of("RF-1"), List.of(BranchScoutFixtures.B1),
                                List.of(BranchScoutFixtures.B2))));

        RepositoryFileCandidates candidates = runner(gateway, 18).run(
                BranchScoutFixtures.navigation(0,
                        BranchScoutFixtures.group(MAP, "pkg/a",
                                BranchScoutFixtures.A1, BranchScoutFixtures.A2),
                        BranchScoutFixtures.group(MAP, "pkg/b",
                                BranchScoutFixtures.B1, BranchScoutFixtures.B2)));

        assertEquals(List.of("RF-1", "RF-2"), gateway.referencesOf(0));
        assertEquals(List.of("RF-1", "RF-2"), gateway.referencesOf(1),
                "两组的编号完全一样");
        assertEquals(List.of(BranchScoutFixtures.A1, BranchScoutFixtures.A2),
                gateway.pathsOf(0));
        assertEquals(List.of(BranchScoutFixtures.B1, BranchScoutFixtures.B2),
                gateway.pathsOf(1), "同样的 RF-1 在 b 支解出的是 b 支自己的文件");
        assertEquals(List.of(BranchScoutFixtures.A1, BranchScoutFixtures.B1,
                        BranchScoutFixtures.A2, BranchScoutFixtures.B2),
                paths(candidates));
    }

    // ---------------------------------------------------------------- 去重

    @Test
    void duplicateFileCandidatesDoNotProduceDuplicateMergedEntries() {
        // b 支在多个区域重复给出同一个文件；另外两支正常。
        BranchScoutFixtures.ScriptedFileScoutGateway gateway =
                new BranchScoutFixtures.ScriptedFileScoutGateway(threeBranchScript());

        RepositoryFileCandidates candidates = runner(gateway, 18).run(threeBranches());

        assertEquals(candidates.size(), paths(candidates).stream().distinct().count(),
                "合并结果里不得出现重复文件");
    }

    @Test
    void sameFileInTwoGroupsYieldsASingleMergedEntry() {
        // 终态组本应互不相交；这里防御性地构造重叠，验证合并仍不重复。
        RepositoryTerminalFileGroup shared =
                BranchScoutFixtures.group(MAP, "pkg/a", BranchScoutFixtures.A1,
                        BranchScoutFixtures.A2, BranchScoutFixtures.A3);
        RepositoryFileCandidates candidates = runner(
                new BranchScoutFixtures.ScriptedFileScoutGateway(List.of(
                        List.of(List.of(BranchScoutFixtures.A1),
                                List.of(BranchScoutFixtures.A2),
                                List.of(BranchScoutFixtures.A3)),
                        List.of(List.of(BranchScoutFixtures.A3),
                                List.of(BranchScoutFixtures.B1),
                                List.of(BranchScoutFixtures.B2)))),
                18).run(BranchScoutFixtures.navigation(0, shared,
                        BranchScoutFixtures.group(MAP, "pkg/b", BranchScoutFixtures.A3,
                                BranchScoutFixtures.B1, BranchScoutFixtures.B2)));

        // 两组的并集是 {A1, A2, A3, B1, B2} 五个文件；重叠的 A3 只出现一次。
        assertEquals(5, candidates.size());
        assertEquals(List.of(BranchScoutFixtures.A1, BranchScoutFixtures.A3,
                        BranchScoutFixtures.A2, BranchScoutFixtures.B1,
                        BranchScoutFixtures.B2),
                paths(candidates));
        assertEquals(candidates.size(), paths(candidates).stream().distinct().count());
    }

    // ---------------------------------------------------------------- 直接文件组

    @Test
    void directFileGroupsParticipateDeterministicallyInTraversalPosition() {
        // 仓库根目录直属组排在子分支之前——那是结构约定，不是模型给出的优先关系；
        // 这里只断言它**确定性地**参与轮转。
        RepositoryTerminalFileGroup rootDirect = BranchScoutFixtures.group(MAP, "",
                BranchScoutFixtures.A1);
        BranchScoutFixtures.ScriptedFileScoutGateway gateway =
                new BranchScoutFixtures.ScriptedFileScoutGateway(List.of(
                        List.of(List.of(BranchScoutFixtures.A1),
                                List.of(BranchScoutFixtures.A1),
                                List.of(BranchScoutFixtures.A1)),
                        List.of(List.of(BranchScoutFixtures.B1),
                                List.of(BranchScoutFixtures.B2),
                                List.of(BranchScoutFixtures.B1))));

        RepositoryFileCandidates candidates = runner(gateway, 18).run(
                BranchScoutFixtures.navigation(0, rootDirect,
                        BranchScoutFixtures.group(MAP, "pkg/b", BranchScoutFixtures.B1,
                                BranchScoutFixtures.B2)));

        assertEquals(List.of(BranchScoutFixtures.A1, BranchScoutFixtures.B1,
                        BranchScoutFixtures.B2),
                paths(candidates), "直属组在该位置参与第一轮");
    }

    // ---------------------------------------------------------------- 预算

    @Test
    void exactTotalBudgetSucceeds() {
        // Region Scout 用满 12，剩 6 次刚好够六个终态组：12 + 6 = 18。
        BranchScoutFixtures.ScriptedFileScoutGateway gateway =
                new BranchScoutFixtures.ScriptedFileScoutGateway(oneFilePerGroupScript(6));

        RepositoryFileCandidates candidates =
                runner(gateway, 18).run(oneFilePerGroup(12, 6));

        assertEquals(18, candidates.totalScoutCalls(), "刚好用满，必须成功");
        assertEquals(12, candidates.regionScoutCalls());
        assertEquals(6, candidates.fileScoutCalls());
        assertEquals(6, gateway.calls());
    }

    @Test
    void nextCallBeyondTotalBudgetFailsBeforeGateway() {
        // 12 + 6 已经用满，第七个终态组必须在调用之前被挡下。
        BranchScoutFixtures.ScriptedFileScoutGateway gateway =
                new BranchScoutFixtures.ScriptedFileScoutGateway(oneFilePerGroupScript(7));

        ScoutCallBudgetExceededException failure = assertThrows(
                ScoutCallBudgetExceededException.class,
                () -> runner(gateway, 18).run(oneFilePerGroup(12, 7)));

        assertTrue(failure.getMessage()
                .contains(ScoutCallBudgetExceededException.MAX_TOTAL_SCOUT_CALLS_EXCEEDED));
        assertEquals(6, gateway.calls(), "被挡下的那一次不得触达模型");
    }

    @Test
    void regionScoutCallsBeyondTheRegionBudgetFailsBeforeAnyGatewayCall() {
        // 13 + 1 = 14 也没超过总数 18，但已经违反「Region Scout ≤ 12」这条独立上限。
        // 导航结果是公开类型，Runner 不能把这条前置条件交给调用方自觉。
        BranchScoutFixtures.ScriptedFileScoutGateway gateway =
                new BranchScoutFixtures.ScriptedFileScoutGateway(oneFilePerGroupScript(1));

        RegionNavigationBudgetExceededException failure = assertThrows(
                RegionNavigationBudgetExceededException.class,
                () -> runner(gateway, 18).run(oneFilePerGroup(13, 1)));

        assertTrue(failure.getMessage()
                .contains(RegionNavigationBudgetExceededException.MAX_REGION_SCOUT_CALLS_EXCEEDED));
        assertEquals(0, gateway.calls(), "前置条件不成立时一次 Gateway 调用都不该发生");
    }

    @Test
    void regionScoutCallsExactlyAtTheRegionBudgetSucceeds() {
        BranchScoutFixtures.ScriptedFileScoutGateway gateway =
                new BranchScoutFixtures.ScriptedFileScoutGateway(oneFilePerGroupScript(1));

        RepositoryFileCandidates candidates =
                runner(gateway, 18).run(oneFilePerGroup(12, 1));

        assertEquals(13, candidates.totalScoutCalls());
        assertEquals(1, gateway.calls());
    }

    // ---------------------------------------------------------------- 失败

    @Test
    void fileScoutFailureFailsTheWholeRunWithoutPartialResult() {
        BranchScoutFixtures.ScriptedFileScoutGateway gateway =
                new BranchScoutFixtures.ScriptedFileScoutGateway(List.of(
                        List.of(List.of(BranchScoutFixtures.A1),
                                List.of(BranchScoutFixtures.A2),
                                List.of(BranchScoutFixtures.A3)),
                        List.of(List.of("RF-9"), List.of(BranchScoutFixtures.B1),
                                List.of(BranchScoutFixtures.B2)),
                        List.of(List.of(BranchScoutFixtures.C1),
                                List.of(BranchScoutFixtures.C2),
                                List.of(BranchScoutFixtures.C3))));

        assertThrows(AiGatewayException.class,
                () -> runner(gateway, 18).run(threeBranches()));
        assertEquals(2, gateway.calls(), "a 支成功、b 支失败即整次失败");
    }

    // ---------------------------------------------------------------- 参数

    @Test
    void rejectsInvalidConstructionAndNullNavigation() {
        BranchScoutFixtures.ScriptedFileScoutGateway gateway =
                new BranchScoutFixtures.ScriptedFileScoutGateway(threeBranchScript());

        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryBranchScoutRunner(
                        null, REGION_BUDGET, new ScoutCallBudget(18)));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryBranchScoutRunner(
                        new RepositoryScoutExtraction(gateway, MAPPER), null,
                        new ScoutCallBudget(18)));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryBranchScoutRunner(
                        new RepositoryScoutExtraction(gateway, MAPPER), REGION_BUDGET, null));
        assertThrows(IllegalArgumentException.class, () -> new ScoutCallBudget(0));
        assertThrows(IllegalArgumentException.class, () -> new RegionRecursionBudget(0, 12));
        assertThrows(IllegalArgumentException.class, () -> new RegionRecursionBudget(8, 0));
        assertThrows(IllegalArgumentException.class,
                () -> runner(gateway, 18).run(null));
    }
}
