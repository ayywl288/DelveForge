package com.ayywl.delveforge.application.evolution.planning;

import java.util.List;
import com.ayywl.delveforge.domain.evolution.VerificationCriterion;

/**
 * AI 协议中的 Step 候选，不携带领域身份、生命周期状态或执行授权。
 */
public record AiPlanningStepProposal(String goal, String scope, List<String> plannedChanges,
        List<VerificationCriterion> verificationCriteria) {
    public AiPlanningStepProposal {
        plannedChanges = List.copyOf(plannedChanges);
        verificationCriteria = List.copyOf(verificationCriteria);
    }
}
