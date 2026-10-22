package com.ayywl.delveforge.application.evolution.planning;

import java.util.List;

/**
 * AI 协议中的 Step 候选，不携带领域身份、生命周期状态或执行授权。
 */
public record AiPlanningStepProposal(String goal, String scope, List<String> plannedChanges) {
    public AiPlanningStepProposal {
        plannedChanges = List.copyOf(plannedChanges);

    }
}
