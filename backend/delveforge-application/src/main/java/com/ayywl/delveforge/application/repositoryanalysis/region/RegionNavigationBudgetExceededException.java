package com.ayywl.delveforge.application.repositoryanalysis.region;

/**
 * 分层导航用尽了 {@link RegionRecursionBudget} 允许的探索范围。
 *
 * <p>两种耗尽方式共用本类型，各带一个稳定标识：
 *
 * <pre>
 * MAX_REGION_SCOUT_ROUNDS_EXCEEDED   沿单条分支的轮数用尽
 * MAX_REGION_SCOUT_CALLS_EXCEEDED    一次分析的总调用数用尽
 * </pre>
 *
 * <p>用尽即失败关闭：**不返回已经走出来的那一部分结果**，也不改成采样、截断或放宽上限。
 * 一次只走了一半的导航会让调用方以为「这个仓库就是这些分支」，而真实情况是预算不够。
 */
public class RegionNavigationBudgetExceededException extends RuntimeException {

    /** 沿单条分支的轮数用尽。 */
    public static final String MAX_REGION_SCOUT_ROUNDS_EXCEEDED =
            "MAX_REGION_SCOUT_ROUNDS_EXCEEDED";

    /** 一次分析的总 Region Scout 调用数用尽。 */
    public static final String MAX_REGION_SCOUT_CALLS_EXCEEDED =
            "MAX_REGION_SCOUT_CALLS_EXCEEDED";

    public RegionNavigationBudgetExceededException(String detail) {
        super(detail);
    }
}
