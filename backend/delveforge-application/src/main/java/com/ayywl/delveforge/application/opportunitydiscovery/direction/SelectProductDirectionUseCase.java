package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.domain.direction.*;
import com.ayywl.delveforge.domain.evolution.*;
import java.util.ArrayList;
import java.util.List;

/** Explicit human selection. INV-D09 and INV-P08 are committed together.
 * Domain operations run on isolated candidates; failed reads/writes never mutate loaded instances.
 */
public final class SelectProductDirectionUseCase {
    private final ProductDirectionRepository directions;
    private final EvolutionPlanRepository plans;
    private final EvolutionLifecycleCommitPort commits;

    public SelectProductDirectionUseCase(ProductDirectionRepository directions,
            EvolutionPlanRepository plans, EvolutionLifecycleCommitPort commits) {
        if (directions == null || plans == null || commits == null)
            throw new IllegalArgumentException("Direction selection dependencies are required");
        this.directions = directions;
        this.plans = plans;
        this.commits = commits;
    }

    public ProductDirection select(ProductDirectionId id) {
        if (id == null) throw new IllegalArgumentException("Direction identity is required");
        var loadedTarget = directions.findById(id).orElseThrow(() -> new ProductDirectionNotFoundException(id));
        var target = loadedTarget.copy();
        var targetBasis = target.status();
        target.select();
        var current = directions.findCurrentSelected();
        List<ProductDirectionTransition> directionTransitions = new ArrayList<>();
        List<EvolutionPlanTransition> planTransitions = new ArrayList<>();
        if (current.isPresent()) {
            var previous = current.get().copy();
            var previousBasis = previous.status();
            for (var loadedPlan : plans.findByProductDirectionId(previous.id())) {
                if (loadedPlan.status() == EvolutionPlanStatus.PROPOSED || loadedPlan.status() == EvolutionPlanStatus.ACTIVE) {
                    var candidate = loadedPlan.copy();
                    candidate.supersede();
                    planTransitions.add(new EvolutionPlanTransition(candidate, loadedPlan.status(), loadedPlan.workingCopyId()));
                }
            }
            previous.supersede();
            directionTransitions.add(new ProductDirectionTransition(previous, previousBasis));
        }
        directionTransitions.add(new ProductDirectionTransition(target, targetBasis));
        commits.commitDirectionSelection(List.copyOf(directionTransitions), List.copyOf(planTransitions));
        return target;
    }
}
