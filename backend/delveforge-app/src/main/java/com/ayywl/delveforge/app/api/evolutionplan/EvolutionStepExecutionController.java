package com.ayywl.delveforge.app.api.evolutionplan;

import com.ayywl.delveforge.application.evolution.execution.ExecuteEvolutionStepUseCase;
import com.ayywl.delveforge.domain.evolution.*;
import org.springframework.web.bind.annotation.*;

/** 从当前 READY Step 在一次调用中重新准备、校验和执行；不接受客户端序列化的可信提案。 */
@RestController
@RequestMapping("/api/evolution-plans/{planId}/steps/{stepId}")
public class EvolutionStepExecutionController {
    private final ExecuteEvolutionStepUseCase execute;
    public EvolutionStepExecutionController(ExecuteEvolutionStepUseCase execute) { this.execute = execute; }

    @PostMapping("/execute")
    public EvolutionPlanResponse execute(@PathVariable String planId, @PathVariable String stepId) {
        return EvolutionPlanResponse.from(execute.execute(new EvolutionPlanId(planId), new EvolutionStepId(stepId)));
    }
}
