package com.ayywl.delveforge.domain.evolution;

import java.util.Set;

/**
 * 只判断 Plan / WorkingCopy 可见的生命周期和可信基线；资产授权与物理 revision 仍由 Application / Workspace 检查。
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

    /** 允许修复当前失败 Candidate；仅此入口允许 current 与 lastVerified 不同。 */
    public void requireRepairAllowed(EvolutionPlan plan, EvolutionStepId id, WorkingCopy copy) {
        EvolutionStep step = plan.step(id);
        if (plan.status() != EvolutionPlanStatus.ACTIVE || step.status() != EvolutionStepStatus.VERIFICATION_FAILED
                || copy == null || copy.status() != WorkingCopyStatus.EVOLVING
                || !copy.id().value().equals(plan.workingCopyId()) || !copy.sourceAssetId().equals(plan.baseAssetId())
                || !step.baselineRevision().equals(copy.lastVerifiedRevision())
                || !step.executionResult().candidateRevision().equals(copy.currentRevision())
                || !step.verificationResult().confirmsFailure(step.verificationCriteria())) {
            throw new EvolutionPlanStateException("Repair requires the bound failed Candidate and unchanged trusted baseline");
        }
        for (EvolutionStep preceding : plan.steps()) {
            if (preceding.id().equals(id)) {
                break;
            }
            if (preceding.status() != EvolutionStepStatus.SUCCEEDED) {
                throw new EvolutionPlanStateException("Every preceding required Step must have succeeded");
            }
        }
        if (plan.steps().stream().anyMatch(other -> !other.id().equals(id) && OCCUPIED.contains(other.status()))) {
            throw new EvolutionPlanStateException("Working Copy has another unresolved Step flow");
        }
    }

    private void requireAllowed(EvolutionPlan plan, EvolutionStepId id, WorkingCopy copy,
            EvolutionStepStatus expected) {
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
