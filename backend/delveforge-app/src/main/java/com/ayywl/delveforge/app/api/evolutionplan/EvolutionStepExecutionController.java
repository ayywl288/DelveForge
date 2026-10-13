package com.ayywl.delveforge.app.api.evolutionplan;

import com.ayywl.delveforge.application.evolution.execution.ExecuteEvolutionStepUseCase;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 原 Step 执行或独立授权 Repair；每次调用重新准备、校验，不接受客户端序列化的可信提案。 */
@RestController
@RequestMapping("/api/evolution-plans/{planId}/steps/{stepId}")
public class EvolutionStepExecutionController {
    private final ExecuteEvolutionStepUseCase execute;

    public EvolutionStepExecutionController(ExecuteEvolutionStepUseCase execute) {
        this.execute = execute;
    }

    @PostMapping("/execute")
    public EvolutionPlanResponse execute(@PathVariable String planId, @PathVariable String stepId) {
        return EvolutionPlanResponse.from(execute.execute(new EvolutionPlanId(planId), new EvolutionStepId(stepId)));
    }

    @PostMapping("/repair")
    public EvolutionPlanResponse repair(@PathVariable String planId, @PathVariable String stepId,
            @RequestBody RepairRequest request) {
        return EvolutionPlanResponse.from(execute.repair(new EvolutionPlanId(planId), new EvolutionStepId(stepId),
                request.expectedCandidateRevision(), UUID.fromString(request.authorizationId()).toString()));
    }

    public record RepairRequest(String expectedCandidateRevision, String authorizationId) {
        public RepairRequest {
            if (expectedCandidateRevision == null || !expectedCandidateRevision.matches("[0-9a-f]{40}")
                    || authorizationId == null || !authorizationId.matches(
                            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                throw new IllegalArgumentException("Repair requires a Candidate commit and an explicit authorization ID");
            }
        }
    }
}
