package com.ayywl.delveforge.domain.evolution;
/**
 * 结构解析成功的提案未通过领域接受规则。
 */
public class EvolutionPlanningRejectedException extends RuntimeException {
    public EvolutionPlanningRejectedException(String message) {
        super(message);
    }
}
