package com.ayywl.delveforge.application.evolution.planning;
import com.ayywl.delveforge.application.opportunitydiscovery.direction.ProductDirectionNotFoundException;
import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.application.repositoryanalysis.asset.SoftwareAssetNotFoundException;
import com.ayywl.delveforge.application.repositoryanalysis.profile.RepositoryProfileNotFoundException;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanningService;
import java.util.Objects;

/** One AI invocation, followed by domain acceptance and one coherent persistence write.
 * Loaded Aggregates are never mutated. No Workspace capability participates in planning.
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
        if (request == null) throw new IllegalArgumentException("Planning request is required");
        var direction = directions.findById(request.productDirectionId())
                .orElseThrow(() -> new ProductDirectionNotFoundException(request.productDirectionId()));
        var asset = assets.findById(request.baseAssetId())
                .orElseThrow(() -> new SoftwareAssetNotFoundException(request.baseAssetId()));
        var profile = profiles.findById(request.baseRepositoryProfileId())
                .orElseThrow(() -> new RepositoryProfileNotFoundException(request.baseRepositoryProfileId()));
        planning.requirePlanningBasis(direction, asset, profile);
        var proposal = extraction.extract(direction, profile);
        // Recheck mutable eligibility after external work, without changing the planning inputs.
        var currentDirection = directions.findById(direction.id())
                .orElseThrow(() -> new ProductDirectionNotFoundException(direction.id()));
        var currentAsset = assets.findById(asset.id())
                .orElseThrow(() -> new SoftwareAssetNotFoundException(asset.id()));
        planning.requirePlanningBasis(currentDirection, currentAsset, profile);
        var plan = planning.plan(direction, asset, profile, proposal);
        plans.save(plan);
        return plan;
    }
}
