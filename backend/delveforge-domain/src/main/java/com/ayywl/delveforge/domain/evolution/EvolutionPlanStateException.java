package com.ayywl.delveforge.domain.evolution;

/** A requested evolution lifecycle operation conflicts with its current basis. */
public class EvolutionPlanStateException extends RuntimeException {
    public EvolutionPlanStateException(String message) { super(message); }
}
