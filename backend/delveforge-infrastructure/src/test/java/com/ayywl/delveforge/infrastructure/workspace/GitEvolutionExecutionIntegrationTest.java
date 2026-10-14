package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.evolution.preparation.*;
import com.ayywl.delveforge.application.evolution.execution.EvolutionExecutionException;
import com.ayywl.delveforge.application.port.workspace.*;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.evidence.*;
import com.ayywl.delveforge.domain.evolution.*;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories.*;
import static org.junit.jupiter.api.Assertions.*;

class GitEvolutionExecutionIntegrationTest {
    private final GitTestRepositories repositories = new GitTestRepositories();
    private final EvolutionPlanId planId = new EvolutionPlanId("plan");
    private final EvolutionStepId stepId = new EvolutionStepId("step");
    private final SoftwareAssetId assetId = new SoftwareAssetId("asset");
    private final GitWorkspaceAdapter git = new GitWorkspaceAdapter(repositories.directory("managed"));
    private final GitWorkspaceVerificationAdapter verification = new GitWorkspaceVerificationAdapter(git, git,
            new VerificationProperties(Duration.ofSeconds(60), Duration.ofSeconds(180), 1048576, 16, 100));
    private Path source;
    private Path copy;
    private String baseline;
    private EvolutionPlan plan;
    private WorkingCopy workingCopy;

    private AuthorizedStepExecution fixture(boolean maven, int assertion) throws Exception {
        return fixture(maven, assertion, false);
    }

    private AuthorizedStepExecution fixture(boolean maven, int assertion, boolean parameterized) throws Exception {
        return fixture(maven, assertion, parameterized, "");
    }

    private AuthorizedStepExecution fixture(boolean maven, int assertion, boolean parameterized, String mavenOverrides) throws Exception {
        return fixture(maven, assertion, parameterized, mavenOverrides, false);
    }

    private AuthorizedStepExecution fixture(boolean maven, int assertion, boolean parameterized, String mavenOverrides,
            boolean additionDeletion) throws Exception {
        return fixture(maven, assertion, parameterized, mavenOverrides, additionDeletion, null);
    }

    private AuthorizedStepExecution fixture(boolean maven, int assertion, boolean parameterized, String mavenOverrides,
            boolean additionDeletion, String rollbackDriver) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Report.java", "public class Report { static int value() { return 0; } }\n");
        if (maven) {
            files.clear();
            files.put("src/main/java/Report.java", "public class Report { static int value() { return 0; } }\n");
            files.put("pom.xml", """
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
            Path root = Path.of("../..").toAbsolutePath().normalize();
            for (String path : List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties")) {
                files.put(path, Files.readString(root.resolve(path)));
            }
            if (!mavenOverrides.isEmpty()) {
                files.put("pom.xml", files.get("pom.xml").replace("</project>", mavenOverrides + "</project>"));
                files.put("src/test/java/FailingTest.java", "public class FailingTest { @org.junit.jupiter.api.Test"
                        + " void omitted() { org.junit.jupiter.api.Assertions.fail(\"Must not be omitted\"); } }\n");
            }
        }
        if (additionDeletion) {
            files.put("obsolete.txt", "obsolete\n");
            files.put("untouched.txt", "safe\n");
        }
        if (rollbackDriver != null) {
            files.put("rules.txt", "*.txt filter=" + rollbackDriver + "\n");
        }
        source = repositories.createCommitted("source", files);
        baseline = headRevision(source);
        copy = Path.of(git.provision(new WorkspaceRef(source.toString()), baseline, "copy").workspace().value());
        EvidenceBasis evidence = new EvidenceBasis(new Evidence(EvidenceSourceType.REPOSITORY, "Report", "source", 0.8, true),
                new RepositoryProfileEvidenceOrigin(new RepositoryProfileId("profile")));
        plan = EvolutionPlan.reconstitute(planId, new ProductDirectionId("direction"), assetId,
                new RepositoryProfileId("profile"), new CurrentState("current", List.of(), List.of(), List.of()),
                new TargetState("problem", "target", "difference"), List.of(), List.of("change"),
                List.of(EvolutionStep.reconstitute(stepId, planId, "goal", "scope", List.of("change"), List.of(VerificationCriterion.unsupported("check")),
                        EvolutionStepStatus.READY)), List.of(), List.of(evidence), new WorkingCopyId("copy"), EvolutionPlanStatus.ACTIVE);
        workingCopy = WorkingCopy.reconstitute(new WorkingCopyId("copy"), assetId, baseline, copy.toString(),
                baseline, baseline, WorkingCopyStatus.READY);
        String report = maven ? "src/main/java/Report.java" : "Report.java";
        String original = files.get(report);
        String patch = "--- a/" + report + "\n+++ b/" + report + "\n@@ -1 +1 @@\n-" + original
                + "+" + original.replace("return 0", "return 1");
        List<String> affected = new ArrayList<>(List.of(report));
        if (maven) {
            String testPath = "src/test/java/ReportTest.java";
            String annotation = parameterized
                    ? " @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={1,2})"
                    : " @org.junit.jupiter.api.Test";
            String signature = parameterized ? " void value(int input)" : " void value()";
            patch += "--- /dev/null\n+++ b/" + testPath + "\n@@ -0,0 +1 @@\n+public class ReportTest {"
                    + annotation + signature + " { org.junit.jupiter.api.Assertions.assertEquals("
                    + assertion + ", Report.value()); } }\n";
            affected.add(testPath);
        }
        Map<String, String> originals = new LinkedHashMap<>(Map.of(report, original));
        if (additionDeletion) {
            patch += "--- a/obsolete.txt\n+++ /dev/null\n@@ -1 +0,0 @@\n-obsolete\n"
                    + "--- /dev/null\n+++ b/new.txt\n@@ -0,0 +1 @@\n+owned addition\n";
            affected.addAll(List.of("obsolete.txt", "new.txt"));
            originals.put("obsolete.txt", "obsolete\n");
        }
        if (rollbackDriver != null) {
            patch += "--- a/rules.txt\n+++ b/rules.txt\n@@ -1 +1 @@\n-*.txt filter=" + rollbackDriver
                    + "\n+# inert unpublished rules\n";
            affected.add("rules.txt");
            originals.put("rules.txt", files.get("rules.txt"));
        }
        CodingProposal proposal = new CodingProposal(baseline, "change", affected, patch, List.of(
                new CodingProposal.VerificationAction(CodingProposal.Action.DIFF_CHECK, "", List.of(1))));
        ValidatedExecutionProposal validated = new CodingProposalValidator(new DeterministicRepositorySecretPolicy(), git,
                new ExecutionPreparationLimits(2,24,48,65536,524288,786432,262144,16,16)).validate(proposal,
                new ExecutionPreparationBasis(planId, stepId, workingCopy.id(), baseline), plan.step(stepId),
                new WorkspaceRef(copy.toString()), originals, Set.of(), files.keySet());
        plan.startStep(stepId, workingCopy, new StepExecutionPolicy());
        return AuthorizedStepExecution.from(plan, stepId, workingCopy, new WorkspaceRef(source.toString()), validated);
    }

    @ParameterizedTest
    @ValueSource(strings = {"normal", "tracked-tamper", "extra", "index-lock", "filter-unset", "filter-unspecified", "addition-deletion", "cleanup-failure"})
    void confirmedPublishFailureRecordsPhysicalDiffAndRestoresOnlyProvenStepTree(String fault) throws Exception {
        AuthorizedStepExecution authorized = fixture(false, 1, false, "", fault.equals("addition-deletion"));
        String originalPhysical = Files.readString(copy.resolve("Report.java"));
        Path[] ownedIndex = {null};
        GitWorkspaceAdapter failing = new GitWorkspaceAdapter(repositories.directory("managed")) {
            @Override
            protected WorkspaceCommandRunner.Result mutationGitResult(Path directory, Path index, String... arguments) {
                if (Arrays.asList(arguments).contains("update-ref")) {
                    try {
                        Files.writeString(copy.resolve(".git/HEAD.lock"), "TASK OWNED FAULT");
                        if (fault.equals("tracked-tamper")) {
                            Files.writeString(copy.resolve("Report.java"), "USER MODIFICATION\n");
                        }
                        return super.mutationGitResult(directory, index, arguments);
                    } catch (java.io.IOException failure) {
                        throw new AssertionError(failure);
                    } finally {
                        try { Files.deleteIfExists(copy.resolve(".git/HEAD.lock")); }
                        catch (java.io.IOException failure) { throw new AssertionError(failure); }
                    }
                }
                var result = super.mutationGitResult(directory, index, arguments);
                if (fault.equals("cleanup-failure") && index != null && Arrays.asList(arguments).contains("write-tree")) {
                    try {
                        ownedIndex[0] = index;
                        Files.setAttribute(index, "dos:readonly", true);
                    } catch (java.io.IOException failure) { throw new AssertionError(failure); }
                }
                return result;
            }
        };
        var rejection = assertThrows(ConfirmedExecutionFailureException.class, () -> failing.applyCandidate(authorized));
        var failure = rejection.result();
        if (fault.equals("cleanup-failure")) {
            try {
                assertTrue(failure.diagnostic().contains("ARTIFACT_CLEANUP_NOT_CONFIRMED"));
                assertTrue(Files.exists(ownedIndex[0]));
            } finally {
                Files.setAttribute(ownedIndex[0], "dos:readonly", false);
            }
        }
        assertEquals("PUBLISH", failure.phase());
        assertEquals(baseline, failure.observedRevision());
        assertEquals(baseline, headRevision(copy));
        assertTrue(failure.diffComplete());
        assertFalse(failure.diff().isEmpty());
        assertTrue(failure.diff().stream().anyMatch(entry -> entry.path().equals("Report.java")));
        assertNull(plan.step(stepId).executionResult());
        plan.failStepExecution(stepId, workingCopy, failure);
        assertEquals(EvolutionStepStatus.ROLLING_BACK, plan.copy().step(stepId).status());
        assertNull(plan.step(stepId).rollbackAuthorization());
        if (fault.equals("extra")) { Files.writeString(copy.resolve("user.txt"), "KEEP USER FILE"); }
        if (fault.equals("index-lock")) { Files.writeString(copy.resolve(".git/index.lock"), "KEEP LOCK"); }
        if (fault.startsWith("filter-")) {
            String driver = fault.substring("filter-".length());
            Files.createDirectories(copy.resolve(".git/info"));
            Files.writeString(copy.resolve(".git/info/attributes"), "* filter=" + driver + "\n");
            runGit(copy, "config", "filter." + driver + ".clean", "echo FILTER-RAN > .git/filter-ran");
            runGit(copy, "config", "filter." + driver + ".smudge", "echo FILTER-RAN > .git/filter-ran");
        }
        var rollback = AuthorizedStepRollback.fromExecutionFailure(plan, stepId, workingCopy,
                new WorkspaceRef(source.toString()));
        if (fault.equals("normal") || fault.equals("addition-deletion") || fault.equals("cleanup-failure")) {
            var restored = git.restoreBaseline(rollback);
            plan.finishAutomaticStepRollback(stepId, workingCopy, new AutomaticRollbackResult(failure.id(), baseline,
                    restored.revision(), true, "EXACT_BASELINE_CONFIRMED", restored.restoredAt()));
            assertEquals(EvolutionStepStatus.FAILED, plan.copy().step(stepId).status());
            assertEquals(baseline, workingCopy.currentRevision());
            assertEquals(originalPhysical, Files.readString(copy.resolve("Report.java")));
            git.requireTrustedState(new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), baseline);
            if (fault.equals("addition-deletion")) {
                assertFalse(Files.exists(copy.resolve("new.txt")));
                assertEquals("obsolete\n", Files.readString(copy.resolve("obsolete.txt")).replace("\r\n", "\n"));
                assertEquals("safe\n", Files.readString(copy.resolve("untouched.txt")).replace("\r\n", "\n"));
            }
        } else {
            var blocked = assertThrows(WorkingCopyRollbackException.class, () -> git.restoreBaseline(rollback));
            plan.finishAutomaticStepRollback(stepId, workingCopy, new AutomaticRollbackResult(failure.id(), baseline,
                    blocked.observedRevision(), false, blocked.phase(), java.time.Instant.now()));
            assertEquals(EvolutionStepStatus.RECOVERY_REQUIRED, plan.copy().step(stepId).status());
            assertEquals(WorkingCopyStatus.RECOVERY_REQUIRED, workingCopy.status());
            if (fault.equals("extra")) { assertEquals("KEEP USER FILE", Files.readString(copy.resolve("user.txt"))); }
            if (fault.equals("tracked-tamper")) { assertEquals("USER MODIFICATION\n", Files.readString(copy.resolve("Report.java"))); }
        }
        assertFalse(Files.exists(copy.resolve(".git/filter-ran")));
        assertEquals(baseline, workingCopy.lastVerifiedRevision());
        assertNull(plan.step(stepId).executionResult());
        assertNull(plan.step(stepId).verificationResult());
        assertEquals(failure, plan.copy().step(stepId).executionFailureResult());
        assertEquals(baseline, headRevision(source));
    }

    @ParameterizedTest
    @CsvSource({"unset,clean", "unspecified,clean", "unset,process", "unspecified,process"})
    void mutableExternalAttributeSourceCannotRunDuringAutomaticRestore(String driver, String kind) throws Exception {
        AuthorizedStepExecution authorized = fixture(false, 1, false, "", false, driver);
        GitWorkspaceAdapter rejected = new GitWorkspaceAdapter(repositories.directory("managed")) {
            @Override
            protected WorkspaceCommandRunner.Result mutationGitResult(Path directory, Path index, String... arguments) {
                if (Arrays.asList(arguments).contains("update-ref")) {
                    return new WorkspaceCommandRunner.Result(128, "fatal: controlled publication rejection");
                }
                return super.mutationGitResult(directory, index, arguments);
            }
        };
        ExecutionFailureResult failure = assertThrows(ConfirmedExecutionFailureException.class,
                () -> rejected.applyCandidate(authorized)).result();
        assertEquals("PUBLISH", failure.phase());
        assertTrue(failure.diffComplete());
        plan.failStepExecution(stepId, workingCopy, failure);
        AuthorizedStepRollback rollback = AuthorizedStepRollback.fromExecutionFailure(plan, stepId, workingCopy,
                new WorkspaceRef(source.toString()));
        runGit(copy, "config", "core.attributesFile", copy.resolve("rules.txt").toString());
        runGit(copy, "config", "filter." + driver + "." + kind, kind.equals("clean")
                ? "printf hit >> .git/filter-ran; cat" : "printf hit >> .git/filter-ran; exit 1");
        String rulesBefore = Files.readString(copy.resolve("rules.txt"));
        assertAll(
                () -> assertThrows(WorkingCopyRollbackException.class, () -> git.restoreBaseline(rollback)),
                () -> assertFalse(Files.exists(copy.resolve(".git/filter-ran"))),
                () -> assertEquals(rulesBefore, Files.readString(copy.resolve("rules.txt"))),
                () -> assertTrue(Files.readString(copy.resolve("Report.java")).contains("return 1")),
                () -> assertEquals(baseline, headRevision(copy)),
                () -> assertNull(plan.step(stepId).executionResult()),
                () -> assertEquals(baseline, workingCopy.lastVerifiedRevision()));
    }

    @ParameterizedTest
    @CsvSource({"APPLY,137", "APPLY,139", "APPLY,-1073741819", "APPLY,128",
            "PUBLISH,137", "PUBLISH,139", "PUBLISH,-1073741819", "PUBLISH,128"})
    void unrecognizedProcessExitCannotBecomeConfirmedExecutionFailure(String phase, int exit) throws Exception {
        AuthorizedStepExecution authorized = fixture(false, 1, false);
        boolean[] reached = new boolean[4];
        GitWorkspaceAdapter unknown = new GitWorkspaceAdapter(repositories.directory("managed")) {
            @Override
            protected WorkspaceCommandRunner.Result mutationGitResult(Path directory, Path index, String... arguments) {
                List<String> args = Arrays.asList(arguments);
                if (index != null && args.contains("write-tree")) {
                    reached[0] = true;
                }
                if (index == null && args.contains("apply") && args.contains("--check")) {
                    reached[1] = true;
                }
                boolean apply = index == null && args.contains("apply") && !args.contains("--check");
                boolean publish = args.contains("update-ref");
                if (apply) {
                    reached[2] = true;
                }
                if ((phase.equals("APPLY") && apply) || (phase.equals("PUBLISH") && publish)) {
                    assertTrue(reached[0], "Expected tree preflight completed");
                    assertTrue(reached[1], "Apply check preflight completed");
                    assertTrue(reached[2], "Real apply stage reached");
                    reached[3] = true;
                    if (apply) {
                        // 模拟真实 APPLY 完成但结果丢失；物理修改不能被推断为已确认失败或 Candidate。
                        assertTrue(super.mutationGitResult(directory, index, arguments).succeeded());
                    }
                    return new WorkspaceCommandRunner.Result(exit, "process result lost");
                }
                return super.mutationGitResult(directory, index, arguments);
            }

            @Override
            public RestoredBaseline restoreBaseline(AuthorizedStepRollback rollback) {
                fail("UNKNOWN must not start automatic restoration");
                return null;
            }
        };
        RuntimeException failure = assertThrows(WorkspaceException.class, () -> unknown.applyCandidate(authorized));
        assertFalse(failure instanceof ConfirmedExecutionFailureException);
        assertTrue(reached[3], "Unknown result was injected only at " + phase);
        assertEquals(EvolutionStepStatus.EXECUTING, plan.step(stepId).status());
        assertNull(plan.step(stepId).executionFailureResult());
        assertNull(plan.step(stepId).executionResult());
        assertNull(plan.step(stepId).automaticRollbackResult());
        assertEquals(baseline, workingCopy.lastVerifiedRevision());
        assertEquals(baseline, headRevision(copy));
        assertTrue(Files.readString(copy.resolve("Report.java")).contains("return 1"));
        assertEquals(baseline, headRevision(source));
    }

    private CandidateVerification request(String candidate, CodingProposal.Action... actions) {
        return new CandidateVerification(new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), baseline,
                candidate, Arrays.stream(actions).map(action -> new CodingProposal.VerificationAction(action,
                        action == CodingProposal.Action.FOCUSED_TEST ? "ReportTest#value" : "", List.of(1))).toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusesPreExistingPrivatePathWithoutRemovingIt(boolean directory) throws Exception {
        AuthorizedStepExecution authorized = fixture(false, 1);
        CandidateMutationResult candidate = git.applyCandidate(authorized);
        Map<String, String> original = snapshot(source);
        Path run = copy.resolve(".git/v");
        Path sentinel = run;
        if (directory) {
            Files.createDirectory(run);
            sentinel = run.resolve("keep.txt");
        }
        Files.writeString(sentinel, "owned by another invocation");
        assertThrows(WorkspaceException.class,
                () -> verification.verify(request(candidate.revision(), CodingProposal.Action.DIFF_CHECK)));
        assertEquals("owned by another invocation", Files.readString(sentinel));
        assertEquals(directory, Files.isDirectory(run));
        assertEquals(candidate.revision(), headRevision(copy));
        assertEquals(baseline, workingCopy.lastVerifiedRevision());
        assertEquals(original, snapshot(source));
    }

    @Test
    void repeatedChecksCreateFreshPrivateDirectoriesAndLeaveNoArtifacts() throws Exception {
        AuthorizedStepExecution authorized = fixture(false, 1);
        CandidateMutationResult candidate = git.applyCandidate(authorized);
        Map<String, String> original = snapshot(source);
        List<Path> metadataBefore;
        try (var entries = Files.list(copy.resolve(".git"))) {
            metadataBefore = entries.sorted().toList();
        }
        for (int call = 0; call < 2; call++) {
            VerificationFacts facts = verification.verify(request(candidate.revision(), CodingProposal.Action.DIFF_CHECK));
            assertTrue(facts.checks().getFirst().passed(), facts.toString());
            try (var entries = Files.list(copy.resolve(".git"))) {
                assertEquals(metadataBefore, entries.sorted().toList());
            }
            assertEquals(candidate.revision(), headRevision(copy));
            assertEquals(baseline, workingCopy.lastVerifiedRevision());
        }
        assertEquals(original, snapshot(source));
    }

    @Test void createsExactlyOneInternalCandidateAndDiffVerificationLeavesOriginalUntouched() throws Exception {
        var authorized = fixture(false, 1);
        var before = snapshot(source);
        Path hook = copy.resolve(".git/hooks/pre-commit");
        Files.createDirectories(hook.getParent());
        Files.writeString(hook, "#!/bin/sh\nprintf hook > hook-ran\n");
        CandidateMutationResult candidate = git.applyCandidate(authorized);
        assertNotEquals(baseline, candidate.revision());
        assertEquals(baseline, runGit(copy, "rev-parse", "HEAD^").trim());
        assertEquals(List.of("Report.java"), candidate.changedPaths());
        assertFalse(Files.exists(copy.resolve("hook-ran")));
        assertEquals(baseline, workingCopy.lastVerifiedRevision());
        assertEquals("1", runGit(copy, "rev-list", "--count", baseline + "..HEAD").trim());
        VerificationFacts facts = verification.verify(request(candidate.revision(), CodingProposal.Action.DIFF_CHECK));
        assertTrue(facts.checks().getFirst().passed());
        assertEquals(candidate.revision(), facts.candidateRevision());
        assertEquals(candidate.revision(), headRevision(copy));
        assertEquals(before, snapshot(source));
        git.requireTrustedState(new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate.revision());
    }

    @Test void runsRealMavenBuildTestsAndFocusedTestsAtExactCandidateWithoutPollutingCopy() throws Exception {
        var authorized = fixture(true, 1);
        var before = snapshot(source);
        CandidateMutationResult candidate = git.applyCandidate(authorized);
        List<Path> metadataBefore;
        try (var entries = Files.list(copy.resolve(".git"))) {
            metadataBefore = entries.sorted().toList();
        }
        VerificationFacts facts = verification.verify(request(candidate.revision(), CodingProposal.Action.BUILD,
                CodingProposal.Action.TEST, CodingProposal.Action.FOCUSED_TEST, CodingProposal.Action.DIFF_CHECK));
        assertEquals(4, facts.checks().size());
        assertTrue(facts.checks().stream().allMatch(VerificationFacts.Check::passed), facts.toString());
        assertFalse(Files.exists(copy.resolve("target")));
        try (var entries = Files.list(copy.resolve(".git"))) {
            assertEquals(metadataBefore, entries.sorted().toList());
        }
        assertEquals(before, snapshot(source));
        assertEquals(candidate.revision(), headRevision(copy));
        git.requireTrustedState(new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate.revision());
    }

    @Test void realReactorUsesEachModulesDefaultTestSourceDirectory() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
              <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId>
                <version>3.5.16</version><relativePath/></parent>
              <groupId>test</groupId><artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging>
              <properties><java.version>21</java.version></properties>
              <modules><module>nested/first</module><module>second</module></modules>
              <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
                <scope>test</scope></dependency></dependencies>
            </project>
            """);
        for (String module : List.of("nested/first", "second")) {
            String artifact = Path.of(module).getFileName().toString();
            files.put(module + "/pom.xml", "<project><modelVersion>4.0.0</modelVersion><parent><groupId>test</groupId>"
                    + "<artifactId>reactor</artifactId><version>1</version><relativePath>"
                    + (module.startsWith("nested/") ? "../../pom.xml" : "../pom.xml")
                    + "</relativePath></parent><artifactId>" + artifact + "</artifactId></project>\n");
            files.put(module + "/src/test/java/PassingTest.java", "public class PassingTest {"
                    + " @org.junit.jupiter.api.Test void value() {} }\n");
        }
        Path root = Path.of("../..").toAbsolutePath().normalize();
        for (String path : List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties")) {
            files.put(path, Files.readString(root.resolve(path)));
        }
        source = repositories.createCommitted("reactor-source", files);
        baseline = headRevision(source);
        copy = Path.of(git.provision(new WorkspaceRef(source.toString()), baseline, "reactor-copy").workspace().value());
        Map<String, String> original = snapshot(source);
        Map<String, String> unchangedCopy = snapshot(copy);
        // 三份继承 Boot 的生效 POM 使用明确的有界预算，不绕过普通项目的配置检查。
        var reactorVerification = new GitWorkspaceVerificationAdapter(git, git,
                new VerificationProperties(Duration.ofSeconds(60), Duration.ofSeconds(180), 4 * 1048576, 16, 100));
        VerificationFacts facts = reactorVerification.verify(request(baseline, CodingProposal.Action.TEST));
        assertTrue(facts.checks().getFirst().passed(), facts.toString());
        assertTrue(facts.checks().getFirst().summary().contains("executed=2"), facts.toString());
        assertEquals(original, snapshot(source));
        assertEquals(unchangedCopy, snapshot(copy));
    }

    @Test void realTestFailureProducesFailedFactWithoutPromotingOrRewritingCandidate() throws Exception {
        var authorized = fixture(true, 2);
        CandidateMutationResult candidate = git.applyCandidate(authorized);
        VerificationFacts facts = verification.verify(request(candidate.revision(), CodingProposal.Action.TEST, CodingProposal.Action.DIFF_CHECK));
        assertFalse(facts.checks().getFirst().passed());
        assertFalse(facts.checks().getLast().passed());
        assertEquals(VerificationResult.Outcome.FAILED, facts.checks().getFirst().outcome());
        assertEquals(VerificationResult.Outcome.NOT_RUN, facts.checks().getLast().outcome());
        assertEquals(candidate.revision(), headRevision(copy));
        assertEquals(baseline, workingCopy.lastVerifiedRevision());
    }

    @Test void actualParameterizedMethodEvidenceSupportsTheRequestedLogicalMethod() throws Exception {
        AuthorizedStepExecution authorized = fixture(true, 1, true);
        CandidateMutationResult candidate = git.applyCandidate(authorized);
        VerificationFacts facts = verification.verify(request(candidate.revision(), CodingProposal.Action.FOCUSED_TEST));
        assertTrue(facts.checks().getFirst().passed(), facts.toString());
        assertTrue(facts.checks().getFirst().summary().contains("executed=2"));
        assertEquals(candidate.revision(), headRevision(copy));
        assertEquals(baseline, workingCopy.lastVerifiedRevision());
        for (String target : List.of("ReportTest#valueExtra", "OtherTest#value")) {
            CandidateVerification wrong = new CandidateVerification(new WorkspaceRef(copy.toString()),
                    new WorkspaceRef(source.toString()), baseline, candidate.revision(),
                    List.of(new CodingProposal.VerificationAction(CodingProposal.Action.FOCUSED_TEST, target, List.of(1))));
            assertFalse(verification.verify(wrong).checks().getFirst().passed());
        }
    }

    @Test void failingParameterizedInvocationsNeverSupportSuccessfulVerification() throws Exception {
        AuthorizedStepExecution authorized = fixture(true, 2, true);
        CandidateMutationResult candidate = git.applyCandidate(authorized);
        assertFalse(verification.verify(request(candidate.revision(), CodingProposal.Action.FOCUSED_TEST)).checks().getFirst().passed());
        assertEquals(baseline, workingCopy.lastVerifiedRevision());
    }

    @ParameterizedTest @ValueSource(strings = {
        "<name>ReportTest</name><build><plugins><plugin>"
                + "<artifactId>maven-surefire-plugin</artifactId><configuration><test>${project.name}</test></configuration>"
                + "</plugin></plugins></build>",
        "<build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><configuration><includes>"
                + "<include>**/ReportTest.java</include></includes></configuration></plugin></plugins></build>",
        "<profiles><profile><id>default</id><activation><activeByDefault>true</activeByDefault></activation><build><plugins>"
                + "<plugin><artifactId>maven-surefire-plugin</artifactId><configuration><excludes><exclude>**/FailingTest.java</exclude>"
                + "</excludes></configuration></plugin></plugins></build></profile></profiles>"
    })
    void successfulMavenWithEffectivePomSelectorsIsNotFullSuiteEvidence(String configuration) throws Exception {
        AuthorizedStepExecution authorized = fixture(true, 1, false, configuration);
        CandidateMutationResult candidate = git.applyCandidate(authorized);
        Map<String, String> original = snapshot(source);
        VerificationFacts facts = verification.verify(request(candidate.revision(), CodingProposal.Action.TEST));
        assertFalse(facts.checks().getFirst().passed(), facts.toString());
        assertEquals(VerificationResult.Outcome.INCONCLUSIVE, facts.checks().getFirst().outcome());
        assertTrue(facts.checks().getFirst().summary().contains("effective Maven inputs"), facts.toString());
        assertEquals(baseline, workingCopy.lastVerifiedRevision());
        assertEquals(candidate.revision(), headRevision(copy));
        assertEquals(original, snapshot(source));
    }

    @Test void externalRevisionOrArtifactsRejectMutationWithoutCandidate() throws Exception {
        var authorized = fixture(false, 1);
        Files.writeString(copy.resolve("unexpected.java"), "external\n");
        assertThrows(WorkingCopyBasisChangedException.class, () -> git.applyCandidate(authorized));
        assertEquals(baseline, headRevision(copy));
        assertEquals("public class Report { static int value() { return 0; } }\n", git.readFile(new WorkspaceRef(copy.toString()), baseline, "Report.java"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void stagedDriftAfterScopeInspectionCannotBecomeCandidate(boolean samePath) throws Exception {
        AuthorizedStepExecution authorized = fixture(false, 1);
        Map<String, String> original = snapshot(source);
        GitWorkspaceAdapter racing = new GitWorkspaceAdapter(repositories.directory("managed")) {
            private int inspections;
            @Override public void requireSafePaths(WorkspaceRef workspace, List<String> paths) {
                super.requireSafePaths(workspace, paths);
                if (++inspections == 2) {
                    // 在真实范围检查之后、write-tree 之前，确定性模拟外部 index 写入。
                    try {
                        String path = samePath ? "Report.java" : "unexpected.txt";
                        Files.writeString(copy.resolve(path), "concurrent replacement\n");
                        runGit(copy, "add", "--", path);
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }
            }
        };
        assertThrows(EvolutionExecutionException.class, () -> racing.applyCandidate(authorized));
        assertEquals(baseline, headRevision(copy));
        assertEquals(EvolutionStepStatus.EXECUTING, plan.step(stepId).status());
        assertNull(plan.step(stepId).executionResult());
        assertEquals(baseline, workingCopy.currentRevision());
        assertEquals(baseline, workingCopy.lastVerifiedRevision());
        assertEquals(original, snapshot(source));
    }

    @Test void unsupportedBuildAndCommandLikeFocusedTargetsFailClosed() throws Exception {
        fixture(false, 1);
        assertThrows(EvolutionExecutionException.class, () -> verification.requireSupported(request(baseline, CodingProposal.Action.TEST)));
        var invalid = new CandidateVerification(new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), baseline,
                baseline, List.of(new CodingProposal.VerificationAction(CodingProposal.Action.DIFF_CHECK, "../", List.of(1))));
        assertThrows(EvolutionExecutionException.class, () -> verification.requireSupported(invalid));
    }

    @Test void candidateHeadDriftBlocksVerification() throws Exception {
        var authorized = fixture(false, 1);
        CandidateMutationResult candidate = git.applyCandidate(authorized);
        runGit(copy, "checkout", "--detach", baseline);
        assertThrows(WorkingCopyBasisChangedException.class, () -> verification.verify(request(candidate.revision(), CodingProposal.Action.DIFF_CHECK)));
    }

    @Test void absentTestsAndMissingFocusedSelectorCannotBeReportedAsPassed() throws Exception {
        var authorized = fixture(true, 1);
        VerificationFacts empty = verification.verify(request(baseline, CodingProposal.Action.TEST));
        assertFalse(empty.checks().getFirst().passed());
        CandidateMutationResult candidate = git.applyCandidate(authorized);
        var missing = new CandidateVerification(new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()),
                baseline, candidate.revision(), List.of(new CodingProposal.VerificationAction(
                        CodingProposal.Action.FOCUSED_TEST, "AbsentTest", List.of(1))));
        assertFalse(verification.verify(missing).checks().getFirst().passed());
        var injection = new CandidateVerification(new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()),
                baseline, candidate.revision(), List.of(new CodingProposal.VerificationAction(
                        CodingProposal.Action.FOCUSED_TEST, "ReportTest#value$bad", List.of(1))));
        assertThrows(EvolutionExecutionException.class, () -> verification.requireSupported(injection));
    }

    @Test void originalAssetCannotBeUsedAsMutationTarget() throws Exception {
        var authorized = fixture(false, 1);
        WorkingCopy forged = WorkingCopy.reconstitute(workingCopy.id(), assetId, baseline, source.toString(),
                baseline, baseline, WorkingCopyStatus.EVOLVING);
        var wrong = AuthorizedStepExecution.from(plan, stepId, forged, new WorkspaceRef(source.toString()), authorized.validated());
        var before = snapshot(source);
        assertThrows(WorkingCopyBasisChangedException.class, () -> git.applyCandidate(wrong));
        assertEquals(before, snapshot(source));
    }
}
