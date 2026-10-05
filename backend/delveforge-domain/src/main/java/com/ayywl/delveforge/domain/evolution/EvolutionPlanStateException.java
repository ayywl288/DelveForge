package com.ayywl.delveforge.domain.evolution;

/**
 * 请求的演化生命周期操作与当前依据冲突。
 */
public class EvolutionPlanStateException extends RuntimeException {
    public EvolutionPlanStateException(String message) {
        super(message);
    }
}
