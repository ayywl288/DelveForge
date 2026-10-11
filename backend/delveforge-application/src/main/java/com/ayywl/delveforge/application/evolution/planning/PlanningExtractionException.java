package com.ayywl.delveforge.application.evolution.planning;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;

import java.util.Objects;

/** 规划调用的安全阶段分类。原因链仅供类型与堆栈诊断，不允许输出自由文本。 */
public final class PlanningExtractionException extends AiGatewayException {
    public enum Phase {
        INPUT_SERIALIZATION,
        PROVIDER_CALL,
        PROPOSAL_PARSE,
        EVIDENCE_RESOLVE
    }

    private final Phase phase;

    public PlanningExtractionException(Phase phase, Throwable cause) {
        super(Objects.requireNonNull(phase).name(), cause);
        this.phase = phase;
    }

    public Phase phase() {
        return phase;
    }
}
