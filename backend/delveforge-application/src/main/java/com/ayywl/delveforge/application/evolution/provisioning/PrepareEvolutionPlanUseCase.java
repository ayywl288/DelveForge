package com.ayywl.delveforge.application.evolution.provisioning;

import com.ayywl.delveforge.application.evolution.planning.EvolutionPlanNotFoundException;
import com.ayywl.delveforge.application.opportunitydiscovery.direction.ProductDirectionNotFoundException;
import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.application.port.workspace.*;
import com.ayywl.delveforge.application.repositoryanalysis.asset.SoftwareAssetNotFoundException;
import com.ayywl.delveforge.application.repositoryanalysis.profile.RepositoryProfileNotFoundException;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.evolution.*;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 先校验依据并完成外部准备，再在隔离领域候选上转换状态，最后一次原子提交。
 * 失败不会改变已加载的 Aggregate；准备成功也不授予 EvolutionStep 执行权限。
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

        EvolutionPlan plan = plans.findById(id).orElseThrow(() -> new EvolutionPlanNotFoundException(id));
        ProductDirection direction = directions.findById(plan.productDirectionId())
                .orElseThrow(() -> new ProductDirectionNotFoundException(plan.productDirectionId()));
        SoftwareAsset asset = assets.findById(plan.baseAssetId())
                .orElseThrow(() -> new SoftwareAssetNotFoundException(plan.baseAssetId()));
        RepositoryProfile profile = profiles.findById(plan.baseRepositoryProfileId())
                .orElseThrow(() -> new RepositoryProfileNotFoundException(plan.baseRepositoryProfileId()));

        activation.requirePreparationAllowed(plan, direction, asset, profile);

        WorkingCopyId copyId = Objects.requireNonNull(ids.get());
        // 先完成外部准备，避免 Git 失败时留下半完成的权威领域状态。
        PreparedWorkspace prepared = provisioning.provision(new WorkspaceRef(asset.location()),
                profile.analyzedRevision(), copyId.value());

        try {
            // 外部准备期间资格可能变化，进入领域转换前再次核对。
            ProductDirection currentDirection = directions.findById(direction.id())
                    .orElseThrow(() -> new ProductDirectionNotFoundException(direction.id()));
            SoftwareAsset currentAsset = assets.findById(asset.id())
                    .orElseThrow(() -> new SoftwareAssetNotFoundException(asset.id()));
            activation.requirePreparationAllowed(plan, currentDirection, currentAsset, profile);
            if (!asset.location().equals(currentAsset.location())) {
                throw new EvolutionPlanStateException("Asset source location changed during preparation");
            }

            WorkingCopy copy = WorkingCopy.create(copyId, asset.id(), profile.analyzedRevision(), prepared.workspace().value());
            copy.markReady(prepared.revision());

            EvolutionPlan candidate = plan.copy();
            candidate.bindWorkingCopy(copy);
            candidate.activate(activation, copy, currentDirection, currentAsset, profile);

            commits.commitActivation(candidate, copy, currentAsset);
            return candidate;
        } catch (RuntimeException failure) {
            // 只补偿本次已准备但未成功提交的候选；清理失败不能掩盖原始失败。
            try {
                provisioning.discard(prepared);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
                // 只记录异常类型，避免日志泄露异常消息、源位置、提示词或 Provider 数据（ADR-0002）。
                log.warn("operation=evolution.prepare planId={} copyId={} result=CLEANUP_FAILED exceptionType={}",
                        id.value(), copyId.value(), cleanup.getClass().getName());
            }
            throw failure;
        }
    }
}
