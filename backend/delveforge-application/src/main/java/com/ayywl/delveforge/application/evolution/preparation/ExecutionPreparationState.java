package com.ayywl.delveforge.application.evolution.preparation;

import com.ayywl.delveforge.application.evolution.planning.EvolutionPlanNotFoundException;
import com.ayywl.delveforge.application.evolution.provisioning.WorkingCopyNotFoundException;
import com.ayywl.delveforge.application.opportunitydiscovery.direction.ProductDirectionNotFoundException;
import com.ayywl.delveforge.application.port.persistence.EvolutionPlanRepository;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.persistence.WorkingCopyRepository;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyInspectionPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.asset.SoftwareAssetNotFoundException;
import com.ayywl.delveforge.domain.asset.AssetUsagePolicy;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanStateException;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanStatus;
import com.ayywl.delveforge.domain.evolution.EvolutionStep;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepStatus;
import com.ayywl.delveforge.domain.evolution.StepExecutionPolicy;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;
import com.ayywl.delveforge.domain.evolution.WorkingCopyId;

import java.util.Objects;

/** 只读加载和重查可变资格，结合 Domain 生命周期与 Workspace 实际状态拒绝过期依据。 */
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

    /** confirmation 区分待确认与已 READY 的资格；这里只核对，不改变生命周期。 */
    public Loaded load(EvolutionPlanId id, EvolutionStepId stepId, boolean confirmation) {
        Loaded loaded = loadObjects(id, stepId);
        if (confirmation) {
            policy.requireConfirmationAllowed(loaded.plan(), stepId, loaded.copy());
        } else {
            policy.requireExecutionAllowed(loaded.plan(), stepId, loaded.copy());
        }
        inspection.requireTrustedState(new WorkspaceRef(loaded.copy().location()),
                new WorkspaceRef(loaded.asset().location()), loaded.copy().lastVerifiedRevision());
        return loaded;
    }

    private Loaded loadObjects(EvolutionPlanId id, EvolutionStepId stepId) {
        EvolutionPlan plan = plans.findById(id).orElseThrow(() -> new EvolutionPlanNotFoundException(id));
        plans.requireFlowBinding(plan);
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
        inspection.requirePreparationProvenance(new WorkspaceRef(copy.location()), copy.preparation());
        return new Loaded(plan, copy, asset);
    }

    /** Delivery 恢复期间物理 Git 可能已到 C；这里只检查授权与数据库可信基线。 */
    public Loaded loadDeliveryMetadata(EvolutionPlanId id, EvolutionStepId stepId) {
        Loaded loaded = loadObjects(id, stepId);
        policy.requireExecutionAllowed(loaded.plan(), stepId, loaded.copy());
        return loaded;
    }

    public Loaded revalidate(Loaded expected, EvolutionStepId stepId, boolean confirmation) {
        Loaded actual = load(expected.plan().id(), stepId, confirmation);
        if (!actual.copy().id().equals(expected.copy().id())
                || !actual.copy().lastVerifiedRevision().equals(expected.copy().lastVerifiedRevision())
                || !actual.copy().location().equals(expected.copy().location())
                || !actual.asset().location().equals(expected.asset().location())) {
            throw new EvolutionPlanStateException("Step confirmation basis changed");
        }
        return actual;
    }

    public record Loaded(EvolutionPlan plan, WorkingCopy copy, SoftwareAsset asset) {
    }
}
