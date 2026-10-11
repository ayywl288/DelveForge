package com.ayywl.delveforge.app.api.project;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.asset.SoftwareAssetSource;
import com.ayywl.delveforge.domain.asset.SoftwareAssetType;
import com.ayywl.delveforge.domain.asset.UsageAuthorization;
import com.ayywl.delveforge.domain.direction.DirectionEvidenceSupport;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.evidence.RepositoryProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.evolution.ExecutionResult;
import com.ayywl.delveforge.domain.evolution.VerificationResult;
import com.ayywl.delveforge.domain.evolution.VerificationCriterion;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStatus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 临时 SQLite 中的合成历史，不是真实 GS REST / Provider 成功证据。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProjectWorkspaceIntegrationTest {
    private static final Path DB = Path.of("target", "test-databases", UUID.randomUUID().toString(), "history.db");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DB::toString);
        registry.add("delveforge.workspace.root", () -> DB.getParent().resolve("workspaces").toAbsolutePath().toString());
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserProfileRepository users;
    @Autowired private SoftwareAssetRepository assets;
    @Autowired private RepositoryProfileRepository repositories;
    @Autowired private ProductDirectionRepository directions;
    @MockitoBean private AiGateway ai;

    @BeforeEach
    void seedResources() {
        users.save(UserProfile.reconstitute(new UserProfileId("shared-user"), UserProfileStatus.CONFIRMED, 2,
                List.of("Java"), List.of("本地学习"), List.of(), List.of("Java"), List.of("实习项目"),
                List.of("每周有限时间"), List.of()));
        users.save(UserProfile.create(new UserProfileId("unconfirmed")));
        seedRepository("one");
        seedRepository("two");
    }

    private void seedRepository(String name) {
        SoftwareAssetId id = new SoftwareAssetId("asset-" + name);
        assets.save(SoftwareAsset.create(id, SoftwareAssetType.GIT_REPOSITORY, SoftwareAssetSource.USER_SPECIFIED,
                DB.getParent().resolve(name).toAbsolutePath().toString(), true, "MIT", UsageAuthorization.ALLOWED));
        repositories.save(RepositoryProfile.create(new RepositoryProfileId("repo-" + name), id, "e38e4a1",
                name + " REST", List.of("Java"), List.of("rest"), List.of("GET"), List.of("controller"),
                List.of(), List.of(), List.of(new Evidence(EvidenceSourceType.REPOSITORY, "src/Main.java", "GET", null, false))));
    }

    private JsonNode create(String name, String profileId) throws Exception {
        var request = json.createObjectNode().put("name", name);
        if (profileId != null) {
            request.put("confirmedUserProfileId", profileId);
        }
        return json.readTree(mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private String context(long version, String profile, String asset, String repo, String direction, String plan) {
        var value = json.createObjectNode().put("expectedVersion", version);
        value.put("userProfileId", profile);
        value.put("planId", plan);
        if (asset != null) { value.putArray("assetIds").add(asset); } else { value.putArray("assetIds"); }
        if (repo != null) { value.putArray("repositoryProfileIds").add(repo); } else { value.putArray("repositoryProfileIds"); }
        if (direction != null) { value.putArray("directionIds").add(direction); } else { value.putArray("directionIds"); }
        return value.toString();
    }

    @Test
    void sharedProfileHasIndependentExplicitRepositoryContextsAndSurvivesReadback() throws Exception {
        JsonNode one = create("项目一", "shared-user");
        JsonNode two = create("项目二", "shared-user");
        for (var entry : Map.of("one", one, "two", two).entrySet()) {
            mvc.perform(put("/api/projects/{id}/context", entry.getValue().path("id").asText())
                            .contentType(MediaType.APPLICATION_JSON).content(context(0, "shared-user",
                                    "asset-" + entry.getKey(), "repo-" + entry.getKey(), null, null)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
            mvc.perform(get("/api/projects/{key}", "project:" + entry.getValue().path("id").asText()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.userProfile.id").value("shared-user"))
                    .andExpect(jsonPath("$.repositoryProfiles[0].id").value("repo-" + entry.getKey()))
                    .andExpect(jsonPath("$.repositoryProfiles.length()").value(1));
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM project_flow_context", Integer.class));
        verifyNoInteractions(ai);
    }

    @Test
    void blankFlowAndConfirmedReuseDoNotCreateOrModifyProfiles() throws Exception {
        JsonNode blank = create("全新探索", null);
        mvc.perform(get("/api/projects/{key}", "project:" + blank.path("id").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("DISCOVERY"))
                .andExpect(jsonPath("$.userProfile").doesNotExist());
        create("复用画像", "shared-user");
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"拒绝复用\",\"confirmedUserProfileId\":\"unconfirmed\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CONTEXT_CONFLICT"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM user_profile", Integer.class));
        verifyNoInteractions(ai);
    }

    @Test
    void staleContextCannotOverwriteResourceSelectionOrChangeBoundProfile() throws Exception {
        String id = create("CAS", "shared-user").path("id").asText();
        mvc.perform(put("/api/projects/{id}/context", id).contentType(MediaType.APPLICATION_JSON)
                        .content(context(0, "shared-user", "asset-one", "repo-one", null, null)))
                .andExpect(status().isOk());
        for (String body : List.of(context(0, "shared-user", "asset-two", "repo-two", null, null),
                context(1, "unconfirmed", "asset-one", "repo-one", null, null),
                context(1, "shared-user", "asset-two", "repo-one", null, null))) {
            mvc.perform(put("/api/projects/{id}/context", id).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CONTEXT_CONFLICT"));
        }
        mvc.perform(get("/api/projects/{key}", "project:" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.context.version").value(1)).andExpect(jsonPath("$.assets[0].id").value("asset-one"));
    }

    @Test
    void missingOrMismatchedResourceCannotBeSavedAndNumericVersionIsStrict() throws Exception {
        String id = create("关联校验", "shared-user").path("id").asText();
        for (String body : List.of(context(0, "shared-user", "asset-two", "repo-one", null, null),
                context(0, "shared-user", "missing", null, null, null))) {
            mvc.perform(put("/api/projects/{id}/context", id).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict());
        }
        for (String value : List.of("0.1", "1e0", "\"0\"", "-1", "9223372036854775808", "null")) {
            mvc.perform(put("/api/projects/{id}/context", id).contentType(MediaType.APPLICATION_JSON)
                            .content(context(0, "shared-user", null, null, null, null).replace("\"expectedVersion\":0",
                                    "\"expectedVersion\":" + value)))
                    .andExpect(status().isBadRequest());
        }
    }

    private void seedHistoricalPlan(String id, String repoName, boolean succeeded) throws Exception {
        var repoId = new RepositoryProfileId("repo-" + repoName);
        var assetId = new SoftwareAssetId("asset-" + repoName);
        var evidence = new Evidence(EvidenceSourceType.REPOSITORY, "src/Main.java", "GET", null, false);
        var basis = new EvidenceBasis(evidence, new RepositoryProfileEvidenceOrigin(repoId));
        var direction = ProductDirection.create(new ProductDirectionId("direction-" + id), new UserProfileId("shared-user"), 2,
                List.of(repoId), "REST " + id, "业务请求", "扩展 REST", "Java 学习", List.of(assetId), "本地",
                "测试", "小型", List.of(), new DirectionEvidenceSupport(List.of(basis), List.of(basis), List.of(basis)));
        directions.save(direction);
        jdbc.update("INSERT INTO evolution_plan(id, product_direction_id, base_asset_id, base_repository_profile_id, "
                + "working_copy_id, current_summary, target_problem, target_product, target_differentiation, status) "
                + "VALUES(?, ?, ?, ?, ?, 'GET', '请求', 'REST', '本地', 'ACTIVE')", id, direction.id().value(),
                assetId.value(), repoId.value(), "copy-" + id);
        jdbc.update("INSERT INTO evolution_plan_section_item(plan_id, section, position, value) "
                + "VALUES(?, 'changes', 0, '新增 REST 行为')", id);
        jdbc.update("INSERT INTO evolution_plan_evidence(plan_id, position, source_type, source_ref, claim, confirmed, "
                + "origin_kind, origin_repository_profile_id) VALUES(?, 0, 'REPOSITORY', 'src/Main.java', 'GET', 0, "
                + "'repositoryProfile', ?)", id, repoId.value());
        jdbc.update("INSERT INTO working_copy(id, source_asset_id, source_revision, location, current_revision, "
                + "last_verified_revision, status) VALUES(?, ?, 'e38e4a1', ?, ?, ?, 'EVOLVING')", "copy-" + id,
                assetId.value(), DB.getParent().resolve("workspaces/copy-" + id).toAbsolutePath().toString(),
                succeeded ? "c822cc3" : "e38e4a1", succeeded ? "c822cc3" : "e38e4a1");
        for (int i = 1; i <= 4; i++) {
            jdbc.update("INSERT INTO evolution_step(id, plan_id, position, goal, scope, status, baseline_revision, "
                    + "execution_result, verification_result) VALUES(?, ?, ?, '新增 REST 行为', 'src', ?, ?, ?, ?)",
                    id + "-step-" + i, id, i - 1, i == 1 && succeeded ? "SUCCEEDED" : "PENDING_CONFIRMATION",
                    i == 1 && succeeded ? "e38e4a1" : null,
                    i == 1 && succeeded ? json.writeValueAsString(new ExecutionResult("c822cc3", List.of("REST"),
                            List.of("src/Main.java"), List.of(), List.of(), Instant.parse("2026-10-10T00:00:00Z"))) : null,
                    i == 1 && succeeded ? json.writeValueAsString(new VerificationResult("c822cc3", List.of(
                            new VerificationResult.Check("BUILD", "", List.of(1), true, "BUILD PASS"),
                            new VerificationResult.Check("FOCUSED_TEST", "RestTest", List.of(2), true, "10 tests PASS")),
                            true, List.of(), "合成检查事实", Instant.parse("2026-10-10T00:01:00Z"))) : null);
            jdbc.update("INSERT INTO evolution_step_section_item(step_id, section, position, value) "
                    + "VALUES(?, 'plannedChanges', 0, '新增 REST 行为')", id + "-step-" + i);
            for (int c = 0; c < 2; c++) {
                jdbc.update("INSERT INTO evolution_step_section_item(step_id, section, position, value, criterion_kind) "
                        + "VALUES(?, 'verificationCriteria', ?, ?, ?)", id + "-step-" + i, c,
                        c == 0 ? VerificationCriterion.Kind.BUILD_SUCCEEDS.description()
                                : VerificationCriterion.Kind.SELECTED_TESTS_PASS.description(),
                        c == 0 ? "BUILD_SUCCEEDS" : "SELECTED_TESTS_PASS");
            }
        }
    }

    @Test
    void legacyHistoryReadsOneOfFourSuccessAndExactVerifiedCandidateWithoutWrites() throws Exception {
        seedHistoricalPlan("gs", "one", true);
        mvc.perform(get("/api/projects")).andExpect(status().isOk()).andExpect(jsonPath("$[0].key").value("plan:gs"))
                .andExpect(jsonPath("$[0].succeededSteps").value(1)).andExpect(jsonPath("$[0].totalSteps").value(4))
                .andExpect(jsonPath("$[0].stepCounts.PENDING_CONFIRMATION").value(3))
                .andExpect(jsonPath("$[0].lastVerifiedRevision").value("c822cc3"));
        mvc.perform(get("/api/projects/{key}", "plan:gs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.legacy").value(true)).andExpect(jsonPath("$.context").doesNotExist())
                .andExpect(jsonPath("$.plan.steps[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.plan.steps[0].verificationResult.checks[1].summary").value("10 tests PASS"))
                .andExpect(jsonPath("$.issues").isArray());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM project_flow_context", Integer.class));
        verifyNoInteractions(ai);
    }

    @Test
    void distinctPlansOfSameRepositoryRemainSeparateAndAdoptionIsIdempotent() throws Exception {
        seedHistoricalPlan("p1", "one", true);
        seedHistoricalPlan("p2", "one", false);
        String first = mvc.perform(post("/api/projects/legacy-plans/p1/resume")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/projects/legacy-plans/p1/resume")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(json.readTree(first).path("id"), json.readTree(second).path("id"));
        mvc.perform(get("/api/projects")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/projects/{key}", "plan:p2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.id").value("p2")).andExpect(jsonPath("$.directions[0].id").value("direction-p2"));
        verifyNoInteractions(ai);
    }

    @Test
    void orphanResourcesAreSelectableWithoutCreatingProjectsOrGuessingMembership() throws Exception {
        mvc.perform(get("/api/projects/resources").param("kind", "USER_PROFILE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/projects/resources").param("kind", "REPOSITORY_PROFILE").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/projects")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/projects/resources").param("kind", "USER_PROFILE").param("limit", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/projects/{key}", "project:missing")).andExpect(status().isNotFound());
    }

    @Test
    void changedAuthorizationAndProfileBasisRemainVisibleWithoutRewritingHistory() throws Exception {
        seedHistoricalPlan("old", "one", true);
        jdbc.update("UPDATE software_asset SET usage_authorization = 'DENIED' WHERE id = 'asset-one'");
        jdbc.update("UPDATE user_profile SET status = 'EXPLORING' WHERE id = 'shared-user'");
        mvc.perform(get("/api/projects/{key}", "plan:old")).andExpect(status().isOk())
                .andExpect(jsonPath("$.boundUserProfileRevision").value(2))
                .andExpect(jsonPath("$.plan.steps[0].status").value("SUCCEEDED"));
        assertEquals("c822cc3", jdbc.queryForObject("SELECT last_verified_revision FROM working_copy WHERE id = 'copy-old'", String.class));
        assertNull(jdbc.queryForObject("SELECT verification_result FROM evolution_step WHERE id = 'old-step-2'", String.class));
        verifyNoInteractions(ai);
    }
}
