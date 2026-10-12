package com.ayywl.delveforge.app.api.evolutionplan;

import com.ayywl.delveforge.application.port.ai.*;
import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.domain.asset.*;
import com.ayywl.delveforge.domain.direction.*;
import com.ayywl.delveforge.domain.evidence.*;
import com.ayywl.delveforge.domain.evolution.*;
import com.ayywl.delveforge.domain.repositoryprofile.*;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EvolutionStepPreparationApiIntegrationTest {
    private static final Path BASE = Path.of("target", "step-preparation-api", UUID.randomUUID().toString()).toAbsolutePath();
    private static final SoftwareAssetId ASSET = new SoftwareAssetId("asset");
    private static final RepositoryProfileId PROFILE = new RepositoryProfileId("profile");
    private static final String PATCH = "--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-old\n+new\n";
    private static final String PLANNING = """
        {"currentState":{"summary":"Existing exports","capabilities":["Export"],"modules":["reports"],"limitations":[]},
         "targetState":{"problem":"Manual","targetProduct":"Scheduled","differentiation":"Personal"},
         "reusableCapabilities":["Export"],"changes":["Add scheduling"],
         "steps":[{"goal":"增量调整报表工程基础代码","scope":"reports","plannedChanges":["调整报表工程代码"],
         "verificationCriteria":[{"kind":"FULL_TEST_SUITE_PASSES","description":"项目完整自动化测试套件通过"}]}],
         "risks":[],"evidence":["R-E1","D-E1"]}
        """;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", () -> BASE.resolve("state.db").toString());
        registry.add("delveforge.workspace.root", () -> BASE.resolve("managed").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired SoftwareAssetRepository assets;
    @Autowired RepositoryProfileRepository profiles;
    @Autowired ProductDirectionRepository directions;
    @Autowired EvolutionPlanRepository plans;
    @Autowired WorkingCopyRepository copies;
    @Autowired StepConfirmationCommitPort confirmations;
    @MockitoBean AiGateway ai;

    private String planId;
    private String stepId;
    private String revision;
    private Path source;
    private Path copy;

    @BeforeEach void setup() throws Exception {
        source = BASE.resolve("source-" + UUID.randomUUID());
        Files.createDirectories(source);
        git(source, "init", "-q");
        Files.writeString(source.resolve("A.java"), "old\n");
        Files.write(source.resolve("Legacy.java"), "caf\u00e9\n".getBytes(StandardCharsets.ISO_8859_1));
        Files.writeString(source.resolve("application.yml"), "password: CANARY-DB-PASSWORD-0001\n");
        Files.writeString(source.resolve(".env"), "SECRET=never-send\n");
        git(source, "add", ".");
        git(source, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "initial");
        revision = git(source, "rev-parse", "HEAD").trim();
        for (String table : List.of("evolution_plan", "evolution_step", "evolution_step_section_item",
                "evolution_plan_section_item", "evolution_plan_evidence", "working_copy",
                "product_direction", "product_direction_repository_profile", "product_direction_candidate_asset",
                "product_direction_risk", "product_direction_evidence_support", "repository_profile",
                "repository_profile_section_item", "repository_profile_evidence")) {
            jdbc.update("DELETE FROM " + table);
        }
        Evidence evidence = new Evidence(EvidenceSourceType.REPOSITORY, "A.java", "Existing exports", 0.8, true);
        EvidenceBasis basis = new EvidenceBasis(evidence, new RepositoryProfileEvidenceOrigin(PROFILE));
        assets.save(SoftwareAsset.create(ASSET, SoftwareAssetType.GIT_REPOSITORY, SoftwareAssetSource.USER_SPECIFIED,
                source.toString(), true, "MIT", UsageAuthorization.ALLOWED));
        profiles.save(RepositoryProfile.create(PROFILE, ASSET, revision, "Reporting", List.of("Java"),
                List.of("reports"), List.of("Export"), List.of("Export"), List.of(), List.of(), List.of(evidence)));
        ProductDirection direction = ProductDirection.create(new ProductDirectionId("direction"), new UserProfileId("user"), 1,
                List.of(PROFILE), "Reports", "Manual", "Scheduled", "Fits", List.of(ASSET), "Personal",
                "Value", "Small", List.of(), new DirectionEvidenceSupport(List.of(basis), List.of(basis), List.of(basis)));
        direction.select();
        directions.save(direction);
        when(ai.generate(any())).thenReturn(PLANNING);
        JsonNode proposed = mapper.readTree(mvc.perform(post("/api/evolution-plans/planning")
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"productDirectionId":"direction","baseAssetId":"asset","baseRepositoryProfileId":"profile"}
                """)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        planId = proposed.path("id").asText();
        stepId = proposed.path("steps").get(0).path("id").asText();
        JsonNode active = mapper.readTree(mvc.perform(post("/api/evolution-plans/{id}/prepare", planId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        copy = Path.of(copies.findById(new WorkingCopyId(active.path("workingCopyId").asText())).orElseThrow().location());
        reset(ai);
    }

    private String route(String action) {
        return "/api/evolution-plans/" + planId + "/steps/" + stepId + "/" + action;
    }

    private void confirm() throws Exception {
        mvc.perform(post(route("confirm"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("READY"))
                .andExpect(jsonPath("$.steps[0].baselineRevision").isEmpty());
    }

    private void readyAndUnchanged() throws Exception {
        assertMetadata(EvolutionStepStatus.READY);
        assertEquals("old\n", Files.readString(copy.resolve("A.java")).replace("\r\n", "\n"));
    }

    private void assertMetadata(EvolutionStepStatus expectedStatus) throws Exception {
        mvc.perform(get("/api/evolution-plans/{id}", planId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value(expectedStatus.name()))
                .andExpect(jsonPath("$.steps[0].baselineRevision").isEmpty());
        assertEquals(revision, git(copy, "rev-parse", "HEAD").trim());
        WorkingCopy state = copies.findById(new WorkingCopyId(plans.findById(new EvolutionPlanId(planId)).orElseThrow().workingCopyId())).orElseThrow();
        assertEquals(revision, state.currentRevision());
        assertEquals(revision, state.lastVerifiedRevision());
        assertEquals(WorkingCopyStatus.READY, state.status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"--assume-unchanged", "--skip-worktree"})
    void hiddenWorkingTreeChangesBlockBothConfirmationAndPreparationWithoutChangingIndex(String flag) throws Exception {
        Map<String, String> beforeSource = snapshot(source);
        byte[] original = Files.readAllBytes(copy.resolve("A.java"));
        git(copy, "update-index", flag, "--", "A.java");
        Files.writeString(copy.resolve("A.java"), "hidden edit\n");
        assertTrue(git(copy, "--no-optional-locks", "-c", "core.fsmonitor=false", "status", "--porcelain=v1").isEmpty());
        Map<String, String> beforeConfirmation = snapshot(copy);
        mvc.perform(post(route("confirm"))).andExpect(status().isConflict());
        assertMetadata(EvolutionStepStatus.PENDING_CONFIRMATION);
        verifyNoInteractions(ai);
        assertEquals(beforeSource, snapshot(source));
        assertEquals(beforeConfirmation, snapshot(copy));

        git(copy, "update-index", flag.replace("--", "--no-"), "--", "A.java");
        Files.write(copy.resolve("A.java"), original);
        confirm();
        git(copy, "update-index", flag, "--", "A.java");
        Files.writeString(copy.resolve("A.java"), "hidden edit\n");
        Map<String, String> beforePreparation = snapshot(copy);
        mvc.perform(post(route("execution-preparation"))).andExpect(status().isConflict());
        assertMetadata(EvolutionStepStatus.READY);
        verifyNoInteractions(ai);
        assertEquals(beforeSource, snapshot(source));
        assertEquals(beforePreparation, snapshot(copy));
    }

    @Test void rejectsPatchAgainstLossilyDecodedNonUtf8BlobWithoutChangingRepositoriesOrState() throws Exception {
        confirm();
        String decoded = "caf\ufffd\n";
        String patch = "--- a/Legacy.java\n+++ b/Legacy.java\n@@ -1 +1 @@\n-" + decoded + "+new\n";
        String coding = mapper.writeValueAsString(Map.of("type", "CHANGE_PROPOSAL", "expectedRevision", revision,
                "summary", "Schedule reports", "declaredAffectedPaths", List.of("Legacy.java"), "unifiedDiff", patch,
                "verificationStrategy", List.of(Map.of("action", "TEST", "target", "", "criterionIndexes", List.of(1)))));
        when(ai.generate(any())).thenAnswer(invocation -> {
            AiRequest request = invocation.getArgument(0);
            JsonNode payload = mapper.readTree(request.messages().stream().filter(message -> message.role() == AiRole.USER)
                    .findFirst().orElseThrow().content());
            if (payload.has("sourceFiles")) {
                assertTrue(request.messages().toString().contains("caf\ufffd"));
                return coding;
            }
            String ref = "";
            for (JsonNode file : payload.path("fileCatalog")) {
                if (file.path("path").asText().equals("Legacy.java")) {
                    ref = file.path("reference").asText();
                }
            }
            assertFalse(ref.isEmpty());
            return mapper.writeValueAsString(Map.of("focusAreas", List.of(
                    Map.of("label", "code", "fileRefs", List.of(ref)),
                    Map.of("label", "behavior", "fileRefs", List.of(ref)),
                    Map.of("label", "test", "fileRefs", List.of(ref)))));
        });
        Map<String, String> beforeSource = snapshot(source);
        Map<String, String> beforeCopy = snapshot(copy);
        mvc.perform(post(route("execution-preparation"))).andExpect(status().isConflict());
        readyAndUnchanged();
        assertEquals(beforeSource, snapshot(source));
        assertEquals(beforeCopy, snapshot(copy));
        verify(ai, times(2)).generate(any());
    }

    @Test void confirmationPersistsAndPreparedPatchIsExecutableWithoutChangingEitherRepository() throws Exception {
        Map<String, String> beforeSource = snapshot(source);
        Map<String, String> beforeCopy = snapshot(copy);
        confirm();
        verifyNoInteractions(ai);
        mvc.perform(post(route("confirm"))).andExpect(status().isConflict());
        String scout = """
                {"focusAreas":[{"label":"code","fileRefs":["RF-1"]},
                {"label":"behavior","fileRefs":["RF-1"]},{"label":"test","fileRefs":["RF-1"]}]}
                """;
        String coding = mapper.writeValueAsString(Map.of("type", "CHANGE_PROPOSAL", "expectedRevision", revision,
                "summary", "Schedule reports", "declaredAffectedPaths", List.of("A.java"), "unifiedDiff", PATCH,
                "verificationStrategy", List.of(Map.of("action", "TEST", "target", "", "criterionIndexes", List.of(1)))));
        when(ai.generate(any())).thenReturn(scout, coding);
        mvc.perform(post(route("execution-preparation"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.basis.expectedRevision").value(revision))
                .andExpect(jsonPath("$.proposal.unifiedDiff").value(PATCH));
        readyAndUnchanged();
        assertEquals(beforeSource, snapshot(source));
        assertEquals(beforeCopy, snapshot(copy));
        verify(ai, times(2)).generate(argThat(request ->
                !request.messages().toString().contains("CANARY-DB-PASSWORD-0001")
                        && !request.messages().toString().contains("SECRET=never-send")));
        Path patch = BASE.resolve("checked.patch");
        Files.writeString(patch, PATCH);
        git(copy, "apply", "--check", patch.toString());
        assertEquals(beforeCopy, snapshot(copy));
    }

    @Test void cannotPrepareAnUnconfirmedOrDirtyStep() throws Exception {
        mvc.perform(post(route("execution-preparation"))).andExpect(status().isConflict());
        verifyNoInteractions(ai);
        confirm();
        Files.writeString(copy.resolve("A.java"), "external edit\n");
        mvc.perform(post(route("execution-preparation"))).andExpect(status().isConflict());
        verifyNoInteractions(ai);
        assertNull(plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId)).baselineRevision());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void malformedHunkLogsOnlyNumericBoundsAndPreservesReadyState(CapturedOutput output) throws Exception {
        confirm();
        Map<String, String> beforeSource = snapshot(source);
        Map<String, String> beforeCopy = snapshot(copy);
        String sentinel = "PRIVATE-PROPOSAL-MATERIAL-NEVER-LOG";
        String patch = "--- a/A.java\n+++ b/A.java\n@@ -9 +9 @@\n-old\n+" + sentinel + "\n";
        String coding = mapper.writeValueAsString(Map.of("type", "CHANGE_PROPOSAL", "expectedRevision", revision,
                "summary", sentinel, "declaredAffectedPaths", List.of("A.java"), "unifiedDiff", patch,
                "verificationStrategy", List.of(Map.of("action", "TEST", "target", "", "criterionIndexes", List.of(1)))));
        String scout = """
                {"focusAreas":[{"label":"code","fileRefs":["RF-1"]},
                {"label":"behavior","fileRefs":["RF-1"]},{"label":"test","fileRefs":["RF-1"]}]}
                """;
        when(ai.generate(any())).thenReturn(scout, coding);
        mvc.perform(post(route("execution-preparation"))).andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("CODING_PROPOSAL_INVALID"));
        readyAndUnchanged();
        assertEquals(beforeSource, snapshot(source));
        assertEquals(beforeCopy, snapshot(copy));
        String logs = output.getAll();
        assertTrue(logs.contains("code=DIFF_HUNK_POSITION_INVALID"));
        assertTrue(logs.contains("oldStart=9 oldCount=1 newStart=9 newCount=1"));
        assertTrue(logs.contains("oldLineCount=1"));
        assertFalse(logs.contains(sentinel));
        assertFalse(logs.contains("CANARY-DB-PASSWORD-0001"));
        verify(ai, times(2)).generate(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @ExtendWith(OutputCaptureExtension.class)
    void invalidOldLinesLogOnlyCategoryAndCounts(boolean exhausted, CapturedOutput output) throws Exception {
        confirm();
        Map<String, String> beforeSource = snapshot(source);
        Map<String, String> beforeCopy = snapshot(copy);
        String sentinel = "PRIVATE-OLD-LINE-NEVER-LOG";
        String patch = exhausted
                ? "--- a/A.java\n+++ b/A.java\n@@ -1 +1,2 @@\n old\n-" + sentinel + "\n+new\n"
                : "--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-" + sentinel + "\n+new\n";
        String coding = mapper.writeValueAsString(Map.of("type", "CHANGE_PROPOSAL", "expectedRevision", revision,
                "summary", sentinel, "declaredAffectedPaths", List.of("A.java"), "unifiedDiff", patch,
                "verificationStrategy", List.of(Map.of("action", "TEST", "target", "", "criterionIndexes", List.of(1)))));
        String scout = """
                {"focusAreas":[{"label":"code","fileRefs":["RF-1"]},
                {"label":"behavior","fileRefs":["RF-1"]},{"label":"test","fileRefs":["RF-1"]}]}
                """;
        when(ai.generate(any())).thenReturn(scout, coding);
        mvc.perform(post(route("execution-preparation"))).andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("CODING_PROPOSAL_INVALID"));
        readyAndUnchanged();
        assertEquals(beforeSource, snapshot(source));
        assertEquals(beforeCopy, snapshot(copy));
        String logs = output.getAll();
        assertTrue(logs.contains("code=" + (exhausted
                ? "DIFF_HUNK_OLD_COUNT_EXHAUSTED" : "DIFF_CONTEXT_MISMATCH")));
        assertTrue(logs.contains("oldCount=1"));
        assertTrue(logs.contains("expectedChars=" + (exhausted ? -1 : 3)));
        assertFalse(logs.contains(sentinel));
        assertFalse(logs.contains("CANARY-DB-PASSWORD-0001"));
        verify(ai, times(2)).generate(any());
    }

    @Test void rejectedAIProposalLeavesPersistedReadyAndSourceUnchanged() throws Exception {
        confirm();
        when(ai.generate(any())).thenReturn("""
                {"focusAreas":[{"label":"code","fileRefs":["RF-1"]},
                {"label":"behavior","fileRefs":["RF-1"]},{"label":"test","fileRefs":["RF-1"]}]}
                """, "{}");
        mvc.perform(post(route("execution-preparation"))).andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("CODING_PROPOSAL_INVALID"));
        readyAndUnchanged();
    }

    @Test void staleConfirmationBasisIsRejectedByRealSQLiteConditionalWrite() {
        EvolutionPlan loaded = plans.findById(new EvolutionPlanId(planId)).orElseThrow();
        WorkingCopy workingCopy = copies.findById(new WorkingCopyId(loaded.workingCopyId())).orElseThrow();
        EvolutionPlan candidate = loaded.copy();
        candidate.confirmStep(new EvolutionStepId(stepId), workingCopy, new StepExecutionPolicy());
        jdbc.update("UPDATE working_copy SET current_revision = 'changed' WHERE id = ?", workingCopy.id().value());
        assertThrows(EvolutionLifecycleConflictException.class, () -> confirmations.confirm(candidate,
                new EvolutionStepId(stepId), workingCopy, assets.findById(ASSET).orElseThrow()));
        assertEquals("PENDING_CONFIRMATION", jdbc.queryForObject("SELECT status FROM evolution_step WHERE id = ?", String.class, stepId));
        assertEquals(EvolutionStepStatus.PENDING_CONFIRMATION, loaded.step(new EvolutionStepId(stepId)).status());
    }

    @Test void foreignStepIsNotFoundAndSupersededPlanCannotBeConfirmed() throws Exception {
        mvc.perform(post("/api/evolution-plans/{plan}/steps/foreign/confirm", planId)).andExpect(status().isNotFound());
        jdbc.update("UPDATE evolution_plan SET status = 'SUPERSEDED' WHERE id = ?", planId);
        mvc.perform(post(route("confirm"))).andExpect(status().isConflict());
        verifyNoInteractions(ai);
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (var stream = Files.walk(root)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                result.put(root.relativize(file).toString(), Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
            }
        }
        return result;
    }

    private static String git(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
