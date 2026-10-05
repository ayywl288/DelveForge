package com.ayywl.delveforge.application.evolution.planning;

import com.ayywl.delveforge.application.opportunitydiscovery.direction.ProductDirectionNotFoundException;
import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.application.repositoryanalysis.asset.SoftwareAssetNotFoundException;
import com.ayywl.delveforge.application.repositoryanalysis.profile.RepositoryProfileNotFoundException;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanningService;
import com.ayywl.delveforge.domain.evolution.PlanningProposal;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import java.util.Objects;

/**
 * 先完成一次 AI 调用与领域接受，再整体保存规划结果。
 * 不修改已加载的 Aggregate，也不引入 Workspace 能力。
 */
public final class GenerateEvolutionPlanUseCase {
    private final ProductDirectionRepository directions;
    private final SoftwareAssetRepository assets;
    private final RepositoryProfileRepository profiles;
    private final EvolutionPlanningExtraction extraction;
    private final EvolutionPlanningService planning;
    private final EvolutionPlanRepository plans;

    public GenerateEvolutionPlanUseCase(ProductDirectionRepository directions, SoftwareAssetRepository assets,
            RepositoryProfileRepository profiles, EvolutionPlanningExtraction extraction,
            EvolutionPlanningService planning, EvolutionPlanRepository plans) {
        this.directions = Objects.requireNonNull(directions);
        this.assets = Objects.requireNonNull(assets);
        this.profiles = Objects.requireNonNull(profiles);
        this.extraction = Objects.requireNonNull(extraction);
        this.planning = Objects.requireNonNull(planning);
        this.plans = Objects.requireNonNull(plans);
    }

    public EvolutionPlan generate(GenerateEvolutionPlanRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Planning request is required");
        }

        ProductDirection direction = directions.findById(request.productDirectionId())
                .orElseThrow(() -> new ProductDirectionNotFoundException(request.productDirectionId()));
        SoftwareAsset asset = assets.findById(request.baseAssetId())
                .orElseThrow(() -> new SoftwareAssetNotFoundException(request.baseAssetId()));
        RepositoryProfile profile = profiles.findById(request.baseRepositoryProfileId())
                .orElseThrow(() -> new RepositoryProfileNotFoundException(request.baseRepositoryProfileId()));

        planning.requirePlanningBasis(direction, asset, profile);

        PlanningProposal proposal = extraction.extract(direction, profile);

        // 外部调用后重新核对可变资格，同时保留本次规划使用的原始语义输入。
        ProductDirection currentDirection = directions.findById(direction.id())
                .orElseThrow(() -> new ProductDirectionNotFoundException(direction.id()));
        SoftwareAsset currentAsset = assets.findById(asset.id())
                .orElseThrow(() -> new SoftwareAssetNotFoundException(asset.id()));
        planning.requirePlanningBasis(currentDirection, currentAsset, profile);

        EvolutionPlan plan = planning.plan(direction, asset, profile, proposal);

        plans.save(plan);
        return plan;
    }
}
