package com.ayywl.delveforge.app.api.evolutionplan;

import com.ayywl.delveforge.application.evolution.execution.ExecuteEvolutionStepUseCase;
import com.ayywl.delveforge.application.evolution.execution.RetryEvolutionStepUseCase;
import com.ayywl.delveforge.application.evolution.execution.RollbackEvolutionStepUseCase;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 原 Step 的 Execution / Repair / Rollback / Retry 入口；授权与执行仍分别通过对应用例校验。 */
@RestController
@RequestMapping("/api/evolution-plans/{planId}/steps/{stepId}")
public class EvolutionStepExecutionController {
    private final ExecuteEvolutionStepUseCase execute;
    private final RollbackEvolutionStepUseCase rollback;
    private final RetryEvolutionStepUseCase retry;

    public EvolutionStepExecutionController(ExecuteEvolutionStepUseCase execute, RollbackEvolutionStepUseCase rollback,
            RetryEvolutionStepUseCase retry) {
        this.execute = execute;
        this.rollback = rollback;
        this.retry = retry;
    }

    @PostMapping("/execute")
    public EvolutionPlanResponse execute(@PathVariable String planId, @PathVariable String stepId,
            @RequestBody(required = false) ExecuteRequest request) {
        return EvolutionPlanResponse.from(execute.execute(new EvolutionPlanId(planId), new EvolutionStepId(stepId),
                request == null ? null : request.retryAuthorizationId()));
    }

    public record ExecuteRequest(String retryAuthorizationId) {
        public ExecuteRequest {
            retryAuthorizationId = canonicalRetryId(retryAuthorizationId);
        }
    }

    @PostMapping("/retry")
    public EvolutionPlanResponse retry(@PathVariable String planId, @PathVariable String stepId,
            @RequestBody RetryRequest request) {
        return EvolutionPlanResponse.from(retry.retry(new EvolutionPlanId(planId), new EvolutionStepId(stepId),
                request.expectedFailureId(), request.expectedBaselineRevision(), request.authorizationId()));
    }

    public record RetryRequest(String expectedFailureId, String expectedBaselineRevision, String authorizationId) {
        public RetryRequest {
            expectedFailureId = canonicalRetryId(expectedFailureId);
            authorizationId = canonicalRetryId(authorizationId);
            if (expectedBaselineRevision == null || !expectedBaselineRevision.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("Retry requires the original baseline commit");
            }
        }
    }

    private static String canonicalRetryId(String value) {
        if (value == null || !value.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("Retry requires an explicit canonical action identity");
        }
        return UUID.fromString(value).toString();
    }

    @PostMapping("/repair")
    public EvolutionPlanResponse repair(@PathVariable String planId, @PathVariable String stepId,
            @RequestBody RepairRequest request) {
        return EvolutionPlanResponse.from(execute.repair(new EvolutionPlanId(planId), new EvolutionStepId(stepId),
                request.expectedCandidateRevision(), UUID.fromString(request.authorizationId()).toString(),
                request.retryAuthorizationId()));
    }

    public record RepairRequest(String expectedCandidateRevision, String authorizationId, String retryAuthorizationId) {
        public RepairRequest(String expectedCandidateRevision, String authorizationId) {
            this(expectedCandidateRevision, authorizationId, null);
        }
        public RepairRequest {
            if (retryAuthorizationId != null) {
                retryAuthorizationId = canonicalRetryId(retryAuthorizationId);
            }
            if (expectedCandidateRevision == null || !expectedCandidateRevision.matches("[0-9a-f]{40}")
                    || authorizationId == null || !authorizationId.matches(
                            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                throw new IllegalArgumentException("Repair requires a Candidate commit and an explicit authorization ID");
            }
        }
    }

    @PostMapping("/rollback")
    public EvolutionPlanResponse rollback(@PathVariable String planId, @PathVariable String stepId,
            @RequestBody RollbackRequest request) {
        return EvolutionPlanResponse.from(rollback.rollback(new EvolutionPlanId(planId), new EvolutionStepId(stepId),
                request.expectedCandidateRevision(), request.expectedBaselineRevision(),
                UUID.fromString(request.authorizationId()).toString(), request.retryAuthorizationId()));
    }

    public record RollbackRequest(String expectedCandidateRevision, String expectedBaselineRevision,
            String authorizationId, String retryAuthorizationId) {
        public RollbackRequest {
            new RepairRequest(expectedCandidateRevision, authorizationId, retryAuthorizationId);
            if (retryAuthorizationId != null) {
                retryAuthorizationId = canonicalRetryId(retryAuthorizationId);
            }
            if (expectedBaselineRevision == null || !expectedBaselineRevision.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("Rollback requires its original complete baseline commit");
            }
        }
    }
}
