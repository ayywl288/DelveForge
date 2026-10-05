package com.ayywl.delveforge.app.api.evolutionplan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.domain.asset.*;
import com.ayywl.delveforge.domain.direction.*;
import com.ayywl.delveforge.domain.evidence.*;
import com.ayywl.delveforge.domain.evolution.*;
import com.ayywl.delveforge.domain.repositoryprofile.*;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Real HTTP → application/domain → SQLite and Git, with only the external AI proposal stubbed.
 * No enclosing transaction: endpoint results are committed and read independently.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkingCopyActivationApiIntegrationTest {
    private static final Path BASE = Path.of("target", "evolution-api", UUID.randomUUID().toString()).toAbsolutePath();
    private static final Path ROOT = BASE.resolve("workspaces");
    private static final SoftwareAssetId ASSET = new SoftwareAssetId("asset");
    private static final RepositoryProfileId PROFILE = new RepositoryProfileId("profile");
    private static final Evidence FACT = new Evidence(EvidenceSourceType.REPOSITORY, "report.txt", "Existing exports", 0.8, true);
    private static final EvidenceBasis BASIS = new EvidenceBasis(FACT, new RepositoryProfileEvidenceOrigin(PROFILE));
    private static final String JSON = """
        {"currentState":{"summary":"Existing exports","capabilities":["Export"],"modules":["reports"],"limitations":[]},
         "targetState":{"problem":"Manual exports","targetProduct":"Scheduled reports","differentiation":"Personal schedule"},
         "reusableCapabilities":["Render"],"changes":["Add scheduling"],
         "steps":[{"goal":"Schedule reports","scope":"reports","plannedChanges":["Add scheduling"],"preconditions":[],"verificationCriteria":["Reports run on schedule"]}],
         "risks":[],"evidence":["R-E1","D-E1"]}
        """;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", () -> BASE.resolve("activation.db").toString());
        registry.add("delveforge.workspace.root", ROOT::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired SoftwareAssetRepository assets;
    @Autowired RepositoryProfileRepository profiles;
    @Autowired ProductDirectionRepository directions;
    @Autowired EvolutionPlanRepository plans;
    @Autowired WorkingCopyRepository copies;
    @MockitoBean AiGateway ai;
    private Path source;
    private String revision;

    @BeforeEach void seed() throws Exception {
        for (String table : List.of("evolution_plan", "evolution_plan_section_item", "evolution_step",
                "evolution_step_section_item", "evolution_plan_evidence", "working_copy",
                "product_direction", "product_direction_repository_profile", "product_direction_candidate_asset",
                "product_direction_risk", "product_direction_evidence_support", "repository_profile",
                "repository_profile_section_item", "repository_profile_evidence")) jdbc.update("DELETE FROM " + table);
        source = BASE.resolve("source-" + UUID.randomUUID());
        Files.createDirectories(source);
        git(source, "init", "-q", "--initial-branch=main");
        Files.writeString(source.resolve("report.txt"), "committed");
        git(source, "add", "report.txt"); commit("initial");
        revision = git(source, "rev-parse", "HEAD").trim();
        assets.save(asset(true, "MIT", UsageAuthorization.ALLOWED));
        profiles.save(RepositoryProfile.create(PROFILE, ASSET, revision, "Reporting", List.of("Java"),
                List.of("reports"), List.of("Export"), List.of("Render"), List.of(), List.of(), List.of(FACT)));
        var selected = direction("direction"); selected.select(); directions.save(selected);
        when(ai.generate(any())).thenReturn(JSON);
    }
    private SoftwareAsset asset(boolean read, String license, UsageAuthorization authorization) {
        return SoftwareAsset.create(ASSET, SoftwareAssetType.GIT_REPOSITORY, SoftwareAssetSource.USER_SPECIFIED,
                source.toString(), read, license, authorization);
    }
    private ProductDirection direction(String id) {
        return ProductDirection.create(new ProductDirectionId(id), new UserProfileId("user"), 1, List.of(PROFILE),
                "Personal reports", "Manual exports", "Scheduled reports", "Fits goals", List.of(ASSET),
                "Personal schedule", "Scheduling", "Small", List.of(),
                new DirectionEvidenceSupport(List.of(BASIS), List.of(BASIS), List.of(BASIS)));
    }
    private JsonNode plan() throws Exception {
        return mapper.readTree(mvc.perform(post("/api/evolution-plans/planning").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productDirectionId\":\"direction\",\"baseAssetId\":\"asset\",\"baseRepositoryProfileId\":\"profile\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode prepare(String id) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/evolution-plans/{id}/prepare", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.steps[0].status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.steps[0].baselineRevision").isEmpty())
                .andReturn().getResponse().getContentAsString());
    }
    @AfterEach void cleanup() throws Exception {
        delete(ROOT); delete(source);
    }
    @Test void planningAndPreparationProduceCommittedActivePlanAndPhysicallyIsolatedReadyCopy() throws Exception {
        Files.writeString(source.resolve("report.txt"), "dirty");
        Files.writeString(source.resolve("untracked.txt"), "untracked");
        var before = snapshot(source);
        String id = plan().path("id").asText();
        var active = prepare(id);
        String copyId = active.path("workingCopyId").asText();
        var body = mvc.perform(get("/api/working-copies/{id}", copyId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.sourceAssetId").value("asset"))
                .andExpect(jsonPath("$.sourceRevision").value(revision))
                .andExpect(jsonPath("$.currentRevision").value(revision))
                .andExpect(jsonPath("$.lastVerifiedRevision").value(revision))
                .andReturn().getResponse().getContentAsString();
        Path location = Path.of(mapper.readTree(body).path("location").asText());
        assertEquals(ROOT, location.getParent());
        assertEquals(revision, git(location, "rev-parse", "HEAD").trim());
        assertEquals("committed", Files.readString(location.resolve("report.txt")));
        assertFalse(Files.exists(location.resolve("untracked.txt")));
        assertFalse(Files.exists(location.resolve(".git/objects/info/alternates")));
        assertEquals(before, snapshot(source));
        mvc.perform(get("/api/evolution-plans/{id}", id)).andExpect(status().isOk())
                .andExpect(content().json(active.toString()));
        mvc.perform(post("/api/evolution-plans/{id}/prepare", id)).andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM working_copy", Integer.class));
        verify(ai, times(1)).generate(any());
    }
    @Test void revokedAuthorizationAndUnknownLicenseAreRecheckedBeforeAnyClone() throws Exception {
        String id = plan().path("id").asText();
        for (var rejected : List.of(asset(true, "MIT", UsageAuthorization.DENIED),
                asset(true, "MIT", UsageAuthorization.UNCLEAR), asset(false, "MIT", UsageAuthorization.ALLOWED),
                asset(true, null, UsageAuthorization.ALLOWED))) {
            assets.save(rejected);
            mvc.perform(post("/api/evolution-plans/{id}/prepare", id)).andExpect(status().isConflict());
            assertUnchanged(id);
        }
        assertFalse(Files.exists(ROOT));
    }
    @Test void changedSourceRevisionRefusesPreparationUntilReanalysisAndReplanning() throws Exception {
        String id = plan().path("id").asText();
        Files.writeString(source.resolve("report.txt"), "next"); git(source, "add", "report.txt"); commit("next");
        var before = snapshot(source);
        mvc.perform(post("/api/evolution-plans/{id}/prepare", id)).andExpect(status().isConflict());
        assertUnchanged(id);
        assertEquals(before, snapshot(source));
        assertFalse(Files.exists(ROOT));
    }
    @Test void failedDomainCommitAfterRealCloneRollsBackMetadataAndRemovesCandidate() throws Exception {
        String id = plan().path("id").asText();
        var before = snapshot(source);
        jdbc.execute("CREATE TRIGGER fail_activation BEFORE UPDATE ON evolution_plan BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
        try {
            mvc.perform(post("/api/evolution-plans/{id}/prepare", id)).andExpect(status().isInternalServerError());
            assertUnchanged(id);
            try (var children = Files.list(ROOT)) { assertEquals(0, children.count()); }
            assertEquals(before, snapshot(source));
        } finally { jdbc.execute("DROP TRIGGER fail_activation"); }
    }
    @Test void explicitDirectionSwitchSupersedesProposedAndActivePlansAtomicallyAndPreservesHistory() throws Exception {
        String activeId = plan().path("id").asText(); var active = prepare(activeId);
        String proposedId = plan().path("id").asText();
        directions.save(direction("new-direction"));
        mvc.perform(post("/api/product-directions/new-direction/select")).andExpect(status().isOk());
        for (String id : List.of(activeId, proposedId)) {
            mvc.perform(get("/api/evolution-plans/{id}", id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUPERSEDED"))
                    .andExpect(jsonPath("$.steps[0].status").value("PENDING_CONFIRMATION"));
            mvc.perform(post("/api/evolution-plans/{id}/prepare", id)).andExpect(status().isConflict());
        }
        var historical = plans.findById(new EvolutionPlanId(activeId)).orElseThrow();
        assertEquals(active.path("workingCopyId").asText(), historical.workingCopyId());
        assertEquals(WorkingCopyStatus.READY, copies.findById(new WorkingCopyId(historical.workingCopyId())).orElseThrow().status());
        assertTrue(Files.exists(Path.of(copies.findById(new WorkingCopyId(historical.workingCopyId())).orElseThrow().location())));
    }
    @Test void mapsMissingPlanAndWorkingCopyTo404() throws Exception {
        mvc.perform(post("/api/evolution-plans/missing/prepare")).andExpect(status().isNotFound());
        mvc.perform(get("/api/working-copies/missing")).andExpect(status().isNotFound());
        assertFalse(Files.exists(ROOT));
        verifyNoInteractions(ai);
    }
    @Test void directionCommitFailurePreservesSelectedDirectionAndItsActiveAndProposedPlans() throws Exception {
        String activeId = plan().path("id").asText(); var active = prepare(activeId);
        String proposedId = plan().path("id").asText();
        directions.save(direction("new-direction"));
        jdbc.execute("CREATE TRIGGER fail_direction BEFORE UPDATE ON product_direction "
                + "WHEN NEW.id='new-direction' BEGIN SELECT missing_test_storage_function(); END");
        try {
            mvc.perform(post("/api/product-directions/new-direction/select")).andExpect(status().isInternalServerError());
            assertEquals(ProductDirectionStatus.SELECTED, directions.findById(new ProductDirectionId("direction")).orElseThrow().status());
            assertEquals(ProductDirectionStatus.CANDIDATE, directions.findById(new ProductDirectionId("new-direction")).orElseThrow().status());
            assertEquals(EvolutionPlanStatus.ACTIVE, plans.findById(new EvolutionPlanId(activeId)).orElseThrow().status());
            assertEquals(EvolutionPlanStatus.PROPOSED, plans.findById(new EvolutionPlanId(proposedId)).orElseThrow().status());
            assertEquals(active.path("workingCopyId").asText(), plans.findById(new EvolutionPlanId(activeId)).orElseThrow().workingCopyId());
        } finally { jdbc.execute("DROP TRIGGER fail_direction"); }
    }
    private void assertUnchanged(String id) {
        var plan = plans.findById(new EvolutionPlanId(id)).orElseThrow();
        assertEquals(EvolutionPlanStatus.PROPOSED, plan.status()); assertNull(plan.workingCopyId());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM working_copy", Integer.class));
    }
    private void commit(String message) throws Exception {
        git(source, "-c", "user.name=Test", "-c", "user.email=test@delveforge.local", "commit", "-q", "-m", message);
    }
    private static String git(Path path, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", path.toString())); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output); return output;
    }
    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).toList())
                result.put(root.relativize(path).toString(), Base64.getEncoder().encodeToString(Files.readAllBytes(path))
                        + "|" + Files.getLastModifiedTime(path).toMillis());
        }
        return result;
    }
    private static void delete(Path directory) throws Exception {
        if (directory == null || !Files.exists(directory)) return;
        if (!directory.toAbsolutePath().normalize().startsWith(BASE)) throw new IllegalArgumentException("Test cleanup escaped test root");
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true); Files.delete(path);
            }
        }
    }
}
