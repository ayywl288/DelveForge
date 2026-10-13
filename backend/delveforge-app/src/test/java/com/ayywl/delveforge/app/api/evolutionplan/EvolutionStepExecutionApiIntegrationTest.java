package com.ayywl.delveforge.app.api.evolutionplan;

import com.ayywl.delveforge.application.port.ai.*;
import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.application.port.workspace.*;
import com.ayywl.delveforge.application.evolution.preparation.CodingProposal;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
class EvolutionStepExecutionApiIntegrationTest {
    private static final Path BASE = Path.of("target", "step-execution-api", UUID.randomUUID().toString()).toAbsolutePath();
    private static final SoftwareAssetId ASSET = new SoftwareAssetId("asset");
    private static final RepositoryProfileId PROFILE = new RepositoryProfileId("profile");
    private static final String PATCH = "--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-old\n+new\n";
    private static final String PLANNING = """
        {"currentState":{"summary":"Existing exports","capabilities":["Export"],"modules":["reports"],"limitations":[]},
         "targetState":{"problem":"Manual","targetProduct":"Scheduled","differentiation":"Personal"},
         "reusableCapabilities":["Export"],"changes":["Add scheduling"],
         "steps":[{"goal":"Schedule reports","scope":"reports","plannedChanges":["Add scheduling"],
         "verificationCriteria":[{"kind":"DIFF_CHECK_PASSES","description":"本次代码差异无空白错误或冲突标记"}]}],
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
    @Autowired StepExecutionCommitPort executionCommits;
    @MockitoBean AiGateway ai;
    @Autowired com.ayywl.delveforge.application.evolution.preparation.PrepareEvolutionStepUseCase preparation;
    @Autowired com.ayywl.delveforge.application.evolution.execution.ExecuteEvolutionStepUseCase execute;
    @Autowired WorkspaceVerificationPort verification;


    private String planId;
    private String stepId;
    private String revision;
    private Path source;
    private Path copy;
    private String codingOutput;

    @BeforeEach void setup(TestInfo test) throws Exception {
        codingOutput = null;
        source = BASE.resolve("source-" + UUID.randomUUID());
        Files.createDirectories(source);
        git(source, "init", "-q");
        Files.writeString(source.resolve("A.java"), "old\n");
        Files.createDirectories(source.resolve("src/main/java"));
        Files.writeString(source.resolve("src/main/java/Report.java"), "public class Report { public static int value() { return 0; } }\n");
        Path repositoryRoot = Path.of("../..").toAbsolutePath().normalize();
        for (String path : List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties")) {
            Path destination = source.resolve(path);
            Files.createDirectories(destination.getParent());
            Files.copy(repositoryRoot.resolve(path), destination);
        }
        Files.writeString(source.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId>
                <version>3.5.16</version><relativePath/></parent>
              <groupId>test</groupId><artifactId>report</artifactId><version>1</version>
              <properties><java.version>21</java.version></properties>
              <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
                <scope>test</scope></dependency></dependencies>
            </project>
            """);
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
        String method = test.getTestMethod().orElseThrow().getName();
        VerificationCriterion.Kind kind = switch (method) {
            case "businessCriterionCannotBeProvenByUnrelatedTechnicalActions" -> VerificationCriterion.Kind.UNSUPPORTED;
            case "executesNewTestInSamePatchWithRealMavenBeforeTrustedPromotion",
                    "focusedTestInCustomSourceDirectoryStillProvesSelectedTests" -> VerificationCriterion.Kind.SELECTED_TESTS_PASS;
            case "normalM3TechnicalStepBuildsAndPromotesTheActualCandidate", "wrongEvidenceTypeFailsBeforeMutation",
                    "completedCompilationFailureIsSaved" -> VerificationCriterion.Kind.BUILD_SUCCEEDS;
            case "focusedTestCannotProveTheFullSuiteCriterion", "fullSuiteCannotBeNarrowedByCandidateConfiguration",
                    "normalFullSuiteProvesTheStructuredCriterion",
                    "confirmedMavenTestFailureIsSavedWithoutTrustPromotion",
                    "crashedTestProcessIsUnknownAndCannotBecomeConfirmedFailure",
                    "customTestSourceDirectoryCannotProveFullSuiteOrPromoteTrust" -> VerificationCriterion.Kind.FULL_TEST_SUITE_PASSES;
            default -> VerificationCriterion.Kind.DIFF_CHECK_PASSES;
        };
        var planningNode = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(PLANNING);
        var stepNode = (com.fasterxml.jackson.databind.node.ObjectNode) planningNode.path("steps").get(0);
        stepNode.put("goal", "增量调整报表工程基础代码");
        stepNode.putArray("plannedChanges").add("调整报表工程代码");
        var criterion = (com.fasterxml.jackson.databind.node.ObjectNode) stepNode.path("verificationCriteria").get(0);
        criterion.put("kind", kind.name());
        criterion.put("description", kind == VerificationCriterion.Kind.UNSUPPORTED ? "Reports run on schedule" : kind.description());
        if (method.equals("failedDiffCheckRetainsCandidateAndBlocksFurtherExecution")) {
            ((com.fasterxml.jackson.databind.node.ArrayNode) planningNode.path("steps")).add(stepNode.deepCopy());
        }
        if (method.equals("unsupportedCriteriaRejectBeforeAnyProviderAndPreservePersistedCheckpoint")) {
            var criteria = stepNode.putArray("verificationCriteria");
            for (VerificationCriterion.Kind technical : List.of(VerificationCriterion.Kind.BUILD_SUCCEEDS,
                    VerificationCriterion.Kind.SELECTED_TESTS_PASS, VerificationCriterion.Kind.DIFF_CHECK_PASSES)) {
                criteria.addObject().put("kind", technical.name()).put("description", technical.description());
            }
            criteria.addObject().put("kind", "UNSUPPORTED").put("description", "Reports run on schedule");
            kind = VerificationCriterion.Kind.BUILD_SUCCEEDS;
        }
        when(ai.generate(any())).thenAnswer(invocation -> {
            AiRequest request = invocation.getArgument(0);
            String instruction = request.messages().getFirst().content();
            assertTrue(instruction.contains("BUILD_SUCCEEDS"));
            assertTrue(instruction.contains("不得提供如何执行的命令或测试类名"));
            assertTrue(instruction.contains("优先规划可由上述技术标准验证的小步骤"));
            assertTrue(instruction.contains("不得为了声称计划可执行而删除、伪造或转换用户验收要求"));
            assertFalse(instruction.contains("ReportTest"));
            assertFalse(planningNode.toString().contains("ReportTest"));
            return mapper.writeValueAsString(planningNode);
        });
        JsonNode proposed = mapper.readTree(mvc.perform(post("/api/evolution-plans/planning")
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"productDirectionId":"direction","baseAssetId":"asset","baseRepositoryProfileId":"profile"}
                """)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        planId = proposed.path("id").asText();
        assertFalse(proposed.path("steps").get(0).has("preconditions"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM evolution_step_section_item WHERE section='preconditions'", Integer.class));
        assertEquals(kind.name(), proposed.path("steps").get(0).path("verificationCriteria").get(0).path("kind").asText());
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


    private void coding(String patch, String action) throws Exception {
        when(ai.generate(any())).thenAnswer(invocation -> {
            AiRequest request = invocation.getArgument(0);
            assertFalse(request.messages().toString().contains("CANARY-DB-PASSWORD-0001"));
            JsonNode payload = mapper.readTree(request.messages().stream().filter(message -> message.role() == AiRole.USER)
                    .findFirst().orElseThrow().content());
            if (payload.has("sourceFiles")) {
                if (codingOutput != null) {
                    return codingOutput;
                }
                VerificationCriterion criterion = plans.findById(new EvolutionPlanId(planId)).orElseThrow()
                        .step(new EvolutionStepId(stepId)).verificationCriteria().getFirst();
                JsonNode suppliedCriterion = payload.path("step").path("verificationCriteria").get(0);
                assertEquals(criterion.kind().name(), suppliedCriterion.path("kind").asText());
                assertEquals(criterion.description(), suppliedCriterion.path("description").asText());
                assertFalse(suppliedCriterion.toString().contains("ReportTest"));
                List<String> paths = java.util.regex.Pattern.compile("(?m)^\\+\\+\\+ b/(.+)$").matcher(patch)
                        .results().map(match -> match.group(1)).toList();
                return mapper.writeValueAsString(Map.of("type", "CHANGE_PROPOSAL", "expectedRevision", revision, "summary", "change",
                        "declaredAffectedPaths", paths, "unifiedDiff", patch, "verificationStrategy",
                        List.of(Map.of("action", action, "target", action.equals("FOCUSED_TEST") ? "ReportTest#report" : "", "criterionIndexes", List.of(1)))));
            }
            List<String> refs = new ArrayList<>();
            for (JsonNode file : payload.path("fileCatalog")) {
                if (patch.contains("a/" + file.path("path").asText() + "\n")) {
                    refs.add(file.path("reference").asText());
                }
            }
            assertFalse(refs.isEmpty());
            return mapper.writeValueAsString(Map.of("focusAreas", List.of(
                    Map.of("label", "code", "fileRefs", refs), Map.of("label", "behavior", "fileRefs", refs),
                    Map.of("label", "test", "fileRefs", refs))));
        });
    }
    private WorkingCopy persistedCopy() {
        EvolutionPlan plan = plans.findById(new EvolutionPlanId(planId)).orElseThrow();
        return copies.findById(new WorkingCopyId(plan.workingCopyId())).orElseThrow();
    }

    @Test void realCandidateDiffVerificationAndRestartReadPreserveTrustedTransition() throws Exception {
        confirm();
        coding(PATCH, "DIFF_CHECK");
        Map<String, String> original = snapshot(source);
        mvc.perform(post(route("execute"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.steps[0].baselineRevision").value(revision))
                .andExpect(jsonPath("$.steps[0].verificationResult.passed").value(true));
        String candidate = git(copy, "rev-parse", "HEAD").trim();
        assertNotEquals(revision, candidate);
        assertEquals(revision, git(copy, "rev-parse", "HEAD^").trim());
        assertEquals(candidate, persistedCopy().currentRevision());
        assertEquals(candidate, persistedCopy().lastVerifiedRevision());
        assertEquals(WorkingCopyStatus.EVOLVING, persistedCopy().status());
        mvc.perform(get("/api/evolution-plans/{id}", planId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].executionResult.candidateRevision").value(candidate))
                .andExpect(jsonPath("$.steps[0].verificationResult.candidateRevision").value(candidate));
        assertEquals(original, snapshot(source));
        assertEquals("1", git(copy, "rev-list", "--count", revision + "..HEAD").trim());
        assertFalse(jdbc.queryForObject("SELECT execution_result FROM evolution_step WHERE id = ?", String.class, stepId).contains(PATCH));
        verify(ai, times(2)).generate(any());
    }

    @Test void executesNewTestInSamePatchWithRealMavenBeforeTrustedPromotion() throws Exception {
        confirm();
        String patch = PATCH + "--- /dev/null\n+++ b/src/test/java/ReportTest.java\n@@ -0,0 +1 @@\n"
                + "+public class ReportTest { @org.junit.jupiter.params.ParameterizedTest"
                + " @org.junit.jupiter.params.provider.ValueSource(ints={1,2}) void report(int input) throws Exception {"
                + " org.junit.jupiter.api.Assertions.assertEquals(\"new\", java.nio.file.Files.readString("
                + "java.nio.file.Path.of(\"A.java\")).strip()); } }\n";
        coding(patch, "FOCUSED_TEST");
        Map<String, String> original = snapshot(source);
        mvc.perform(post(route("execute"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.steps[0].verificationResult.checks[0].action").value("FOCUSED_TEST"))
                .andExpect(jsonPath("$.steps[0].verificationResult.checks[0].target").value("ReportTest#report"));
        assertTrue(plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId))
                .verificationResult().checks().getFirst().summary().contains("executed=2"));
        assertEquals(git(copy, "rev-parse", "HEAD").trim(), persistedCopy().lastVerifiedRevision());
        assertTrue(Files.exists(copy.resolve("src/test/java/ReportTest.java")));
        assertFalse(Files.exists(copy.resolve("target")));
        assertEquals(original, snapshot(source));
        verify(ai, times(2)).generate(any());
    }

    @Test void normalM3TechnicalStepBuildsAndPromotesTheActualCandidate() throws Exception {
        confirm();
        String report = "src/main/java/Report.java";
        String old = "public class Report { public static int value() { return 0; } }\n";
        coding("--- a/" + report + "\n+++ b/" + report + "\n@@ -1 +1 @@\n-" + old + "+" + old.replace("return 0", "return 1"), "BUILD");
        Map<String, String> original = snapshot(source);
        mvc.perform(post(route("execute"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.steps[0].verificationCriteria[0].kind").value("BUILD_SUCCEEDS"))
                .andExpect(jsonPath("$.steps[0].verificationCriteria[0].description").value("项目构建成功"))
                .andExpect(jsonPath("$.steps[0].verificationResult.checks[0].action").value("BUILD"));
        assertEquals(git(copy, "rev-parse", "HEAD").trim(), persistedCopy().lastVerifiedRevision());
        assertEquals(List.of(report), plans.findById(new EvolutionPlanId(planId)).orElseThrow()
                .step(new EvolutionStepId(stepId)).executionResult().affectedPaths());
        assertEquals(original, snapshot(source));
        verify(ai, times(2)).generate(any());
    }

    private String suitePatch(boolean failing) {
        return PATCH + newFilePatch("src/test/java/PassingTest.java", "public class PassingTest {"
                + " @org.junit.jupiter.api.Test void value() {} }\n")
                + newFilePatch("src/test/java/FailingTest.java", "public class FailingTest {"
                + " @org.junit.jupiter.api.Test void value() { org.junit.jupiter.api.Assertions.assertTrue("
                + !failing + "); } }\n");
    }

    private String newFilePatch(String path, String content) {
        return "--- /dev/null\n+++ b/" + path + "\n@@ -0,0 +1 @@\n+" + content;
    }

    @ParameterizedTest @ValueSource(strings = {".mvn/maven.config", ".mvn/jvm.config"})
    void fullSuiteCannotBeNarrowedByCandidateConfiguration(String configuration) throws Exception {
        confirm();
        coding(suitePatch(true) + newFilePatch(configuration, "-Dtest=PassingTest\n"), "TEST");
        Map<String, String> original = snapshot(source);
        mvc.perform(post(route("execute"))).andExpect(status().isConflict());
        EvolutionStep step = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
        assertEquals(EvolutionStepStatus.VERIFYING, step.status());
        assertEquals(revision, step.baselineRevision());
        assertNull(step.verificationResult());
        assertNotEquals(revision, persistedCopy().currentRevision());
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
        assertEquals(original, snapshot(source));
        VerificationFacts rejected = verification.verify(new CandidateVerification(new WorkspaceRef(copy.toString()),
                new WorkspaceRef(source.toString()), revision, persistedCopy().currentRevision(), List.of(
                new CodingProposal.VerificationAction(CodingProposal.Action.TEST, "", List.of(1)))));
        assertFalse(rejected.checks().getFirst().passed());
        assertTrue(rejected.checks().getFirst().summary().contains("NOT_RUN"));
        assertFalse(rejected.checks().getFirst().summary().contains("exit"));
    }

    @Test void normalFullSuiteProvesTheStructuredCriterion() throws Exception {
        confirm();
        coding(suitePatch(false), "TEST");
        mvc.perform(post(route("execute"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("SUCCEEDED"));
        EvolutionStep step = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
        assertTrue(step.verificationResult().checks().getFirst().summary().contains("executed=2"));
        assertEquals(step.executionResult().candidateRevision(), persistedCopy().lastVerifiedRevision());
    }

    private String customTestSourcePatch() throws Exception {
        int closingLine = (int) Files.readString(copy.resolve("pom.xml")).lines().count();
        return "--- a/pom.xml\n+++ b/pom.xml\n@@ -" + closingLine + " +" + closingLine + " @@\n-</project>\n"
                + "+<build><testSourceDirectory>${project.basedir}/selected/src/test/java</testSourceDirectory></build></project>\n";
    }

    @Test void customTestSourceDirectoryCannotProveFullSuiteOrPromoteTrust() throws Exception {
        confirm();
        coding(suitePatch(true) + customTestSourcePatch() + newFilePatch("selected/src/test/java/PassingTest.java",
                "public class PassingTest { @org.junit.jupiter.api.Test void value() {} }\n"), "TEST");
        Map<String, String> original = snapshot(source);
        mvc.perform(post(route("execute"))).andExpect(status().isConflict());
        EvolutionStep step = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
        String candidate = git(copy, "rev-parse", "HEAD").trim();
        assertNotEquals(revision, candidate);
        assertEquals(EvolutionStepStatus.VERIFYING, step.status());
        assertNull(step.verificationResult());
        assertEquals(candidate, step.executionResult().candidateRevision());
        assertEquals(candidate, persistedCopy().currentRevision());
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
        Map<String, String> unchangedCandidate = snapshot(copy);
        VerificationFacts rejected = verification.verify(new CandidateVerification(new WorkspaceRef(copy.toString()),
                new WorkspaceRef(source.toString()), revision, candidate, List.of(
                new CodingProposal.VerificationAction(CodingProposal.Action.TEST, "", List.of(1)))));
        assertFalse(rejected.checks().getFirst().passed(), rejected.toString());
        // Maven 确实成功退出；输入资格被拒绝后，现有适配器不再认证或报告 TEST 的执行数量。
        assertTrue(rejected.checks().getFirst().summary().contains("exit 0"), rejected.toString());
        assertTrue(rejected.checks().getFirst().summary().contains("effective Maven inputs"), rejected.toString());
        assertEquals(unchangedCandidate, snapshot(copy));
        assertEquals(original, snapshot(source));
        reset(ai);
        mvc.perform(post(route("execute"))).andExpect(status().isConflict());
        verifyNoInteractions(ai);
    }

    @Test void focusedTestInCustomSourceDirectoryStillProvesSelectedTests() throws Exception {
        confirm();
        coding(PATCH + customTestSourcePatch() + newFilePatch("selected/src/test/java/ReportTest.java",
                "public class ReportTest { @org.junit.jupiter.api.Test void report() {} }\n")
                + newFilePatch("src/test/java/FailingTest.java", "public class FailingTest {"
                + " @org.junit.jupiter.api.Test void value() { org.junit.jupiter.api.Assertions.fail(); } }\n"), "FOCUSED_TEST");
        Map<String, String> original = snapshot(source);
        mvc.perform(post(route("execute"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("SUCCEEDED"));
        assertEquals(git(copy, "rev-parse", "HEAD").trim(), persistedCopy().lastVerifiedRevision());
        assertEquals(git(copy, "show", "HEAD:pom.xml").replace("\r\n", "\n"),
                Files.readString(copy.resolve("pom.xml")).replace("\r\n", "\n"));
        assertEquals(original, snapshot(source));
    }

    @Test void wrongEvidenceTypeFailsBeforeMutation() throws Exception {
        confirm();
        coding(PATCH, "DIFF_CHECK");
        mvc.perform(post(route("execute"))).andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("CODING_PROPOSAL_INVALID"));
        readyAndUnchanged();
    }

    @Test void focusedTestCannotProveTheFullSuiteCriterion() throws Exception {
        confirm();
        coding(PATCH, "FOCUSED_TEST");
        mvc.perform(post(route("execute"))).andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("CODING_PROPOSAL_INVALID"));
        readyAndUnchanged();
    }

    @ParameterizedTest @ValueSource(strings = {"BUILD", "DIFF_CHECK", "TEST", "FOCUSED_TEST"})
    void businessCriterionCannotBeProvenByUnrelatedTechnicalActions(String action) throws Exception {
        confirm();
        String patch = PATCH + "--- /dev/null\n+++ b/src/test/java/ReportTest.java\n@@ -0,0 +1 @@\n"
                + "+public class ReportTest { @org.junit.jupiter.api.Test void report() {"
                + " org.junit.jupiter.api.Assertions.assertEquals(1, 1); } }\n";
        coding(patch, action);
        Map<String, String> original = snapshot(source);
        mvc.perform(post(route("execute"))).andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("EXECUTION_CRITERIA_UNSUPPORTED"));
        verifyNoInteractions(ai);
        readyAndUnchanged();
        assertFalse(Files.exists(copy.resolve("src/test/java/ReportTest.java")));
        assertEquals(original, snapshot(source));
        assertNull(plans.findById(new EvolutionPlanId(planId)).orElseThrow()
                .step(new EvolutionStepId(stepId)).verificationResult());
    }

    @Test void unsupportedCriteriaRejectBeforeAnyProviderAndPreservePersistedCheckpoint() throws Exception {
        confirm();
        EvolutionPlan beforePlan = plans.findById(new EvolutionPlanId(planId)).orElseThrow();
        List<VerificationCriterion> criteria = beforePlan.step(new EvolutionStepId(stepId)).verificationCriteria();
        Map<String, String> beforeSource = snapshot(source);
        Map<String, String> beforeCopy = snapshot(copy);
        String beforePlanRow = jdbc.queryForMap("SELECT * FROM evolution_plan WHERE id = ?", planId).toString();
        String beforeStepRow = jdbc.queryForMap("SELECT * FROM evolution_step WHERE id = ?", stepId).toString();
        String beforeCopyRow = jdbc.queryForMap("SELECT * FROM working_copy WHERE id = ?", beforePlan.workingCopyId()).toString();

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(route("execute"))).andExpect(status().is(422))
                    .andExpect(jsonPath("$.code").value("EXECUTION_CRITERIA_UNSUPPORTED"));
            verifyNoInteractions(ai);
            readyAndUnchanged();
        }

        EvolutionPlan afterPlan = plans.findById(new EvolutionPlanId(planId)).orElseThrow();
        EvolutionStep afterStep = afterPlan.step(new EvolutionStepId(stepId));
        assertEquals(EvolutionPlanStatus.ACTIVE, afterPlan.status());
        assertEquals(criteria, afterStep.verificationCriteria());
        assertNull(afterStep.executionResult());
        assertNull(afterStep.verificationResult());
        assertEquals(beforePlanRow, jdbc.queryForMap("SELECT * FROM evolution_plan WHERE id = ?", planId).toString());
        assertEquals(beforeStepRow, jdbc.queryForMap("SELECT * FROM evolution_step WHERE id = ?", stepId).toString());
        assertEquals(beforeCopyRow, jdbc.queryForMap("SELECT * FROM working_copy WHERE id = ?", beforePlan.workingCopyId()).toString());
        assertEquals(beforeSource, snapshot(source));
        assertEquals(beforeCopy, snapshot(copy));
        assertEquals(revision, git(source, "rev-parse", "HEAD").trim());
        assertEquals("", git(copy, "status", "--porcelain"));
    }

    @Test void providerFailureIsClassifiedBeforeMutation() throws Exception {
        confirm();
        when(ai.generate(any())).thenThrow(new AiGatewayException("PRIVATE_PROVIDER_CREDENTIAL"));
        mvc.perform(post(route("execute"))).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXECUTION_AI_FAILED"));
        verify(ai, times(1)).generate(any());
        readyAndUnchanged();
        assertNull(plans.findById(new EvolutionPlanId(planId)).orElseThrow()
                .step(new EvolutionStepId(stepId)).executionResult());
    }

    @Test void returnedCodingContentIsClassifiedAsLocalProposalFailure() throws Exception {
        confirm();
        coding(PATCH, "DIFF_CHECK");
        codingOutput = "PRIVATE_INVALID_CODING_RESPONSE";
        mvc.perform(post(route("execute"))).andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("CODING_PROPOSAL_INVALID"));
        verify(ai, times(2)).generate(any());
        readyAndUnchanged();
        assertNull(plans.findById(new EvolutionPlanId(planId)).orElseThrow()
                .step(new EvolutionStepId(stepId)).executionResult());
    }

    @Test void failedDiffCheckRetainsCandidateAndBlocksFurtherExecution() throws Exception {
        confirm();
        coding(PATCH.replace("+new\n", "+new \n"), "DIFF_CHECK");
        Map<String, String> original = snapshot(source);
        mvc.perform(post(route("execute"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("VERIFICATION_FAILED"))
                .andExpect(jsonPath("$.steps[0].verificationResult.passed").value(false));
        EvolutionStep step = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
        assertEquals(EvolutionStepStatus.VERIFICATION_FAILED, step.status());
        assertNotEquals(revision, step.executionResult().candidateRevision());
        assertNotNull(step.verificationResult());
        assertEquals(VerificationResult.Outcome.FAILED, step.verificationResult().checks().getFirst().outcome());
        assertEquals(revision, step.baselineRevision());
        assertEquals(git(copy, "rev-parse", "HEAD").trim(), step.executionResult().candidateRevision());
        assertEquals(WorkingCopyStatus.EVOLVING, persistedCopy().status());
        assertEquals(step.executionResult().candidateRevision(), persistedCopy().currentRevision());
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
        reset(ai);
        mvc.perform(post(route("execute"))).andExpect(status().isConflict());
        String nextId = plans.findById(new EvolutionPlanId(planId)).orElseThrow().steps().get(1).id().value();
        for (String action : List.of("confirm", "execution-preparation", "execute")) {
            mvc.perform(post("/api/evolution-plans/{plan}/steps/{step}/{action}", planId, nextId, action))
                    .andExpect(status().isConflict());
        }
        verifyNoInteractions(ai);
        assertEquals(original, snapshot(source));
    }

    @Test void confirmedMavenTestFailureIsSavedWithoutTrustPromotion() throws Exception {
        confirm();
        coding(suitePatch(true), "TEST");
        Map<String, String> original = snapshot(source);
        mvc.perform(post(route("execute"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("VERIFICATION_FAILED"))
                .andExpect(jsonPath("$.steps[0].verificationResult.checks[0].outcome").value("FAILED"));
        EvolutionStep step = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
        assertFalse(step.verificationResult().passed());
        assertFalse(step.verificationResult().failures().isEmpty());
        assertEquals(git(copy, "rev-parse", "HEAD").trim(), persistedCopy().currentRevision());
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
        assertEquals(original, snapshot(source));
    }

    @Test void legacySuccessWithoutOutcomeRemainsReadableWithoutRewrite() throws Exception {
        confirm();
        coding(PATCH, "DIFF_CHECK");
        mvc.perform(post(route("execute"))).andExpect(status().isOk());
        var json = mapper.readTree(jdbc.queryForObject("SELECT verification_result FROM evolution_step WHERE id=?",
                String.class, stepId));
        json.path("checks").forEach(check -> ((com.fasterxml.jackson.databind.node.ObjectNode) check).remove("outcome"));
        String historical = mapper.writeValueAsString(json);
        jdbc.update("UPDATE evolution_step SET verification_result=? WHERE id=?", historical, stepId);
        mvc.perform(get("/api/evolution-plans/{id}", planId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.steps[0].verificationResult.checks[0].outcome").value("PASSED"));
        assertEquals(historical, jdbc.queryForObject("SELECT verification_result FROM evolution_step WHERE id=?",
                String.class, stepId));
    }

    @Test void completedCompilationFailureIsSaved() throws Exception {
        confirm();
        coding(PATCH + newFilePatch("src/main/java/Broken.java", "public class Broken { int value = missing; }\n"), "BUILD");
        mvc.perform(post(route("execute"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("VERIFICATION_FAILED"))
                .andExpect(jsonPath("$.steps[0].verificationResult.checks[0].outcome").value("FAILED"));
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
        assertEquals(git(copy, "rev-parse", "HEAD").trim(), persistedCopy().currentRevision());
        assertEquals(revision, git(source, "rev-parse", "HEAD").trim());
    }

    @Test void crashedTestProcessIsUnknownAndCannotBecomeConfirmedFailure() throws Exception {
        confirm();
        coding(PATCH + newFilePatch("src/test/java/CrashTest.java", "public class CrashTest {"
                + " @org.junit.jupiter.api.Test void crash() { Runtime.getRuntime().halt(137); } }\n"), "TEST");
        Map<String, String> original = snapshot(source);
        mvc.perform(post(route("execute"))).andExpect(status().isConflict());
        EvolutionStep step = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
        assertEquals(EvolutionStepStatus.VERIFYING, step.status());
        assertNull(step.verificationResult());
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
        assertEquals(git(copy, "rev-parse", "HEAD").trim(), persistedCopy().currentRevision());
        assertEquals(original, snapshot(source));
        reset(ai);
        mvc.perform(post(route("execute"))).andExpect(status().isConflict());
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorization", "direction", "revision", "step"})
    void failureCheckpointRejectsChangedStoredBasis(String change) throws Exception {
        confirm();
        EvolutionPlan ready = plans.findById(new EvolutionPlanId(planId)).orElseThrow();
        WorkingCopy initial = persistedCopy();
        SoftwareAsset asset = assets.findById(ASSET).orElseThrow();
        EvolutionStepId id = new EvolutionStepId(stepId);
        EvolutionPlan executing = ready.copy();
        WorkingCopy evolving = initial.copy();
        executing.startStep(id, evolving, new StepExecutionPolicy());
        executionCommits.commit(ready, id, initial, asset, executing, evolving);
        EvolutionPlan verifying = executing.copy();
        WorkingCopy candidate = evolving.copy();
        // 只测试持久化 Port 的条件提交；合成 revision 不宣称已完成真实 Git 执行。
        verifying.recordExecution(id, candidate, new ExecutionResult("b".repeat(40), List.of("change"),
                List.of("A.java"), List.of(), List.of(), java.time.Instant.now()));
        executionCommits.commit(executing, id, evolving, asset, verifying, candidate);
        EvolutionPlan failed = verifying.copy();
        WorkingCopy retained = candidate.copy();
        failed.failStepVerification(id, retained, new VerificationResult("b".repeat(40), List.of(
                new VerificationResult.Check("DIFF_CHECK", "", List.of(1), VerificationResult.Outcome.FAILED, "Failed")),
                false, List.of("Failed"), "Failed", java.time.Instant.now()));
        switch (change) {
            case "authorization" -> jdbc.update("UPDATE software_asset SET read_permission=0 WHERE id=?", ASSET.value());
            case "direction" -> jdbc.update("UPDATE product_direction SET status='SUPERSEDED' WHERE id='direction'");
            case "revision" -> jdbc.update("UPDATE working_copy SET current_revision=? WHERE id=?", "c".repeat(40), candidate.id().value());
            default -> jdbc.update("UPDATE evolution_step SET verification_result='{}' WHERE id=?", stepId);
        }
        assertThrows(EvolutionLifecycleConflictException.class,
                () -> executionCommits.commit(verifying, id, candidate, asset, failed, retained));
        assertEquals("VERIFYING", jdbc.queryForObject("SELECT status FROM evolution_step WHERE id=?", String.class, stepId));
        assertEquals(revision, jdbc.queryForObject("SELECT last_verified_revision FROM working_copy WHERE id=?", String.class, candidate.id().value()));
        assertEquals(EvolutionStepStatus.VERIFYING, verifying.step(id).status());
        assertNull(verifying.step(id).verificationResult());
        assertEquals("b".repeat(40), candidate.currentRevision());
    }

    @ParameterizedTest @ValueSource(strings = {"start", "candidate", "success", "verification-failure"})
    void sqliteFailureRollsBackWholeCheckpointAndRetainsPreviousState(String phase) throws Exception {
        confirm();
        coding(phase.equals("verification-failure") ? PATCH.replace("+new\n", "+new \n") : PATCH, "DIFF_CHECK");
        EvolutionPlan loaded = plans.findById(new EvolutionPlanId(planId)).orElseThrow();
        WorkingCopy loadedCopy = persistedCopy();
        String condition = switch (phase) {
            case "start" -> "NEW.status = 'EVOLVING' AND OLD.status = 'READY'";
            case "candidate" -> "NEW.current_revision <> OLD.current_revision";
            case "verification-failure" -> "OLD.current_revision <> OLD.last_verified_revision"
                    + " AND NEW.last_verified_revision = OLD.last_verified_revision";
            default -> "NEW.last_verified_revision <> OLD.last_verified_revision";
        };
        jdbc.execute("CREATE TRIGGER inject_execution_failure BEFORE UPDATE ON working_copy WHEN " + condition
                + " BEGIN SELECT RAISE(ABORT, 'injected checkpoint failure'); END");
        try {
            mvc.perform(post(route("execute"))).andExpect(status().isInternalServerError());
        } finally {
            jdbc.execute("DROP TRIGGER inject_execution_failure");
        }
        EvolutionStep step = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
        EvolutionStepStatus status = switch (phase) {
            case "start" -> EvolutionStepStatus.READY;
            case "candidate" -> EvolutionStepStatus.EXECUTING;
            default -> EvolutionStepStatus.VERIFYING;
        };
        assertEquals(status, step.status());
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
        assertNull(step.verificationResult());
        assertEquals(EvolutionStepStatus.READY, loaded.step(new EvolutionStepId(stepId)).status());
        assertNull(loaded.step(new EvolutionStepId(stepId)).baselineRevision());
        assertEquals(revision, loadedCopy.currentRevision());
        if (phase.equals("start")) {
            assertNull(step.baselineRevision());
            assertEquals(revision, git(copy, "rev-parse", "HEAD").trim());
        } else if (phase.equals("candidate")) {
            assertNull(step.executionResult());
            assertEquals(revision, persistedCopy().currentRevision());
            assertNotEquals(revision, git(copy, "rev-parse", "HEAD").trim());
        } else {
            assertEquals(git(copy, "rev-parse", "HEAD").trim(), persistedCopy().currentRevision());
        }
        if (!phase.equals("start")) {
            reset(ai);
            mvc.perform(post(route("execute"))).andExpect(status().isConflict());
            verifyNoInteractions(ai);
        }
    }

    @Test void preparedProposalCannotExecuteAfterRevisionDriftAndUnconfirmedExecutionDoesNotCallAI() throws Exception {
        mvc.perform(post(route("execute"))).andExpect(status().isConflict());
        verifyNoInteractions(ai);
        confirm();
        coding(PATCH, "DIFF_CHECK");
        var validated = preparation.prepare(new EvolutionPlanId(planId), new EvolutionStepId(stepId));
        Files.writeString(copy.resolve("A.java"), "external edit\n");
        assertThrows(com.ayywl.delveforge.application.port.workspace.WorkingCopyBasisChangedException.class,
                () -> execute.execute(validated));
        assertMetadata(EvolutionStepStatus.READY);
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
