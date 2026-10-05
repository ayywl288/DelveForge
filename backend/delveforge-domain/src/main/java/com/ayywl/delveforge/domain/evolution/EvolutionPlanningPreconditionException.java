package com.ayywl.delveforge.domain.evolution;
/**
 * 请求使用的规划依据当前不满足资格条件。
 */
public class EvolutionPlanningPreconditionException extends RuntimeException {
    public EvolutionPlanningPreconditionException(String message) {
        super(message);
    }
}
