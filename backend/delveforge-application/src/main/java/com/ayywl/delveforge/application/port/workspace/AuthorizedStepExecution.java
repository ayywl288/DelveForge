package com.ayywl.delveforge.application.port.workspace;

import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationBasis;
import com.ayywl.delveforge.application.evolution.preparation.ValidatedExecutionProposal;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanStateException;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanStatus;
import com.ayywl.delveforge.domain.evolution.EvolutionStep;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepStatus;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;
import com.ayywl.delveforge.domain.evolution.WorkingCopyStatus;

/** 从已进入 EXECUTING 的完整检查点建立；客户端不能提交任意路径 / shell。 */
public final class AuthorizedStepExecution {
    private final WorkspaceRef workingCopy;
    private final WorkspaceRef originalAsset;
    private final ValidatedExecutionProposal validated;

    private AuthorizedStepExecution(WorkspaceRef copy, WorkspaceRef original, ValidatedExecutionProposal validated) {
        this.workingCopy = copy;
        this.originalAsset = original;
        this.validated = validated;
    }

    /** 仅从匹配提案依据的 EXECUTING 状态构造修改请求；不制造新的用户授权。 */
    public static AuthorizedStepExecution from(EvolutionPlan plan, EvolutionStepId stepId, WorkingCopy copy,
            WorkspaceRef original, ValidatedExecutionProposal validated) {
        EvolutionStep step = plan.step(stepId);
        ExecutionPreparationBasis basis = validated.basis();
        if (plan.status() != EvolutionPlanStatus.ACTIVE || step.status() != EvolutionStepStatus.EXECUTING
                || !basis.planId().equals(plan.id()) || !basis.stepId().equals(stepId)
                || !basis.workingCopyId().equals(copy.id()) || !copy.id().value().equals(plan.workingCopyId())
                || copy.status() != WorkingCopyStatus.EVOLVING
                || !basis.expectedRevision().equals(step.baselineRevision())
                || !basis.expectedRevision().equals(copy.currentRevision())
                || !basis.expectedRevision().equals(copy.lastVerifiedRevision())) {
            throw new EvolutionPlanStateException("Mutation requires its authorized execution checkpoint");
        }
        return new AuthorizedStepExecution(new WorkspaceRef(copy.location()), original, validated);
    }

    public WorkspaceRef workingCopy() {
        return workingCopy;
    }

    public WorkspaceRef originalAsset() {
        return originalAsset;
    }

    public ValidatedExecutionProposal validated() {
        return validated;
    }
}
