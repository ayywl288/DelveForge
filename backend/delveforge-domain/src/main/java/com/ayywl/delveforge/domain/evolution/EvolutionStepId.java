package com.ayywl.delveforge.domain.evolution;
public record EvolutionStepId(String value) {
    public EvolutionStepId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("EvolutionStepId must not be blank");
        }
    }
}
