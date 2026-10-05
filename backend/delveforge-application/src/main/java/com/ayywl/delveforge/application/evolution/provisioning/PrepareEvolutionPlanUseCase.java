package com.ayywl.delveforge.application.evolution.provisioning;

import com.ayywl.delveforge.application.evolution.planning.EvolutionPlanNotFoundException;
import com.ayywl.delveforge.application.opportunitydiscovery.direction.ProductDirectionNotFoundException;
import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.application.port.workspace.*;
import com.ayywl.delveforge.application.repositoryanalysis.asset.SoftwareAssetNotFoundException;
import com.ayywl.delveforge.application.repositoryanalysis.profile.RepositoryProfileNotFoundException;
import com.ayywl.delveforge.domain.evolution.*;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Read/validate → external candidate → isolated domain candidate → single atomic commit.
 * No loaded Aggregate is mutated and no Step authorization is granted.
 */
public final class PrepareEvolutionPlanUseCase {
    private static final Logger log = LoggerFactory.getLogger(PrepareEvolutionPlanUseCase.class);
    private final EvolutionPlanRepository plans;
    private final ProductDirectionRepository directions;
    private final SoftwareAssetRepository assets;
    private final RepositoryProfileRepository profiles;
    private final WorkingCopyProvisioningPort provisioning;
    private final EvolutionLifecycleCommitPort commits;
    private final PlanActivationPolicy activation;
    private final Supplier<WorkingCopyId> ids;

    public PrepareEvolutionPlanUseCase(EvolutionPlanRepository plans, ProductDirectionRepository directions,
            SoftwareAssetRepository assets, RepositoryProfileRepository profiles,
            WorkingCopyProvisioningPort provisioning, EvolutionLifecycleCommitPort commits,
            PlanActivationPolicy activation, Supplier<WorkingCopyId> ids) {
        this.plans = Objects.requireNonNull(plans);
        this.directions = Objects.requireNonNull(directions);
        this.assets = Objects.requireNonNull(assets);
        this.profiles = Objects.requireNonNull(profiles);
        this.provisioning = Objects.requireNonNull(provisioning);
        this.commits = Objects.requireNonNull(commits);
        this.activation = Objects.requireNonNull(activation);
        this.ids = Objects.requireNonNull(ids);
    }

    public EvolutionPlan prepare(EvolutionPlanId id) {
        Objects.requireNonNull(id);
        var plan = plans.findById(id).orElseThrow(() -> new EvolutionPlanNotFoundException(id));
        var direction = directions.findById(plan.productDirectionId())
                .orElseThrow(() -> new ProductDirectionNotFoundException(plan.productDirectionId()));
        var asset = assets.findById(plan.baseAssetId())
                .orElseThrow(() -> new SoftwareAssetNotFoundException(plan.baseAssetId()));
        var profile = profiles.findById(plan.baseRepositoryProfileId())
                .orElseThrow(() -> new RepositoryProfileNotFoundException(plan.baseRepositoryProfileId()));
        activation.requirePreparationAllowed(plan, direction, asset, profile);
        WorkingCopyId copyId = Objects.requireNonNull(ids.get());
        PreparedWorkspace prepared = provisioning.provision(new WorkspaceRef(asset.location()),
                profile.analyzedRevision(), copyId.value());
        try {
            var currentDirection = directions.findById(direction.id())
                    .orElseThrow(() -> new ProductDirectionNotFoundException(direction.id()));
            var currentAsset = assets.findById(asset.id())
                    .orElseThrow(() -> new SoftwareAssetNotFoundException(asset.id()));
            activation.requirePreparationAllowed(plan, currentDirection, currentAsset, profile);
            if (!asset.location().equals(currentAsset.location()))
                throw new EvolutionPlanStateException("Asset source location changed during preparation");
            var copy = WorkingCopy.create(copyId, asset.id(), profile.analyzedRevision(), prepared.workspace().value());
            copy.markReady(prepared.revision());
            var candidate = plan.copy();
            candidate.bindWorkingCopy(copy);
            candidate.activate(activation, copy, currentDirection, currentAsset, profile);
            commits.commitActivation(candidate, copy, currentAsset);
            return candidate;
        } catch (RuntimeException failure) {
            try { provisioning.discard(prepared); }
            catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
                // Never log exception messages, source locations, prompts or provider data (ADR-0002).
                log.warn("operation=evolution.prepare planId={} copyId={} result=CLEANUP_FAILED exceptionType={}",
                        id.value(), copyId.value(), cleanup.getClass().getName());
            }
            throw failure;
        }
    }
}
