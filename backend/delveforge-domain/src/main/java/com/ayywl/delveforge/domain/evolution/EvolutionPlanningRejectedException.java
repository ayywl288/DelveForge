package com.ayywl.delveforge.domain.evolution;

import java.util.Objects;

/**
 * 结构解析成功的提案未通过领域接受规则；固定原因不包含提案或画像原文。
 */
public class EvolutionPlanningRejectedException extends RuntimeException {
    public enum Reason {
        PROPOSAL_MISSING,
        TARGET_INTENT_MISMATCH,
        CURRENT_CAPABILITIES_NOT_FROM_BASE_PROFILE,
        CURRENT_MODULES_NOT_FROM_BASE_PROFILE,
        CURRENT_LIMITATIONS_NOT_FROM_BASE_PROFILE,
        REUSABLE_CAPABILITIES_NOT_FROM_BASE_PROFILE,
        EVIDENCE_NOT_FROM_INPUTS,
        BASE_PROFILE_EVIDENCE_MISSING,
        STEPS_OR_EVIDENCE_MISSING
    }

    private final Reason reason;

    public EvolutionPlanningRejectedException(Reason reason) {
        super(Objects.requireNonNull(reason).name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
