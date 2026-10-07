package com.ayywl.delveforge.domain.evolution;

import java.util.Set;

/**
 * 只判断 Plan / WorkingCopy 可见的生命周期和可信基线；外部前置条件仍由 Application 检查。
 * 本策略不会设置 baselineRevision，也不会开始 Execution。
 */
public final class StepExecutionPolicy {
    private static final Set<EvolutionStepStatus> OCCUPIED = Set.of(EvolutionStepStatus.EXECUTING,
            EvolutionStepStatus.VERIFYING, EvolutionStepStatus.VERIFICATION_FAILED,
            EvolutionStepStatus.ROLLING_BACK, EvolutionStepStatus.RECOVERY_REQUIRED);

    public void requireConfirmationAllowed(EvolutionPlan plan, EvolutionStepId id, WorkingCopy copy) {
        requireAllowed(plan, id, copy, EvolutionStepStatus.PENDING_CONFIRMATION);
    }

    public void requireExecutionAllowed(EvolutionPlan plan, EvolutionStepId id, WorkingCopy copy) {
        requireAllowed(plan, id, copy, EvolutionStepStatus.READY);
    }

    private void requireAllowed(EvolutionPlan plan, EvolutionStepId id, WorkingCopy copy, EvolutionStepStatus expected) {
        EvolutionStep step = plan.step(id);
        if (plan.status() != EvolutionPlanStatus.ACTIVE || step.status() != expected
                || copy == null || !copy.id().value().equals(plan.workingCopyId())
                || !copy.sourceAssetId().equals(plan.baseAssetId())
                || (copy.status() != WorkingCopyStatus.READY && copy.status() != WorkingCopyStatus.EVOLVING)
                || copy.lastVerifiedRevision() == null || copy.lastVerifiedRevision().isBlank()
                || !copy.lastVerifiedRevision().equals(copy.currentRevision())) {
            throw new EvolutionPlanStateException("Step requires an ACTIVE Plan and its trusted available Working Copy");
        }
        for (EvolutionStep preceding : plan.steps()) {
            if (preceding.id().equals(id)) {
                break;
            }
            if (preceding.status() != EvolutionStepStatus.SUCCEEDED) {
                throw new EvolutionPlanStateException("Every preceding required Step must have succeeded");
            }
        }
        if (plan.steps().stream().anyMatch(other -> OCCUPIED.contains(other.status()))) {
            throw new EvolutionPlanStateException("Working Copy has an unresolved Step flow");
        }
    }
}
