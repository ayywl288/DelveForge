package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanStatus;
import java.util.Objects;

/**
 * 同时携带领域候选及原生命周期依据，供提交时校验并发变化。
 */
public record EvolutionPlanTransition(EvolutionPlan plan, EvolutionPlanStatus expectedStatus,
        String expectedWorkingCopyId) {
    public EvolutionPlanTransition {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(expectedStatus);
    }
}
