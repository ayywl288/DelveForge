package com.ayywl.delveforge.domain.evolution;

import java.util.List;

/**
 * 表达待接受的工程增量，不携带身份、授权、执行结果或具体命令。
 * EvolutionStep 接受其字段形成正式定义，不长期持有这个提案对象。
 */
public record PlanningStepProposal(String goal, String scope, List<String> plannedChanges,
                                   List<VerificationCriterion> verificationCriteria) {
    public PlanningStepProposal {
        goal = PlanningContent.text(goal, "step.goal");
        scope = PlanningContent.text(scope, "step.scope");
        plannedChanges = PlanningContent.section(plannedChanges, "step.plannedChanges", true);
        verificationCriteria = PlanningContent.criteria(verificationCriteria);
    }
}
