package com.ayywl.delveforge.domain.evolution;

import java.time.Instant;

/** 单次回滚的诊断事实；observedRevision 仅是观测，不代表完整工作树已恢复。 */
public record RollbackResult(String authorizationId, String baselineRevision, String observedRevision,
        boolean restored, String diagnostic, Instant completedAt) {
    public RollbackResult {
        authorizationId = PlanningContent.text(authorizationId, "rollback.authorizationId");
        baselineRevision = PlanningContent.text(baselineRevision, "rollback.baselineRevision");
        diagnostic = PlanningContent.text(diagnostic, "rollback.diagnostic");
        if (completedAt == null || (observedRevision != null && observedRevision.isBlank())
                || (restored && !baselineRevision.equals(observedRevision))) {
            throw new EvolutionPlanStateException("Rollback success requires the exact original baseline");
        }
    }
}
