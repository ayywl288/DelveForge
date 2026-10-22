package com.ayywl.delveforge.app.agent;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentStore;
import com.ayywl.delveforge.application.agent.Delivery;
import com.ayywl.delveforge.application.port.persistence.EvolutionPlanRepository;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.domain.agent.AgentRunState;
import com.ayywl.delveforge.domain.asset.AssetUsagePolicy;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
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
import com.ayywl.delveforge.domain.evolution.PlanningProposal;
import com.ayywl.delveforge.domain.evolution.PlanningStepProposal;
import com.ayywl.delveforge.domain.evolution.TargetState;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStatus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实公开工程、HTTP、模型、Docker、Maven、Git 和 SQLite；M1–M3 为明确标注的领域夹具。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_TASK2_E2E", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AutonomousEvolutionLiveTest {
    static final Path ROOT = Path.of(System.getenv().getOrDefault("DELVEFORGE_TASK2_ROOT",
            "target/task2-live")).toAbsolutePath().resolve(UUID.randomUUID().toString());
    static final Path PUBLIC = Path.of(System.getenv().getOrDefault("DELVEFORGE_TASK2_PUBLIC_REPOSITORY",
            "../../data/t2e/public")).toAbsolutePath().normalize();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", () -> ROOT.resolve("application.db").toString());
        registry.add("delveforge.workspace.root", () -> ROOT.resolve("working-copies").toString());
        registry.add("delveforge.agent.sandbox.root", () -> ROOT.resolve("runs").toString());
        registry.add("delveforge.agent.limits.max-model-calls", () -> 24);
        registry.add("delveforge.agent.limits.max-tool-calls", () -> 80);
    }

    @Autowired TestRestTemplate http;
    @Autowired UserProfileRepository users;
    @Autowired SoftwareAssetRepository assets;
    @Autowired RepositoryProfileRepository profiles;
    @Autowired ProductDirectionRepository directions;
    @Autowired EvolutionPlanRepository plans;
    @Autowired AgentStore agents;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test
    void publicRepositoryTwoConfirmedStepsUseRealAgentAndAutomaticDelivery() throws Exception {
        assertTrue(Files.isRegularFile(PUBLIC.resolve("complete/pom.xml")), "Provide an unmodified public Spring guide clone");
        String baseline = git(PUBLIC, "rev-parse", "HEAD");
        assertEquals("", git(PUBLIC, "status", "--porcelain"));
        var registered = http.postForEntity("/api/software-assets", Map.of("location", PUBLIC.toString(),
                "readPermissionAllowed", true, "licenseInfo", "Apache-2.0 (public Spring guide)",
                "usageAuthorization", "ALLOWED"), JsonNode.class);
        assertEquals(HttpStatus.CREATED, registered.getStatusCode(), String.valueOf(registered.getBody()));
        var assetId = new SoftwareAssetId(registered.getBody().path("id").asText());
        var profileId = new RepositoryProfileId(UUID.randomUUID().toString());
        var userId = new UserProfileId(UUID.randomUUID().toString());
        users.save(UserProfile.reconstitute(userId, UserProfileStatus.CONFIRMED, 1, List.of("Spring REST"),
                List.of("练习公开指南"), List.of(), List.of("Java"), List.of("完善问候输入处理"), List.of(), List.of()));
        Evidence fact = new Evidence(EvidenceSourceType.REPOSITORY,
                "complete/src/main/java/com/example/restservice/GreetingController.java", "GET /greeting with a name", 1.0, true);
        var profile = RepositoryProfile.create(profileId, assetId, baseline, "Spring REST guide", List.of("Java", "Maven"),
                List.of("complete"), List.of("Greeting endpoint"), List.of("REST controller"),
                List.of("Names are not normalized"), List.of(), List.of(fact));
        profiles.save(profile);
        EvidenceBasis basis = new EvidenceBasis(fact, new RepositoryProfileEvidenceOrigin(profileId));
        var direction = ProductDirection.create(new ProductDirectionId(UUID.randomUUID().toString()), userId, 1,
                List.of(profileId), "Normalized greetings", "Names are not normalized", "Predictable greeting names",
                "Spring practice", List.of(assetId), "Whitespace handling", "REST regression tests", "Small", List.of(),
                new DirectionEvidenceSupport(List.of(basis), List.of(basis), List.of(basis)));
        direction.select();
        directions.save(direction);
        AtomicInteger ordinal = new AtomicInteger();
        var plan = new EvolutionPlanningService(new AssetUsagePolicy(), () -> new EvolutionPlanId(UUID.randomUUID().toString()),
                () -> new EvolutionStepId("live-step-" + ordinal.incrementAndGet())).plan(direction,
                assets.findById(assetId).orElseThrow(), profile, new PlanningProposal(
                new CurrentState("Spring greeting endpoint", List.of("Greeting endpoint"), List.of("complete"),
                        List.of("Names are not normalized")),
                new TargetState(direction.problem(), direction.targetProduct(), direction.differentiation()),
                List.of("REST controller"), List.of("Handle blank names", "Trim names"), List.of(
                new PlanningStepProposal("在 complete Maven 工程中让 /greeting 的空白 name 按 World 问候；保留普通名字行为。"
                        + "增加 MockMvc 回归测试，并在 complete 目录运行完整 Maven test。", "complete 问候输入处理与测试",
                        List.of("空白 name 回退 World", "新增空白名字测试并运行完整测试")),
                new PlanningStepProposal("在 complete 工程的 /greeting 中裁剪非空 name 首尾空白，保留已有 World 回退。"
                        + "增加带首尾空白名字的 MockMvc 回归测试，并在 complete 目录运行完整 Maven test。",
                        "complete 问候输入处理与测试", List.of("裁剪名字", "保留上一 Step 行为并完整重测"))), List.of(), List.of(basis)));
        plans.save(plan);
        String planId = plan.id().value();
        var activation = http.postForEntity("/api/evolution-plans/" + planId + "/prepare", null, JsonNode.class);
        assertEquals(HttpStatus.OK, activation.getStatusCode(), String.valueOf(activation.getBody()));
        List<Map<String, Object>> evidence = new ArrayList<>();
        String previous = baseline;
        for (var step : plan.steps()) {
            var confirmation = http.postForEntity("/api/evolution-plans/" + planId + "/steps/" + step.id().value()
                    + "/confirm", null, JsonNode.class);
            assertEquals(HttpStatus.OK, confirmation.getStatusCode(), String.valueOf(confirmation.getBody()));
            var started = http.postForEntity("/api/evolution-plans/" + planId + "/steps/" + step.id().value()
                    + "/agent-runs", Map.of("targetJdk", 17), AgentProtocol.Run.class);
            assertEquals(HttpStatus.ACCEPTED, started.getStatusCode());
            AgentProtocol.Run run = started.getBody();
            assertNotNull(run);
            int attempts = 1;
            long deadline = System.nanoTime() + Duration.ofMinutes(25).toNanos();
            Delivery delivery = null;
            while (System.nanoTime() < deadline) {
                run = http.getForObject("/api/agent-runs/" + run.id(), AgentProtocol.Run.class);
                if (run.state().terminal()) {
                    if (run.state() != AgentRunState.SUCCEEDED && attempts++ == 1) {
                        assertEquals("READY", jdbc.queryForObject("SELECT status FROM evolution_step WHERE id=?",
                                String.class, step.id().value()));
                        assertEquals(previous, jdbc.queryForObject("SELECT last_verified_revision FROM working_copy WHERE id=?",
                                String.class, run.workingCopyId()));
                        assertEquals(null, run.candidateId());
                        evidence.add(Map.of("failedRun", run, "retry", "Explicit fresh HTTP attempt; no conversation resume",
                                "events", agents.events(run.id(), 0, 200)));
                        String failedId = run.id();
                        var retry = http.postForEntity("/api/evolution-plans/" + planId + "/steps/" + step.id().value()
                                + "/agent-runs", Map.of("targetJdk", 17), AgentProtocol.Run.class);
                        assertEquals(HttpStatus.ACCEPTED, retry.getStatusCode());
                        run = retry.getBody();
                        assertTrue(!failedId.equals(run.id()));
                        continue;
                    }
                    assertEquals("SUCCEEDED", run.state().name(), run.reason() + " " + agents.events(run.id(), 0, 200));
                    delivery = http.getForObject("/api/agent-candidates/" + run.candidateId() + "/delivery", Delivery.class);
                    if (delivery != null && delivery.state() == Delivery.State.COMPLETED) {
                        break;
                    }
                    if (delivery != null && (delivery.state() == Delivery.State.RECOVERY_REQUIRED
                            || delivery.state() == Delivery.State.DELIVERY_BLOCKED)) {
                        throw new AssertionError("Live Delivery blocked: " + delivery);
                    }
                }
                Thread.sleep(500);
            }
            assertNotNull(delivery, "Automatic Delivery did not produce a durable identity");
            assertEquals(Delivery.State.COMPLETED, delivery.state());
            var frozen = agents.candidate(run.candidateId());
            assertEquals(previous, frozen.baseline());
            assertTrue(frozen.changedPaths().stream().anyMatch(path -> path.startsWith("complete/src/main/") && path.endsWith(".java")));
            assertTrue(frozen.changedPaths().stream().anyMatch(path -> path.startsWith("complete/src/test/") && path.endsWith(".java")));
            assertTrue(frozen.testEvidence().contains("TESTS_PASSED"), frozen.testEvidence());
            assertTrue(run.modelCalls() >= 2 && run.toolCalls() >= 3);
            String location = jdbc.queryForObject("SELECT location FROM working_copy WHERE id=?", String.class, run.workingCopyId());
            assertEquals(frozen.expectedTree(), git(Path.of(location), "rev-parse", "HEAD^{tree}"));
            assertEquals(previous, git(Path.of(location), "rev-parse", "HEAD^"));
            assertEquals(delivery.revision(), jdbc.queryForObject("SELECT last_verified_revision FROM working_copy WHERE id=?",
                    String.class, run.workingCopyId()));
            assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM evolution_step WHERE id=?", String.class, step.id().value()));
            var repeated = http.postForEntity("/api/agent-candidates/" + frozen.id() + "/delivery", null, Delivery.class);
            assertEquals(delivery.revision(), repeated.getBody().revision());
            evidence.add(Map.of("run", run, "delivery", delivery, "testEvidence", json.readTree(frozen.testEvidence()),
                    "candidateTree", frozen.expectedTree(), "changedPaths", frozen.changedPaths(), "events", agents.events(run.id(), 0, 200)));
            previous = delivery.revision();
        }
        assertEquals(baseline, git(PUBLIC, "rev-parse", "HEAD"));
        assertEquals("", git(PUBLIC, "status", "--porcelain"));
        Files.writeString(ROOT.resolve("evidence.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("planningOrigin", "M1-M3 domain fixtures; Agent and Delivery real", "publicSource", PUBLIC.toString(),
                        "sourceRevision", baseline, "planId", planId, "results", evidence)));
        System.out.println("TASK2_LIVE_EVIDENCE root=" + ROOT + " plan=" + planId + " steps=" + evidence.size());
    }

    private static String git(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), result);
        return result.strip();
    }
}
