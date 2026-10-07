package com.ayywl.delveforge.domain.evolution;

import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import java.util.HashSet;
import java.util.List;

/**
 * 规划与生命周期的 Aggregate Root；激活不会授权其 EvolutionStep。
 */
public final class EvolutionPlan {
    private final EvolutionPlanId id;
    private final ProductDirectionId productDirectionId;
    private final SoftwareAssetId baseAssetId;
    private final RepositoryProfileId baseRepositoryProfileId;
    private final CurrentState currentState;
    private final TargetState targetState;
    private final List<String> reusableCapabilities;
    private final List<String> changes;
    private final List<EvolutionStep> steps;
    private final List<String> risks;
    private final List<EvidenceBasis> evidence;
    private WorkingCopyId workingCopyId;
    private EvolutionPlanStatus status = EvolutionPlanStatus.PROPOSED;

    EvolutionPlan(EvolutionPlanId id, ProductDirectionId directionId, SoftwareAssetId assetId,
            RepositoryProfileId profileId, CurrentState currentState, TargetState targetState,
            List<String> reusableCapabilities, List<String> changes, List<EvolutionStep> steps,
            List<String> risks, List<EvidenceBasis> evidence) {
        if (id == null || directionId == null || assetId == null || profileId == null
                || currentState == null || targetState == null || steps == null || evidence == null) {
            throw new IllegalArgumentException("Plan identity and planning basis are required");
        }
        if (steps.isEmpty() || evidence.isEmpty()) {
            throw new EvolutionPlanningRejectedException("Plan requires steps and traceable evidence");
        }
        if (steps.stream().anyMatch(step -> step == null)
                || new HashSet<>(steps.stream().map(EvolutionStep::id).toList()).size() != steps.size()) {
            throw new IllegalArgumentException("Step identities must be unique and complete");
        }
        if (steps.stream().anyMatch(step -> !id.equals(step.planId()))) {
            throw new IllegalArgumentException("Every Step must belong to its Plan");
        }
        this.id = id;
        this.productDirectionId = directionId;
        this.baseAssetId = assetId;
        this.baseRepositoryProfileId = profileId;
        this.currentState = currentState;
        this.targetState = targetState;
        this.reusableCapabilities = PlanningContent.section(reusableCapabilities, "reusableCapabilities", false);
        this.changes = PlanningContent.section(changes, "changes", true);
        this.steps = List.copyOf(steps);
        this.risks = PlanningContent.section(risks, "risks", false);
        this.evidence = List.copyOf(evidence);
    }

    /**
     * 还原已保存的规划事实，不重新判断可变的当前 Direction / Asset 资格。
     * 即使依据已不再满足新规划条件，历史 EvolutionPlan 仍应可读。
     */
    public static EvolutionPlan reconstitute(EvolutionPlanId id, ProductDirectionId directionId,
            SoftwareAssetId assetId, RepositoryProfileId profileId, CurrentState currentState, TargetState targetState,
            List<String> reusableCapabilities, List<String> changes, List<EvolutionStep> steps,
            List<String> risks, List<EvidenceBasis> evidence) {
        return new EvolutionPlan(id, directionId, assetId, profileId, currentState, targetState,
                reusableCapabilities, changes, steps, risks, evidence);
    }

    public static EvolutionPlan reconstitute(EvolutionPlanId id, ProductDirectionId directionId,
            SoftwareAssetId assetId, RepositoryProfileId profileId, CurrentState currentState, TargetState targetState,
            List<String> reusableCapabilities, List<String> changes, List<EvolutionStep> steps,
            List<String> risks, List<EvidenceBasis> evidence, WorkingCopyId workingCopyId, EvolutionPlanStatus status) {
        if (status == null || status == EvolutionPlanStatus.COMPLETED) {
            throw new IllegalArgumentException("Unsupported M3 Plan lifecycle state");
        }
        if (status == EvolutionPlanStatus.ACTIVE && workingCopyId == null) {
            throw new IllegalArgumentException("ACTIVE Plan must have a Working Copy");
        }
        EvolutionPlan plan = reconstitute(id, directionId, assetId, profileId, currentState, targetState,
                reusableCapabilities, changes, steps, risks, evidence);
        plan.workingCopyId = workingCopyId;
        plan.status = status;
        return plan;
    }

    /**
     * 先在隔离候选上修改，避免失败时改变 Repository 已加载的对象。
     */
    public EvolutionPlan copy() {
        // Step 生命周期可变，候选必须隔离内部 Entity，防止失败污染已加载对象。
        return reconstitute(id, productDirectionId, baseAssetId, baseRepositoryProfileId, currentState, targetState,
                reusableCapabilities, changes, steps.stream().map(EvolutionStep::copy).toList(), risks, evidence, workingCopyId, status);
    }

    public EvolutionStep step(EvolutionStepId stepId) {
        return steps.stream().filter(step -> step.id().equals(stepId)).findFirst()
                .orElseThrow(() -> new EvolutionStepNotFoundException(stepId));
    }

    public void confirmStep(EvolutionStepId stepId, WorkingCopy copy, StepExecutionPolicy policy) {
        policy.requireConfirmationAllowed(this, stepId, copy);
        step(stepId).confirm();
    }

    public void bindWorkingCopy(WorkingCopy copy) {
        if (status != EvolutionPlanStatus.PROPOSED || workingCopyId != null || copy == null
                || !baseAssetId.equals(copy.sourceAssetId()) || copy.status() != WorkingCopyStatus.READY) {
            throw new EvolutionPlanStateException("Only an unbound PROPOSED Plan can bind its READY Working Copy");
        }
        workingCopyId = copy.id();
    }

    public void startStep(EvolutionStepId id, WorkingCopy copy, StepExecutionPolicy policy) {
        policy.requireExecutionAllowed(this, id, copy);
        // M4 尚无自然语言前置条件证明器；不能把 AI 的判断作为满足条件的事实。
        if (!step(id).preconditions().isEmpty()) {
            throw new EvolutionPlanStateException("External Step Preconditions cannot yet be established for execution");
        }
        copy.startEvolution();
        step(id).start(copy.lastVerifiedRevision());
    }

    private void requireExecutionOwner(WorkingCopy copy) {
        if (status != EvolutionPlanStatus.ACTIVE || !copy.id().value().equals(workingCopyId())
                || !copy.sourceAssetId().equals(baseAssetId)) {
            throw new EvolutionPlanStateException("Execution checkpoint no longer belongs to the ACTIVE Plan");
        }
    }

    public void recordExecution(EvolutionStepId id, WorkingCopy copy, ExecutionResult result) {
        requireExecutionOwner(copy);
        EvolutionStep step = step(id);
        if (step.status() != EvolutionStepStatus.EXECUTING || result == null
                || step.baselineRevision().equals(result.candidateRevision())) {
            throw new EvolutionPlanStateException("Invalid execution checkpoint");
        }
        copy.requireCheckpoint(step.baselineRevision(), step.baselineRevision());
        copy.recordCandidate(step.baselineRevision(), result.candidateRevision());
        step.recordExecution(result);
    }

    public void succeedStep(EvolutionStepId id, WorkingCopy copy, VerificationResult result) {
        requireExecutionOwner(copy);
        EvolutionStep step = step(id);
        step.requireVerificationAccepted(result);
        copy.requireCheckpoint(step.baselineRevision(), result.candidateRevision());
        copy.trustCandidate(step.baselineRevision(), result.candidateRevision());
        step.succeed(result);
    }

    public void activate(PlanActivationPolicy policy, WorkingCopy copy,
            ProductDirection direction,
            SoftwareAsset asset,
            RepositoryProfile profile) {
        if (policy == null) {
            throw new IllegalArgumentException("Activation policy is required");
        }
        policy.requireActivationAllowed(this, copy, direction, asset, profile);
        status = EvolutionPlanStatus.ACTIVE;
    }

    /**
     * 按 DOMAIN_MODEL.md §8.17 与 INV-P08 终止当前计划，保留步骤定义与历史结果。
     */
    public void supersede() {
        if (status != EvolutionPlanStatus.PROPOSED && status != EvolutionPlanStatus.ACTIVE) {
            throw new EvolutionPlanStateException("Only PROPOSED or ACTIVE Plans can be superseded");
        }
        status = EvolutionPlanStatus.SUPERSEDED;
    }

    public EvolutionPlanId id() {
        return id;
    }

    public ProductDirectionId productDirectionId() {
        return productDirectionId;
    }

    public SoftwareAssetId baseAssetId() {
        return baseAssetId;
    }

    public RepositoryProfileId baseRepositoryProfileId() {
        return baseRepositoryProfileId;
    }

    public String workingCopyId() {
        return workingCopyId == null ? null : workingCopyId.value();
    }

    public CurrentState currentState() {
        return currentState;
    }

    public TargetState targetState() {
        return targetState;
    }

    public List<String> reusableCapabilities() {
        return reusableCapabilities;
    }

    public List<String> changes() {
        return changes;
    }

    public List<EvolutionStep> steps() {
        return steps;
    }

    public List<String> risks() {
        return risks;
    }

    public List<EvidenceBasis> evidence() {
        return evidence;
    }

    public EvolutionPlanStatus status() {
        return status;
    }
}
