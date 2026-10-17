package com.ayywl.delveforge.application.evolution.execution;

import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationState;
import com.ayywl.delveforge.application.evolution.preparation.PrepareEvolutionStepUseCase;
import com.ayywl.delveforge.application.port.persistence.StepExecutionCommitPort;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.StepExecutionPolicy;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;

import java.time.Instant;

/** 仅持久化 FAILED → READY 的独立 Retry 授权；不调用 Provider，也不开始代码执行。 */
public final class RetryEvolutionStepUseCase {
    private final ExecutionPreparationState state;
    private final StepExecutionPolicy policy;
    private final StepExecutionCommitPort commits;

    public RetryEvolutionStepUseCase(ExecutionPreparationState state, StepExecutionPolicy policy,
            StepExecutionCommitPort commits) {
        this.state = state;
        this.policy = policy;
        this.commits = commits;
    }

    public EvolutionPlan retry(EvolutionPlanId planId, EvolutionStepId stepId, String failureId, String baseline,
            String authorizationId) {
        ExecutionPreparationState.Loaded loaded = state.loadRetryFailure(planId, stepId);
        PrepareEvolutionStepUseCase.requireSupportedCriteria(loaded.plan().step(stepId));
        EvolutionPlan authorized = loaded.plan().copy();
        WorkingCopy retained = loaded.copy().copy();
        authorized.authorizeStepRetry(stepId, retained, policy, authorizationId, failureId, baseline, Instant.now());
        commits.commit(loaded.plan(), stepId, loaded.copy(), loaded.asset(), authorized, retained);
        return authorized;
    }
}
