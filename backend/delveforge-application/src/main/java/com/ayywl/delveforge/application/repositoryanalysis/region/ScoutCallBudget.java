package com.ayywl.delveforge.application.repositoryanalysis.region;

/**
 * 一次仓库分析里 **Scout 调用总数**的守卫（ADR-0005）。
 *
 * <pre>
 * Scout 调用总数 = Region Scout 调用 + File Scout 调用
 * </pre>
 *
 * <h2>为什么是「总数」而不是各自的上限</h2>
 *
 * <p>早期草案给终态分支单独设了一个上限，那会让 Region Scout 与 File Scout 各自有独立额度、
 * 彼此不相干。实际被约束的是**这次分析一共能问模型多少次**——分层下降多问几次，
 * 留给分支 File Scout 的额度就该少几次。因此两条通道合并计数。
 *
 * <p>它是整次分析的守卫，与 {@link RegionNavigationLimits}（一次调用的载荷与选择）
 * 和 {@link RegionRecursionBudget}（Region Scout 的轮数与调用数）都不同层。
 *
 * <h2>这是技术/配置守卫，不是领域规则</h2>
 *
 * <p>默认值由前一项的最坏包络推算而来，不是已验证的最优值。改变它不需要改领域模型，
 * 但会改变一次分析能问模型多少次（AGENTS.md §8.9）。具体数值来自配置，不在代码里兜底。
 *
 * @param maxTotalCalls Region Scout 与 File Scout 的调用总数上限，必须大于 0
 */
public record ScoutCallBudget(int maxTotalCalls) {

    public ScoutCallBudget {
        if (maxTotalCalls <= 0) {
            throw new IllegalArgumentException(
                    "Scout 调用总数上限必须大于 0: " + maxTotalCalls);
        }
    }
}
