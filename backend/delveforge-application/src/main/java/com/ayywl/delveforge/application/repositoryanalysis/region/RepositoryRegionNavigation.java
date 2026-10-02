package com.ayywl.delveforge.application.repositoryanalysis.region;

import java.util.List;

/**
 * 一次分层导航的结果：有序的终态文件组，以及本次用掉的 Region Scout 调用数。
 *
 * <pre>
 * oversized source set
 *         ↓  按目录结构递归下降
 * terminal file groups（顺序即分支优先级）
 * </pre>
 *
 * <h2>顺序就是分支优先级</h2>
 *
 * <p>列表顺序由 Region Scout 的选择顺序决定：越靠前的分支越应该先被处理。
 * 同一节点被分解时，**先给该节点直属文件的组，再按顺序给各选中分支的组**——
 * 直属文件就在这一层，不属于任何一个子分支。
 *
 * <p>顺序是确定的：Region Scout 的返回顺序确定，目录结构的遍历顺序确定。
 *
 * <h2>它不读文件、不调用 File Scout</h2>
 *
 * <p>结果只是「哪几组文件、按什么顺序」；对每一组跑分支本地 File Scout、以及把多组结果合并，
 * 都属于后续步骤。本类型不含任何文件内容。
 *
 * <h2>要么完整，要么没有</h2>
 *
 * <p>导航失败时不会产生本对象：预算耗尽、目录超限、结构不可再分、模型输出不合法——
 * 任一种都以异常结束，不存在「走到一半的结果」。
 */
public final class RepositoryRegionNavigation {

    private final String analyzedRevision;
    private final List<RepositoryTerminalFileGroup> terminalFileGroups;
    private final int regionScoutCalls;

    private RepositoryRegionNavigation(String analyzedRevision,
                                       List<RepositoryTerminalFileGroup> terminalFileGroups,
                                       int regionScoutCalls) {
        this.analyzedRevision = analyzedRevision;
        this.terminalFileGroups = terminalFileGroups;
        this.regionScoutCalls = regionScoutCalls;
    }

    /**
     * 建立一次导航结果。
     *
     * @param analyzedRevision   本次导航固定的 commit id，不得为空白
     * @param terminalFileGroups 有序的终态文件组，不得为 {@code null} 或空，元素不得为 {@code null}
     * @param regionScoutCalls   本次用掉的 Region Scout 调用数，不得为负数
     * @throws IllegalArgumentException 参数不满足上述约束
     */
    public static RepositoryRegionNavigation of(
            String analyzedRevision,
            List<RepositoryTerminalFileGroup> terminalFileGroups,
            int regionScoutCalls) {
        if (analyzedRevision == null || analyzedRevision.isBlank()) {
            throw new IllegalArgumentException(
                    "RepositoryRegionNavigation 必须指定 analyzedRevision");
        }
        if (terminalFileGroups == null || terminalFileGroups.isEmpty()) {
            throw new IllegalArgumentException(
                    "RepositoryRegionNavigation 的 terminalFileGroups 不能为空");
        }
        if (regionScoutCalls < 0) {
            throw new IllegalArgumentException(
                    "RepositoryRegionNavigation 的 regionScoutCalls 不能为负数: "
                            + regionScoutCalls);
        }
        return new RepositoryRegionNavigation(
                analyzedRevision, List.copyOf(terminalFileGroups), regionScoutCalls);
    }

    /** 本次导航固定的 commit id。 */
    public String analyzedRevision() {
        return analyzedRevision;
    }

    /** 有序的终态文件组，顺序即分支优先级。 */
    public List<RepositoryTerminalFileGroup> terminalFileGroups() {
        return terminalFileGroups;
    }

    /**
     * 本次导航用掉的 Region Scout 调用数。
     *
     * <p>用于观测与验证守卫是否按预期生效；不含任何模型输出。
     */
    public int regionScoutCalls() {
        return regionScoutCalls;
    }

    /** 全部终态组里的文件总数。 */
    public int totalSourceFiles() {
        int total = 0;
        for (RepositoryTerminalFileGroup group : terminalFileGroups) {
            total += group.size();
        }
        return total;
    }
}
