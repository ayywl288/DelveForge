package com.ayywl.delveforge.application.repositoryanalysis.region;

import java.util.List;

/**
 * 一次分层导航的结果：有序的终态文件组，以及本次用掉的 Region Scout 调用数。
 *
 * <pre>
 * oversized source set
 *         ↓  按目录结构递归下降
 * terminal file groups（按目录结构深度优先展开）
 * </pre>
 *
 * <h2>顺序是结构遍历顺序，不是一份统一的优先级</h2>
 *
 * <p>列表按目录结构深度优先展开：同一节点被分解时，先给该节点**直属文件**的组，
 * 再按 Region Scout 的返回顺序给各选中分支的组。顺序是确定的，但其中**只有一部分**带模型含义：
 *
 * <pre>
 * 被选中的分支之间     保持 Region Scout 的返回顺序 —— 这才是 ADR-0005 说的分支优先级
 * 直属组与子分支之间   结构约定（直属在前），不是模型给出的优先关系
 * </pre>
 *
 * <p>直属文件没有被任何一次 Region Scout 排序过：它们不对应任何子目录，Scout 根本看不到它们。
 * 因此本类型**不宣称**整个列表是一份优先级——把结构顺序当成模型的取舍，会让「谁先谁后」
 * 变成一个没有人真正决定过的事实。跨组如何交织（例如保序轮转）由消费方决定，
 * 属于后续步骤。
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

    /**
     * 终态文件组，按目录结构深度优先展开。
     *
     * <p>被选中的分支之间保持 Region Scout 的返回顺序（分支优先级）；节点直属文件的组排在
     * 该节点子分支之前，那是结构约定，不是模型给出的优先关系。整个列表因此是**遍历顺序**，
     * 不是一份统一的优先级。
     */
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
