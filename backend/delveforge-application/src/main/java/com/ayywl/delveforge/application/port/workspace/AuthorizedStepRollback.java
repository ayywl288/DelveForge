package com.ayywl.delveforge.application.port.workspace;

import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.ExecutionFailureResult;
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
    private final ExecutionFailureResult failure;

    private AuthorizedStepRollback(WorkspaceRef copy, WorkspaceRef original, RollbackAuthorization authorization) {
        this.workingCopy = copy;
        this.originalAsset = original;
        this.authorization = authorization;
        this.failure = null;
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

    private AuthorizedStepRollback(WorkspaceRef copy, WorkspaceRef original, ExecutionFailureResult failure) {
        this.workingCopy = copy;
        this.originalAsset = original;
        this.authorization = null;
        this.failure = failure;
    }

    /** 系统触发只接受已保存的确认失败；不构造或继承 User Rollback 授权。 */
    public static AuthorizedStepRollback fromExecutionFailure(EvolutionPlan plan, EvolutionStepId id,
            WorkingCopy copy, WorkspaceRef original) {
        EvolutionStep step = plan.step(id);
        ExecutionFailureResult failure = step.executionFailureResult();
        if (plan.status() != EvolutionPlanStatus.ACTIVE || step.status() != EvolutionStepStatus.ROLLING_BACK
                || failure == null || step.automaticRollbackResult() != null || step.rollbackAuthorization() != null
                || !copy.id().value().equals(plan.workingCopyId()) || !copy.sourceAssetId().equals(plan.baseAssetId())
                || copy.status() != WorkingCopyStatus.EVOLVING
                || !failure.baselineRevision().equals(step.baselineRevision())
                || !failure.baselineRevision().equals(copy.lastVerifiedRevision())
                || !failure.startingRevision().equals(copy.currentRevision())
                || !failure.startingRevision().equals(step.executionStartRevision())) {
            throw new EvolutionPlanStateException("Automatic rollback requires its confirmed failure checkpoint");
        }
        return new AuthorizedStepRollback(new WorkspaceRef(copy.location()), original, failure);
    }

    public ExecutionFailureResult executionFailure() { return failure; }

    public String startingRevision() {
        return failure == null ? authorization.candidateRevision() : failure.startingRevision();
    }

    public String baselineRevision() {
        return failure == null ? authorization.baselineRevision() : failure.baselineRevision();
    }

    public WorkspaceRef workingCopy() { return workingCopy; }
    public WorkspaceRef originalAsset() { return originalAsset; }
    public RollbackAuthorization authorization() { return authorization; }
}
