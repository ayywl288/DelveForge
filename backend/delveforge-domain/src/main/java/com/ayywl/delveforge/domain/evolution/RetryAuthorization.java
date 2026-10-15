package com.ayywl.delveforge.domain.evolution;

import java.time.Instant;
import java.util.List;

/** 一次完整 Retry 的独立授权，及被该次授权归档的上一轮 FAILED 事实；不递归复制更早 Retry。 */
public record RetryAuthorization(String id, String failedCheckpointId, String baselineRevision, Instant authorizedAt,
        ExecutionResult previousExecution, VerificationResult previousVerification,
        List<RepairAuthorization> previousRepairs, RollbackAuthorization previousRollbackAuthorization,
        RollbackResult previousRollbackResult, ExecutionFailureResult previousExecutionFailure,
        AutomaticRollbackResult previousAutomaticRollback) {
    public RetryAuthorization {
        id = PlanningContent.text(id, "retry.authorizationId");
        failedCheckpointId = PlanningContent.text(failedCheckpointId, "retry.failedCheckpointId");
        baselineRevision = PlanningContent.text(baselineRevision, "retry.baselineRevision");
        previousRepairs = List.copyOf(previousRepairs);
        boolean automatic = previousExecutionFailure != null;
        if (authorizedAt == null || id.equals(failedCheckpointId)
                || (automatic && (previousExecution != null || previousVerification != null
                        || previousRollbackAuthorization != null || previousRollbackResult != null
                        || previousAutomaticRollback == null || !previousAutomaticRollback.restored()
                        || !failedCheckpointId.equals(previousExecutionFailure.id())
                        || !failedCheckpointId.equals(previousAutomaticRollback.failureId())
                        || !baselineRevision.equals(previousExecutionFailure.baselineRevision())
                        || !baselineRevision.equals(previousAutomaticRollback.baselineRevision())))
                || (!automatic && (previousExecution == null || previousVerification == null
                        || previousVerification.passed() || previousRollbackAuthorization == null
                        || previousRollbackResult == null || !previousRollbackResult.restored()
                        || previousAutomaticRollback != null
                        || !failedCheckpointId.equals(previousRollbackAuthorization.id())
                        || !failedCheckpointId.equals(previousRollbackResult.authorizationId())
                        || !baselineRevision.equals(previousRollbackAuthorization.baselineRevision())
                        || !baselineRevision.equals(previousRollbackResult.baselineRevision())))) {
            throw new EvolutionPlanStateException("Retry requires a distinct authorization and exact restored failure");
        }
    }
}
