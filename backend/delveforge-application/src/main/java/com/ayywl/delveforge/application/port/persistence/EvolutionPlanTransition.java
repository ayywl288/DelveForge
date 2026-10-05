package com.ayywl.delveforge.application.port.persistence;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanStatus;
import java.util.Objects;

/** A domain-produced candidate plus the lifecycle facts on which the transition relied. */
public record EvolutionPlanTransition(EvolutionPlan plan, EvolutionPlanStatus expectedStatus,
        String expectedWorkingCopyId) {
    public EvolutionPlanTransition {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(expectedStatus);
    }
}
