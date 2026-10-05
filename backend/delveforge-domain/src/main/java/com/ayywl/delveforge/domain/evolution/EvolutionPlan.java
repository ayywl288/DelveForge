package com.ayywl.delveforge.domain.evolution;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import java.util.HashSet;
import java.util.List;

/** Planning/lifecycle Aggregate Root. Activation never authorizes its Steps. */
public final class EvolutionPlan {
    private final EvolutionPlanId id;
    private final ProductDirectionId productDirectionId;
    private final SoftwareAssetId baseAssetId;
    private final RepositoryProfileId baseRepositoryProfileId;
    private final PlanningProposal content;
    private final List<EvolutionStep> steps;
    private WorkingCopyId workingCopyId;
    private EvolutionPlanStatus status = EvolutionPlanStatus.PROPOSED;

    EvolutionPlan(EvolutionPlanId id, ProductDirectionId directionId, SoftwareAssetId assetId,
                  RepositoryProfileId profileId, PlanningProposal content, List<EvolutionStepId> stepIds) {
        if (id == null || directionId == null || assetId == null || profileId == null || content == null)
            throw new IllegalArgumentException("Plan identity and planning basis are required");
        if (content.steps().isEmpty() || content.evidence().isEmpty())
            throw new EvolutionPlanningRejectedException("Plan requires steps and traceable evidence");
        if (stepIds == null || stepIds.size() != content.steps().size()
                || stepIds.contains(null) || new HashSet<>(stepIds).size() != stepIds.size())
            throw new IllegalArgumentException("Step identities must be unique and complete");
        this.id = id;
        this.productDirectionId = directionId;
        this.baseAssetId = assetId;
        this.baseRepositoryProfileId = profileId;
        this.content = content;
        this.steps = java.util.stream.IntStream.range(0, stepIds.size())
                .mapToObj(index -> EvolutionStep.planned(stepIds.get(index), id, content.steps().get(index))).toList();
    }

    /** Restore saved planning facts without consulting mutable current Direction/Asset state.
     * Historical plans remain readable after their basis becomes ineligible.
     */
    public static EvolutionPlan reconstitute(EvolutionPlanId id, ProductDirectionId directionId,
            SoftwareAssetId assetId, RepositoryProfileId profileId, PlanningProposal content,
            List<EvolutionStepId> stepIds) {
        return new EvolutionPlan(id, directionId, assetId, profileId, content, stepIds);
    }

    public static EvolutionPlan reconstitute(EvolutionPlanId id, ProductDirectionId directionId,
            SoftwareAssetId assetId, RepositoryProfileId profileId, PlanningProposal content,
            List<EvolutionStepId> stepIds, WorkingCopyId workingCopyId, EvolutionPlanStatus status) {
        if (status == null || status == EvolutionPlanStatus.COMPLETED)
            throw new IllegalArgumentException("Unsupported M3 Plan lifecycle state");
        if (status == EvolutionPlanStatus.ACTIVE && workingCopyId == null)
            throw new IllegalArgumentException("ACTIVE Plan must have a Working Copy");
        EvolutionPlan plan = reconstitute(id, directionId, assetId, profileId, content, stepIds);
        plan.workingCopyId = workingCopyId;
        plan.status = status;
        return plan;
    }

    /** Mutations are applied to an isolated candidate so repository-loaded objects remain unchanged on failure. */
    public EvolutionPlan copy() {
        return reconstitute(id, productDirectionId, baseAssetId, baseRepositoryProfileId, content,
                steps.stream().map(EvolutionStep::id).toList(), workingCopyId, status);
    }

    public void bindWorkingCopy(WorkingCopy copy) {
        if (status != EvolutionPlanStatus.PROPOSED || workingCopyId != null || copy == null
                || !baseAssetId.equals(copy.sourceAssetId()) || copy.status() != WorkingCopyStatus.READY)
            throw new EvolutionPlanStateException("Only an unbound PROPOSED Plan can bind its READY Working Copy");
        workingCopyId = copy.id();
    }

    public void activate(PlanActivationPolicy policy, WorkingCopy copy,
            com.ayywl.delveforge.domain.direction.ProductDirection direction,
            com.ayywl.delveforge.domain.asset.SoftwareAsset asset,
            com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile profile) {
        if (policy == null) throw new IllegalArgumentException("Activation policy is required");
        policy.requireActivationAllowed(this, copy, direction, asset, profile);
        status = EvolutionPlanStatus.ACTIVE;
    }

    /** Documented terminal transition (§8.17, INV-P08), preserving definitions and results. */
    public void supersede() {
        if (status != EvolutionPlanStatus.PROPOSED && status != EvolutionPlanStatus.ACTIVE)
            throw new EvolutionPlanStateException("Only PROPOSED or ACTIVE Plans can be superseded");
        status = EvolutionPlanStatus.SUPERSEDED;
    }
    public EvolutionPlanId id() { return id; }
    public ProductDirectionId productDirectionId() { return productDirectionId; }
    public SoftwareAssetId baseAssetId() { return baseAssetId; }
    public RepositoryProfileId baseRepositoryProfileId() { return baseRepositoryProfileId; }
    public String workingCopyId() { return workingCopyId == null ? null : workingCopyId.value(); }
    public CurrentState currentState() { return content.currentState(); }
    public TargetState targetState() { return content.targetState(); }
    public List<String> reusableCapabilities() { return content.reusableCapabilities(); }
    public List<String> changes() { return content.changes(); }
    public List<EvolutionStep> steps() { return steps; }
    public List<String> risks() { return content.risks(); }
    public List<EvidenceBasis> evidence() { return content.evidence(); }
    public EvolutionPlanStatus status() { return status; }
}
