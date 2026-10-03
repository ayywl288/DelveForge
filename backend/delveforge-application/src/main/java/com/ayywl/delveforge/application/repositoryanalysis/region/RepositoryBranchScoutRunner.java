package com.ayywl.delveforge.application.repositoryanalysis.region;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryFileReference;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryInspectionArea;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryInspectionPlan;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryScoutExtraction;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryScoutInputs;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 对分层导航产出的每一个终态文件组各跑一次 **分支本地 File Scout**，再把结果合并成
 * 一份有序候选（ADR-0005 的第 3、4 段）。
 *
 * <pre>
 * RepositoryRegionNavigation（有序终态组）
 *         ↓  逐个终态组：
 *             重新编号 → RepositoryMap → RepositoryScoutInputs → 现有 File Scout
 *             → 引用校验换回本组的原描述符 → 组内优先级顺序
 *         ↓  保序轮转合并
 * RepositoryFileCandidates
 * </pre>
 *
 * <h2>每组都是一次全新的调用</h2>
 *
 * <p>分支本地 File Scout 看到的是**它自己那一份目录**：编号按组内位置重新分配
 * （{@code RF-1}…{@code RF-n}）。每次调用都是同步的
 * 「本次目录 → 请求 → 解析 → **用同一份目录**校验引用」，两次调用之间没有传递结果或目录的
 * 路径，因此一次调用的编号不会在另一次里被解释。
 *
 * <p>但要说清编号**本身**不携带调用身份：两组各有第一个文件时，两边都是 {@code RF-1}，
 * 这个字符串在各自目录里都会成功解析，只是解出的文件不同。隔离来自「每次调用绑定自己那份
 * 目录」，不是来自编号互不相同。
 *
 * <p>File Scout 的契约一字未改：仍然是「描述符清单 → 模型 → 严格解析 → 引用校验」，
 * 复用同一个 {@link RepositoryScoutExtraction}，不另写一套解析或提示词。
 *
 * <h2>合并：保序轮转</h2>
 *
 * <pre>
 * A: A1 A2 A3        A1 B1 C1
 * B: B1 B2       →   A2 B2 C2
 * C: C1 C2 C3        A3 C3
 * </pre>
 *
 * <p>第 <i>r</i> 轮取各分支的第 <i>r</i> 个文件，按分支顺序；某一支取完就跳过。
 * 分支顺序 = 终态组的遍历顺序（其中**被 Region Scout 选中的兄弟分支之间**保持模型的取舍顺序，
 * 那是真正的分支优先级；直属文件组与子分支之间的先后只是结构约定）。
 *
 * <h2>入口先核对独立 Region 预算</h2>
 *
 * <p>{@code RepositoryRegionNavigation} 是公开类型，它声明的 Region Scout 调用数只能由调用方
 * 保证。总预算不够替代这条独立上限——`15 + 3` 也不超过 18，但它已经违反了
 * 「Region Scout ≤ 12」。因此进入任何一次 Gateway 调用之前先核对，不成立即失败关闭。
 *
 * <h2>失败是整次的</h2>
 *
 * <p>输入不满足前置条件、任意一条分支的 File Scout 失败、或下一次调用会超出 Scout 调用总数，
 * 都让整次运行以异常结束，**不返回已经跑完的那几条分支的合并结果**。
 */
public final class RepositoryBranchScoutRunner {

    private final RepositoryScoutExtraction fileScout;
    private final RegionRecursionBudget regionBudget;
    private final ScoutCallBudget budget;

    /**
     * @param fileScout    现有的 File Scout 契约实现，不得为 {@code null}
     * @param regionBudget Region Scout 的守卫；本类用它校验输入导航声明的 Region 调用数，
     *                     不得为 {@code null}
     * @param budget       整次分析的 Scout 调用总数守卫，不得为 {@code null}
     */
    public RepositoryBranchScoutRunner(RepositoryScoutExtraction fileScout,
                                       RegionRecursionBudget regionBudget,
                                       ScoutCallBudget budget) {
        if (fileScout == null) {
            throw new IllegalArgumentException(
                    "RepositoryBranchScoutRunner 必须指定 fileScout");
        }
        if (regionBudget == null) {
            throw new IllegalArgumentException(
                    "RepositoryBranchScoutRunner 必须指定 regionBudget");
        }
        if (budget == null) {
            throw new IllegalArgumentException(
                    "RepositoryBranchScoutRunner 必须指定 budget");
        }
        this.fileScout = fileScout;
        this.regionBudget = regionBudget;
        this.budget = budget;
    }

    /**
     * 逐个终态组执行 File Scout，并合并结果。
     *
     * @param navigation 分层导航的结果，不得为 {@code null}
     * @return 有序候选文件
     * @throws IllegalArgumentException                    navigation 为 {@code null}
     * @throws RegionNavigationBudgetExceededException    输入导航声明的 Region Scout 调用数超过 Region 上限
     * @throws ScoutCallBudgetExceededException            下一次调用会超出 Scout 调用总数
     * @throws com.ayywl.delveforge.application.port.ai.AiGatewayException
     *                                                     某条分支的 File Scout 调用失败或输出不合法
     */
    public RepositoryFileCandidates run(RepositoryRegionNavigation navigation) {
        if (navigation == null) {
            throw new IllegalArgumentException(
                    "RepositoryBranchScoutRunner 必须指定 navigation");
        }
        String revision = navigation.analyzedRevision();

        // 入口先核对独立 Region 预算：本类信任「导航结果说的调用数」，而那是一个公开类型，
        // 只能由调用方保证。总预算不能替代这条独立上限——15 + 3 也不会超过 18，
        // 但它已经违反了「Region Scout ≤ 12」。
        int regionScoutCalls = navigation.regionScoutCalls();
        if (regionScoutCalls > regionBudget.maxRegionScoutCalls()) {
            throw new RegionNavigationBudgetExceededException(
                    RegionNavigationBudgetExceededException.MAX_REGION_SCOUT_CALLS_EXCEEDED
                            + ": 输入导航声明用了 " + regionScoutCalls + " 次 Region Scout，"
                            + "超过 Region 上限 " + regionBudget.maxRegionScoutCalls()
                            + ": " + revision);
        }

        FileScoutAttempts attempts = new FileScoutAttempts(regionScoutCalls, revision);
        List<List<RepositoryMapEntry>> branches = new ArrayList<>();
        for (RepositoryTerminalFileGroup group : navigation.terminalFileGroups()) {
            branches.add(branchOrder(group, revision, attempts));
        }

        return RepositoryFileCandidates.of(
                revision, mergeRoundRobin(branches),
                regionScoutCalls, attempts.used());
    }

    /**
     * 一次运行的 File Scout 尝试计数与许可。
     *
     * <p>它同时承担两件事：作为 {@link ScoutAttemptPermit} 在**每次尝试之前**守住
     * 整次分析的总数上限（契约违反后的重试因此也要先过这一关），并记下这个终态分支阶段
     * 实际付出了多少次 Provider 调用。
     */
    private final class FileScoutAttempts {

        private final int regionScoutCalls;
        private final String revision;
        private int used;

        private FileScoutAttempts(int regionScoutCalls, String revision) {
            this.regionScoutCalls = regionScoutCalls;
            this.revision = revision;
        }

        private void claim(RepositoryTerminalFileGroup group) {
            int totalBefore = regionScoutCalls + used;
            if (totalBefore + 1 > budget.maxTotalCalls()) {
                throw new ScoutCallBudgetExceededException(
                        ScoutCallBudgetExceededException.MAX_TOTAL_SCOUT_CALLS_EXCEEDED
                                + ": 本次分析已用掉 Region Scout " + regionScoutCalls
                                + " 次、File Scout " + used + " 次，为终态组「"
                                + describe(group) + "」再调一次将超过总数上限 "
                                + budget.maxTotalCalls() + ": " + revision);
            }
            used++;
        }

        private int used() {
            return used;
        }
    }

    /**
     * 一个终态组的组内优先级顺序。
     *
     * <p>本组先被重新编号成一次独立调用的目录，File Scout 返回的引用再由它自己校验、
     * 换回**本组在 Map 上的原描述符**。于是返回值既带着模型表达的组内顺序，
     * 又不引入任何跨调用的编号。
     *
     * <p>调用经由 {@code attempts} 许可：第一次尝试与契约违反后的重试都各自占用一次额度。
     */
    private List<RepositoryMapEntry> branchOrder(RepositoryTerminalFileGroup group,
                                                 String revision,
                                                 FileScoutAttempts attempts) {
        List<RepositoryMapEntry> localCatalog = new ArrayList<>(group.size());
        Map<RepositoryFileReference, RepositoryMapEntry> originals = new LinkedHashMap<>();
        for (int index = 0; index < group.size(); index++) {
            RepositoryMapEntry original = group.sourceFiles().get(index);
            RepositoryFileReference reference = RepositoryFileReference.of(index + 1);
            originals.put(reference, original);
            localCatalog.add(new RepositoryMapEntry(
                    reference,
                    original.relativePath(),
                    original.sizeInBytes(),
                    original.language(),
                    original.materialKind(),
                    original.roleHints()));
        }

        RepositoryInspectionPlan plan = fileScout.scout(
                RepositoryScoutInputs.of(RepositoryMap.of(revision, localCatalog)),
                () -> attempts.claim(group));

        // 区域顺序 = 模型表达的优先级；同一文件出现在多个区域时只保留首次出现。
        LinkedHashSet<RepositoryMapEntry> ordered = new LinkedHashSet<>();
        for (RepositoryInspectionArea area : plan.areas()) {
            for (RepositoryMapEntry entry : area.entries()) {
                ordered.add(originals.get(entry.reference()));
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * 保序轮转合并：第 r 轮按分支顺序取各分支的第 r 个文件，取完的分支跳过。
     *
     * <p>去重按**文件本身**（原描述符）进行：同一文件不会进入两份终态组，
     * 但防御性地再拦一次，保证合并结果里不会出现同一个文件两次。
     */
    private static List<RepositoryMapEntry> mergeRoundRobin(
            List<List<RepositoryMapEntry>> branches) {
        int longest = 0;
        for (List<RepositoryMapEntry> branch : branches) {
            longest = Math.max(longest, branch.size());
        }

        List<RepositoryMapEntry> merged = new ArrayList<>();
        LinkedHashSet<RepositoryMapEntry> seen = new LinkedHashSet<>();
        for (int round = 0; round < longest; round++) {
            for (List<RepositoryMapEntry> branch : branches) {
                if (round < branch.size() && seen.add(branch.get(round))) {
                    merged.add(branch.get(round));
                }
            }
        }
        return merged;
    }

    private static String describe(RepositoryTerminalFileGroup group) {
        return group.pathPrefix().isEmpty() ? "仓库根目录" : group.pathPrefix();
    }
}
