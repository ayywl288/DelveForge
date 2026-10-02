package com.ayywl.delveforge.application.repositoryanalysis.region;

/**
 * 执行下一次 Scout 会超出 {@link ScoutCallBudget} 允许的调用总数。
 *
 * <p>这是**整次分析**的守卫，两条通道合并计数：层分下降已经用掉多少次 Region Scout，
 * 就直接决定终态分支还能调用几次 File Scout。
 *
 * <p>稳定标识：{@code MAX_TOTAL_SCOUT_CALLS_EXCEEDED}。
 *
 * <p>超出即失败关闭：**被挡下的那一次不会触达模型**，已产生的分支结果也不会被交出去。
 * 截断终态组、只跑一部分分支然后返回一个看起来完整的合并结果，等于让调用方以为
 * 「这个仓库就是这些候选」——而真实情况是预算不够（ADR-0005）。
 */
public class ScoutCallBudgetExceededException extends RuntimeException {

    /** 整次分析的 Scout 调用总数用尽。 */
    public static final String MAX_TOTAL_SCOUT_CALLS_EXCEEDED =
            "MAX_TOTAL_SCOUT_CALLS_EXCEEDED";

    public ScoutCallBudgetExceededException(String detail) {
        super(detail);
    }
}
