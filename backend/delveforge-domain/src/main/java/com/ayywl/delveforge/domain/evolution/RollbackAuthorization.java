package com.ayywl.delveforge.domain.evolution;

import java.time.Instant;

/** 原 Step 对当前 Candidate 的一次独立回滚授权；目标固定为首次 baseline。 */
public record RollbackAuthorization(String id, String candidateRevision, String baselineRevision, Instant authorizedAt) {
    public RollbackAuthorization {
        id = PlanningContent.text(id, "rollback.authorizationId");
        candidateRevision = PlanningContent.text(candidateRevision, "rollback.candidateRevision");
        baselineRevision = PlanningContent.text(baselineRevision, "rollback.baselineRevision");
        if (authorizedAt == null || candidateRevision.equals(baselineRevision)) {
            throw new EvolutionPlanStateException("Rollback requires its failed Candidate and original baseline");
        }
    }
}
