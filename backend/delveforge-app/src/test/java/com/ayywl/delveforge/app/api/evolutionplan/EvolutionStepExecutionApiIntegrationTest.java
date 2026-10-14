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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
    @MockitoSpyBean WorkspaceVerificationPort verification;
    @MockitoSpyBean WorkspaceRollbackPort rollback;
    @MockitoSpyBean WorkspaceMutationPort mutation;


    private String planId;
    private String stepId;
    private String revision;
    private Path source;
    private Path copy;
    private String codingOutput;
    private Runnable providerHook;

    @BeforeEach void setup(TestInfo test) throws Exception {
        reset(rollback, mutation);
        codingOutput = null;
        providerHook = null;
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
                    "focusedTestInCustomSourceDirectoryStillProvesSelectedTests",
                    "repairCannotNarrowPreviouslySelectedTests" -> VerificationCriterion.Kind.SELECTED_TESTS_PASS;
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
        doAnswer(invocation -> {
            if (providerHook != null) {
                providerHook.run();
            }
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
                List<String> paths = java.util.regex.Pattern.compile("(?m)^(?:\\+\\+\\+ b/|--- a/)(.+)$").matcher(patch)
                        .results().map(match -> match.group(1)).distinct().toList();
                return mapper.writeValueAsString(Map.of("type", "CHANGE_PROPOSAL", "expectedRevision", payload.path("expectedRevision").asText(), "summary", "change",
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
        }).when(ai).generate(any());
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

    @ParameterizedTest
    @ValueSource(strings = {"initial", "repair", "rollback-failure", "unknown-rollback", "false-success", "asset-revoked", "plan-obsolete"})
    void m5dRealExecutionRejectionPreservesEvidenceHistoryAndHonorsExactRollback(String scenario) throws Exception {
        Map<String, String> original = snapshot(source);
        EvolutionStep previous = null;
        String starting = revision;
        String repairId = UUID.randomUUID().toString();
        if (scenario.equals("repair")) {
            previous = failedCandidate();
            starting = previous.executionResult().candidateRevision();
            coding("--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-new \n+repaired\n", "DIFF_CHECK");
        } else {
            confirm();
            coding(PATCH, "DIFF_CHECK");
        }
        providerHook = () -> {
            try { Files.writeString(copy.resolve(".git/index.lock"), "M5D OWNED FAULT"); }
            catch (Exception failure) { throw new AssertionError(failure); }
        };
        doAnswer(invocation -> {
            try { return invocation.callRealMethod(); }
            catch (ConfirmedExecutionFailureException failure) {
                Files.delete(copy.resolve(".git/index.lock"));
                if (scenario.equals("asset-revoked")) {
                    jdbc.update("UPDATE software_asset SET read_permission=0 WHERE id=?", ASSET.value());
                } else if (scenario.equals("plan-obsolete")) {
                    jdbc.update("UPDATE evolution_plan SET status='SUPERSEDED' WHERE id=?", planId);
                }
                throw failure;
            }
        }).when(mutation).applyCandidate(any());
        if (scenario.equals("rollback-failure")) {
            doAnswer(invocation -> {
                Files.writeString(copy.resolve("user.txt"), "KEEP USER DATA");
                return invocation.callRealMethod();
            }).when(rollback).restoreBaseline(any());
        } else if (scenario.equals("unknown-rollback")) {
            doThrow(new WorkspaceException("result lost")).when(rollback).restoreBaseline(any());
        } else if (scenario.equals("false-success")) {
            doReturn(new WorkspaceRollbackPort.RestoredBaseline("f".repeat(40), java.time.Instant.now()))
                    .when(rollback).restoreBaseline(any());
        }
        clearInvocations(mutation, rollback);
        var request = scenario.equals("repair")
                ? post(route("repair")).contentType(MediaType.APPLICATION_JSON).content(repairBody(starting, repairId))
                : post(route("execute"));
        mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(scenario.equals("plan-obsolete") ? "SUPERSEDED" : "ACTIVE"));
        EvolutionStep step = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
        boolean restored = scenario.equals("initial") || scenario.equals("repair");
        assertEquals(restored ? EvolutionStepStatus.FAILED : EvolutionStepStatus.RECOVERY_REQUIRED, step.status());
        assertNotNull(step.executionFailureResult());
        assertEquals(starting, step.executionFailureResult().startingRevision());
        assertEquals(revision, step.executionFailureResult().baselineRevision());
        assertTrue(step.executionFailureResult().diffComplete());
        assertEquals(restored, step.automaticRollbackResult().restored());
        assertNull(step.executionResult());
        assertNull(step.verificationResult());
        assertNull(step.rollbackAuthorization());
        assertEquals(revision, step.baselineRevision());
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
        assertEquals(restored ? revision : starting, persistedCopy().currentRevision());
        if (previous != null) {
            assertEquals(1, step.repairAuthorizations().size());
            assertEquals(repairId, step.repairAuthorizations().getFirst().id());
            assertEquals(previous.verificationResult(), step.repairAuthorizations().getFirst().previousVerification());
            assertFalse(step.executionFailureResult().diff().isEmpty());
        }
        if (scenario.equals("rollback-failure")) { assertEquals("KEEP USER DATA", Files.readString(copy.resolve("user.txt"))); }
        verify(mutation, times(1)).applyCandidate(any());
        verify(rollback, times(scenario.equals("asset-revoked") || scenario.equals("plan-obsolete") ? 0 : 1))
                .restoreBaseline(any());
        if (scenario.equals("asset-revoked")) {
            jdbc.update("UPDATE software_asset SET read_permission=1 WHERE id=?", ASSET.value());
        }
        reset(ai);
        mvc.perform(post(route("execute"))).andExpect(status().isConflict());
        mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON)
                .content(repairBody(starting, UUID.randomUUID().toString()))).andExpect(status().isConflict());
        verifyNoInteractions(ai);
        assertEquals(original, snapshot(source));
        assertM5dRestartReadsExactlyTheSavedCheckpoint(step);
    }

    @Test
    void m5dConcurrentRequestsCannotReplaySavedAutomaticRollback() throws Exception {
        confirm(); coding(PATCH, "DIFF_CHECK");
        providerHook = () -> {
            try { Files.writeString(copy.resolve(".git/index.lock"), "M5D OWNED FAULT"); }
            catch (Exception failure) { throw new AssertionError(failure); }
        };
        doAnswer(invocation -> {
            try { return invocation.callRealMethod(); }
            catch (ConfirmedExecutionFailureException failure) {
                Files.delete(copy.resolve(".git/index.lock")); throw failure;
            }
        }).when(mutation).applyCandidate(any());
        var prepared = preparation.prepare(new EvolutionPlanId(planId), new EvolutionStepId(stepId));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(30, java.util.concurrent.TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(rollback).restoreBaseline(any());
        var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var firstRequest = workers.submit(() -> execute.execute(prepared));
            assertTrue(entered.await(15, java.util.concurrent.TimeUnit.SECONDS));
            EvolutionStep rolling = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
            assertEquals(EvolutionStepStatus.ROLLING_BACK, rolling.status());
            assertNotNull(rolling.executionFailureResult());
            mvc.perform(post(route("execute"))).andExpect(status().isConflict());
            mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON)
                    .content(repairBody(revision, UUID.randomUUID().toString()))).andExpect(status().isConflict());
            mvc.perform(post(route("rollback")).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("expectedCandidateRevision", revision,
                            "expectedBaselineRevision", revision, "authorizationId", UUID.randomUUID().toString()))))
                    .andExpect(status().isConflict());
            release.countDown();
            assertEquals(EvolutionStepStatus.FAILED, firstRequest.get(60, java.util.concurrent.TimeUnit.SECONDS).step(new EvolutionStepId(stepId)).status());
            verify(rollback, times(1)).restoreBaseline(any());
            assertEquals(EvolutionStepStatus.FAILED, plans.findById(new EvolutionPlanId(planId)).orElseThrow()
                    .step(new EvolutionStepId(stepId)).status());
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    private void assertM5dRestartReadsExactlyTheSavedCheckpoint(EvolutionStep expected) throws Exception {
        try (var restarted = new org.springframework.boot.builder.SpringApplicationBuilder(
                com.ayywl.delveforge.app.DelveForgeApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE).run(
                        "--delveforge.persistence.database-file=" + BASE.resolve("state.db"),
                        "--delveforge.workspace.root=" + BASE.resolve("managed"),
                        "--delveforge.ai.deepseek.api-key=", "--spring.profiles.active=m5d-restart-test")) {
            EvolutionStep actual = restarted.getBean(EvolutionPlanRepository.class)
                    .findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
            assertEquals(expected.status(), actual.status());
            assertEquals(expected.executionFailureResult(), actual.executionFailureResult());
            assertEquals(expected.automaticRollbackResult(), actual.automaticRollbackResult());
            assertEquals(expected.repairAuthorizations(), actual.repairAuthorizations());
            assertNull(actual.executionResult());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"diagnosis", "success", "all-terminal"})
    void m5dSqliteCheckpointFaultDoesNotFakeRollbackOrLoseEarlierEvidence(String phase) throws Exception {
        confirm(); coding(PATCH, "DIFF_CHECK");
        providerHook = () -> {
            try { Files.writeString(copy.resolve(".git/index.lock"), "M5D OWNED FAULT"); }
            catch (Exception failure) { throw new AssertionError(failure); }
        };
        doAnswer(invocation -> {
            try { return invocation.callRealMethod(); }
            catch (ConfirmedExecutionFailureException failure) {
                Files.delete(copy.resolve(".git/index.lock")); throw failure;
            }
        }).when(mutation).applyCandidate(any());
        String condition = phase.equals("diagnosis") ? "NEW.status='ROLLING_BACK'"
                : phase.equals("success") ? "NEW.status='FAILED'" : "NEW.status IN ('FAILED','RECOVERY_REQUIRED')";
        jdbc.execute("CREATE TRIGGER m5d_fault BEFORE UPDATE ON evolution_step WHEN " + condition
                + " BEGIN SELECT RAISE(ABORT,'M5D INJECTED CHECKPOINT'); END");
        try {
            mvc.perform(post(route("execute"))).andExpect(phase.equals("success") ? status().isOk() : status().isInternalServerError());
        } finally { jdbc.execute("DROP TRIGGER m5d_fault"); }
        EvolutionStep step = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
        assertEquals(phase.equals("diagnosis") ? EvolutionStepStatus.EXECUTING
                : phase.equals("success") ? EvolutionStepStatus.RECOVERY_REQUIRED : EvolutionStepStatus.ROLLING_BACK, step.status());
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
        assertNull(step.executionResult());
        if (phase.equals("diagnosis")) {
            assertNull(step.executionFailureResult()); verify(rollback, never()).restoreBaseline(any());
        } else {
            assertNotNull(step.executionFailureResult()); verify(rollback, times(1)).restoreBaseline(any());
            if (phase.equals("all-terminal")) { assertNull(step.automaticRollbackResult()); }
        }
        assertM5dRestartReadsExactlyTheSavedCheckpoint(step);
        mvc.perform(post(route("execute"))).andExpect(status().isConflict());
        assertEquals(step.status(), plans.findById(new EvolutionPlanId(planId)).orElseThrow()
                .step(new EvolutionStepId(stepId)).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"lost-result", "timeout", "interrupted", "crash"})
    void m5dUnknownMutationNeverStartsAutomaticRollback(String fault) throws Exception {
        confirm(); coding(PATCH, "DIFF_CHECK");
        doAnswer(invocation -> {
            if (fault.equals("lost-result")) { invocation.callRealMethod(); }
            throw new WorkspaceException("UNKNOWN " + fault);
        }).when(mutation).applyCandidate(any());
        mvc.perform(post(route("execute"))).andExpect(status().isInternalServerError());
        EvolutionStep step = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
        assertEquals(EvolutionStepStatus.EXECUTING, step.status());
        assertNull(step.executionFailureResult()); assertNull(step.automaticRollbackResult());
        assertNull(step.executionResult()); assertNull(step.verificationResult());
        assertEquals(revision, persistedCopy().currentRevision());
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
        verify(rollback, never()).restoreBaseline(any());
        assertM5dRestartReadsExactlyTheSavedCheckpoint(step);
        mvc.perform(post(route("execute"))).andExpect(status().isConflict());
    }

    @Test
    void repairPassRetainsFailedHistoryAndOriginalBaseline() throws Exception {
        confirm();
        coding(PATCH.replace("+new\n", "+new \n"), "DIFF_CHECK");
        mvc.perform(post(route("execute"))).andExpect(status().isOk());
        EvolutionStep failed = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
        String candidate = failed.executionResult().candidateRevision();
        String authorization = UUID.randomUUID().toString();
        coding("--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-new \n+repaired\n", "DIFF_CHECK");
        mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("expectedCandidateRevision", candidate,
                        "authorizationId", authorization))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.steps[0].baselineRevision").value(revision))
                .andExpect(jsonPath("$.steps[0].repairAuthorizations[0].id").value(authorization))
                .andExpect(jsonPath("$.steps[0].repairAuthorizations[0].previousVerification.passed").value(false));
        assertEquals(candidate, failed.executionResult().candidateRevision());
        assertEquals(EvolutionStepStatus.VERIFICATION_FAILED, failed.status());
        assertEquals(revision, git(source, "rev-parse", "HEAD").trim());
        assertEquals(git(copy, "rev-parse", "HEAD").trim(), persistedCopy().lastVerifiedRevision());
    }

    private EvolutionStep failedCandidate() throws Exception {
        confirm();
        coding(PATCH.replace("+new\n", "+new \n"), "DIFF_CHECK");
        mvc.perform(post(route("execute"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("VERIFICATION_FAILED"));
        return plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
    }

    private String repairBody(String candidate, String authorization) throws Exception {
        return mapper.writeValueAsString(Map.of("expectedCandidateRevision", candidate, "authorizationId", authorization));
    }

    private String rollbackBody(String candidate, String baseline, String authorization) throws Exception {
        return mapper.writeValueAsString(Map.of("expectedCandidateRevision", candidate,
                "expectedBaselineRevision", baseline, "authorizationId", authorization));
    }

    private EvolutionStep currentStep() {
        return plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
    }

    @Test
    void rollbackAfterMultipleRepairsRestoresExactFirstBaselineAndKeepsHistory() throws Exception {
        EvolutionStep first = failedCandidate();
        Map<String, String> original = snapshot(source);
        for (int index = 1; index <= 2; index++) {
            String content = Files.readString(source.resolve("src/main/java/Report.java"));
            coding("--- a/src/main/java/Report.java\n+++ b/src/main/java/Report.java\n@@ -1 +1 @@\n-"
                    + content.replace("return 0", "return " + (index - 1))
                    + "+" + content.replace("return 0", "return " + index), "DIFF_CHECK");
            mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON)
                    .content(repairBody(persistedCopy().currentRevision(), UUID.randomUUID().toString())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.steps[0].status").value("VERIFICATION_FAILED"));
        }
        EvolutionStep latest = currentStep();
        String candidate = latest.executionResult().candidateRevision();
        String authorization = UUID.randomUUID().toString();
        String body = rollbackBody(candidate, revision, authorization);
        reset(ai);
        mvc.perform(post(route("rollback")).contentType(MediaType.APPLICATION_JSON)
                .content(rollbackBody(candidate, revision, latest.repairAuthorizations().getFirst().id())))
                .andExpect(status().isConflict());
        mvc.perform(post(route("rollback")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.steps[0].status").value("FAILED"))
                .andExpect(jsonPath("$.steps[0].rollbackResult.restored").value(true));
        assertEquals(revision, git(copy, "rev-parse", "HEAD").trim());
        assertEquals(git(source, "rev-parse", "HEAD^{tree}").trim(), git(copy, "write-tree").trim());
        assertTrue(git(copy, "status", "--porcelain", "--untracked-files=all", "--ignored").isBlank());
        assertEquals(revision, persistedCopy().currentRevision());
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
        assertEquals(WorkingCopyStatus.EVOLVING, persistedCopy().status());
        assertEquals(latest.executionResult(), currentStep().executionResult());
        assertEquals(latest.verificationResult(), currentStep().verificationResult());
        assertEquals(latest.repairAuthorizations(), currentStep().repairAuthorizations());
        assertEquals(first.verificationResult(), currentStep().repairAuthorizations().getFirst().previousVerification());
        assertEquals(original, snapshot(source));
        mvc.perform(post(route("rollback")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(post(route("execute"))).andExpect(status().isConflict());
        verifyNoInteractions(ai);
        verify(rollback, times(1)).restoreBaseline(any());
        assertRollbackRestart(EvolutionStepStatus.FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"candidate", "baseline", "missing-authorization", "dirty", "extra", "ignored", "permission"})
    void rollbackRejectsStaleOrUnsafeRequestBeforeAnyWrite(String reason) throws Exception {
        EvolutionStep failed = failedCandidate();
        String candidate = failed.executionResult().candidateRevision();
        String body = rollbackBody(reason.equals("candidate") ? revision : candidate,
                reason.equals("baseline") ? candidate : revision, UUID.randomUUID().toString());
        switch (reason) {
            case "missing-authorization" -> body = "{\"expectedCandidateRevision\":\"" + candidate
                    + "\",\"expectedBaselineRevision\":\"" + revision + "\"}";
            case "dirty" -> Files.writeString(copy.resolve("A.java"), "USER CONTENT\n");
            case "extra" -> Files.writeString(copy.resolve("user.txt"), "USER CONTENT\n");
            case "ignored" -> {
                Files.createDirectories(copy.resolve(".git/info"));
                Files.writeString(copy.resolve(".git/info/exclude"), "user.txt\n");
                Files.writeString(copy.resolve("user.txt"), "USER CONTENT\n");
            }
            case "permission" -> jdbc.update("UPDATE software_asset SET read_permission=0 WHERE id=?", ASSET.value());
            default -> { }
        }
        Map<String, String> before = snapshot(copy);
        reset(ai);
        mvc.perform(post(route("rollback")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(reason.equals("missing-authorization") ? 400 : 409));
        assertNull(currentStep().rollbackAuthorization());
        assertEquals(failed.verificationResult(), currentStep().verificationResult());
        assertEquals(before, snapshot(copy));
        assertEquals(candidate, git(copy, "rev-parse", "HEAD").trim());
        verify(rollback, never()).restoreBaseline(any());
        verifyNoInteractions(ai);
    }

    @Test
    void rollbackRemovesOnlyCandidateTrackedAdditionAndRestoresDeletedBaselineFile() throws Exception {
        confirm();
        Map<String, String> original = snapshot(source);
        Map<String, String> baselineFiles = workingTreeSnapshot(copy);
        String baselineReport = Files.readString(copy.resolve("src/main/java/Report.java"));
        String report = Files.readString(source.resolve("src/main/java/Report.java"));
        coding(PATCH.replace("+new\n", "+new \n") + newFilePatch("candidate.txt", "Candidate owned file\n")
                + "--- a/src/main/java/Report.java\n+++ /dev/null\n@@ -1 +0,0 @@\n-" + report, "DIFF_CHECK");
        mvc.perform(post(route("execute"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("VERIFICATION_FAILED"));
        assertFalse(Files.exists(copy.resolve("src/main/java/Report.java")));
        assertTrue(Files.exists(copy.resolve("candidate.txt")));
        String candidate = persistedCopy().currentRevision();
        reset(ai);
        mvc.perform(post(route("rollback")).contentType(MediaType.APPLICATION_JSON)
                .content(rollbackBody(candidate, revision, UUID.randomUUID().toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.steps[0].status").value("FAILED"));
        assertFalse(Files.exists(copy.resolve("candidate.txt")));
        assertEquals(baselineReport, Files.readString(copy.resolve("src/main/java/Report.java")));
        assertEquals(baselineFiles, workingTreeSnapshot(copy));
        assertEquals(original, snapshot(source));
        verifyNoInteractions(ai);
    }

    private void installSentinelFilter(String driver) throws Exception {
        Files.createDirectories(copy.resolve(".git/info"));
        Files.writeString(copy.resolve(".git/info/attributes"), "A.java filter=" + driver + "\n");
        git(copy, "config", "filter." + driver + ".clean", "printf 'old\\n'; printf hit >> .git/filter-ran");
        Files.writeString(copy.resolve("A.java"), "evil\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"execute:unset", "execute:unspecified", "repair:unset", "repair:unspecified",
            "rollback:unset", "rollback:unspecified"})
    void sharedCheckerRejectsSentinelFiltersBeforeAnyNewAuthorization(String scenario) throws Exception {
        String[] parts = scenario.split(":");
        String action = parts[0];
        EvolutionStep failed = action.equals("execute") ? null : failedCandidate();
        if (failed == null) {
            confirm();
        }
        String candidate = persistedCopy().currentRevision();
        installSentinelFilter(parts[1]);
        Map<String, String> original = snapshot(source);
        reset(ai);
        var request = post(route(action));
        if (!action.equals("execute")) {
            request.contentType(MediaType.APPLICATION_JSON).content(action.equals("repair")
                    ? repairBody(candidate, UUID.randomUUID().toString())
                    : rollbackBody(candidate, revision, UUID.randomUUID().toString()));
        }
        mvc.perform(request).andExpect(status().isConflict());
        EvolutionStep after = currentStep();
        assertEquals(failed == null ? EvolutionStepStatus.READY : EvolutionStepStatus.VERIFICATION_FAILED, after.status());
        assertTrue(after.repairAuthorizations().isEmpty());
        assertNull(after.rollbackAuthorization());
        assertFalse(Files.exists(copy.resolve(".git/filter-ran")));
        assertEquals("evil\n", Files.readString(copy.resolve("A.java")));
        assertEquals(original, snapshot(source));
        assertEquals(candidate, persistedCopy().currentRevision());
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unset", "unspecified"})
    void repairSentinelFilterAfterGrantKeepsUnknownCheckpointWithoutSideEffects(String driver) throws Exception {
        EvolutionStep failed = failedCandidate();
        String candidate = failed.executionResult().candidateRevision();
        coding("--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-new \n+repaired\n", "DIFF_CHECK");
        providerHook = () -> {
            try {
                installSentinelFilter(driver);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        };
        String authorization = UUID.randomUUID().toString();
        mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON)
                .content(repairBody(candidate, authorization))).andExpect(status().isConflict());
        assertEquals(EvolutionStepStatus.EXECUTING, currentStep().status());
        assertEquals(authorization, currentStep().repairAuthorizations().getLast().id());
        assertNull(currentStep().executionResult());
        assertEquals(candidate, persistedCopy().currentRevision());
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
        assertFalse(Files.exists(copy.resolve(".git/filter-ran")));
        assertEquals("evil\n", Files.readString(copy.resolve("A.java")));
    }

    private void hideUnchangedTrackedContent() throws Exception {
        git(copy, "config", "core.trustctime", "false");
        git(copy, "config", "core.checkstat", "minimal");
        Path unchanged = copy.resolve("src/main/java/Report.java");
        java.nio.file.attribute.FileTime old = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 10000);
        Files.setLastModifiedTime(unchanged, old);
        git(copy, "update-index", "--refresh");
        Files.writeString(unchanged, Files.readString(unchanged).replace("return 0", "return 9"));
        Files.setLastModifiedTime(unchanged, old);
        assertEquals("", git(copy, "status", "--porcelain"));
        assertEquals("", git(copy, "diff", "--no-ext-diff"));
    }

    @Test
    void rollbackRejectsHiddenTrackedTamperingBeforeAuthorization() throws Exception {
        EvolutionStep failed = failedCandidate();
        String candidate = failed.executionResult().candidateRevision();
        hideUnchangedTrackedContent();
        Map<String, String> untouchedCopy = snapshot(copy);
        Map<String, String> original = snapshot(source);
        reset(ai);
        mvc.perform(post(route("rollback")).contentType(MediaType.APPLICATION_JSON)
                .content(rollbackBody(candidate, revision, UUID.randomUUID().toString())))
                .andExpect(status().isConflict());
        EvolutionStep rejected = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
        assertEquals(EvolutionStepStatus.VERIFICATION_FAILED, rejected.status());
        assertNull(rejected.rollbackAuthorization());
        assertNull(rejected.rollbackResult());
        assertEquals(candidate, persistedCopy().currentRevision());
        assertEquals(untouchedCopy, snapshot(copy));
        assertEquals(original, snapshot(source));
        verify(rollback, never()).restoreBaseline(any());
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @ValueSource(strings = {"index-lock", "head-lock", "extra-after-grant", "ignored-after-grant", "dirty-after-grant",
            "unknown", "wrong-success", "post-restore-change", "revoke-after-restore", "replace-baseline", "graft-view",
            "hidden-after-grant", "hidden-post-restore-change", "filter-unset-after-grant", "filter-unspecified-after-grant"})
    void rollbackFailureOrUnknownBlocksWithoutDeletingUserFiles(String fault) throws Exception {
        EvolutionStep failed = failedCandidate();
        String candidate = failed.executionResult().candidateRevision();
        Map<String, String> original = snapshot(source);
        doAnswer(invocation -> {
            switch (fault) {
                case "index-lock" -> Files.writeString(copy.resolve(".git/index.lock"), "TEST OWNED LOCK");
                case "head-lock" -> Files.writeString(copy.resolve(".git/HEAD.lock"), "TEST OWNED LOCK");
                case "extra-after-grant", "ignored-after-grant" -> {
                    if (fault.equals("ignored-after-grant")) {
                        Files.createDirectories(copy.resolve(".git/info"));
                        Files.writeString(copy.resolve(".git/info/exclude"), "user.txt\n");
                    }
                    Files.writeString(copy.resolve("user.txt"), "USER CONTENT\n");
                }
                case "dirty-after-grant" -> Files.writeString(copy.resolve("A.java"), "USER CONTENT\n");
                case "hidden-after-grant" -> hideUnchangedTrackedContent();
                case "filter-unset-after-grant", "filter-unspecified-after-grant" ->
                        installSentinelFilter(fault.contains("unspecified") ? "unspecified" : "unset");
                case "replace-baseline" -> {
                    String tree = git(copy, "rev-parse", "HEAD^{tree}").trim();
                    String replacement = git(copy, "-c", "user.name=Fixture", "-c", "user.email=fixture@invalid",
                            "commit-tree", tree, "-m", "replacement without parents").trim();
                    git(copy, "replace", revision, replacement);
                }
                case "graft-view" -> {
                    Files.createDirectories(copy.resolve(".git/info"));
                    Files.writeString(copy.resolve(".git/info/grafts"), revision + "\n");
                }
                case "unknown" -> throw new WorkspaceException("injected process outcome unknown");
                case "wrong-success" -> {
                    return new WorkspaceRollbackPort.RestoredBaseline(revision, java.time.Instant.now());
                }
                default -> { }
            }
            Object restored = invocation.callRealMethod();
            if (fault.equals("post-restore-change")) {
                Files.writeString(copy.resolve("A.java"), "USER CONTENT\n");
            }
            if (fault.equals("hidden-post-restore-change")) {
                hideUnchangedTrackedContent();
            }
            if (fault.equals("revoke-after-restore")) {
                jdbc.update("UPDATE software_asset SET read_permission=0 WHERE id=?", ASSET.value());
            }
            return restored;
        }).when(rollback).restoreBaseline(any());
        String body = rollbackBody(candidate, revision, UUID.randomUUID().toString());
        reset(ai);
        mvc.perform(post(route("rollback")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.steps[0].status").value("RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.steps[0].rollbackResult.restored").value(false));
        assertEquals(WorkingCopyStatus.RECOVERY_REQUIRED, persistedCopy().status());
        assertEquals(candidate, persistedCopy().currentRevision());
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
        assertEquals(failed.executionResult(), currentStep().executionResult());
        assertEquals(failed.verificationResult(), currentStep().verificationResult());
        assertFalse(currentStep().rollbackResult().diagnostic().isBlank());
        assertEquals(original, snapshot(source));
        if (fault.contains("extra") || fault.contains("ignored")) {
            assertEquals("USER CONTENT\n", Files.readString(copy.resolve("user.txt")));
        }
        if (fault.contains("dirty") || fault.equals("post-restore-change")) {
            assertEquals("USER CONTENT\n", Files.readString(copy.resolve("A.java")));
        }
        if (fault.equals("replace-baseline") || fault.equals("graft-view")) {
            assertTrue(currentStep().rollbackResult().diagnostic().contains("OBJECT_VIEW"));
            assertEquals(candidate, git(copy, "rev-parse", "HEAD").trim());
            assertTrue(fault.equals("replace-baseline")
                    ? !git(copy, "for-each-ref", "--format=%(refname)", "refs/replace/").isBlank()
                    : Files.exists(copy.resolve(".git/info/grafts")));
        }
        if (fault.startsWith("filter-")) {
            assertFalse(Files.exists(copy.resolve(".git/filter-ran")));
            assertEquals("evil\n", Files.readString(copy.resolve("A.java")));
        }
        mvc.perform(post(route("rollback")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON)
                .content(repairBody(candidate, UUID.randomUUID().toString()))).andExpect(status().isConflict());
        verify(rollback, times(1)).restoreBaseline(any());
        verifyNoInteractions(ai);
        if (fault.equals("unknown")) {
            assertRollbackRestart(EvolutionStepStatus.RECOVERY_REQUIRED);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorization", "success", "all-final"})
    void rollbackSqliteFailureNeverExposesPartialCheckpointOrFalseSuccess(String phase) throws Exception {
        EvolutionStep failed = failedCandidate();
        String candidate = failed.executionResult().candidateRevision();
        EvolutionPlan loaded = plans.findById(new EvolutionPlanId(planId)).orElseThrow();
        WorkingCopy loadedCopy = persistedCopy();
        String condition = phase.equals("authorization") ? "ROLLING_BACK" : phase.equals("success") ? "FAILED" : "FAILED','RECOVERY_REQUIRED";
        jdbc.execute("CREATE TRIGGER m5c_checkpoint_failure BEFORE UPDATE ON working_copy WHEN EXISTS"
                + " (SELECT 1 FROM evolution_step WHERE id='" + stepId + "' AND status IN ('" + condition + "'))"
                + " BEGIN SELECT RAISE(ABORT, 'injected M5-C checkpoint failure'); END");
        String body = rollbackBody(candidate, revision, UUID.randomUUID().toString());
        reset(ai);
        try {
            mvc.perform(post(route("rollback")).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().is(phase.equals("success") ? 200 : 500));
        } finally {
            jdbc.execute("DROP TRIGGER m5c_checkpoint_failure");
        }
        assertEquals(EvolutionStepStatus.VERIFICATION_FAILED, loaded.step(new EvolutionStepId(stepId)).status());
        assertEquals(candidate, loadedCopy.currentRevision());
        assertEquals(candidate, persistedCopy().currentRevision());
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
        assertEquals(phase.equals("authorization") ? candidate : revision, git(copy, "rev-parse", "HEAD").trim());
        assertEquals(phase.equals("authorization") ? EvolutionStepStatus.VERIFICATION_FAILED
                : phase.equals("success") ? EvolutionStepStatus.RECOVERY_REQUIRED : EvolutionStepStatus.ROLLING_BACK,
                currentStep().status());
        assertEquals(failed.verificationResult(), currentStep().verificationResult());
        assertEquals(phase.equals("success") ? WorkingCopyStatus.RECOVERY_REQUIRED : WorkingCopyStatus.EVOLVING,
                persistedCopy().status());
        verify(rollback, times(phase.equals("authorization") ? 0 : 1)).restoreBaseline(any());
        verifyNoInteractions(ai);
        if (phase.equals("all-final")) {
            assertNull(currentStep().rollbackResult());
            assertRollbackRestart(EvolutionStepStatus.ROLLING_BACK);
            mvc.perform(post(route("rollback")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
            mvc.perform(post(route("execute"))).andExpect(status().isConflict());
            verify(rollback, times(1)).restoreBaseline(any());
        }
    }

    @Test
    void rollbackConcurrentRequestAndRepairCannotReuseGrantedAuthorization() throws Exception {
        String candidate = failedCandidate().executionResult().candidateRevision();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("Rollback test barrier timed out");
            }
            return invocation.callRealMethod();
        }).when(rollback).restoreBaseline(any());
        String body = rollbackBody(candidate, revision, UUID.randomUUID().toString());
        reset(ai);
        try (var workers = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = workers.submit(() -> mvc.perform(post(route("rollback"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()));
            try {
                assertTrue(entered.await(15, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(EvolutionStepStatus.ROLLING_BACK, currentStep().status());
                mvc.perform(post(route("rollback")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
                mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON)
                        .content(repairBody(candidate, UUID.randomUUID().toString()))).andExpect(status().isConflict());
            } finally {
                release.countDown();
            }
            first.get(60, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(EvolutionStepStatus.FAILED, currentStep().status());
        verify(rollback, times(1)).restoreBaseline(any());
        verifyNoInteractions(ai);
    }

    @Test
    void rollbackInterruptedOperationRecordsRecoveryBeforeRestoringInterruptFlag() throws Exception {
        String candidate = failedCandidate().executionResult().candidateRevision();
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new WorkspaceException("injected interrupted process");
        }).when(rollback).restoreBaseline(any());
        reset(ai);
        boolean interrupted;
        try {
            mvc.perform(post(route("rollback")).contentType(MediaType.APPLICATION_JSON)
                    .content(rollbackBody(candidate, revision, UUID.randomUUID().toString())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.steps[0].status").value("RECOVERY_REQUIRED"));
            interrupted = Thread.currentThread().isInterrupted();
        } finally {
            Thread.interrupted();
        }
        assertTrue(interrupted);
        assertEquals(WorkingCopyStatus.RECOVERY_REQUIRED, persistedCopy().status());
        verify(rollback, times(1)).restoreBaseline(any());
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXECUTING", "VERIFYING"})
    void rollbackCannotReinterpretUnknownExecutionOrVerificationAsConfirmedFailure(String status) throws Exception {
        String candidate = failedCandidate().executionResult().candidateRevision();
        jdbc.update("UPDATE evolution_step SET status=?, verification_result=NULL WHERE id=?", status, stepId);
        if (status.equals("EXECUTING")) {
            jdbc.update("UPDATE evolution_step SET execution_result=NULL WHERE id=?", stepId);
        }
        reset(ai);
        mvc.perform(post(route("rollback")).contentType(MediaType.APPLICATION_JSON)
                .content(rollbackBody(candidate, revision, UUID.randomUUID().toString()))).andExpect(status().isConflict());
        assertEquals(EvolutionStepStatus.valueOf(status), currentStep().status());
        assertNull(currentStep().rollbackAuthorization());
        assertEquals(candidate, git(copy, "rev-parse", "HEAD").trim());
        verify(rollback, never()).restoreBaseline(any());
        verifyNoInteractions(ai);
    }

    private void assertRollbackRestart(EvolutionStepStatus status) {
        EvolutionStep before = currentStep();
        WorkingCopy copyBefore = persistedCopy();
        // 新上下文与连接回读，不宣称实际强杀了 OS 进程；启动没有补偿或 Git 重放。
        try (var restarted = new org.springframework.boot.builder.SpringApplicationBuilder(
                com.ayywl.delveforge.app.DelveForgeApplication.class).web(org.springframework.boot.WebApplicationType.NONE)
                .run("--delveforge.persistence.database-file=" + BASE.resolve("state.db"),
                        "--delveforge.workspace.root=" + BASE.resolve("managed"),
                        "--delveforge.ai.deepseek.base-url=http://127.0.0.1:1", "--delveforge.ai.deepseek.api-key=",
                        "--spring.profiles.active=m5c-restart-test")) {
            EvolutionStep restored = restarted.getBean(EvolutionPlanRepository.class).findById(new EvolutionPlanId(planId))
                    .orElseThrow().step(new EvolutionStepId(stepId));
            assertEquals(status, restored.status());
            assertEquals(before.rollbackAuthorization(), restored.rollbackAuthorization());
            assertEquals(before.rollbackResult(), restored.rollbackResult());
            assertEquals(before.repairAuthorizations(), restored.repairAuthorizations());
            assertEquals(before.executionResult(), restored.executionResult());
            assertEquals(before.verificationResult(), restored.verificationResult());
            WorkingCopy restoredCopy = restarted.getBean(WorkingCopyRepository.class).findById(copyBefore.id()).orElseThrow();
            assertEquals(copyBefore.status(), restoredCopy.status());
            assertEquals(copyBefore.currentRevision(), restoredCopy.currentRevision());
            assertEquals(copyBefore.lastVerifiedRevision(), restoredCopy.lastVerifiedRevision());
        }
    }

    @Test
    void repairAgainFailThenPassKeepsAllFailuresAndChecksTheWholeStepDiff() throws Exception {
        EvolutionStep first = failedCandidate();
        String b = first.executionResult().candidateRevision();
        Map<String, String> original = snapshot(source);
        String authorization1 = UUID.randomUUID().toString();
        String report = Files.readString(source.resolve("src/main/java/Report.java"));
        coding("--- a/src/main/java/Report.java\n+++ b/src/main/java/Report.java\n@@ -1 +1 @@\n-"
                + report + "+" + report.replace("return 0", "return 1"), "DIFF_CHECK");
        mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON).content(repairBody(b, authorization1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.steps[0].status").value("VERIFICATION_FAILED"));
        EvolutionStep again = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
        String c = again.executionResult().candidateRevision();
        assertNotEquals(b, c);
        assertEquals(b, git(copy, "rev-parse", "HEAD^").trim());
        // B..C 是干净的 Repair Diff；A..C 仍含此前空白错误，所以必须继续 FAIL。
        git(copy, "diff", "--check", b, c, "--");
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
        assertEquals(first.verificationResult(), again.repairAuthorizations().getFirst().previousVerification());
        reset(ai);
        mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON).content(repairBody(c, authorization1)))
                .andExpect(status().isConflict());
        mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON)
                .content(repairBody(b, UUID.randomUUID().toString()))).andExpect(status().isConflict());
        verifyNoInteractions(ai);
        coding("--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-new \n+repaired\n", "DIFF_CHECK");
        mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON)
                .content(repairBody(c, UUID.randomUUID().toString()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("SUCCEEDED"));
        EvolutionStep succeeded = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
        assertEquals(List.of(b, c), succeeded.repairAuthorizations().stream().map(RepairAuthorization::candidateRevision).toList());
        assertEquals(again.verificationResult(), succeeded.repairAuthorizations().get(1).previousVerification());
        assertEquals(revision, succeeded.baselineRevision());
        assertEquals(git(copy, "rev-parse", "HEAD").trim(), persistedCopy().lastVerifiedRevision());
        assertEquals(original, snapshot(source));
    }

    @Test
    void repairCannotNarrowPreviouslySelectedTests() throws Exception {
        confirm();
        String failingTest = "public class ReportTest { @org.junit.jupiter.api.Test void report() {"
                + " org.junit.jupiter.api.Assertions.fail(\"original failure\"); } }\n";
        coding(PATCH + newFilePatch("src/test/java/ReportTest.java", failingTest), "FOCUSED_TEST");
        mvc.perform(post(route("execute"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("VERIFICATION_FAILED"));
        String b = persistedCopy().currentRevision();
        String patch = "--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-new\n+repair\n"
                + newFilePatch("src/test/java/NewTest.java", "public class NewTest { @org.junit.jupiter.api.Test void passes() {} }\n");
        coding(patch, "FOCUSED_TEST");
        codingOutput = mapper.writeValueAsString(Map.of("type", "CHANGE_PROPOSAL", "expectedRevision", b,
                "summary", "change", "declaredAffectedPaths", List.of("A.java", "src/test/java/NewTest.java"),
                "unifiedDiff", patch, "verificationStrategy", List.of(Map.of("action", "FOCUSED_TEST",
                        "target", "NewTest", "criterionIndexes", List.of(1)))));
        mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON)
                .content(repairBody(b, UUID.randomUUID().toString()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].status").value("VERIFICATION_FAILED"))
                .andExpect(jsonPath("$.steps[0].verificationResult.checks[0].target").value("ReportTest#report"))
                .andExpect(jsonPath("$.steps[0].verificationResult.checks[1].outcome").value("NOT_RUN"));
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
    }

    @Test
    void repairRequiresExplicitAuthorizationAndCandidateBeforeProviderCalls() throws Exception {
        EvolutionStep failed = failedCandidate();
        reset(ai);
        for (String body : List.of("{}", "{\"authorizationId\":\"wrong\"}", "{\"expectedCandidateRevision\":null}")) {
            mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(route("repair"))).andExpect(status().isBadRequest());
        mvc.perform(post(route("execute"))).andExpect(status().isConflict());
        mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON)
                .content(repairBody(revision, UUID.randomUUID().toString()))).andExpect(status().isConflict());
        verifyNoInteractions(ai);
        EvolutionStep unchanged = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
        assertEquals(failed.verificationResult(), unchanged.verificationResult());
        assertTrue(unchanged.repairAuthorizations().isEmpty());
    }

    @Test
    void repairProviderFailureRetainsAuthorizationAndCannotBeReplayed() throws Exception {
        EvolutionStep failed = failedCandidate();
        String b = failed.executionResult().candidateRevision();
        reset(ai);
        doThrow(new AiGatewayException("injected Provider failure")).when(ai).generate(any());
        String body = repairBody(b, UUID.randomUUID().toString());
        mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("EXECUTION_AI_FAILED"));
        EvolutionStep current = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
        assertEquals(EvolutionStepStatus.EXECUTING, current.status());
        assertNull(current.executionResult());
        assertEquals(1, current.repairAuthorizations().size());
        assertEquals(failed.verificationResult(), current.repairAuthorizations().getFirst().previousVerification());
        mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        verify(ai, times(1)).generate(any());
        assertEquals(b, persistedCopy().currentRevision());
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorization", "candidate", "verification"})
    void repairCheckpointFailureRollsBackDatabaseAndPreservesPreviousHistory(String phase) throws Exception {
        EvolutionStep failed = failedCandidate();
        String b = failed.executionResult().candidateRevision();
        EvolutionPlan loaded = plans.findById(new EvolutionPlanId(planId)).orElseThrow();
        WorkingCopy before = persistedCopy();
        reset(ai);
        coding("--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-new \n+repaired\n", "DIFF_CHECK");
        String condition = switch (phase) {
            case "authorization" -> "NEW.current_revision = OLD.current_revision"
                    + " AND OLD.current_revision <> OLD.last_verified_revision"
                    + " AND EXISTS(SELECT 1 FROM evolution_step WHERE status='EXECUTING')";
            case "candidate" -> "NEW.current_revision <> OLD.current_revision";
            default -> "NEW.last_verified_revision <> OLD.last_verified_revision";
        };
        jdbc.execute("CREATE TRIGGER inject_repair_failure BEFORE UPDATE ON working_copy WHEN " + condition
                + " BEGIN SELECT RAISE(ABORT, 'injected Repair checkpoint failure'); END");
        try {
            mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON)
                    .content(repairBody(b, UUID.randomUUID().toString()))).andExpect(status().isInternalServerError());
        } finally {
            jdbc.execute("DROP TRIGGER inject_repair_failure");
        }
        EvolutionStep after = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
        assertEquals(EvolutionStepStatus.VERIFICATION_FAILED, loaded.step(new EvolutionStepId(stepId)).status());
        assertTrue(loaded.step(new EvolutionStepId(stepId)).repairAuthorizations().isEmpty());
        assertEquals(b, before.currentRevision());
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
        if (phase.equals("authorization")) {
            assertEquals(EvolutionStepStatus.VERIFICATION_FAILED, after.status());
            assertEquals(failed.verificationResult(), after.verificationResult());
            assertTrue(after.repairAuthorizations().isEmpty());
            assertEquals(b, git(copy, "rev-parse", "HEAD").trim());
            verifyNoInteractions(ai);
        } else {
            assertEquals(1, after.repairAuthorizations().size());
            assertEquals(failed.verificationResult(), after.repairAuthorizations().getFirst().previousVerification());
            assertEquals(phase.equals("candidate") ? EvolutionStepStatus.EXECUTING : EvolutionStepStatus.VERIFYING, after.status());
            assertNull(after.verificationResult());
            assertEquals(phase.equals("candidate") ? b : git(copy, "rev-parse", "HEAD").trim(), persistedCopy().currentRevision());
        }
    }

    @Test
    void repairConcurrentRequestCannotReuseAuthorizationOrStartAnotherProviderCall() throws Exception {
        String b = failedCandidate().executionResult().candidateRevision();
        reset(ai);
        coding("--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-new \n+repaired\n", "DIFF_CHECK");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var firstCall = new java.util.concurrent.atomic.AtomicBoolean(true);
        providerHook = () -> {
            if (firstCall.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    if (!release.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new AssertionError("Repair Provider test barrier timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        };
        String body = repairBody(b, UUID.randomUUID().toString());
        try (var workers = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var firstRequest = workers.submit(() -> mvc.perform(post(route("repair"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()));
            try {
                assertTrue(entered.await(15, java.util.concurrent.TimeUnit.SECONDS));
                mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isConflict());
                EvolutionStep executing = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
                assertEquals(EvolutionStepStatus.EXECUTING, executing.status());
                assertEquals(1, executing.repairAuthorizations().size());
                verify(ai, times(1)).generate(any());
            } finally {
                release.countDown();
            }
            firstRequest.get(60, java.util.concurrent.TimeUnit.SECONDS);
        }
        verify(ai, times(2)).generate(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorization", "dirty-candidate", "superseded-plan"})
    void repairRechecksAssetIsolationAndPlanBeforeGrantingAuthorization(String change) throws Exception {
        EvolutionStep failed = failedCandidate();
        String b = failed.executionResult().candidateRevision();
        reset(ai);
        switch (change) {
            case "authorization" -> jdbc.update("UPDATE software_asset SET read_permission=0 WHERE id=?", ASSET.value());
            case "dirty-candidate" -> Files.writeString(copy.resolve("A.java"), "external\n");
            default -> jdbc.update("UPDATE evolution_plan SET status='SUPERSEDED' WHERE id=?", planId);
        }
        mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON)
                .content(repairBody(b, UUID.randomUUID().toString()))).andExpect(status().isConflict());
        verifyNoInteractions(ai);
        EvolutionStep after = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
        assertEquals(failed.verificationResult(), after.verificationResult());
        assertTrue(after.repairAuthorizations().isEmpty());
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "process-crash", "interrupted"})
    void repairVerificationExceptionKeepsCandidateAndUnknownCheckpoint(String failure) throws Exception {
        EvolutionStep failed = failedCandidate();
        String b = failed.executionResult().candidateRevision();
        coding("--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-new \n+repaired\n", "DIFF_CHECK");
        doThrow(new WorkspaceException("injected " + failure)).when(verification).verify(any());
        String authorization = UUID.randomUUID().toString();
        mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON).content(repairBody(b, authorization)))
                .andExpect(status().isInternalServerError());
        EvolutionStep unknown = plans.findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
        assertEquals(EvolutionStepStatus.VERIFYING, unknown.status());
        assertNull(unknown.verificationResult());
        assertEquals(git(copy, "rev-parse", "HEAD").trim(), unknown.executionResult().candidateRevision());
        assertEquals(revision, persistedCopy().lastVerifiedRevision());
        assertEquals(failed.verificationResult(), unknown.repairAuthorizations().getFirst().previousVerification());
        reset(ai);
        mvc.perform(post(route("repair")).contentType(MediaType.APPLICATION_JSON)
                .content(repairBody(unknown.executionResult().candidateRevision(), UUID.randomUUID().toString())))
                .andExpect(status().isConflict());
        verifyNoInteractions(ai);
        if (failure.equals("process-crash")) {
            try (var restarted = new org.springframework.boot.builder.SpringApplicationBuilder(
                    com.ayywl.delveforge.app.DelveForgeApplication.class)
                    .web(org.springframework.boot.WebApplicationType.NONE).run(
                            "--delveforge.persistence.database-file=" + BASE.resolve("state.db"),
                            "--delveforge.workspace.root=" + BASE.resolve("managed"),
                            "--delveforge.ai.deepseek.base-url=http://127.0.0.1:1",
                            "--delveforge.ai.deepseek.api-key=", "--spring.profiles.active=m5b-restart-test")) {
                EvolutionStep restored = restarted.getBean(EvolutionPlanRepository.class)
                        .findById(new EvolutionPlanId(planId)).orElseThrow().step(new EvolutionStepId(stepId));
                assertEquals(EvolutionStepStatus.VERIFYING, restored.status());
                assertNull(restored.verificationResult());
                assertEquals(unknown.executionResult(), restored.executionResult());
                assertEquals(unknown.repairAuthorizations(), restored.repairAuthorizations());
                assertEquals(revision, restarted.getBean(WorkingCopyRepository.class)
                        .findById(persistedCopy().id()).orElseThrow().lastVerifiedRevision());
            }
        }
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

    private static Map<String, String> workingTreeSnapshot(Path root) throws Exception {
        Map<String, String> result = snapshot(root);
        result.keySet().removeIf(path -> Path.of(path).startsWith(".git"));
        return result;
    }

}
