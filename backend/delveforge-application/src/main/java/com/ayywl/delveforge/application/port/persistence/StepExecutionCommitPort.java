package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.evolution.*;

/** 一个事务提交当前 Step 与 WorkingCopy 的完整检查点；依据改变时必须全部拒绝。 */
public interface StepExecutionCommitPort {
    void commit(EvolutionPlan expectedPlan, EvolutionStepId stepId, WorkingCopy expectedCopy, SoftwareAsset asset,
            EvolutionPlan updatedPlan, WorkingCopy updatedCopy);
}
