package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;

public class EvolutionPlanAlreadyExistsException extends RuntimeException {
    public EvolutionPlanAlreadyExistsException(EvolutionPlanId id) {
        super("Evolution Plan already exists: " + id.value());
    }
}
