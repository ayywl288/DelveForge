package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.port.workspace.AuthorizedStepRollback;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyBasisChangedException;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyRollbackException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.evidence.RepositoryProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.evolution.CurrentState;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanStatus;
import com.ayywl.delveforge.domain.evolution.EvolutionStep;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepStatus;
import com.ayywl.delveforge.domain.evolution.ExecutionResult;
import com.ayywl.delveforge.domain.evolution.StepExecutionPolicy;
import com.ayywl.delveforge.domain.evolution.TargetState;
import com.ayywl.delveforge.domain.evolution.VerificationCriterion;
import com.ayywl.delveforge.domain.evolution.VerificationResult;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;
import com.ayywl.delveforge.domain.evolution.WorkingCopyId;
import com.ayywl.delveforge.domain.evolution.WorkingCopyStatus;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories.commit;
import static com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories.headRevision;
import static com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories.runGit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 真实内容篡改夹具；刻意建立会漏报的稳定 Git stat 缓存，不用 mocked diff 证明恢复。 */
class GitRollbackContentIntegrationTest {
    private final GitTestRepositories repositories = new GitTestRepositories();
    private final GitWorkspaceAdapter adapter = new GitWorkspaceAdapter(repositories.directory("managed"));
    private Path source;
    private Path copy;
    private String baseline;
    private String candidate;

    @AfterEach
    void cleanup() throws Exception {
        repositories.deleteAll();
    }

    private AuthorizedStepRollback fixture() throws Exception {
        return fixture("*.txt -text\n");
    }

    private AuthorizedStepRollback fixture(String attributes) throws Exception {
        source = repositories.createCommitted("source", Map.of(".gitattributes", attributes, "changed.txt", "before\n",
                "unchanged.txt", "untouched\n", "data.bin", "binary\0content"));
        baseline = headRevision(source);
        copy = Path.of(adapter.provision(new WorkspaceRef(source.toString()), baseline, "copy").workspace().value());
        Files.writeString(copy.resolve("changed.txt"), "after \n");
        runGit(copy, "add", "changed.txt");
        commit(copy, "Candidate");
        candidate = headRevision(copy);
        EvolutionPlanId planId = new EvolutionPlanId("plan");
        EvolutionStepId stepId = new EvolutionStepId("step");
        SoftwareAssetId assetId = new SoftwareAssetId("asset");
        ExecutionResult execution = new ExecutionResult(candidate, List.of("change"), List.of("changed.txt"),
                List.of(), List.of(), Instant.now());
        VerificationResult verification = new VerificationResult(candidate, List.of(new VerificationResult.Check(
                "DIFF_CHECK", "", List.of(1), VerificationResult.Outcome.FAILED, "Whitespace failure")),
                false, List.of("Whitespace failure"), "Failed", Instant.now());
        EvolutionStep step = EvolutionStep.reconstitute(stepId, planId, "goal", "scope", List.of("change"),
                List.of(VerificationCriterion.technical(VerificationCriterion.Kind.DIFF_CHECK_PASSES)),
                EvolutionStepStatus.VERIFICATION_FAILED, baseline, execution, verification);
        EvidenceBasis evidence = new EvidenceBasis(new Evidence(EvidenceSourceType.REPOSITORY, "changed.txt", "fact", 0.8, true),
                new RepositoryProfileEvidenceOrigin(new RepositoryProfileId("profile")));
        EvolutionPlan plan = EvolutionPlan.reconstitute(planId, new ProductDirectionId("direction"), assetId,
                new RepositoryProfileId("profile"), new CurrentState("current", List.of(), List.of(), List.of()),
                new TargetState("problem", "target", "difference"), List.of(), List.of("change"), List.of(step),
                List.of(), List.of(evidence), new WorkingCopyId("copy"), EvolutionPlanStatus.ACTIVE);
        WorkingCopy workingCopy = WorkingCopy.reconstitute(new WorkingCopyId("copy"), assetId, baseline, copy.toString(),
                candidate, baseline, WorkingCopyStatus.EVOLVING);
        String authorization = UUID.randomUUID().toString();
        plan.authorizeStepRollback(stepId, workingCopy, new StepExecutionPolicy(), authorization, candidate, baseline, Instant.now());
        return AuthorizedStepRollback.from(plan, stepId, workingCopy, new WorkspaceRef(source.toString()), authorization);
    }

    private void hideSameLengthChange() throws Exception {
        runGit(copy, "config", "core.trustctime", "false");
        runGit(copy, "config", "core.checkstat", "minimal");
        Path unchanged = copy.resolve("unchanged.txt");
        FileTime old = FileTime.fromMillis(System.currentTimeMillis() - 10000);
        Files.setLastModifiedTime(unchanged, old);
        runGit(copy, "update-index", "--refresh");
        Files.writeString(unchanged, "tampered!\n");
        Files.setLastModifiedTime(unchanged, old);
        assertEquals("", runGit(copy, "status", "--porcelain"));
        assertEquals("", runGit(copy, "diff", "--no-ext-diff"));
        assertEquals("tampered!\n", Files.readString(unchanged));
    }

    @Test
    void trustedStateRejectsUnchangedTrackedFileHiddenByStableStatCache() throws Exception {
        fixture();
        hideSameLengthChange();
        assertThrows(WorkingCopyBasisChangedException.class, () -> adapter.requireTrustedState(
                new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate));
        assertEquals(candidate, headRevision(copy));
        assertEquals("tampered!\n", Files.readString(copy.resolve("unchanged.txt")));
    }

    @Test
    void rollbackCannotConfirmBaselineWithHiddenUnchangedFileTampering() throws Exception {
        AuthorizedStepRollback authorized = fixture();
        hideSameLengthChange();
        assertThrows(WorkingCopyRollbackException.class, () -> adapter.restoreBaseline(authorized));
        assertEquals(candidate, headRevision(copy));
        assertEquals("tampered!\n", Files.readString(copy.resolve("unchanged.txt")));
    }

    @Test
    void postCheckoutHiddenTamperingCannotReturnRestoredBaseline() throws Exception {
        AuthorizedStepRollback authorized = fixture();
        GitWorkspaceAdapter injecting = new GitWorkspaceAdapter(repositories.directory("managed")) {
            @Override
            public void requireTrustedState(WorkspaceRef working, WorkspaceRef original, String expected) {
                if (expected.equals(baseline)) {
                    try {
                        hideSameLengthChange();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }
                super.requireTrustedState(working, original, expected);
            }
        };
        WorkingCopyRollbackException failure = assertThrows(WorkingCopyRollbackException.class,
                () -> injecting.restoreBaseline(authorized));
        assertEquals("POSTCHECK", failure.phase());
        assertEquals(baseline, failure.observedRevision());
        assertEquals("tampered!\n", Files.readString(copy.resolve("unchanged.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lf", "crlf"})
    void gitAttributesAllowLegitimateCheckoutLineEndings(String eol) throws Exception {
        AuthorizedStepRollback authorized = fixture("*.txt text eol=" + eol + "\n");
        adapter.requireTrustedState(new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate);
        assertEquals(baseline, adapter.restoreBaseline(authorized).revision());
        String ending = eol.equals("crlf") ? "\r\n" : "\n";
        assertEquals("before" + ending, Files.readString(copy.resolve("changed.txt")));
        assertEquals("untouched" + ending, Files.readString(copy.resolve("unchanged.txt")));
    }

    @Test
    void customCleanFilterCannotHideContentOrRunDuringProof() throws Exception {
        fixture("*.txt filter=fixture\n");
        runGit(copy, "config", "filter.fixture.clean", "echo FILTER-INVOKED > .git/filter-ran");
        assertThrows(WorkingCopyBasisChangedException.class, () -> adapter.requireTrustedState(
                new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate));
        assertFalse(Files.exists(copy.resolve(".git/filter-ran")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ident", "working-tree-encoding=UTF-16"})
    void unprovenContentConversionsFailClosedWithoutRewritingFiles(String attribute) throws Exception {
        fixture();
        Files.createDirectories(copy.resolve(".git/info"));
        Files.writeString(copy.resolve(".git/info/attributes"), "*.txt " + attribute + "\n");
        assertThrows(WorkingCopyBasisChangedException.class, () -> adapter.requireTrustedState(
                new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), candidate));
        assertEquals("untouched\n", Files.readString(copy.resolve("unchanged.txt")));
    }

    @Test
    void ordinaryRollbackConfirmsAllBaselineFiles() throws Exception {
        AuthorizedStepRollback authorized = fixture();
        assertEquals(baseline, adapter.restoreBaseline(authorized).revision());
        assertEquals("before\n", Files.readString(copy.resolve("changed.txt")));
        assertEquals("untouched\n", Files.readString(copy.resolve("unchanged.txt")));
        assertEquals(baseline, headRevision(copy));
    }
}
