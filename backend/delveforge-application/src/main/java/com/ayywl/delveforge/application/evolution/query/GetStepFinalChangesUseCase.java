package com.ayywl.delveforge.application.evolution.query;

import com.ayywl.delveforge.application.evolution.planning.EvolutionPlanNotFoundException;
import com.ayywl.delveforge.application.port.persistence.EvolutionPlanRepository;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.persistence.WorkingCopyRepository;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyTreeDiffPort;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyTreeDiffPort.FileChange;
import com.ayywl.delveforge.application.repositoryanalysis.asset.SoftwareAssetNotFoundException;
import com.ayywl.delveforge.domain.asset.AssetAuthorizationConflictException;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanStateException;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepStatus;
import com.ayywl.delveforge.domain.evolution.WorkingCopyId;

import java.util.List;

/** 从历史 Step 自身确定端点；副本的当前 revision 不参与最终差异的含义。 */
public final class GetStepFinalChangesUseCase {
    private final EvolutionPlanRepository plans;
    private final WorkingCopyRepository copies;
    private final SoftwareAssetRepository assets;
    private final WorkingCopyTreeDiffPort trees;

    public GetStepFinalChangesUseCase(EvolutionPlanRepository plans, WorkingCopyRepository copies,
            SoftwareAssetRepository assets, WorkingCopyTreeDiffPort trees) {
        this.plans = plans;
        this.copies = copies;
        this.assets = assets;
        this.trees = trees;
    }

    public Result get(EvolutionPlanId planId, EvolutionStepId stepId) {
        var plan = plans.findById(planId).orElseThrow(() -> new EvolutionPlanNotFoundException(planId));
        var step = plan.step(stepId);
        String baseline = step.baselineRevision();
        if (step.status() != EvolutionStepStatus.SUCCEEDED) {
            return new Result(planId.value(), stepId.value(), baseline, null, "NOT_APPLICABLE",
                    "STEP_NOT_SUCCEEDED", null, null, null, null);
        }
        String verified = step.deliveryRevision();
        if (baseline == null || verified == null) {
            throw new EvolutionPlanStateException("Successful Step has no accepted Delivery identity");
        }
        var asset = assets.findById(plan.baseAssetId())
                .orElseThrow(() -> new SoftwareAssetNotFoundException(plan.baseAssetId()));
        asset.requireAnalysisAllowed();
        var copy = plan.workingCopyId() == null ? null
                : copies.findById(new WorkingCopyId(plan.workingCopyId())).orElse(null);
        if (copy == null) {
            return unavailable(planId, stepId, baseline, verified, "WORKSPACE_MISSING");
        }
        if (!copy.sourceAssetId().equals(plan.baseAssetId())) {
            throw new EvolutionPlanStateException("Stored WorkingCopy asset binding differs from Plan");
        }
        var difference = trees.compare(copy, baseline, verified);
        var currentAsset = assets.findById(plan.baseAssetId())
                .orElseThrow(() -> new SoftwareAssetNotFoundException(plan.baseAssetId()));
        currentAsset.requireAnalysisAllowed();
        if (currentAsset.authorizationRevision() != asset.authorizationRevision()) {
            throw new AssetAuthorizationConflictException();
        }
        if (difference.reason() != null) {
            return unavailable(planId, stepId, baseline, verified, difference.reason());
        }
        List<FileChange> files = List.copyOf(difference.files());
        return new Result(planId.value(), stepId.value(), baseline, verified, "AVAILABLE", null,
                difference.baselineTree(), difference.verifiedTree(), files,
                new Counts(count(files, "ADDED"), count(files, "MODIFIED"), count(files, "DELETED")));
    }

    private static int count(List<FileChange> files, String type) {
        return (int) files.stream().filter(file -> type.equals(file.changeType())).count();
    }

    private static Result unavailable(EvolutionPlanId plan, EvolutionStepId step, String baseline,
            String verified, String reason) {
        return new Result(plan.value(), step.value(), baseline, verified, "UNAVAILABLE", reason,
                null, null, null, null);
    }

    public record Counts(int added, int modified, int deleted) {}

    public record Result(String planId, String stepId, String baselineRevision, String verifiedRevision,
            String availability, String reason, String baselineTree, String verifiedTree,
            List<FileChange> files, Counts counts) {}
}
