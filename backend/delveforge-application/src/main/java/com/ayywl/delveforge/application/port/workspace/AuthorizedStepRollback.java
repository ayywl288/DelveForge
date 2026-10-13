package com.ayywl.delveforge.application.port.workspace;

import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanStateException;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanStatus;
import com.ayywl.delveforge.domain.evolution.EvolutionStep;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepStatus;
import com.ayywl.delveforge.domain.evolution.RollbackAuthorization;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;
import com.ayywl.delveforge.domain.evolution.WorkingCopyStatus;

/** 从本次 ROLLING_BACK 检查点构造；客户端不能提供任意仓库或目标 revision。 */
public final class AuthorizedStepRollback {
    private final WorkspaceRef workingCopy;
    private final WorkspaceRef originalAsset;
    private final RollbackAuthorization authorization;

    private AuthorizedStepRollback(WorkspaceRef copy, WorkspaceRef original, RollbackAuthorization authorization) {
        this.workingCopy = copy;
        this.originalAsset = original;
        this.authorization = authorization;
    }

    public static AuthorizedStepRollback from(EvolutionPlan plan, EvolutionStepId id, WorkingCopy copy,
            WorkspaceRef original, String authorizationId) {
        EvolutionStep step = plan.step(id);
        RollbackAuthorization authorization = step.rollbackAuthorization();
        if (plan.status() != EvolutionPlanStatus.ACTIVE || step.status() != EvolutionStepStatus.ROLLING_BACK
                || authorization == null || !authorization.id().equals(authorizationId) || step.rollbackResult() != null
                || !copy.id().value().equals(plan.workingCopyId()) || !copy.sourceAssetId().equals(plan.baseAssetId())
                || copy.status() != WorkingCopyStatus.EVOLVING
                || !authorization.baselineRevision().equals(step.baselineRevision())
                || !authorization.baselineRevision().equals(copy.lastVerifiedRevision())
                || !authorization.candidateRevision().equals(copy.currentRevision())
                || !authorization.candidateRevision().equals(step.executionResult().candidateRevision())) {
            throw new EvolutionPlanStateException("Rollback capability requires its matching authorization checkpoint");
        }
        return new AuthorizedStepRollback(new WorkspaceRef(copy.location()), original, authorization);
    }

    public WorkspaceRef workingCopy() { return workingCopy; }
    public WorkspaceRef originalAsset() { return originalAsset; }
    public RollbackAuthorization authorization() { return authorization; }
}
