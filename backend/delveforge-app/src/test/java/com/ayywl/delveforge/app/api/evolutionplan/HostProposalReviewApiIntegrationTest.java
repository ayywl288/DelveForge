package com.ayywl.delveforge.app.api.evolutionplan;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.ai.AiRole;
import com.ayywl.delveforge.application.port.persistence.EvolutionPlanRepository;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionTransition;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.persistence.WorkingCopyRepository;
import com.ayywl.delveforge.domain.asset.AssetUsagePolicy;
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
import com.ayywl.delveforge.domain.evolution.CurrentState;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanningService;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepStatus;
import com.ayywl.delveforge.domain.evolution.PlanningProposal;
import com.ayywl.delveforge.domain.evolution.PlanningStepProposal;
import com.ayywl.delveforge.domain.evolution.TargetState;
import com.ayywl.delveforge.domain.evolution.VerificationCriterion;
import com.ayywl.delveforge.domain.evolution.WorkingCopyId;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 实际 loopback 审查、正常 API、SQLite 与 Git；AI 仅在 Gateway 边界替身。 */
@SpringBootTest
@AutoConfigureMockMvc
class HostProposalReviewApiIntegrationTest {
    private static final Path BASE = Path.of("target", "host-review-api", UUID.randomUUID().toString()).toAbsolutePath();
    private static final AtomicReference<String> MODE = new AtomicReference<>("approve");
    private static final AtomicInteger REVIEWS = new AtomicInteger();
    private static final AtomicReference<Runnable> DURING_REVIEW = new AtomicReference<>(() -> { });
    private static final AtomicReference<Throwable> HANDLER_FAILURE = new AtomicReference<>();
    private static final ObjectMapper HTTP_JSON = new ObjectMapper();
    private static final HttpServer REVIEW = createReviewServer();
    private static final String PATCH = "--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-old\n+new\n";

    private static HttpServer createReviewServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/review", exchange -> {
                int code = 200;
                byte[] response;
                try {
                    JsonNode request = HTTP_JSON.readTree(exchange.getRequestBody());
                    REVIEWS.incrementAndGet();
                    DURING_REVIEW.get().run();
                    response = HTTP_JSON.writeValueAsBytes(Map.of("approved", MODE.get().equals("approve"),
                            "patchSha256", request.path("patchSha256").asText()));
                } catch (Throwable failure) {
                    HANDLER_FAILURE.set(failure);
                    code = 500;
                    response = "{}".getBytes(StandardCharsets.UTF_8);
                }
                exchange.sendResponseHeaders(code, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("delveforge.persistence.database-file", () -> BASE.resolve("state.db").toString());
        properties.add("delveforge.workspace.root", () -> BASE.resolve("managed").toString());
        properties.add("delveforge.execution.proposal-review-url",
                () -> "http://127.0.0.1:" + REVIEW.getAddress().getPort() + "/review");
    }

    @AfterAll
    static void stopReviewServer() {
        REVIEW.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SoftwareAssetRepository assets;
    @Autowired RepositoryProfileRepository profiles;
    @Autowired ProductDirectionRepository directions;
    @Autowired EvolutionPlanRepository plans;
    @Autowired WorkingCopyRepository copies;
    @MockitoBean AiGateway ai;
    private EvolutionPlanId planId;
    private EvolutionStepId stepId;
    private Path source;
    private Path copy;
    private String baseline;
    private boolean malformed;

    @BeforeEach
    void setup() throws Exception {
        MODE.set("approve");
        REVIEWS.set(0);
        HANDLER_FAILURE.set(null);
        malformed = false;
        // 同一测试数据库复用 standalone 流；保留历史并合法结束上一用例的选择。
        directions.findCurrentSelected().ifPresent(previous -> {
            var expected = previous.status();
            previous.supersede();
            directions.saveTransitions(List.of(new ProductDirectionTransition(previous, expected)));
        });
        source = BASE.resolve("source-" + UUID.randomUUID());
        Files.createDirectories(source);
        Files.writeString(source.resolve("A.java"), "old\n");
        git(source, "init", "-q");
        git(source, "-c", "core.autocrlf=false", "add", "A.java");
        git(source, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "initial");
        baseline = git(source, "rev-parse", "HEAD").trim();
        SoftwareAssetId assetId = new SoftwareAssetId(UUID.randomUUID().toString());
        RepositoryProfileId profileId = new RepositoryProfileId(UUID.randomUUID().toString());
        var asset = SoftwareAsset.create(assetId, SoftwareAssetType.GIT_REPOSITORY, SoftwareAssetSource.USER_SPECIFIED,
                source.toString(), true, "MIT", UsageAuthorization.ALLOWED);
        Evidence fact = new Evidence(EvidenceSourceType.REPOSITORY, "A.java", "Existing code", null, true);
        EvidenceBasis basis = new EvidenceBasis(fact, new RepositoryProfileEvidenceOrigin(profileId));
        var profile = RepositoryProfile.create(profileId, assetId, baseline, "Code", List.of("Java"),
                List.of("code"), List.of("Existing"), List.of("Existing"), List.of(), List.of(), List.of(fact));
        var direction = ProductDirection.create(new ProductDirectionId(UUID.randomUUID().toString()),
                new UserProfileId("review-user-" + UUID.randomUUID()), 1, List.of(profileId), "Direction", "Problem", "Target", "Fit",
                List.of(assetId), "Difference", "Value", "Small", List.of(),
                new DirectionEvidenceSupport(List.of(basis), List.of(basis), List.of(basis)));
        direction.select();
        assets.save(asset);
        profiles.save(profile);
        directions.save(direction);
        var proposal = new PlanningProposal(new CurrentState("Existing", List.of("Existing"), List.of("code"), List.of()),
                new TargetState("Problem", "Target", "Difference"), List.of("Existing"), List.of("Change code"),
                List.of(new PlanningStepProposal("Change code", "A.java", List.of("Change code"),
                        List.of(VerificationCriterion.technical(VerificationCriterion.Kind.DIFF_CHECK_PASSES)))),
                List.of(), List.of(basis));
        var plan = new EvolutionPlanningService(new AssetUsagePolicy(),
                () -> new EvolutionPlanId(UUID.randomUUID().toString()),
                () -> new EvolutionStepId(UUID.randomUUID().toString())).plan(direction, asset, profile, proposal);
        plans.save(plan);
        planId = plan.id();
        stepId = plan.steps().getFirst().id();
        JsonNode active = mapper.readTree(mvc.perform(post("/api/evolution-plans/{id}/prepare", planId.value()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        copy = Path.of(copies.findById(new WorkingCopyId(active.path("workingCopyId").asText())).orElseThrow().location());
        mvc.perform(post(route("confirm"))).andExpect(status().isOk());
        when(ai.generate(any())).thenAnswer(invocation -> {
            AiRequest request = invocation.getArgument(0);
            JsonNode input = mapper.readTree(request.messages().stream().filter(message -> message.role() == AiRole.USER)
                    .findFirst().orElseThrow().content());
            if (input.has("sourceFiles")) {
                return malformed ? "not-json" : mapper.writeValueAsString(Map.of("type", "CHANGE_PROPOSAL",
                        "expectedRevision", baseline, "summary", "Change code", "declaredAffectedPaths", List.of("A.java"),
                        "unifiedDiff", PATCH, "verificationStrategy", List.of(
                                Map.of("action", "DIFF_CHECK", "target", "", "criterionIndexes", List.of(1)))));
            }
            String reference = input.path("fileCatalog").get(0).path("reference").asText();
            return mapper.writeValueAsString(Map.of("focusAreas", List.of(
                    Map.of("label", "code", "fileRefs", List.of(reference)),
                    Map.of("label", "behavior", "fileRefs", List.of(reference)),
                    Map.of("label", "test", "fileRefs", List.of(reference)))));
        });
        DURING_REVIEW.set(() -> {
            var step = plans.findById(planId).orElseThrow().step(stepId);
            assertEquals(EvolutionStepStatus.READY, step.status());
            assertNull(step.baselineRevision());
            assertNull(step.executionResult());
            assertNull(step.verificationResult());
        });
    }

    private String route(String action) {
        return "/api/evolution-plans/" + planId.value() + "/steps/" + stepId.value() + "/" + action;
    }

    @Test
    void approvedCallStaysReadyDuringRealHttpReviewThenCommitsAndVerifiesActualGit() throws Exception {
        mvc.perform(post(route("execute"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("SUCCEEDED"));
        var step = plans.findById(planId).orElseThrow().step(stepId);
        assertEquals(1, REVIEWS.get());
        assertNull(HANDLER_FAILURE.get());
        assertEquals(baseline, step.baselineRevision());
        assertNotEquals(baseline, git(copy, "rev-parse", "HEAD").trim());
        assertEquals(step.verificationResult().candidateRevision(), git(copy, "rev-parse", "HEAD").trim());
        assertEquals("new\n", Files.readString(copy.resolve("A.java")).replace("\r\n", "\n"));
        assertEquals("old\n", Files.readString(source.resolve("A.java")));
        verify(ai, times(2)).generate(any());
    }

    @Test
    void rejectedReviewLeavesDomainAndPhysicalCopyAtReadyWithoutImplicitRetry() throws Exception {
        MODE.set("reject");
        mvc.perform(post(route("execute"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTION_PROPOSAL_REVIEW_REJECTED"));
        assertEquals(1, REVIEWS.get());
        assertNull(HANDLER_FAILURE.get());
        assertEquals(EvolutionStepStatus.READY, plans.findById(planId).orElseThrow().step(stepId).status());
        assertNull(plans.findById(planId).orElseThrow().step(stepId).baselineRevision());
        assertEquals(baseline, git(copy, "rev-parse", "HEAD").trim());
        assertEquals("old\n", Files.readString(source.resolve("A.java")));
        verify(ai, times(2)).generate(any());
    }

    @Test
    void malformedProposalNeverReachesHostReviewOrExecution() throws Exception {
        malformed = true;
        mvc.perform(post(route("execute"))).andExpect(status().isUnprocessableEntity());
        assertEquals(0, REVIEWS.get());
        assertEquals(EvolutionStepStatus.READY, plans.findById(planId).orElseThrow().step(stepId).status());
        assertNull(plans.findById(planId).orElseThrow().step(stepId).baselineRevision());
        assertEquals(baseline, git(copy, "rev-parse", "HEAD").trim());
    }

    private static String git(Path directory, String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), result);
        return result;
    }
}
