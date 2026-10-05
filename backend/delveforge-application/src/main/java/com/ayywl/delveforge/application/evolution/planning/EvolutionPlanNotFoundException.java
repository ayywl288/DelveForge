package com.ayywl.delveforge.application.evolution.planning;

import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;

public class EvolutionPlanNotFoundException extends RuntimeException {
    public EvolutionPlanNotFoundException(EvolutionPlanId id) {
        super("Evolution Plan not found: " + id.value());
    }
}
