package com.ayywl.delveforge.application.agent;

import java.time.Instant;

/** 独立于不可变 Candidate 的交付日志；revision 是预定正式 commit，而不是 tree。 */
public record Delivery(String candidateId, String runId, String planId, String stepId, String workingCopyId,
        String baseline, String expectedTree, String location, String originalLocation, State state,
        String revision, String reason, Instant createdAt) {

    public enum State {
        INTENT, APPLIED, COMMIT_READY, PUBLISHED, COMPLETED, DELIVERY_BLOCKED, RECOVERY_REQUIRED
    }
}
