package com.ayywl.delveforge.domain.evolution;
/** A structurally parsed proposal failed domain acceptance. */
public class EvolutionPlanningRejectedException extends RuntimeException {
    public EvolutionPlanningRejectedException(String message) { super(message); }
}
