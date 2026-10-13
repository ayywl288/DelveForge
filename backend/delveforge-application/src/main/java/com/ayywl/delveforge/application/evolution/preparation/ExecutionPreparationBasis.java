package com.ayywl.delveforge.application.evolution.preparation;

import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.WorkingCopyId;

/** 调用内修改依据：首次执行指向可信 A，Repair 指向授权 Candidate B；仍需重查持久化检查点。 */
public record ExecutionPreparationBasis(EvolutionPlanId planId, EvolutionStepId stepId,
        WorkingCopyId workingCopyId, String expectedRevision, String repairAuthorizationId) {
    public ExecutionPreparationBasis(EvolutionPlanId planId, EvolutionStepId stepId,
            WorkingCopyId workingCopyId, String expectedRevision) {
        this(planId, stepId, workingCopyId, expectedRevision, null);
    }
}
