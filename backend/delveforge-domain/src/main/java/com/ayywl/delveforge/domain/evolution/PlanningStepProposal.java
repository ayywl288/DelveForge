package com.ayywl.delveforge.domain.evolution;

import java.util.List;

/**
 * 表达工程增量，不携带身份、授权、执行结果或具体命令。
 */
public record PlanningStepProposal(String goal, String scope, List<String> plannedChanges,
                                   List<String> preconditions, List<String> verificationCriteria) {
    public PlanningStepProposal {
        goal = PlanningContent.text(goal, "step.goal");
        scope = PlanningContent.text(scope, "step.scope");
        plannedChanges = PlanningContent.section(plannedChanges, "step.plannedChanges", true);
        // 首个独立增量允许没有 Step 专属依赖。
        preconditions = PlanningContent.section(preconditions, "step.preconditions", false);
        verificationCriteria = PlanningContent.section(verificationCriteria, "step.verificationCriteria", true);
    }
}
