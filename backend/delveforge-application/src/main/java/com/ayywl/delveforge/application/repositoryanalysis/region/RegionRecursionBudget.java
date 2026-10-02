package com.ayywl.delveforge.application.repositoryanalysis.region;

/**
 * 分层导航的**递归守卫**（ADR-0005 导航预算里约束「探索能走多远」的那两项）。
 *
 * <p>与 {@link RegionNavigationLimits} 分开：后者约束**一次** Region Scout 调用（目录能多大、
 * 一次能选几个区域）；本类型约束**一次仓库分析**里的递归本身。
 *
 * <h2>为什么必须有这两项</h2>
 *
 * <p>递归是「每个被选中的 Region 各自再问一次」。只按目录深度停止是不够的：每一轮都可能选中
 * 多个分支，调用数会沿着分支数增长。没有守卫，一次分析可以付出远超预期的模型调用。
 *
 * <h2>这些是技术/配置守卫，不是领域规则</h2>
 *
 * <p>两者互相独立：轮数管深度，总调用数管「深度 × 宽度」。总调用数小于单分支轮数上限是
 * 合法的组合——那只是说明深度上限不是先耗尽的那一个。
 *
 * <p>改变它们不需要改领域模型，但会改变一次分析能走多深、要调多少次模型（AGENTS.md §8.9）。
 * 具体数值来自配置，不在代码里兜底。
 *
 * @param maxRoundsPerBranch   沿**单条分支**最多能有几次 Region Scout 调用，必须大于 0
 * @param maxTotalScoutCalls   一次仓库分析最多能有几次 Region Scout 调用，必须大于 0
 */
public record RegionRecursionBudget(int maxRoundsPerBranch, int maxTotalScoutCalls) {

    public RegionRecursionBudget {
        if (maxRoundsPerBranch <= 0) {
            throw new IllegalArgumentException(
                    "沿单条分支的最大轮数必须大于 0: " + maxRoundsPerBranch);
        }
        if (maxTotalScoutCalls <= 0) {
            throw new IllegalArgumentException(
                    "一次分析的最大 Region Scout 调用数必须大于 0: " + maxTotalScoutCalls);
        }
    }
}
