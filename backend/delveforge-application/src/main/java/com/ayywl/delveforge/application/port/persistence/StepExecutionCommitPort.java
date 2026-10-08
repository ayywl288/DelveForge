package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;

/** 一个事务提交当前 Step 与 WorkingCopy 的完整检查点；依据改变时必须全部拒绝。 */
public interface StepExecutionCommitPort {

    /** expected 对象用于并发依据核对；updated 对象必须构成完整领域转移，失败不得部分写入。 */
    void commit(EvolutionPlan expectedPlan, EvolutionStepId stepId, WorkingCopy expectedCopy, SoftwareAsset asset,
            EvolutionPlan updatedPlan, WorkingCopy updatedCopy);
}
