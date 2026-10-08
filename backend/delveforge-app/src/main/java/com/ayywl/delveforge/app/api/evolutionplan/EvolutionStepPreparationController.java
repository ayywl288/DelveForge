package com.ayywl.delveforge.app.api.evolutionplan;

import com.ayywl.delveforge.application.evolution.preparation.CodingProposal;
import com.ayywl.delveforge.application.evolution.preparation.ConfirmEvolutionStepUseCase;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationBasis;
import com.ayywl.delveforge.application.evolution.preparation.PrepareEvolutionStepUseCase;
import com.ayywl.delveforge.application.evolution.preparation.ValidatedExecutionProposal;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 只映射明确确认与只读准备；没有 Proposal approval 或代码执行接口。 */
@RestController
@RequestMapping("/api/evolution-plans/{planId}/steps/{stepId}")
public class EvolutionStepPreparationController {
    private final ConfirmEvolutionStepUseCase confirm;
    private final PrepareEvolutionStepUseCase prepare;

    public EvolutionStepPreparationController(ConfirmEvolutionStepUseCase confirm,
            PrepareEvolutionStepUseCase prepare) {
        this.confirm = confirm;
        this.prepare = prepare;
    }

    @PostMapping("/confirm")
    public EvolutionPlanResponse confirm(@PathVariable String planId, @PathVariable String stepId) {
        return EvolutionPlanResponse.from(confirm.confirm(new EvolutionPlanId(planId), new EvolutionStepId(stepId)));
    }

    @PostMapping("/execution-preparation")
    public PreparationResponse prepare(@PathVariable String planId, @PathVariable String stepId) {
        ValidatedExecutionProposal result = prepare.prepare(new EvolutionPlanId(planId), new EvolutionStepId(stepId));
        return new PreparationResponse(result.basis(), result.proposal(), result.modelVisiblePaths());
    }

    public record PreparationResponse(ExecutionPreparationBasis basis, CodingProposal proposal,
            List<String> modelVisiblePaths) {}
}
