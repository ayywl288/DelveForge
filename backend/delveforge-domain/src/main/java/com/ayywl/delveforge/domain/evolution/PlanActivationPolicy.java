package com.ayywl.delveforge.domain.evolution;

import com.ayywl.delveforge.domain.asset.AssetUsagePolicy;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import java.util.Objects;

/** Non-mutating cross-Aggregate preparation/activation checks (§8.7–8.8, §12.7).
 * Actual Git revision/isolation checks belong to Workspace, coordinated by Application.
 */
public final class PlanActivationPolicy {
    private final AssetUsagePolicy usage;
    public PlanActivationPolicy(AssetUsagePolicy usage) { this.usage = Objects.requireNonNull(usage); }

    public void requirePreparationAllowed(EvolutionPlan plan, ProductDirection direction,
            SoftwareAsset asset, RepositoryProfile profile) {
        requireBasis(plan, direction, asset, profile);
        if (plan.workingCopyId() != null)
            throw new EvolutionPlanStateException("Plan already has a Working Copy");
    }

    public void requireActivationAllowed(EvolutionPlan plan, WorkingCopy copy, ProductDirection direction,
            SoftwareAsset asset, RepositoryProfile profile) {
        requireBasis(plan, direction, asset, profile);
        if (copy == null || !copy.id().value().equals(plan.workingCopyId())
                || !copy.sourceAssetId().equals(plan.baseAssetId())
                || copy.status() != WorkingCopyStatus.READY
                || !copy.sourceRevision().equals(profile.analyzedRevision())
                || !copy.currentRevision().equals(copy.sourceRevision())
                || !copy.lastVerifiedRevision().equals(copy.sourceRevision()))
            throw new EvolutionPlanStateException("Plan requires its bound READY Working Copy at the planning revision");
    }

    private void requireBasis(EvolutionPlan plan, ProductDirection direction,
            SoftwareAsset asset, RepositoryProfile profile) {
        if (plan == null || direction == null || asset == null || profile == null)
            throw new IllegalArgumentException("Activation basis is required");
        if (plan.status() != EvolutionPlanStatus.PROPOSED
                || !plan.productDirectionId().equals(direction.id())
                || direction.status() != ProductDirectionStatus.SELECTED
                || !direction.candidateAssetIds().contains(plan.baseAssetId())
                || !plan.baseAssetId().equals(asset.id())
                || !plan.baseRepositoryProfileId().equals(profile.id())
                || !profile.assetId().equals(asset.id())
                || profile.analyzedRevision().isBlank())
            throw new EvolutionPlanStateException("Plan preparation/activation basis is no longer valid");
        usage.requireEvolutionBaseAllowed(asset);
    }
}
