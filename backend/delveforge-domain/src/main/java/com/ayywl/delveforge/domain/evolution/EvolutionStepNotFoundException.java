package com.ayywl.delveforge.domain.evolution;

public final class EvolutionStepNotFoundException extends RuntimeException {
    public EvolutionStepNotFoundException(EvolutionStepId id) {
        super("Step does not belong to the requested Plan: " + id.value());
    }
}
