package com.ayywl.delveforge.application.evolution.preparation;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;

import java.util.Objects;

/** 执行准备的安全阶段分类。原因链只供类型与堆栈诊断，不允许输出自由文本。 */
public final class ExecutionPreparationException extends AiGatewayException {

    public enum Phase {
        PROVIDER_CALL,
        PROPOSAL_PARSE,
        PROPOSAL_VALIDATION
    }

    private final Phase phase;

    public ExecutionPreparationException(Phase phase, Throwable cause) {
        super(Objects.requireNonNull(phase).name(), cause);
        this.phase = phase;
    }

    public Phase phase() {
        return phase;
    }
}
