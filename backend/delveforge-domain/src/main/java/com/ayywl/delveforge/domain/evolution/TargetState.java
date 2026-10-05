package com.ayywl.delveforge.domain.evolution;

/**
 * 逐字保留选定的产品意图，规划不能替换它。
 */
public record TargetState(String problem, String targetProduct, String differentiation) {
    public TargetState {
        problem = PlanningContent.text(problem, "targetState.problem");
        targetProduct = PlanningContent.text(targetProduct, "targetState.targetProduct");
        differentiation = PlanningContent.text(differentiation, "targetState.differentiation");
    }
}
