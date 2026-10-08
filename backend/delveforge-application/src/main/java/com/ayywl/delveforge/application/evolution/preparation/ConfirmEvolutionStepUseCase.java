package com.ayywl.delveforge.application.evolution.preparation;

import com.ayywl.delveforge.application.port.persistence.StepConfirmationCommitPort;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.StepExecutionPolicy;

import java.util.Objects;

/** 确认只提交一个 Step 的 READY 状态，不读取源码、不调用 AI、不开始执行。 */
public final class ConfirmEvolutionStepUseCase {
    private final ExecutionPreparationState state;
    private final StepConfirmationCommitPort commits;
    private final StepExecutionPolicy policy;

    public ConfirmEvolutionStepUseCase(ExecutionPreparationState state, StepConfirmationCommitPort commits,
            StepExecutionPolicy policy) {
        this.state = Objects.requireNonNull(state);
        this.commits = Objects.requireNonNull(commits);
        this.policy = Objects.requireNonNull(policy);
    }

    public EvolutionPlan confirm(EvolutionPlanId planId, EvolutionStepId stepId) {
        ExecutionPreparationState.Loaded loaded = state.load(planId, stepId, true);
        loaded = state.revalidate(loaded, stepId, true);
        EvolutionPlan candidate = loaded.plan().copy();
        candidate.confirmStep(stepId, loaded.copy(), policy);
        commits.confirm(candidate, stepId, loaded.copy(), loaded.asset());
        return candidate;
    }
}
