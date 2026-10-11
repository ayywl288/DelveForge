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
            throw new EvolutionPlanningRejectedException(EvolutionPlanningRejectedException.Reason.PROPOSAL_MISSING);
        }

        TargetState intended = new TargetState(direction.problem(), direction.targetProduct(), direction.differentiation());
        if (!intended.equals(proposal.targetState())) {
            throw new EvolutionPlanningRejectedException(EvolutionPlanningRejectedException.Reason.TARGET_INTENT_MISMATCH);
        }

        CurrentState current = proposal.currentState();
        if (!profile.capabilities().containsAll(current.capabilities())) {
            throw new EvolutionPlanningRejectedException(
                    EvolutionPlanningRejectedException.Reason.CURRENT_CAPABILITIES_NOT_FROM_BASE_PROFILE);
        }
        if (!profile.modules().containsAll(current.modules())) {
            throw new EvolutionPlanningRejectedException(
                    EvolutionPlanningRejectedException.Reason.CURRENT_MODULES_NOT_FROM_BASE_PROFILE);
        }
        if (!profile.limitations().containsAll(current.limitations())) {
            throw new EvolutionPlanningRejectedException(
                    EvolutionPlanningRejectedException.Reason.CURRENT_LIMITATIONS_NOT_FROM_BASE_PROFILE);
        }

        List<String> reusable = new ArrayList<>(profile.capabilities());
        reusable.addAll(profile.reusableAssets());
        if (!reusable.containsAll(proposal.reusableCapabilities())) {
            throw new EvolutionPlanningRejectedException(
                    EvolutionPlanningRejectedException.Reason.REUSABLE_CAPABILITIES_NOT_FROM_BASE_PROFILE);
        }

        List<EvidenceBasis> available = new ArrayList<>(direction.evidenceSupport().allBases());
        for (Evidence evidence : profile.evidence()) {
            available.add(new EvidenceBasis(evidence, new RepositoryProfileEvidenceOrigin(profile.id())));
        }
        if (!available.containsAll(proposal.evidence())) {
            throw new EvolutionPlanningRejectedException(EvolutionPlanningRejectedException.Reason.EVIDENCE_NOT_FROM_INPUTS);
        }
        if (proposal.evidence().stream().noneMatch(basis ->
                basis.origin().equals(new RepositoryProfileEvidenceOrigin(profile.id())))) {
            throw new EvolutionPlanningRejectedException(
                    EvolutionPlanningRejectedException.Reason.BASE_PROFILE_EVIDENCE_MISSING);
        }

        // 这里只校验内容存在和事实来源；自然语言的范围与工程质量仍需用户审阅。
        EvolutionPlanId planId = planIds.get();
        List<EvolutionStep> steps = proposal.steps().stream()
                .map(step -> EvolutionStep.planned(stepIds.get(), planId, step.goal(), step.scope(),
                        step.plannedChanges(), step.verificationCriteria()))
                .toList();

        // 提案仅作为决策输入；接受后 Aggregate 直接拥有正式值，不保留 Proposal 对象。
        return new EvolutionPlan(planId, direction.id(), asset.id(), profile.id(),
                proposal.currentState(), proposal.targetState(), proposal.reusableCapabilities(),
                proposal.changes(), steps, proposal.risks(), proposal.evidence());
    }
}
