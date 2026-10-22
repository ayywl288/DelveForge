package com.ayywl.delveforge.app.api.evolutionplan;

import com.ayywl.delveforge.application.evolution.preparation.ConfirmEvolutionStepUseCase;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 明确确认当前 Step，不调用模型，不执行后续步骤。 */
@RestController
@RequestMapping("/api/evolution-plans/{planId}/steps/{stepId}")
public class EvolutionStepPreparationController {
    private final ConfirmEvolutionStepUseCase confirm;

    public EvolutionStepPreparationController(ConfirmEvolutionStepUseCase confirm) {
        this.confirm = confirm;
    }

    @PostMapping("/confirm")
    public EvolutionPlanResponse confirm(@PathVariable String planId, @PathVariable String stepId) {
        return EvolutionPlanResponse.from(confirm.confirm(new EvolutionPlanId(planId), new EvolutionStepId(stepId)));
    }
}
