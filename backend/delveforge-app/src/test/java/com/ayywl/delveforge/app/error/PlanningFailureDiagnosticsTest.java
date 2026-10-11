package com.ayywl.delveforge.app.error;

import com.ayywl.delveforge.application.evolution.planning.PlanningExtractionException;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanningRejectedException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class PlanningFailureDiagnosticsTest {
    private static final String PRIVATE_TEXT = "PRIVATE_PROMPT_PROVIDER_SOURCE_CREDENTIAL";

    @Test
    void extractionPhasesHaveFixedCodesWithoutLoggingExceptionText(CapturedOutput output) throws Exception {
        for (PlanningExtractionException.Phase phase : PlanningExtractionException.Phase.values()) {
            String code = switch (phase) {
                case INPUT_SERIALIZATION -> "INTERNAL_ERROR";
                case PROVIDER_CALL -> "PLANNING_AI_FAILED";
                case PROPOSAL_PARSE -> "PLANNING_PROPOSAL_INVALID";
                case EVIDENCE_RESOLVE -> "PLANNING_EVIDENCE_INVALID";
            };
            var failure = new PlanningExtractionException(phase,
                    new AiGatewayException(PRIVATE_TEXT, new RuntimeException(PRIVATE_TEXT)));
            String body = MockMvcBuilders.standaloneSetup(new Probe(failure))
                    .setControllerAdvice(new ApiExceptionHandler()).build()
                    .perform(get("/probe/planning"))
                    .andExpect(status().is(phase == PlanningExtractionException.Phase.INPUT_SERIALIZATION ? 500 : 502))
                    .andExpect(jsonPath("$.code").value(code)).andReturn().getResponse().getContentAsString();
            assertFalse(body.contains(PRIVATE_TEXT));
            assertTrue(output.getOut().contains("phase=" + phase));
            assertTrue(output.getOut().contains("code=" + code));
        }
        assertFalse(output.getOut().contains(PRIVATE_TEXT));
        assertFalse(output.getErr().contains(PRIVATE_TEXT));
    }

    @Test
    void domainReasonsIdentifyRejectedFactSectionWithoutPublishingFactValues(CapturedOutput output) throws Exception {
        for (EvolutionPlanningRejectedException.Reason reason : EvolutionPlanningRejectedException.Reason.values()) {
            var failure = new EvolutionPlanningRejectedException(reason);
            failure.initCause(new RuntimeException(PRIVATE_TEXT));
            String code = reason.name().startsWith("CURRENT_")
                    ? "PLANNING_CURRENT_STATE_INVALID" : "PLANNING_REJECTED";
            MockMvcBuilders.standaloneSetup(new Probe(failure)).setControllerAdvice(new ApiExceptionHandler()).build()
                    .perform(get("/probe/planning")).andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value(code));
            assertTrue(output.getOut().contains("phase=DOMAIN_ACCEPTANCE"));
            assertTrue(output.getOut().contains("reason=" + reason));
        }
        assertFalse(output.getOut().contains(PRIVATE_TEXT));
        assertFalse(output.getErr().contains(PRIVATE_TEXT));
    }

    @RestController
    static final class Probe {
        private final RuntimeException failure;

        Probe(RuntimeException failure) {
            this.failure = failure;
        }

        @GetMapping("/probe/planning")
        public void planning() {
            throw failure;
        }
    }
}
