package com.ayywl.delveforge.application.evolution.preparation;

import com.ayywl.delveforge.domain.evolution.*;

/** 调用内依据，Task 2 必须重新核对，不能作为持久执行授权令牌。 */
public record ExecutionPreparationBasis(EvolutionPlanId planId, EvolutionStepId stepId,
        WorkingCopyId workingCopyId, String expectedRevision) {}
