package com.ayywl.delveforge.app.api.evolutionplan;

import com.ayywl.delveforge.application.evolution.query.GetStepFinalChangesUseCase;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/evolution-plans/{planId}/steps/{stepId}/final-changes")
public final class StepFinalChangesController {
    private final GetStepFinalChangesUseCase query;

    public StepFinalChangesController(GetStepFinalChangesUseCase query) {
        this.query = query;
    }

    @GetMapping
    public GetStepFinalChangesUseCase.Result get(@PathVariable String planId, @PathVariable String stepId,
            @RequestParam Map<String, String> parameters) {
        if (!parameters.isEmpty()) {
            throw new IllegalArgumentException("Final changes accepts no query parameters");
        }
        return query.get(new EvolutionPlanId(planId), new EvolutionStepId(stepId));
    }
}
