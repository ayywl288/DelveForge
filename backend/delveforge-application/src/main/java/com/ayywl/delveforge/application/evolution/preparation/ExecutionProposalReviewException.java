package com.ayywl.delveforge.application.evolution.preparation;

/** 宿主机审查拒绝或不可用；不是 Provider 失败，也不产生 Verification 事实。 */
public final class ExecutionProposalReviewException extends RuntimeException {
    public enum Reason {
        REJECTED,
        UNAVAILABLE
    }

    private final Reason reason;

    public ExecutionProposalReviewException(Reason reason) {
        super("Execution proposal review " + reason.name());
        this.reason = reason;
    }

    public ExecutionProposalReviewException(Reason reason, Throwable cause) {
        super("Execution proposal review " + reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
