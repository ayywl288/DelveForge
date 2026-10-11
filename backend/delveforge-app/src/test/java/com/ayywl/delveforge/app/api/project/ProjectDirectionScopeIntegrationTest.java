package com.ayywl.delveforge.app.api.project;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.project.ProjectScopeBasis;
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
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStatus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 独立临时数据库与确定性提案；并发用不同事务，不执行真实演化。 */
@SpringBootTest
@AutoConfigureMockMvc
class ProjectDirectionScopeIntegrationTest {
    private static final Path DB = Path.of("target", "test-databases", UUID.randomUUID().toString(), "scopes.db");
    private static final String PROPOSAL = """
            {"currentState":{"summary":"REST","capabilities":["GET"],"modules":["rest"],"limitations":[]},
            "targetState":{"problem":"业务请求","targetProduct":"扩展 REST","differentiation":"本地"},
            "reusableCapabilities":["controller"],"changes":["新增 REST 行为"],
            "steps":[{"goal":"新增 REST 行为","scope":"src","plannedChanges":["新增端点"],
            "verificationCriteria":[{"kind":"UNSUPPORTED","description":"检查业务行为"}]}],
            "risks":[],"evidence":["R-E1","D-E1"]}
            """;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DB::toString);
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserProfileRepository users;
    @Autowired private SoftwareAssetRepository assets;
    @Autowired private RepositoryProfileRepository repositories;
    @Autowired private ProductDirectionRepository directions;
    @MockitoBean private AiGateway ai;
    private String userId;
    private String one;
    private String two;

    @BeforeEach
    void seed() {
        userId = UUID.randomUUID().toString();
        users.save(UserProfile.reconstitute(new UserProfileId(userId), UserProfileStatus.CONFIRMED, 2,
                List.of("Java"), List.of("学习"), List.of(), List.of("Java"), List.of("实习项目"), List.of(), List.of()));
        one = repository();
        two = repository();
    }

    private String repository() {
        String id = UUID.randomUUID().toString();
        var asset = new SoftwareAssetId(id);
        assets.save(SoftwareAsset.create(asset, SoftwareAssetType.GIT_REPOSITORY, SoftwareAssetSource.USER_SPECIFIED,
                DB.getParent().resolve(id).toAbsolutePath().toString(), true, "MIT", UsageAuthorization.ALLOWED));
        repositories.save(RepositoryProfile.create(new RepositoryProfileId(id), asset, "A", "REST", List.of("Java"),
                List.of("rest"), List.of("GET"), List.of("controller"), List.of(), List.of(), List.of(fact())));
        return id;
    }

    private static Evidence fact() {
        return new Evidence(EvidenceSourceType.REPOSITORY, "src/Main.java", "GET", null, false);
    }

    private JsonNode project(String repo) throws Exception {
        String created = mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("name", "项目", "confirmedUserProfileId", userId))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).path("id").asText();
        var body = json.createObjectNode().put("expectedVersion", 0).put("userProfileId", userId).putNull("planId");
        body.putArray("assetIds").add(repo);
        body.putArray("repositoryProfileIds").add(repo);
        body.putArray("directionIds");
        return json.readTree(mvc.perform(put("/api/projects/{id}/context", id).contentType(MediaType.APPLICATION_JSON)
                .content(body.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String candidate(JsonNode project, String repo) {
        String id = UUID.randomUUID().toString();
        var basis = new EvidenceBasis(fact(), new RepositoryProfileEvidenceOrigin(new RepositoryProfileId(repo)));
        ProductDirection direction = ProductDirection.create(new ProductDirectionId(id), new UserProfileId(userId), 2,
                List.of(new RepositoryProfileId(repo)), "REST", "业务请求", "扩展 REST", "学习", List.of(new SoftwareAssetId(repo)),
                "本地", "测试", "小型", List.of(), new DirectionEvidenceSupport(List.of(basis), List.of(basis), List.of(basis)));
        directions.saveAllInProject(List.of(direction), new ProjectScopeBasis(project.path("id").asText(),
                project.path("version").asLong()));
        return id;
    }

    private String scope(JsonNode project) {
        return json.createObjectNode().put("contextId", project.path("id").asText())
                .put("expectedVersion", project.path("version").asLong()).toString();
    }

    private int select(JsonNode project, String direction) throws Exception {
        return mvc.perform(post("/api/product-directions/{id}/select", direction).contentType(MediaType.APPLICATION_JSON)
                .content(scope(project))).andReturn().getResponse().getStatus();
    }

    private JsonNode plan(JsonNode project, String direction, String repo) throws Exception {
        when(ai.generate(any())).thenReturn(PROPOSAL);
        var body = json.createObjectNode().put("productDirectionId", direction).put("baseAssetId", repo)
                .put("baseRepositoryProfileId", repo);
        body.set("project", json.readTree(scope(project)));
        return json.readTree(mvc.perform(post("/api/evolution-plans/planning").contentType(MediaType.APPLICATION_JSON)
                .content(body.toString())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void rejectedCurrentStateLeavesRoundUnboundAndExplicitResubmissionUsesSameInputs() throws Exception {
        JsonNode context = project(one);
        String direction = candidate(context, one);
        assertEquals(200, select(context, direction));
        ObjectNode body = json.createObjectNode().put("productDirectionId", direction).put("baseAssetId", one)
                .put("baseRepositoryProfileId", one);
        body.set("project", json.readTree(scope(context)));
        when(ai.generate(any())).thenReturn(PROPOSAL.replace("[\"GET\"]", "[\"Invented capability\"]"));
        int before = jdbc.queryForObject("SELECT count(*) FROM evolution_plan", Integer.class);

        mvc.perform(post("/api/evolution-plans/planning").contentType(MediaType.APPLICATION_JSON)
                .content(body.toString())).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PLANNING_CURRENT_STATE_INVALID"));
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM evolution_plan", Integer.class));
        JsonNode loaded = json.readTree(mvc.perform(get("/api/projects/{key}", "project:" + context.path("id").asText()))
                .andReturn().getResponse().getContentAsString()).path("context");
        assertTrue(loaded.path("planId").isNull());
        assertEquals(context.path("version"), loaded.path("version"));

        JsonNode accepted = plan(loaded, direction, one);
        assertEquals(direction, accepted.path("productDirectionId").asText());
        assertEquals(before + 1, jdbc.queryForObject("SELECT count(*) FROM evolution_plan", Integer.class));
        mvc.perform(post("/api/evolution-plans/planning").contentType(MediaType.APPLICATION_JSON)
                .content(body.toString())).andExpect(status().isConflict());
        org.mockito.Mockito.verify(ai, org.mockito.Mockito.times(2)).generate(any());
        var requests = org.mockito.ArgumentCaptor.forClass(com.ayywl.delveforge.application.port.ai.AiRequest.class);
        org.mockito.Mockito.verify(ai, org.mockito.Mockito.times(2)).generate(requests.capture());
        assertEquals(requests.getAllValues().get(0), requests.getAllValues().get(1));
    }

    @Test
    void staleRoundSupersededDirectionAndWrongBaseRejectBeforeProvider() throws Exception {
        JsonNode context = project(one);
        String direction = candidate(context, one);
        String nextDirection = candidate(context, one);
        assertEquals(200, select(context, direction));
        ObjectNode body = json.createObjectNode().put("productDirectionId", direction).put("baseAssetId", one)
                .put("baseRepositoryProfileId", one);
        ObjectNode staleScope = (ObjectNode) json.readTree(scope(context));
        staleScope.put("expectedVersion", 0);
        body.set("project", staleScope);
        mvc.perform(post("/api/evolution-plans/planning").contentType(MediaType.APPLICATION_JSON)
                .content(body.toString())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_CONTEXT_CONFLICT"));

        body.set("project", json.readTree(scope(context)));
        body.put("baseAssetId", two);
        mvc.perform(post("/api/evolution-plans/planning").contentType(MediaType.APPLICATION_JSON)
                .content(body.toString())).andExpect(status().isConflict());
        assertEquals(200, select(context, nextDirection));
        body.put("baseAssetId", one);
        mvc.perform(post("/api/evolution-plans/planning").contentType(MediaType.APPLICATION_JSON)
                .content(body.toString())).andExpect(status().isConflict());
        verifyNoInteractions(ai);
    }

    @Test
    void twoProjectsCanGeneratePlansWithoutChangingEachOthersBinding() throws Exception {
        JsonNode a = project(one);
        JsonNode b = project(two);
        String da = candidate(a, one);
        String db = candidate(b, two);
        assertEquals(200, select(a, da));
        assertEquals(200, select(b, db));
        JsonNode pa = plan(a, da, one);
        JsonNode pb = plan(b, db, two);
        assertEquals(da, pa.path("productDirectionId").asText());
        assertEquals(db, pb.path("productDirectionId").asText());
        assertEquals(pa.path("id").asText(), jdbc.queryForObject(
                "SELECT plan_id FROM project_flow_context WHERE id=?", String.class, a.path("id").asText()));
        assertEquals(pb.path("id").asText(), jdbc.queryForObject(
                "SELECT plan_id FROM project_flow_context WHERE id=?", String.class, b.path("id").asText()));
    }

    @Test
    void independentProjectsSharingProfileAndOptionallyRepositoryKeepSelectedDirections() throws Exception {
        for (String repo : List.of(one, two)) {
            JsonNode a = project(one);
            JsonNode b = project(repo);
            String da = candidate(a, one);
            String db = candidate(b, repo);
            assertEquals(200, select(a, da));
            assertEquals(200, select(b, db));
            assertEquals("SELECTED", directions.findById(new ProductDirectionId(da)).orElseThrow().status().name());
            assertEquals("SELECTED", directions.findById(new ProductDirectionId(db)).orElseThrow().status().name());
        }
        verifyNoInteractions(ai);
    }

    @Test
    void switchingWithinBLeavesAPlanAndSavedVerificationUntouched() throws Exception {
        JsonNode a = project(one);
        JsonNode b = project(two);
        String da = candidate(a, one);
        String db = candidate(b, two);
        String nextB = candidate(b, two);
        assertEquals(200, select(a, da));
        String planA = plan(a, da, one).path("id").asText();
        // 合成已持久化检查点只存在测试库；测试方向切换不能改变它。
        jdbc.update("UPDATE evolution_plan SET status='ACTIVE', working_copy_id='fixture-copy' WHERE id=?", planA);
        jdbc.update("UPDATE evolution_step SET status='SUCCEEDED' WHERE plan_id=?", planA);
        assertEquals(200, select(b, db));
        String planB = plan(b, db, two).path("id").asText();
        JsonNode refreshedB = json.readTree(mvc.perform(get("/api/projects/{key}", "project:" + b.path("id").asText()))
                .andReturn().getResponse().getContentAsString()).path("context");
        assertEquals(200, select(refreshedB, nextB));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM evolution_plan WHERE id=?", String.class, planA));
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM evolution_step WHERE plan_id=?", String.class, planA));
        assertEquals("SUPERSEDED", jdbc.queryForObject("SELECT status FROM evolution_plan WHERE id=?", String.class, planB));
        assertEquals("SELECTED", directions.findById(new ProductDirectionId(da)).orElseThrow().status().name());
    }

    @Test
    void falseProjectAndStaleVersionCannotSelectOrCallPlanningAi() throws Exception {
        JsonNode a = project(one);
        JsonNode b = project(one);
        String da = candidate(a, one);
        assertEquals(409, select(b, da));
        var stale = a.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) stale).put("version", 0);
        assertEquals(409, select(stale, da));
        var request = json.createObjectNode().put("productDirectionId", da).put("baseAssetId", one)
                .put("baseRepositoryProfileId", one);
        request.set("project", json.readTree(scope(b)));
        mvc.perform(post("/api/evolution-plans/planning").contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVOLUTION_FLOW_SCOPE_CONFLICT"));
        verifyNoInteractions(ai);
    }

    @Test
    void simultaneousSelectionIsIsolatedAcrossFlowsAndUniqueWithinOneFlow() throws Exception {
        for (boolean sameFlow : List.of(false, true)) {
            JsonNode a = project(one);
            JsonNode b = sameFlow ? a : project(one);
            String da = candidate(a, one);
            String db = candidate(b, one);
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> { start.await(); return select(a, da); });
                var second = pool.submit(() -> { start.await(); return select(b, db); });
                start.countDown();
                int r1 = first.get(20, TimeUnit.SECONDS);
                int r2 = second.get(20, TimeUnit.SECONDS);
                assertTrue(r1 == 200 || r1 == 409);
                assertTrue(r2 == 200 || r2 == 409);
                if (!sameFlow) {
                    assertEquals(200, r1);
                    assertEquals(200, r2);
                }
            }
            assertEquals(sameFlow ? 1 : 2, jdbc.queryForObject(
                    "SELECT count(*) FROM product_direction WHERE id IN (?,?) AND status='SELECTED'", Integer.class, da, db));
        }
        verifyNoInteractions(ai);
    }

    @Test
    void explicitNewRoundRetainsOldPlanAndCreatesNewPlanInSameFlowWithoutReusingCopy() throws Exception {
        JsonNode a = project(one);
        String oldDirection = candidate(a, one);
        String newDirection = candidate(a, one);
        assertEquals(200, select(a, oldDirection));
        JsonNode oldPlan = plan(a, oldDirection, one);
        JsonNode current = json.readTree(mvc.perform(get("/api/projects/{key}", "project:" + a.path("id").asText()))
                .andReturn().getResponse().getContentAsString()).path("context");
        assertEquals(200, select(current, newDirection));
        String body = json.createObjectNode().put("expectedVersion", current.path("version").asLong()).toString();
        JsonNode next = json.readTree(mvc.perform(post("/api/projects/{id}/planning-rounds", a.path("id").asText())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.previousContextId").value(a.path("id").asText()))
                .andExpect(jsonPath("$.planId").doesNotExist()).andReturn().getResponse().getContentAsString());
        assertEquals(a.path("flowId"), next.path("flowId"));
        JsonNode newPlan = plan(next, newDirection, one);
        assertTrue(!newPlan.path("id").equals(oldPlan.path("id")));
        assertTrue(newPlan.path("workingCopyId").isNull());
        assertEquals(oldPlan.path("id").asText(), jdbc.queryForObject(
                "SELECT plan_id FROM project_flow_context WHERE id=?", String.class, a.path("id").asText()));
        mvc.perform(get("/api/projects/{id}/rounds", next.path("id").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        assertEquals("SUPERSEDED", jdbc.queryForObject("SELECT status FROM evolution_plan WHERE id=?",
                String.class, oldPlan.path("id").asText()));
        mvc.perform(post("/api/projects/{id}/planning-rounds", a.path("id").asText())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
    }

    @Test
    void stalePlanningContextDuringAiRejectsBeforePlanAndContextCommit() throws Exception {
        JsonNode a = project(one);
        String selected = candidate(a, one);
        assertEquals(200, select(a, selected));
        when(ai.generate(any())).thenAnswer(invocation -> {
            jdbc.update("UPDATE project_flow_context SET version=version+1 WHERE id=?", a.path("id").asText());
            return PROPOSAL;
        });
        var request = json.createObjectNode().put("productDirectionId", selected).put("baseAssetId", one)
                .put("baseRepositoryProfileId", one);
        request.set("project", json.readTree(scope(a)));
        mvc.perform(post("/api/evolution-plans/planning").contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_CONTEXT_CONFLICT"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM evolution_plan WHERE product_direction_id=?",
                Integer.class, selected));
        assertEquals(null, jdbc.queryForObject("SELECT plan_id FROM project_flow_context WHERE id=?",
                String.class, a.path("id").asText()));
    }

    @Test
    void deletedContextCannotSelectOrPlanEvenWithItsLatestVersion() throws Exception {
        JsonNode a = project(one);
        String selected = candidate(a, one);
        assertEquals(200, select(a, selected));
        mvc.perform(delete("/api/projects/{key}", "project:" + a.path("id").asText())
                .contentType(MediaType.APPLICATION_JSON).content(json.createObjectNode()
                        .put("expectedVersion", a.path("version").asLong()).toString()))
                .andExpect(status().isOk());
        ObjectNode current = (ObjectNode) a.deepCopy();
        current.put("version", a.path("version").asLong() + 1);
        assertEquals(409, select(current, selected));
        var request = json.createObjectNode().put("productDirectionId", selected).put("baseAssetId", one)
                .put("baseRepositoryProfileId", one);
        request.set("project", json.readTree(scope(current)));
        mvc.perform(post("/api/evolution-plans/planning").contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isConflict());
        verifyNoInteractions(ai);
    }

    @Test
    void deletionDuringPlanningInvalidatesContextAndRollsBackNewPlanWithoutUndoingDeletion() throws Exception {
        JsonNode a = project(one);
        String selected = candidate(a, one);
        assertEquals(200, select(a, selected));
        when(ai.generate(any())).thenAnswer(invocation -> {
            mvc.perform(delete("/api/projects/{key}", "project:" + a.path("id").asText())
                    .contentType(MediaType.APPLICATION_JSON).content(json.createObjectNode()
                            .put("expectedVersion", a.path("version").asLong()).toString()))
                    .andExpect(status().isOk());
            return PROPOSAL;
        });
        var request = json.createObjectNode().put("productDirectionId", selected).put("baseAssetId", one)
                .put("baseRepositoryProfileId", one);
        request.set("project", json.readTree(scope(a)));
        mvc.perform(post("/api/evolution-plans/planning").contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isConflict());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM evolution_plan WHERE product_direction_id=?",
                Integer.class, selected));
        assertEquals(a.path("version").asInt() + 1, jdbc.queryForObject("SELECT version FROM project_flow_context WHERE id=? AND deleted_at IS NOT NULL",
                Integer.class, a.path("id").asText()));
    }
}
