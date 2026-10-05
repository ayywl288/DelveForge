package com.ayywl.delveforge.domain.evolution;

import com.ayywl.delveforge.domain.asset.AssetUsagePolicy;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.RepositoryProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 无状态的跨 Aggregate 提案接受边界（DOMAIN_MODEL.md §12.5）。
 */
public final class EvolutionPlanningService {
    private final AssetUsagePolicy assetUsagePolicy;
    private final Supplier<EvolutionPlanId> planIds;
    private final Supplier<EvolutionStepId> stepIds;

    public EvolutionPlanningService(AssetUsagePolicy policy, Supplier<EvolutionPlanId> planIds,
                                    Supplier<EvolutionStepId> stepIds) {
        this.assetUsagePolicy = Objects.requireNonNull(policy);
        this.planIds = Objects.requireNonNull(planIds);
        this.stepIds = Objects.requireNonNull(stepIds);
    }

    public void requirePlanningBasis(ProductDirection direction, SoftwareAsset asset, RepositoryProfile profile) {
        if (direction == null || asset == null || profile == null) {
            throw new IllegalArgumentException("Planning basis is required");
        }
        if (direction.status() != ProductDirectionStatus.SELECTED) {
            throw new EvolutionPlanningPreconditionException("Direction must be SELECTED");
        }
        if (!direction.candidateAssetIds().contains(asset.id())) {
            throw new EvolutionPlanningPreconditionException("Base Asset must be a Candidate Asset");
        }
        if (!profile.assetId().equals(asset.id()) || profile.analyzedRevision() == null
                || profile.analyzedRevision().isBlank()) {
            throw new EvolutionPlanningPreconditionException("Base Profile must identify the Base Asset at a concrete revision");
        }
        assetUsagePolicy.requireEvolutionBaseAllowed(asset);
    }

    public EvolutionPlan plan(ProductDirection direction, SoftwareAsset asset,
                              RepositoryProfile profile, PlanningProposal proposal) {
        requirePlanningBasis(direction, asset, profile);
        if (proposal == null) {
            throw new EvolutionPlanningRejectedException("Planning proposal is missing");
        }

        TargetState intended = new TargetState(direction.problem(), direction.targetProduct(), direction.differentiation());
        if (!intended.equals(proposal.targetState())) {
            throw new EvolutionPlanningRejectedException("Target State must retain selected product intent");
        }

        CurrentState current = proposal.currentState();
        if (!profile.capabilities().containsAll(current.capabilities())
                || !profile.modules().containsAll(current.modules())
                || !profile.limitations().containsAll(current.limitations())) {
            throw new EvolutionPlanningRejectedException("Current State facts must come from the Base Profile");
        }

        List<String> reusable = new ArrayList<>(profile.capabilities());
        reusable.addAll(profile.reusableAssets());
        if (!reusable.containsAll(proposal.reusableCapabilities())) {
            throw new EvolutionPlanningRejectedException("Reusable capabilities must come from the Base Profile");
        }

        List<EvidenceBasis> available = new ArrayList<>(direction.evidenceSupport().allBases());
        for (Evidence evidence : profile.evidence()) {
            available.add(new EvidenceBasis(evidence, new RepositoryProfileEvidenceOrigin(profile.id())));
        }
        if (!available.containsAll(proposal.evidence())) {
            throw new EvolutionPlanningRejectedException("Planning evidence must belong to the planning inputs");
        }
        if (proposal.evidence().stream().noneMatch(basis ->
                basis.origin().equals(new RepositoryProfileEvidenceOrigin(profile.id())))) {
            throw new EvolutionPlanningRejectedException("Current State needs evidence from the Base Profile");
        }

        // 这里只校验内容存在和事实来源；自然语言的范围与工程质量仍需用户审阅。
        return new EvolutionPlan(planIds.get(), direction.id(), asset.id(), profile.id(), proposal,
                proposal.steps().stream().map(step -> stepIds.get()).toList());
    }
}
