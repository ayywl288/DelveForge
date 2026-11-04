package com.ayywl.delveforge.app.agent;

import com.ayywl.delveforge.application.agent.AgentStore;
import com.ayywl.delveforge.application.agent.CandidateDeliveryService;
import com.ayywl.delveforge.application.agent.CodingAgentService;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentAcceptanceContextApiTest {
    @Test
    void optionalContextUsesTheOrdinaryStartApiAndLegacyRequestsRemainValid() throws Exception {
        var service = mock(CodingAgentService.class);
        var controller = new CodingAgentController(service, mock(AgentStore.class), mock(CandidateDeliveryService.class));
        try {
            var mvc = MockMvcBuilders.standaloneSetup(controller).build();
            String endpoint = "/api/evolution-plans/plan/steps/step/agent-runs";
            mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("""
                    {"targetJdk":17,"paidUsageAcknowledged":true,"checkDirectories":["backend","frontend"],
                     "acceptanceContext":"Reject late responses and require recheck after failed creation"}
                    """))
                    .andExpect(status().isAccepted());
            verify(service).start("plan", "step", 17, List.of("backend", "frontend"),
                    "Reject late responses and require recheck after failed creation");
            mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("""
                    {"targetJdk":17,"paidUsageAcknowledged":true,"checkDirectories":["backend","frontend"]}
                    """))
                    .andExpect(status().isAccepted());
            verify(service).start("plan", "step", 17, List.of("backend", "frontend"), null);
        } finally {
            controller.closeStreams();
        }
    }

    @Test
    void acceptanceContextDoesNotAcknowledgePaidUsage() throws Exception {
        var service = mock(CodingAgentService.class);
        var controller = new CodingAgentController(service, mock(AgentStore.class), mock(CandidateDeliveryService.class));
        try {
            MockMvcBuilders.standaloneSetup(controller).build()
                    .perform(post("/api/evolution-plans/plan/steps/step/agent-runs")
                            .contentType(MediaType.APPLICATION_JSON).content("""
                                    {"targetJdk":17,"acceptanceContext":"Verify reservation precheck"}
                                    """))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(service);
        } finally {
            controller.closeStreams();
        }
    }
}
