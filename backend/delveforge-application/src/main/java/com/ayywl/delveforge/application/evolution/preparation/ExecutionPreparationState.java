package com.ayywl.delveforge.application.evolution.preparation;

import com.ayywl.delveforge.application.evolution.planning.EvolutionPlanNotFoundException;
import com.ayywl.delveforge.application.evolution.provisioning.WorkingCopyNotFoundException;
import com.ayywl.delveforge.application.opportunitydiscovery.direction.ProductDirectionNotFoundException;
import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.application.port.workspace.*;
import com.ayywl.delveforge.application.repositoryanalysis.asset.SoftwareAssetNotFoundException;
import com.ayywl.delveforge.domain.asset.*;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
import com.ayywl.delveforge.domain.evolution.*;
import java.util.Objects;

/** 每次外部调用前后重读可变资格，准备依据始终来自受信副本。 */
public final class ExecutionPreparationState {
    private final EvolutionPlanRepository plans;
    private final WorkingCopyRepository copies;
    private final SoftwareAssetRepository assets;
    private final ProductDirectionRepository directions;
    private final WorkingCopyInspectionPort inspection;
    private final StepExecutionPolicy policy;

    public ExecutionPreparationState(EvolutionPlanRepository plans, WorkingCopyRepository copies,
            SoftwareAssetRepository assets, ProductDirectionRepository directions,
            WorkingCopyInspectionPort inspection, StepExecutionPolicy policy) {
        this.plans = Objects.requireNonNull(plans);
        this.copies = Objects.requireNonNull(copies);
        this.assets = Objects.requireNonNull(assets);
        this.directions = Objects.requireNonNull(directions);
        this.inspection = Objects.requireNonNull(inspection);
        this.policy = Objects.requireNonNull(policy);
    }

    public Loaded load(EvolutionPlanId id, EvolutionStepId stepId, boolean confirmation) {
        EvolutionPlan plan = plans.findById(id).orElseThrow(() -> new EvolutionPlanNotFoundException(id));
        plan.step(stepId);
        if (plan.workingCopyId() == null) {
            throw new EvolutionPlanStateException("Plan has no Working Copy");
        }
        WorkingCopyId copyId = new WorkingCopyId(plan.workingCopyId());
        WorkingCopy copy = copies.findById(copyId).orElseThrow(() -> new WorkingCopyNotFoundException(copyId));
        SoftwareAsset asset = assets.findById(plan.baseAssetId())
                .orElseThrow(() -> new SoftwareAssetNotFoundException(plan.baseAssetId()));
        if (directions.findById(plan.productDirectionId())
                .orElseThrow(() -> new ProductDirectionNotFoundException(plan.productDirectionId())).status()
                != ProductDirectionStatus.SELECTED) {
            throw new EvolutionPlanStateException("Direction is no longer SELECTED");
        }
        new AssetUsagePolicy().requireEvolutionBaseAllowed(asset);
        if (confirmation) {
            policy.requireConfirmationAllowed(plan, stepId, copy);
        } else {
            policy.requireExecutionAllowed(plan, stepId, copy);
        }
        inspection.requireTrustedState(new WorkspaceRef(copy.location()), new WorkspaceRef(asset.location()),
                copy.lastVerifiedRevision());
        return new Loaded(plan, copy, asset);
    }

    public Loaded revalidate(Loaded expected, EvolutionStepId stepId, boolean confirmation) {
        Loaded actual = load(expected.plan().id(), stepId, confirmation);
        if (!actual.copy().id().equals(expected.copy().id())
                || !actual.copy().lastVerifiedRevision().equals(expected.copy().lastVerifiedRevision())
                || !actual.copy().location().equals(expected.copy().location())
                || !actual.asset().location().equals(expected.asset().location())) {
            throw new EvolutionPlanStateException("Execution preparation basis changed");
        }
        return actual;
    }

    public Loaded requireCheckpoint(Loaded expected, EvolutionStepId stepId, String physicalRevision) {
        EvolutionPlan plan = plans.findById(expected.plan().id())
                .orElseThrow(() -> new EvolutionPlanNotFoundException(expected.plan().id()));
        WorkingCopy copy = copies.findById(expected.copy().id())
                .orElseThrow(() -> new WorkingCopyNotFoundException(expected.copy().id()));
        SoftwareAsset asset = assets.findById(plan.baseAssetId())
                .orElseThrow(() -> new SoftwareAssetNotFoundException(plan.baseAssetId()));
        EvolutionStep step = plan.step(stepId);
        EvolutionStep previous = expected.plan().step(stepId);
        new AssetUsagePolicy().requireEvolutionBaseAllowed(asset);
        if (plan.status() != EvolutionPlanStatus.ACTIVE
                || !Objects.equals(plan.workingCopyId(), expected.plan().workingCopyId())
                || directions.findById(plan.productDirectionId()).orElseThrow().status() != ProductDirectionStatus.SELECTED
                || step.status() != previous.status() || !Objects.equals(step.baselineRevision(), previous.baselineRevision())
                || !Objects.equals(step.executionResult(), previous.executionResult())
                || !Objects.equals(step.verificationResult(), previous.verificationResult())
                || copy.status() != expected.copy().status()
                || !Objects.equals(copy.currentRevision(), expected.copy().currentRevision())
                || !Objects.equals(copy.lastVerifiedRevision(), expected.copy().lastVerifiedRevision())
                || !copy.location().equals(expected.copy().location())
                || !asset.location().equals(expected.asset().location())) {
            throw new EvolutionPlanStateException("Execution checkpoint changed");
        }
        inspection.requireTrustedState(new WorkspaceRef(copy.location()), new WorkspaceRef(asset.location()), physicalRevision);
        return new Loaded(plan, copy, asset);
    }

    public record Loaded(EvolutionPlan plan, WorkingCopy copy, SoftwareAsset asset) {
        public ExecutionPreparationBasis basis(EvolutionStepId id) {
            return new ExecutionPreparationBasis(plan.id(), id, copy.id(), copy.lastVerifiedRevision());
        }
    }
}
