package com.ayywl.delveforge.app.error;

import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationException;
import com.ayywl.delveforge.application.evolution.preparation.UnsupportedVerificationCriteriaException;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class ExecutionFailureDiagnosticsTest {

    private static final String PRIVATE_TEXT = "PRIVATE_PROMPT_PROVIDER_SOURCE_CREDENTIAL";

    @Test
    void preparationPhasesHaveDifferentSafeCodesAndNeverPublishExceptionText(CapturedOutput output) throws Exception {
        for (ExecutionPreparationException.Phase phase : ExecutionPreparationException.Phase.values()) {
            boolean providerFailure = phase == ExecutionPreparationException.Phase.PROVIDER_CALL;
            String code = providerFailure ? "EXECUTION_AI_FAILED" : "CODING_PROPOSAL_INVALID";
            ExecutionPreparationException failure = new ExecutionPreparationException(phase,
                    new AiGatewayException(PRIVATE_TEXT, new RuntimeException(PRIVATE_TEXT)));
            String body = MockMvcBuilders.standaloneSetup(new Probe(failure))
                    .setControllerAdvice(new ApiExceptionHandler()).build()
                    .perform(get("/probe/execution"))
                    .andExpect(status().is(providerFailure ? 502 : 422))
                    .andExpect(jsonPath("$.code").value(code)).andReturn().getResponse().getContentAsString();
            assertFalse(body.contains(PRIVATE_TEXT));
            assertTrue(output.getOut().contains("phase=" + phase));
            assertTrue(output.getOut().contains("code=" + code));
        }
        assertFalse(output.getOut().contains(PRIVATE_TEXT));
        assertFalse(output.getErr().contains(PRIVATE_TEXT));
    }

    @Test
    void unsupportedCriteriaHaveFixedPreflightMessageAndOnlyOrdinalDiagnostics(CapturedOutput output) throws Exception {
        UnsupportedVerificationCriteriaException failure = new UnsupportedVerificationCriteriaException(List.of(4));
        failure.initCause(new RuntimeException(PRIVATE_TEXT));
        String body = MockMvcBuilders.standaloneSetup(new Probe(failure))
                .setControllerAdvice(new ApiExceptionHandler()).build()
                .perform(get("/probe/execution"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("EXECUTION_CRITERIA_UNSUPPORTED"))
                .andExpect(jsonPath("$.message").value(
                        "当前 Step 包含执行器不支持的验收标准，无法自动验证；重复执行不会解决，请审阅计划的验证标准"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(PRIVATE_TEXT));
        assertTrue(output.getOut().contains("phase=PREFLIGHT"));
        assertTrue(output.getOut().contains("criterionIndexes=[4]"));
        assertFalse(output.getOut().contains(PRIVATE_TEXT));
        assertFalse(output.getErr().contains(PRIVATE_TEXT));
    }

    @RestController
    static final class Probe {

        private final RuntimeException failure;

        Probe(RuntimeException failure) {
            this.failure = failure;
        }

        @GetMapping("/probe/execution")
        public void execute() {
            throw failure;
        }
    }
}
