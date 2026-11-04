package com.ayywl.delveforge.app.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentStore;
import com.ayywl.delveforge.application.agent.CandidateDeliveryService;
import com.ayywl.delveforge.application.agent.CodingAgentService;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CandidateDebtDeliveryApiTest {
    @Test
    void legacyDeliveryDoesNotInferDebtAcknowledgmentAndReturnsAnActionableConflict() throws Exception {
        var delivery = mock(CandidateDeliveryService.class);
        var controller = new CodingAgentController(mock(CodingAgentService.class), mock(AgentStore.class), delivery);
        try {
            when(delivery.submit("candidate")).thenThrow(new AgentException("BASELINE_DEBT_ACK_REQUIRED", "review"));
            MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/agent-candidates/candidate/delivery"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BASELINE_DEBT_ACK_REQUIRED"));
            verify(delivery).submit("candidate");
        } finally {
            controller.closeStreams();
        }
    }

    @Test
    void explicitReviewCarriesBothFrozenIdentitiesToTheUseCase() throws Exception {
        var delivery = mock(CandidateDeliveryService.class);
        var controller = new CodingAgentController(mock(CodingAgentService.class), mock(AgentStore.class), delivery);
        try {
            MockMvcBuilders.standaloneSetup(controller).build().perform(post("/api/agent-candidates/candidate/delivery")
                    .contentType(MediaType.APPLICATION_JSON).content("""
                            {"baselineFailuresAcknowledged":true,"expectedDiffSha256":"diff","expectedEvidenceSha256":"evidence"}
                            """))
                    .andExpect(status().isAccepted());
            verify(delivery).acknowledgeBaselineDebtAndSubmit("candidate", "diff", "evidence");
        } finally {
            controller.closeStreams();
        }
    }
}
