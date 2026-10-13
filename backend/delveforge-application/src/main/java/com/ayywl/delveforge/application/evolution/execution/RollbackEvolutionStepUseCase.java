package com.ayywl.delveforge.application.evolution.execution;

import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationState;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationState.Loaded;
import com.ayywl.delveforge.application.port.persistence.StepExecutionCommitPort;
import com.ayywl.delveforge.application.port.workspace.AuthorizedStepRollback;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyRollbackException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRollbackPort;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.RollbackResult;
import com.ayywl.delveforge.domain.evolution.StepExecutionPolicy;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;

import java.time.Instant;

/** 单次显式 Rollback；授权先提交，恢复与 SQLite 分离，未知结果只保存阻塞事实。 */
public final class RollbackEvolutionStepUseCase {
    private final ExecutionPreparationState state;
    private final StepExecutionPolicy policy;
    private final StepExecutionCommitPort commits;
    private final WorkspaceRollbackPort rollback;

    public RollbackEvolutionStepUseCase(ExecutionPreparationState state, StepExecutionPolicy policy,
            StepExecutionCommitPort commits, WorkspaceRollbackPort rollback) {
        this.state = state;
        this.policy = policy;
        this.commits = commits;
        this.rollback = rollback;
    }

    public EvolutionPlan rollback(EvolutionPlanId planId, EvolutionStepId id, String candidate, String baseline,
            String authorizationId) {
        Loaded failed = state.loadRollbackCandidate(planId, id);
        EvolutionPlan authorized = failed.plan().copy();
        WorkingCopy retained = failed.copy().copy();
        authorized.authorizeStepRollback(id, retained, policy, authorizationId, candidate, baseline, Instant.now());
        commits.commit(failed.plan(), id, failed.copy(), failed.asset(), authorized, retained);
        Loaded rolling = new Loaded(authorized, retained, failed.asset());
        String observed = null;
        try {
            rolling = state.requireCheckpoint(rolling, id, candidate);
            WorkspaceRollbackPort.RestoredBaseline restored = rollback.restoreBaseline(AuthorizedStepRollback.from(
                    rolling.plan(), id, rolling.copy(), new WorkspaceRef(rolling.asset().location()), authorizationId));
            observed = restored.revision();
            if (observed != null && !observed.matches("[0-9a-f]{40}")) {
                observed = null;
            }
            if (!baseline.equals(observed) || restored.restoredAt() == null) {
                throw new EvolutionExecutionException("Workspace did not confirm the exact authorized baseline");
            }
            rolling = state.requireCheckpoint(rolling, id, baseline);
            return finish(rolling, id, new RollbackResult(authorizationId, baseline, observed, true,
                    "EXACT_BASELINE_CONFIRMED", restored.restoredAt()));
        } catch (RuntimeException failure) {
            if (failure instanceof WorkingCopyRollbackException detail) {
                observed = detail.observedRevision();
            }
            String diagnostic = failure instanceof WorkingCopyRollbackException detail
                    ? "RESTORATION_NOT_CONFIRMED:" + detail.phase()
                            + (detail.exitCode() == null ? "" : ":exit=" + detail.exitCode())
                    : "ROLLBACK_NOT_COMMITTED:" + failure.getClass().getSimpleName();
            // 中断标志不应阻止安全事实落盘；完成后恢复标志，不重跑 Git。
            boolean interrupted = Thread.interrupted();
            try {
                return finish(rolling, id, new RollbackResult(authorizationId, baseline, observed, false,
                        diagnostic, Instant.now()));
            } catch (RuntimeException checkpointFailure) {
                checkpointFailure.addSuppressed(failure);
                throw checkpointFailure;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private EvolutionPlan finish(Loaded rolling, EvolutionStepId id, RollbackResult result) {
        EvolutionPlan completed = rolling.plan().copy();
        WorkingCopy copy = rolling.copy().copy();
        completed.finishStepRollback(id, copy, result);
        commits.commit(rolling.plan(), id, rolling.copy(), rolling.asset(), completed, copy);
        return completed;
    }
}
