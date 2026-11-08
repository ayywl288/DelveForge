package com.ayywl.delveforge.application.quickstart;

import java.time.Instant;

/** Plan 的说明性产物；生成状态不会改变 Delivery 或可信 revision。 */
public record QuickStart(String planId, String candidateId, String revision, String status,
        String markdown, String reason, Instant updatedAt, boolean stale) {
}
