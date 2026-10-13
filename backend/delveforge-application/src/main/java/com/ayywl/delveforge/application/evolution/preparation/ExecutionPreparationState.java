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
        return new Loaded(plan, copy, asset);
    }

    public Loaded loadRepairCandidate(EvolutionPlanId id, EvolutionStepId stepId) {
        Loaded loaded = loadObjects(id, stepId);
        policy.requireRepairAllowed(loaded.plan(), stepId, loaded.copy());
        inspection.requireTrustedState(new WorkspaceRef(loaded.copy().location()),
                new WorkspaceRef(loaded.asset().location()), loaded.copy().currentRevision());
        return loaded;
    }

    /** 回滚授权前只读核对已失败 Candidate，不清理或接受额外用户修改。 */
    public Loaded loadRollbackCandidate(EvolutionPlanId planId, EvolutionStepId stepId) {
        Loaded loaded = loadObjects(planId, stepId);
        policy.requireRollbackAllowed(loaded.plan(), stepId, loaded.copy());
        inspection.requireTrustedState(new WorkspaceRef(loaded.copy().location()),
                new WorkspaceRef(loaded.asset().location()), loaded.copy().currentRevision());
        return loaded;
    }

    /** 只供新授权调用内的准备使用；REST 不提供重启后重放此检查点的入口。 */
    public Loaded revalidateRepair(Loaded expected, EvolutionStepId stepId) {
        EvolutionStep step = expected.plan().step(stepId);
        if (step.status() != EvolutionStepStatus.EXECUTING || step.repairAuthorizations().isEmpty()
                || step.executionResult() != null || step.verificationResult() != null) {
            throw new EvolutionPlanStateException("Repair preparation requires its uncompleted authorization checkpoint");
        }
        return requireCheckpoint(expected, stepId, step.executionStartRevision());
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

    /**
     * 核对持久化检查点和指定的物理 revision。mutation 后、记录 Candidate 前，数据库仍可保留 A，
     * 因此物理 revision 由编排明确传入，不能直接用 currentRevision 替代。
     */
    public Loaded requireCheckpoint(Loaded expected, EvolutionStepId stepId, String physicalRevision) {
        EvolutionPlan plan = plans.findById(expected.plan().id())
                .orElseThrow(() -> new EvolutionPlanNotFoundException(expected.plan().id()));
        plans.requireFlowBinding(plan);
        WorkingCopy copy = copies.findById(expected.copy().id())
                .orElseThrow(() -> new WorkingCopyNotFoundException(expected.copy().id()));
        SoftwareAsset asset = assets.findById(plan.baseAssetId())
                .orElseThrow(() -> new SoftwareAssetNotFoundException(plan.baseAssetId()));
        EvolutionStep step = plan.step(stepId);
        EvolutionStep previous = expected.plan().step(stepId);
        new AssetUsagePolicy().requireEvolutionBaseAllowed(asset);
        if (plan.status() != EvolutionPlanStatus.ACTIVE
                || !Objects.equals(plan.workingCopyId(), expected.plan().workingCopyId())
                || directions.findById(plan.productDirectionId()).orElseThrow().status()
                        != ProductDirectionStatus.SELECTED
                || step.status() != previous.status() || !Objects.equals(step.baselineRevision(),
                        previous.baselineRevision())
                || !Objects.equals(step.executionResult(), previous.executionResult())
                || !Objects.equals(step.verificationResult(), previous.verificationResult())
                || !step.repairAuthorizations().equals(previous.repairAuthorizations())
                || !Objects.equals(step.rollbackAuthorization(), previous.rollbackAuthorization())
                || !Objects.equals(step.rollbackResult(), previous.rollbackResult())
                || !step.goal().equals(previous.goal()) || !step.scope().equals(previous.scope())
                || !step.plannedChanges().equals(previous.plannedChanges())
                || !step.verificationCriteria().equals(previous.verificationCriteria())
                || copy.status() != expected.copy().status()
                || !Objects.equals(copy.currentRevision(), expected.copy().currentRevision())
                || !Objects.equals(copy.lastVerifiedRevision(), expected.copy().lastVerifiedRevision())
                || !copy.location().equals(expected.copy().location())
                || !asset.location().equals(expected.asset().location())) {
            throw new EvolutionPlanStateException("Execution checkpoint changed");
        }
        inspection.requireTrustedState(new WorkspaceRef(copy.location()), new WorkspaceRef(asset.location()),
                physicalRevision);
        return new Loaded(plan, copy, asset);
    }

    public record Loaded(EvolutionPlan plan, WorkingCopy copy, SoftwareAsset asset) {
        public ExecutionPreparationBasis basis(EvolutionStepId id) {
            return new ExecutionPreparationBasis(plan.id(), id, copy.id(), copy.lastVerifiedRevision());
        }
    }
}
