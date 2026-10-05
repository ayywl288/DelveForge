package com.ayywl.delveforge.domain.evolution;
public record EvolutionPlanId(String value) {
    public EvolutionPlanId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("EvolutionPlanId must not be blank");
    }
}
