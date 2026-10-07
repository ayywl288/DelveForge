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
import static com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories.*;
import static org.junit.jupiter.api.Assertions.*;

class GitEvolutionExecutionIntegrationTest {
    private final GitTestRepositories repositories = new GitTestRepositories();
    private final EvolutionPlanId planId = new EvolutionPlanId("plan");
    private final EvolutionStepId stepId = new EvolutionStepId("step");
    private final SoftwareAssetId assetId = new SoftwareAssetId("asset");
    private final GitWorkspaceAdapter git = new GitWorkspaceAdapter(repositories.directory("managed"));
    private final GitWorkspaceVerificationAdapter verification = new GitWorkspaceVerificationAdapter(git, git,
            new VerificationProperties(Duration.ofSeconds(60), Duration.ofSeconds(180), 1048576, 16));
    private Path source;
    private Path copy;
    private String baseline;
    private EvolutionPlan plan;
    private WorkingCopy workingCopy;

    private AuthorizedStepExecution fixture(boolean maven, int assertion) throws Exception {
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
        }
        source = repositories.createCommitted("source", files);
        baseline = headRevision(source);
        copy = Path.of(git.provision(new WorkspaceRef(source.toString()), baseline, "copy").workspace().value());
        EvidenceBasis evidence = new EvidenceBasis(new Evidence(EvidenceSourceType.REPOSITORY, "Report", "source", 0.8, true),
                new RepositoryProfileEvidenceOrigin(new RepositoryProfileId("profile")));
        plan = EvolutionPlan.reconstitute(planId, new ProductDirectionId("direction"), assetId,
                new RepositoryProfileId("profile"), new CurrentState("current", List.of(), List.of(), List.of()),
                new TargetState("problem", "target", "difference"), List.of(), List.of("change"),
                List.of(EvolutionStep.reconstitute(stepId, planId, "goal", "scope", List.of("change"), List.of(), List.of("check"),
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
            patch += "--- /dev/null\n+++ b/" + testPath + "\n@@ -0,0 +1 @@\n+public class ReportTest {"
                    + " @org.junit.jupiter.api.Test void value() { org.junit.jupiter.api.Assertions.assertEquals("
                    + assertion + ", Report.value()); } }\n";
            affected.add(testPath);
        }
        CodingProposal proposal = new CodingProposal(baseline, "change", affected, patch, List.of(
                new CodingProposal.VerificationAction(CodingProposal.Action.DIFF_CHECK, "", List.of(1))));
        ValidatedExecutionProposal validated = new CodingProposalValidator(new DeterministicRepositorySecretPolicy(), git,
                new ExecutionPreparationLimits(2,24,48,65536,524288,786432,262144,16,16)).validate(proposal,
                new ExecutionPreparationBasis(planId, stepId, workingCopy.id(), baseline), plan.step(stepId),
                new WorkspaceRef(copy.toString()), Map.of(report, original), Set.of(), files.keySet());
        plan.startStep(stepId, workingCopy, new StepExecutionPolicy());
        return AuthorizedStepExecution.from(plan, stepId, workingCopy, new WorkspaceRef(source.toString()), validated);
    }

    private CandidateVerification request(String candidate, CodingProposal.Action... actions) {
        return new CandidateVerification(new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), baseline,
                candidate, Arrays.stream(actions).map(action -> new CodingProposal.VerificationAction(action,
                        action == CodingProposal.Action.FOCUSED_TEST ? "ReportTest#value" : "", List.of(1))).toList());
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
        VerificationFacts facts = verification.verify(request(candidate.revision(), CodingProposal.Action.BUILD,
                CodingProposal.Action.TEST, CodingProposal.Action.FOCUSED_TEST, CodingProposal.Action.DIFF_CHECK));
        assertEquals(4, facts.checks().size());
        assertTrue(facts.checks().stream().allMatch(VerificationFacts.Check::passed), facts.toString());
        assertFalse(Files.exists(copy.resolve("target")));
        assertEquals(before, snapshot(source));
        assertEquals(candidate.revision(), headRevision(copy));
        git.requireTrustedState(new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate.revision());
    }

    @Test void realTestFailureProducesFailedFactWithoutPromotingOrRewritingCandidate() throws Exception {
        var authorized = fixture(true, 2);
        CandidateMutationResult candidate = git.applyCandidate(authorized);
        VerificationFacts facts = verification.verify(request(candidate.revision(), CodingProposal.Action.TEST, CodingProposal.Action.DIFF_CHECK));
        assertFalse(facts.checks().getFirst().passed());
        assertFalse(facts.checks().getLast().passed());
        assertEquals(candidate.revision(), headRevision(copy));
        assertEquals(baseline, workingCopy.lastVerifiedRevision());
    }

    @Test void externalRevisionOrArtifactsRejectMutationWithoutCandidate() throws Exception {
        var authorized = fixture(false, 1);
        Files.writeString(copy.resolve("unexpected.java"), "external\n");
        assertThrows(WorkingCopyBasisChangedException.class, () -> git.applyCandidate(authorized));
        assertEquals(baseline, headRevision(copy));
        assertEquals("public class Report { static int value() { return 0; } }\n", git.readFile(new WorkspaceRef(copy.toString()), baseline, "Report.java"));
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
