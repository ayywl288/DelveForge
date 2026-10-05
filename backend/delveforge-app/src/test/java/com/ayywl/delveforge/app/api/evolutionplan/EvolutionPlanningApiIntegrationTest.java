package com.ayywl.delveforge.app.api.evolutionplan;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ayywl.delveforge.application.port.ai.*;
import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceMutationPort;
import com.ayywl.delveforge.domain.asset.*;
import com.ayywl.delveforge.domain.direction.*;
import com.ayywl.delveforge.domain.evidence.*;
import com.ayywl.delveforge.domain.evolution.*;
import com.ayywl.delveforge.domain.repositoryprofile.*;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Complete real HTTP/application/domain/persistence slice; AI alone is a proposal fixture.
 * Workspace ports are observable mocks to prove planning requests no code access.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EvolutionPlanningApiIntegrationTest {
    private static final Path DB = Path.of("target", "test-databases", UUID.randomUUID().toString(), "planning-api.db");
    private static final String JSON = """
{"currentState":{"summary":"Existing exports","capabilities":["Export"],"modules":["reports"],"limitations":["No scheduling"]},
"targetState":{"problem":"Manual exports","targetProduct":"Scheduled reports","differentiation":"Personal schedule"},
"reusableCapabilities":["Render"],"changes":["Add scheduling"],
"steps":[{"goal":"Schedule reports","scope":"Reporting scheduling","plannedChanges":["Add scheduling"],"preconditions":[],"verificationCriteria":["Reports run on schedule"]}],
"risks":[],"evidence":["R-E1","D-E1"]}
            """;
    private static final String REQUEST = """
            {"productDirectionId":"direction","baseAssetId":"asset","baseRepositoryProfileId":"profile"}
            """;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DB::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProductDirectionRepository directions;
    @Autowired SoftwareAssetRepository assets;
    @Autowired RepositoryProfileRepository profiles;
    @Autowired EvolutionPlanRepository plans;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AiGateway ai;
    @MockitoBean WorkspaceReadPort reads;
    @MockitoBean WorkspaceMutationPort mutations;
    private static final SoftwareAssetId ASSET = new SoftwareAssetId("asset");
    private static final RepositoryProfileId PROFILE = new RepositoryProfileId("profile");
    private static final Evidence FACT = new Evidence(EvidenceSourceType.REPOSITORY, "src/report", "Existing report exports", 0.8, true);
    private static final EvidenceBasis BASIS = new EvidenceBasis(FACT, new RepositoryProfileEvidenceOrigin(PROFILE));
    private static SoftwareAsset asset(boolean read, String license, UsageAuthorization authorization) {
        return SoftwareAsset.create(ASSET, SoftwareAssetType.GIT_REPOSITORY, SoftwareAssetSource.USER_SPECIFIED,
                "never-open-this-path", read, license, authorization);
    }
    private static RepositoryProfile profile() {
        return RepositoryProfile.create(PROFILE, ASSET, "commit123", "Reporting", List.of("Java"),
                List.of("reports"), List.of("Export"), List.of("Render"), List.of("No scheduling"), List.of(), List.of(FACT));
    }
    private static ProductDirection direction(boolean selected) {
        var direction = ProductDirection.create(new ProductDirectionId("direction"), new UserProfileId("user"), 1,
                List.of(PROFILE), "Personal reports", "Manual exports", "Scheduled reports", "Fits goals",
                List.of(ASSET), "Personal schedule", "Scheduling", "Small", List.of(),
                new DirectionEvidenceSupport(List.of(BASIS), List.of(BASIS), List.of(BASIS)));
        if (selected) direction.select();
        return direction;
    }
    private static PlanningProposal proposal() {
        return new PlanningProposal(new CurrentState("Existing exports with no scheduling", List.of("Export"),
                List.of("reports"), List.of("No scheduling")),
                new TargetState("Manual exports", "Scheduled reports", "Personal schedule"),
                List.of("Render", "Export"), List.of("Add scheduling"),
                List.of(new PlanningStepProposal("Schedule reports", "Reporting scheduling",
                        List.of("Add scheduling capability"), List.of(), List.of("Reports run at the user-selected time")),
                        new PlanningStepProposal("Expose schedule management", "Personal schedule controls",
                        List.of("Manage schedules"), List.of("Scheduling capability exists"), List.of("Users can change schedules"))),
                List.of("Missed reports"), List.of(BASIS));
    }

    @BeforeEach void seed() {
        assets.save(asset(true, "MIT", UsageAuthorization.ALLOWED));
        profiles.save(profile());
        directions.save(direction(true));
        when(ai.generate(any())).thenReturn(JSON);
    }
    @Test void createsAndRetrievesThePersistedProposedPlanWithoutWorkspaceAccess() throws Exception {
        String body = mvc.perform(post("/api/evolution-plans/planning").contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST.substring(0, REQUEST.lastIndexOf('}'))
                        + ",\"status\":\"ACTIVE\",\"workingCopyId\":\"injected\",\"steps\":[],\"id\":\"client\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.workingCopyId").isEmpty())
                .andExpect(jsonPath("$.targetState.targetProduct").value("Scheduled reports"))
                .andExpect(jsonPath("$.steps[0].status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.steps[0].baselineRevision").isEmpty())
                .andExpect(jsonPath("$.evidence[0].origin.repositoryProfileId").value("profile"))
                .andReturn().getResponse().getContentAsString();
        var response = mapper.readTree(body);
        String id = response.path("id").asText();
        assertNotEquals("client", id);
        assertEquals(id, response.path("steps").get(0).path("planId").asText());
        mvc.perform(get("/api/evolution-plans/" + id)).andExpect(status().isOk())
                .andExpect(content().json(body));
        assertTrue(plans.findById(new EvolutionPlanId(id)).isPresent());
        verify(ai, times(1)).generate(any());
        verifyNoInteractions(reads, mutations);
    }
    @Test void mapsBadRequestsAndMissingResourcesBeforeAi() throws Exception {
        for (String invalid : List.of("{}", "{\"productDirectionId\":null}", "{\"productDirectionId\":\" \"}"))
            mvc.perform(post("/api/evolution-plans/planning").contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        for (String field : List.of("direction", "asset", "profile"))
            mvc.perform(post("/api/evolution-plans/planning").contentType(MediaType.APPLICATION_JSON)
                    .content(REQUEST.replace("\"" + field + "\"", "\"missing\"")))
                    .andExpect(status().isNotFound());
        mvc.perform(get("/api/evolution-plans/missing")).andExpect(status().isNotFound());
        verifyNoInteractions(ai, reads, mutations);
        assertNoPlans();
    }
    @Test void mapsIneligibleBasisToConflictWithNoAiOrWrites() throws Exception {
        jdbc.update("UPDATE product_direction SET status='CANDIDATE' WHERE id='direction'");
        mvc.perform(post("/api/evolution-plans/planning").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE product_direction SET status='SELECTED' WHERE id='direction'");
        assets.save(asset(true, "MIT", UsageAuthorization.DENIED));
        mvc.perform(post("/api/evolution-plans/planning").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isConflict());
        assets.save(asset(true, null, UsageAuthorization.ALLOWED));
        mvc.perform(post("/api/evolution-plans/planning").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isConflict());
        verifyNoInteractions(ai, reads, mutations);
        assertNoPlans();
    }
    @Test void mapsMalformedAndDomainRejectedAiProposalsTo502AndLeavesNoState() throws Exception {
        for (String invalid : List.of("not json", JSON.replace("R-E1", "invented"),
                JSON.replace("Scheduled reports", "Weakened target"), JSON.replace("\"capabilities\":[\"Export\"]", "\"capabilities\":[\"Invented\"]"))) {
            when(ai.generate(any())).thenReturn(invalid);
            mvc.perform(post("/api/evolution-plans/planning").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("EXTERNAL_CAPABILITY_UNAVAILABLE"));
            assertNoPlans();
        }
        when(ai.generate(any())).thenThrow(new AiGatewayException("sensitive provider data"));
        mvc.perform(post("/api/evolution-plans/planning").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive provider data"))));
        assertNoPlans();
        assertEquals(ProductDirectionStatus.SELECTED, directions.findById(new ProductDirectionId("direction")).orElseThrow().status());
        verifyNoInteractions(reads, mutations);
    }
    private void assertNoPlans() {
        for (String table : List.of("evolution_plan", "evolution_plan_section_item", "evolution_step",
                "evolution_step_section_item", "evolution_plan_evidence"))
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
    }
}
