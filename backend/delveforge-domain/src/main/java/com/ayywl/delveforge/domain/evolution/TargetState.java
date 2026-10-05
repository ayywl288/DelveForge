package com.ayywl.delveforge.domain.evolution;

/** Selected product intent is retained verbatim; planning cannot replace it. */
public record TargetState(String problem, String targetProduct, String differentiation) {
    public TargetState {
        problem = PlanningContent.text(problem, "targetState.problem");
        targetProduct = PlanningContent.text(targetProduct, "targetState.targetProduct");
        differentiation = PlanningContent.text(differentiation, "targetState.differentiation");
    }
}
