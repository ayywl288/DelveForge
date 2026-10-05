package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.domain.direction.*;
import com.ayywl.delveforge.domain.evolution.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 用户显式选择时，将 INV-D09 与 INV-P08 的状态变更一起提交。
 * 领域操作作用于隔离候选，避免读取或提交失败时改变已加载对象。
 */
public final class SelectProductDirectionUseCase {
    private final ProductDirectionRepository directions;
    private final EvolutionPlanRepository plans;
    private final EvolutionLifecycleCommitPort commits;

    public SelectProductDirectionUseCase(ProductDirectionRepository directions,
            EvolutionPlanRepository plans, EvolutionLifecycleCommitPort commits) {
        if (directions == null || plans == null || commits == null) {
            throw new IllegalArgumentException("Direction selection dependencies are required");
        }
        this.directions = directions;
        this.plans = plans;
        this.commits = commits;
    }

    public ProductDirection select(ProductDirectionId id) {
        if (id == null) {
            throw new IllegalArgumentException("Direction identity is required");
        }

        ProductDirection loadedTarget = directions.findById(id).orElseThrow(() -> new ProductDirectionNotFoundException(id));
        ProductDirection target = loadedTarget.copy();
        ProductDirectionStatus targetBasis = target.status();
        target.select();

        Optional<ProductDirection> current = directions.findCurrentSelected();
        List<ProductDirectionTransition> directionTransitions = new ArrayList<>();
        List<EvolutionPlanTransition> planTransitions = new ArrayList<>();
        if (current.isPresent()) {
            ProductDirection previous = current.get().copy();
            ProductDirectionStatus previousBasis = previous.status();
            for (EvolutionPlan loadedPlan : plans.findByProductDirectionId(previous.id())) {
                if (loadedPlan.status() == EvolutionPlanStatus.PROPOSED || loadedPlan.status() == EvolutionPlanStatus.ACTIVE) {
                    EvolutionPlan candidate = loadedPlan.copy();
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
