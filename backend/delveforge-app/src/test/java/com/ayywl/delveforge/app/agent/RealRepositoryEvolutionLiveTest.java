package com.ayywl.delveforge.app.agent;

import com.ayywl.delveforge.app.DelveForgeApplication;
import com.ayywl.delveforge.application.port.persistence.EvolutionPlanRepository;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
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
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.web.client.RestTemplate;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 夹具仅建立 M1–M3；单 Step 由新打包 JAR 的真实 Agent 与自动 Delivery 完成。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_REAL_STEP", matches = "true")
class RealRepositoryEvolutionLiveTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final RestTemplate http = new RestTemplate();
    private final Path root = Path.of(System.getenv("DELVEFORGE_REAL_EVIDENCE"))
            .resolve("attempt-" + UUID.randomUUID()).toAbsolutePath();
    private final Path source = Path.of(System.getenv("DELVEFORGE_REAL_SOURCE")).toAbsolutePath();

    @Test
    void packagedApplicationAutonomouslyChangesPublicRepositoryAndDeliversFrozenTree() throws Exception {
        Files.createDirectories(root);
        assertTrue(Files.isRegularFile(root.getParent().resolve("baseline.json")), "Validate upstream first");
        String baseline = git(source, "rev-parse", "HEAD");
        assertEquals(json.readTree(Files.readString(root.getParent().resolve("baseline.json")))
                .path("baseline").asText(), baseline);
        assertEquals("", git(source, "status", "--porcelain"));
        Path jar = Path.of(System.getenv("DELVEFORGE_REAL_JAR")).toAbsolutePath();
        assertTrue(Files.isRegularFile(jar));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("root", root.toString());
        evidence.put("baseline", baseline);
        evidence.put("publicSource", source.toString());
        evidence.put("sourceTree", git(source, "rev-parse", "HEAD^{tree}"));
        evidence.put("jarSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(jar))));
        evidence.put("planningOrigin", "M1-M3 domain fixtures; real packaged Application Agent/Delivery over HTTP");
        List<String> settings = new ArrayList<>(List.of(
                "--spring.config.location=classpath:/application.yml",
                "--delveforge.persistence.database-file=" + root.resolve("application.db"),
                "--delveforge.workspace.root=" + root.resolve("working-copies"),
                "--delveforge.workspace.existing-roots=",
                "--delveforge.agent.sandbox.root=" + root.resolve("runs"),
                "--delveforge.agent.sandbox.owner=" + System.getenv().getOrDefault(
                        "DELVEFORGE_AGENT_OWNER", "df-real-e2e-validation"),
                "--delveforge.agent.sandbox.cache-volume=" + System.getenv("DELVEFORGE_MAVEN_CACHE"),
                "--delveforge.agent.limits.check-seconds=600",
                "--delveforge.agent.limits.wall-seconds=1800",
                "--logging.file.name=" + root.resolve("logs/delveforge.log")));
        evidence.put("runtimeSettings", settings);
        write(evidence);
        String planId;
        String stepId;
        // 通过仓库端口保存有效领域夹具；正式状态转换仍由启动后的生产 HTTP 用例完成。
        var fixtureArgs = new ArrayList<>(settings);
        fixtureArgs.add("--server.port=0");
        fixtureArgs.add("--delveforge.agent.enabled=false");
        try (var fixtures = new SpringApplicationBuilder(DelveForgeApplication.class)
                .run(fixtureArgs.toArray(String[]::new))) {
            String fixtureApi = "http://127.0.0.1:" + ((ServletWebServerApplicationContext) fixtures)
                    .getWebServer().getPort();
            evidence.put("model", fixtures.getEnvironment().getProperty("delveforge.ai.deepseek.model"));
            evidence.put("responseTokens", fixtures.getEnvironment().getProperty("delveforge.agent.response-tokens"));
            var registered = post(fixtureApi + "/api/software-assets", Map.of("location", source.toString(),
                    "readPermissionAllowed", true, "licenseInfo", "Apache-2.0; LICENSE.txt at pinned upstream",
                    "usageAuthorization", "ALLOWED"));
            var assetId = new SoftwareAssetId(registered.path("id").asText());
            var profileId = new RepositoryProfileId(UUID.randomUUID().toString());
            var userId = new UserProfileId(UUID.randomUUID().toString());
            fixtures.getBean(UserProfileRepository.class).save(UserProfile.reconstitute(userId,
                    UserProfileStatus.CONFIRMED, 1, List.of("Spring REST"), List.of("公开指南练习"),
                    List.of(), List.of("Java"), List.of("可预测的问候输入处理"), List.of(), List.of()));
            var fact = new Evidence(EvidenceSourceType.REPOSITORY,
                    "complete/src/main/java/com/example/restservice/GreetingController.java",
                    "GET /greeting currently formats raw name", 1.0, true);
            var profile = RepositoryProfile.create(profileId, assetId, baseline, "Spring REST guide",
                    List.of("Java 17", "Maven", "Spring Boot 4.0.8"), List.of("complete"),
                    List.of("Greeting endpoint"), List.of("REST controller"),
                    List.of("Internal whitespace is preserved"), List.of(), List.of(fact));
            fixtures.getBean(RepositoryProfileRepository.class).save(profile);
            var basis = new EvidenceBasis(fact, new RepositoryProfileEvidenceOrigin(profileId));
            var direction = ProductDirection.create(new ProductDirectionId(UUID.randomUUID().toString()),
                    userId, 1, List.of(profileId), "Normalized greetings", "Inconsistent name whitespace",
                    "Predictable greeting names", "Spring practice", List.of(assetId), "Whitespace handling",
                    "HTTP behavioral regression tests", "Small", List.of(),
                    new DirectionEvidenceSupport(List.of(basis), List.of(basis), List.of(basis)));
            direction.select();
            fixtures.getBean(ProductDirectionRepository.class).save(direction);
            var plan = new EvolutionPlanningService(new AssetUsagePolicy(),
                    () -> new EvolutionPlanId(UUID.randomUUID().toString()),
                    () -> new EvolutionStepId(UUID.randomUUID().toString())).plan(direction,
                    fixtures.getBean(SoftwareAssetRepository.class).findById(assetId).orElseThrow(), profile,
                    new PlanningProposal(new CurrentState("Raw greeting names", List.of("Greeting endpoint"),
                            List.of("complete"), List.of("Internal whitespace is preserved")),
                            new TargetState(direction.problem(), direction.targetProduct(), direction.differentiation()),
                            List.of("REST controller"), List.of("Normalize names and test HTTP behavior"), List.of(
                            new PlanningStepProposal("在 complete Maven 工程中改进 /greeting 的 name 规范化："
                                    + "裁剪首尾 ASCII 空白，把内部连续空格、tab、换行等 ASCII 空白折叠为一个空格；"
                                    + "缺省、空字符串和纯空白回退 World；普通名字及 Unicode 名字保持。"
                                    + "保留响应 JSON 的 id/content 及递增 id。只改 complete 的相关源码和测试，"
                                    + "不改依赖、构建配置、其它示例或受保护文件。独立探索实现，增加 HTTP 回归测试，"
                                    + "并在 complete 目录执行完整 Maven verify，验证最终源码。",
                                    "complete 问候输入处理与 HTTP 测试",
                                    List.of("裁剪和折叠 ASCII 空白", "保持默认和普通名字行为",
                                            "新增 HTTP 回归测试并完整 Maven verify"))), List.of(), List.of(basis)));
            fixtures.getBean(EvolutionPlanRepository.class).save(plan);
            planId = plan.id().value();
            stepId = plan.steps().getFirst().id().value();
        }
        evidence.put("planId", planId);
        evidence.put("stepId", stepId);
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        String api = "http://127.0.0.1:" + port;
        evidence.put("backendPort", port);
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toString()));
        command.addAll(settings);
        command.add("--server.port=" + port);
        command.add("--delveforge.agent.enabled=true");
        var builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(root.resolve("backend.log").toFile());
        Process backend = builder.start();
        String runId = null;
        try {
            long startupDeadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
            boolean ready = false;
            while (backend.isAlive() && System.nanoTime() < startupDeadline) {
                try { ready = get(api + "/api/system/connectivity").path("status").asText().equals("UP"); }
                catch (RuntimeException unavailable) { /* 只读等待，不重放业务请求。 */ }
                if (ready) { break; }
                Thread.sleep(500);
            }
            assertTrue(ready, "Packaged backend failed startup; inspect isolated backend.log");
            post(api + "/api/evolution-plans/" + planId + "/prepare", null);
            post(api + "/api/evolution-plans/" + planId + "/steps/" + stepId + "/confirm", null);
            JsonNode run = post(api + "/api/evolution-plans/" + planId + "/steps/" + stepId + "/agent-runs",
                    Map.of("targetJdk", Integer.parseInt(System.getenv().getOrDefault(
                            "DELVEFORGE_REAL_TARGET_JDK", "17"))));
            runId = run.path("id").asText();
            evidence.put("runId", runId);
            write(evidence);
            long deadline = System.nanoTime() + Duration.ofMinutes(32).toNanos();
            while (System.nanoTime() < deadline) {
                run = get(api + "/api/agent-runs/" + runId);
                evidence.put("run", run);
                write(evidence);
                String state = run.path("state").asText();
                if (List.of("FAILED", "CANCELLED", "INTERRUPTED").contains(state)) {
                    throw new AssertionError("Real Agent failed: " + run.path("reason").asText());
                }
                if (state.equals("SUCCEEDED")) {
                    String candidateId = run.path("candidateId").asText();
                    JsonNode delivery = get(api + "/api/agent-candidates/" + candidateId + "/delivery");
                    evidence.put("delivery", delivery);
                    if (delivery != null && delivery.path("state").asText().equals("COMPLETED")) { break; }
                    assertTrue(delivery == null || !List.of("RECOVERY_REQUIRED", "DELIVERY_BLOCKED")
                            .contains(delivery.path("state").asText()), "Delivery blocked; inspect isolated evidence.json");
                }
                Thread.sleep(1000);
            }
            assertEquals("SUCCEEDED", run.path("state").asText());
            JsonNode delivery = (JsonNode) evidence.get("delivery");
            assertNotNull(delivery);
            assertEquals("COMPLETED", delivery.path("state").asText());
            assertTrue(run.path("modelCalls").asInt() > 0 && run.path("toolCalls").asInt() >= 3);
            inspectDurable(evidence, runId, run.path("candidateId").asText(), baseline);
            evidence.put("lifecycleAssertions", "PASS; independent behavioral acceptance is separate");
        } finally {
            if (runId != null) {
                try { evidence.put("events", events(runId)); } catch (Exception unavailable) {
                    evidence.put("eventReadFailure", unavailable.getClass().getSimpleName());
                }
            }
            evidence.put("originalHead", git(source, "rev-parse", "HEAD"));
            evidence.put("originalStatus", git(source, "status", "--porcelain"));
            write(evidence);
            backend.destroy();
            if (!backend.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                backend.destroyForcibly().waitFor();
            }
        }
        assertEquals(baseline, git(source, "rev-parse", "HEAD"));
        assertEquals("", git(source, "status", "--porcelain"));
        System.out.println("REAL_STEP_LIFECYCLE evidence=" + root);
    }

    private void inspectDurable(Map<String, Object> evidence, String runId, String candidateId,
            String baseline) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("application.db"))) {
            try (var statement = connection.prepareStatement("SELECT * FROM agent_candidate WHERE id=? AND run_id=?")) {
                statement.setString(1, candidateId);
                statement.setString(2, runId);
                try (var row = statement.executeQuery()) {
                    assertTrue(row.next());
                    evidence.put("candidateId", candidateId);
                    evidence.put("expectedTree", row.getString("expected_tree"));
                    evidence.put("testEvidence", json.readTree(row.getString("test_evidence")));
                    byte[] diff = row.getBytes("diff");
                    assertEquals(row.getString("sha256"), HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(diff)));
                    Files.write(root.resolve("candidate.diff"), diff);
                }
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT location,current_revision,last_verified_revision FROM working_copy")) {
                assertTrue(rows.next());
                Path copy = Path.of(rows.getString("location"));
                String revision = rows.getString("current_revision");
                assertEquals(revision, rows.getString("last_verified_revision"));
                assertEquals(revision, git(copy, "rev-parse", "HEAD"));
                assertEquals(baseline, git(copy, "rev-parse", "HEAD^"));
                assertEquals(evidence.get("expectedTree"), git(copy, "rev-parse", "HEAD^{tree}"));
                assertEquals("", git(copy, "status", "--porcelain"));
                evidence.put("workingCopy", copy.toString());
                evidence.put("revision", revision);
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT status FROM evolution_step")) {
                assertTrue(rows.next());
                assertEquals("SUCCEEDED", rows.getString(1));
            }
        }
    }

    private List<Map<String, Object>> events(String run) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("application.db"));
                var statement = connection.prepareStatement("SELECT sequence,kind,summary FROM agent_event WHERE run_id=? ORDER BY sequence")) {
            statement.setString(1, run);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) { result.add(Map.of("sequence", rows.getLong(1), "kind", rows.getString(2),
                        "summary", rows.getString(3))); }
            }
        }
        return result;
    }

    private JsonNode get(String url) { return http.getForObject(url, JsonNode.class); }
    private JsonNode post(String url, Object body) { return http.postForObject(url, body, JsonNode.class); }
    private void write(Map<String, Object> evidence) throws Exception {
        Files.writeString(root.resolve("evidence.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }
    private static String git(Path directory, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output.strip();
    }
}
