package com.ayywl.delveforge.domain.evolution;

import java.time.Instant;

/** 当前 Step 的一次明确 Repair 授权及其所依据的失败快照；追加保存后不覆盖旧事实。 */
public record RepairAuthorization(String id, String candidateRevision, Instant authorizedAt,
        ExecutionResult previousExecution, VerificationResult previousVerification) {
    public RepairAuthorization {
        id = PlanningContent.text(id, "repair.authorizationId");
        candidateRevision = PlanningContent.text(candidateRevision, "repair.candidateRevision");
        if (authorizedAt == null || previousExecution == null || previousVerification == null
                || !candidateRevision.equals(previousExecution.candidateRevision())
                || !candidateRevision.equals(previousVerification.candidateRevision()) || previousVerification.passed()) {
            throw new EvolutionPlanStateException("Repair authorization requires its failed Candidate snapshot");
        }
    }
}
