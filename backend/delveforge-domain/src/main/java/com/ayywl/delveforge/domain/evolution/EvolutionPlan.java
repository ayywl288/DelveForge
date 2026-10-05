package com.ayywl.delveforge.domain.evolution;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import java.util.HashSet;
import java.util.List;

/** Planning Aggregate Root. Task 1 supports only PROPOSED, with no Working Copy. */
public final class EvolutionPlan {
    private final EvolutionPlanId id;
    private final ProductDirectionId productDirectionId;
    private final SoftwareAssetId baseAssetId;
    private final RepositoryProfileId baseRepositoryProfileId;
    private final PlanningProposal content;
    private final List<EvolutionStep> steps;

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
    public EvolutionPlanId id() { return id; }
    public ProductDirectionId productDirectionId() { return productDirectionId; }
    public SoftwareAssetId baseAssetId() { return baseAssetId; }
    public RepositoryProfileId baseRepositoryProfileId() { return baseRepositoryProfileId; }
    public String workingCopyId() { return null; }
    public CurrentState currentState() { return content.currentState(); }
    public TargetState targetState() { return content.targetState(); }
    public List<String> reusableCapabilities() { return content.reusableCapabilities(); }
    public List<String> changes() { return content.changes(); }
    public List<EvolutionStep> steps() { return steps; }
    public List<String> risks() { return content.risks(); }
    public List<EvidenceBasis> evidence() { return content.evidence(); }
    public EvolutionPlanStatus status() { return EvolutionPlanStatus.PROPOSED; }
}
