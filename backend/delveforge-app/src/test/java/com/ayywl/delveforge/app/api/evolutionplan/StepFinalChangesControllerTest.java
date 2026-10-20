package com.ayywl.delveforge.app.api.evolutionplan;

import com.ayywl.delveforge.app.error.ApiExceptionHandler;
import com.ayywl.delveforge.application.evolution.query.GetStepFinalChangesUseCase;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StepFinalChangesControllerTest {
    @Test
    void exposesOnlyBoundIdentityAndPreservesAvailability() throws Exception {
        var query = mock(GetStepFinalChangesUseCase.class);
        var mvc = MockMvcBuilders.standaloneSetup(new StepFinalChangesController(query))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        when(query.get(new EvolutionPlanId("plan"), new EvolutionStepId("step")))
                .thenReturn(new GetStepFinalChangesUseCase.Result("plan", "step", "a", "c", "AVAILABLE",
                        null, "tree-a", "tree-c", List.of(), new GetStepFinalChangesUseCase.Counts(0, 0, 0)));
        mvc.perform(get("/api/evolution-plans/plan/steps/step/final-changes"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.counts.added").value(0)).andExpect(jsonPath("$.files").isEmpty());
        verify(query).get(new EvolutionPlanId("plan"), new EvolutionStepId("step"));
    }

    @Test
    void rejectsClientRevisionAndPathParameters() throws Exception {
        var query = mock(GetStepFinalChangesUseCase.class);
        var mvc = MockMvcBuilders.standaloneSetup(new StepFinalChangesController(query))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        for (String parameter : List.of("revision", "path", "baselineRevision", "workingCopy")) {
            mvc.perform(get("/api/evolution-plans/plan/steps/step/final-changes").param(parameter, "untrusted"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/evolution-plans/plan/steps/step/final-changes"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(query);
    }
}
