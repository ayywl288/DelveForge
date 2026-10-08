package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;

/** 原子提交明确确认的候选，拒绝过期的 Plan / Copy / Asset 授权依据。 */
public interface StepConfirmationCommitPort {
    void confirm(EvolutionPlan candidate, EvolutionStepId stepId, WorkingCopy copy, SoftwareAsset authorizationBasis);
}
