package com.ayywl.delveforge.application.project;

/** 归属缺失、矛盾或跨流程引用；调用方只能查询历史，不能推断归属。 */
public final class EvolutionFlowScopeException extends RuntimeException {
    public EvolutionFlowScopeException() {
        super("Evolution flow ownership is unknown or inconsistent");
    }
}
