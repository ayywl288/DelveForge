package com.ayywl.delveforge.application.evolution.execution;

import com.ayywl.delveforge.application.evolution.preparation.*;
import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.application.port.workspace.*;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.domain.asset.*;
import com.ayywl.delveforge.domain.direction.*;
import com.ayywl.delveforge.domain.evidence.*;
import com.ayywl.delveforge.domain.evolution.*;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ExecuteEvolutionStepUseCaseTest {
    private static final EvolutionPlanId PLAN = new EvolutionPlanId("plan");
    private static final EvolutionStepId STEP = new EvolutionStepId("step");
    private static final SoftwareAssetId ASSET = new SoftwareAssetId("asset");
    private static final RepositoryProfileId PROFILE = new RepositoryProfileId("profile");
    private static final WorkingCopyId COPY = new WorkingCopyId("copy");

    private static final class Store implements EvolutionPlanRepository, WorkingCopyRepository,
            SoftwareAssetRepository, ProductDirectionRepository, StepExecutionCommitPort {
        EvolutionPlan plan;
        WorkingCopy copy = WorkingCopy.reconstitute(COPY, ASSET, "A", "managed", "A", "A", WorkingCopyStatus.READY);
        SoftwareAsset asset = SoftwareAsset.create(ASSET, SoftwareAssetType.GIT_REPOSITORY, SoftwareAssetSource.USER_SPECIFIED,
                "original", true, "MIT", UsageAuthorization.ALLOWED);
        ProductDirection direction;
        int commits;
        int failAt;
        final List<EvolutionPlan> historicalObjects = new ArrayList<>();
        Store() {
            this(VerificationCriterion.technical(VerificationCriterion.Kind.FULL_TEST_SUITE_PASSES));
        }
        Store(VerificationCriterion criterion) {
            EvidenceBasis evidence = new EvidenceBasis(new Evidence(EvidenceSourceType.REPOSITORY, "src/A.java", "source", 0.8, true),
                    new RepositoryProfileEvidenceOrigin(PROFILE));
            plan = EvolutionPlan.reconstitute(PLAN, new ProductDirectionId("direction"), ASSET, PROFILE,
                    new CurrentState("current", List.of(), List.of(), List.of()), new TargetState("problem", "target", "difference"),
                    List.of(), List.of("change"), List.of(EvolutionStep.reconstitute(STEP, PLAN, "goal", "scope",
                    List.of("change"), List.of(criterion), EvolutionStepStatus.READY)), List.of(),
                    List.of(evidence), COPY, EvolutionPlanStatus.ACTIVE);
            direction = ProductDirection.create(new ProductDirectionId("direction"), new UserProfileId("user"), 1,
                    List.of(PROFILE), "title", "problem", "target", "match", List.of(ASSET), "difference",
                    "value", "small", List.of(), new DirectionEvidenceSupport(List.of(evidence), List.of(evidence), List.of(evidence)));
            direction.select();
        }
        public Optional<EvolutionPlan> findById(EvolutionPlanId id) { return Optional.of(plan); }
        public List<EvolutionPlan> findByProductDirectionId(ProductDirectionId id) { return List.of(plan); }
        public void save(EvolutionPlan plan) { throw new AssertionError(); }
        public Optional<WorkingCopy> findById(WorkingCopyId id) { return Optional.of(copy); }
        public void save(SoftwareAsset value) { asset = value; }
        public Optional<SoftwareAsset> findById(SoftwareAssetId id) { return Optional.of(asset); }
        public void save(ProductDirection value) { direction = value; }
        public void saveAll(List<ProductDirection> values) { throw new AssertionError(); }
        public void saveTransitions(List<ProductDirectionTransition> values) { throw new AssertionError(); }
        public Optional<ProductDirection> findById(ProductDirectionId id) { return Optional.of(direction); }
        public Optional<ProductDirection> findCurrentSelected() { return Optional.of(direction); }
        public void commit(EvolutionPlan expected, EvolutionStepId id, WorkingCopy before, SoftwareAsset asset,
                EvolutionPlan updated, WorkingCopy after) {
            commits++;
            historicalObjects.add(plan);
            assertSame(plan, expected);
            assertSame(copy, before);
            if (commits == failAt) {
                throw new EvolutionLifecycleConflictException("injected persistence failure");
            }
            plan = updated;
            copy = after;
        }
    }

    private static final class Workspace implements WorkingCopyInspectionPort, WorkspaceMutationPort, WorkspaceVerificationPort {
        String head = "A";
        boolean mutationFails;
        boolean unexpected;
        boolean verificationFails;
        boolean unsupported;
        boolean driftAtSupport;
        boolean wrongVerification;
        int mutations;
        int verifications;
        Store store;
        public void requireTrustedState(WorkspaceRef copy, WorkspaceRef original, String revision) {
            assertEquals("managed", copy.value());
            assertEquals("original", original.value());
            if (!head.equals(revision)) {
                throw new WorkingCopyBasisChangedException("drift");
            }
        }
        public void requireSafePaths(WorkspaceRef copy, List<String> paths) {}
        public void requireExactSourceText(WorkspaceRef copy, String revision, Map<String, String> originals) {}
        public void requireSupported(CandidateVerification request) {
            if (unsupported) { throw new EvolutionExecutionException("unsupported action"); }
            if (driftAtSupport) { head = "external"; }
        }
        public CandidateMutationResult applyCandidate(AuthorizedStepExecution execution) {
            mutations++;
            assertEquals(EvolutionStepStatus.EXECUTING, store.plan.step(STEP).status());
            assertEquals("A", store.plan.step(STEP).baselineRevision());
            assertEquals("A", store.copy.lastVerifiedRevision());
            head = "B";
            if (mutationFails) { throw new EvolutionExecutionException("partial mutation"); }
            return new CandidateMutationResult("B", List.of(unexpected ? ".git/config" : "src/A.java"),
                    List.of("one insertion / one deletion"), List.of(), Instant.now());
        }
        public VerificationFacts verify(CandidateVerification request) {
            verifications++;
            assertEquals("B", request.candidateRevision());
            assertEquals("A", request.baselineRevision());
            assertEquals(EvolutionStepStatus.VERIFYING, store.plan.step(STEP).status());
            assertEquals("B", store.copy.currentRevision());
            assertEquals("A", store.copy.lastVerifiedRevision());
            return new VerificationFacts(wrongVerification ? "C" : "B", List.of(new VerificationFacts.Check(
                    CodingProposal.Action.TEST, "", !verificationFails, "exit")), Instant.now());
        }
    }

    private static final class Fixture {
        final Store store;
        final Workspace workspace = new Workspace();
        final ExecutionPreparationState state;
        Fixture() { this(VerificationCriterion.technical(VerificationCriterion.Kind.FULL_TEST_SUITE_PASSES)); }
        Fixture(VerificationCriterion criterion) {
            store = new Store(criterion);
            state = new ExecutionPreparationState(store, store, store, store, workspace, new StepExecutionPolicy());
            workspace.store = store;
        }
        ValidatedExecutionProposal proposal(String revision) {
            return proposal(revision, CodingProposal.Action.TEST);
        }
        ValidatedExecutionProposal proposal(String revision, CodingProposal.Action action) {
            CodingProposal proposal = new CodingProposal(revision, "change", List.of("src/A.java"),
                    "--- a/src/A.java\n+++ b/src/A.java\n@@ -1 +1 @@\n-old\n+new\n",
                    List.of(new CodingProposal.VerificationAction(action,
                            action == CodingProposal.Action.FOCUSED_TEST ? "UnrelatedTest#value" : "", List.of(1))));
            return new CodingProposalValidator(new DeterministicRepositorySecretPolicy(), workspace,
                    new ExecutionPreparationLimits(2,24,48,65536,524288,786432,262144,16,16))
                    .validate(proposal, new ExecutionPreparationBasis(PLAN, STEP, COPY, revision), store.plan.step(STEP),
                    new WorkspaceRef("managed"), Map.of("src/A.java", "old\n"), Set.of(), Set.of("src/A.java"));
        }
        ExecuteEvolutionStepUseCase useCase() {
            return new ExecuteEvolutionStepUseCase(null, state, new StepExecutionPolicy(), store, workspace, workspace);
        }
    }

    @Test void successfulPathCommitsThreeCompleteCheckpointsWithoutMutatingLoadedObjects() {
        Fixture f = new Fixture();
        WorkingCopy loaded = f.store.copy;
        EvolutionPlan result = f.useCase().execute(f.proposal("A"));
        assertEquals(3, f.store.commits);
        assertEquals(EvolutionStepStatus.SUCCEEDED, result.step(STEP).status());
        assertEquals("B", f.store.copy.currentRevision());
        assertEquals("B", f.store.copy.lastVerifiedRevision());
        assertEquals("A", loaded.currentRevision());
        assertEquals(List.of(EvolutionStepStatus.READY, EvolutionStepStatus.EXECUTING, EvolutionStepStatus.VERIFYING),
                f.store.historicalObjects.stream().map(plan -> plan.step(STEP).status()).toList());
        assertEquals(1, f.workspace.mutations);
        assertEquals(1, f.workspace.verifications);
    }
    @ParameterizedTest @ValueSource(ints = {1,2,3})
    void persistenceFailurePreservesLastCompleteCheckpointAndNeverPromotesTrust(int failAt) {
        Fixture f = new Fixture();
        f.store.failAt = failAt;
        assertThrows(EvolutionLifecycleConflictException.class, () -> f.useCase().execute(f.proposal("A")));
        assertEquals(List.of(EvolutionStepStatus.READY, EvolutionStepStatus.EXECUTING, EvolutionStepStatus.VERIFYING).get(failAt - 1),
                f.store.plan.step(STEP).status());
        assertEquals("A", f.store.copy.lastVerifiedRevision());
        assertNull(f.store.plan.step(STEP).verificationResult());
        assertEquals(failAt == 3 ? "B" : "A", f.store.copy.currentRevision());
    }
    @Test void staleProposalAndDriftBeforeMutationAreRejectedWithoutCheckpoint() {
        Fixture f = new Fixture();
        assertThrows(EvolutionExecutionException.class, () -> f.useCase().execute(f.proposal("old")));
        f.workspace.driftAtSupport = true;
        assertThrows(WorkingCopyBasisChangedException.class, () -> f.useCase().execute(f.proposal("A")));
        assertEquals(0, f.store.commits);
        assertEquals(0, f.workspace.mutations);
    }
    @Test void supersededOrNoLongerReadyStepRejectsPreparedProposal() {
        Fixture f = new Fixture();
        ValidatedExecutionProposal proposal = f.proposal("A");
        f.store.plan.supersede();
        assertThrows(EvolutionPlanStateException.class, () -> f.useCase().execute(proposal));
        Fixture g = new Fixture();
        ValidatedExecutionProposal second = g.proposal("A");
        g.store.plan.startStep(STEP, g.store.copy, new StepExecutionPolicy());
        assertThrows(EvolutionPlanStateException.class, () -> g.useCase().execute(second));
        assertEquals(0, f.workspace.mutations);
        assertEquals(0, g.workspace.mutations);
    }
    @Test void partialOrUnexpectedMutationNeverRecordsCandidateOrStartsVerification() {
        for (boolean partial : List.of(true, false)) {
            Fixture f = new Fixture();
            f.workspace.mutationFails = partial;
            f.workspace.unexpected = !partial;
            assertThrows(EvolutionExecutionException.class, () -> f.useCase().execute(f.proposal("A")));
            assertEquals(EvolutionStepStatus.EXECUTING, f.store.plan.step(STEP).status());
            assertNull(f.store.plan.step(STEP).executionResult());
            assertEquals("A", f.store.copy.currentRevision());
            assertEquals("A", f.store.copy.lastVerifiedRevision());
            assertEquals(0, f.workspace.verifications);
            assertThrows(EvolutionPlanStateException.class, () -> f.state.load(PLAN, STEP, false));
        }
    }
    @Test void unsupportedVerificationStopsWhileReadyBeforeAnyMutation() {
        Fixture f = new Fixture();
        f.workspace.unsupported = true;
        assertThrows(EvolutionExecutionException.class, () -> f.useCase().execute(f.proposal("A")));
        assertEquals(EvolutionStepStatus.READY, f.store.plan.step(STEP).status());
        assertNull(f.store.plan.step(STEP).baselineRevision());
        assertEquals(0, f.workspace.mutations);
    }
    @ParameterizedTest @ValueSource(strings = {"BUILD", "DIFF_CHECK", "TEST", "FOCUSED_TEST"})
    void businessCriterionWithUnrelatedTechnicalChecksFailsBeforeMutation(String action) {
        Fixture f = new Fixture(VerificationCriterion.unsupported("Reports run on schedule"));
        assertThrows(EvolutionExecutionException.class, () -> f.useCase().execute(f.proposal("A", CodingProposal.Action.valueOf(action))));
        assertEquals(EvolutionStepStatus.READY, f.store.plan.step(STEP).status());
        assertNull(f.store.plan.step(STEP).baselineRevision());
        assertEquals("A", f.store.copy.currentRevision());
        assertEquals("A", f.store.copy.lastVerifiedRevision());
        assertEquals(0, f.store.commits);
        assertEquals(0, f.workspace.mutations);
        assertEquals(0, f.workspace.verifications);
    }
    @Test void failedVerificationOrWrongCandidateNeverMarksSuccess() {
        for (boolean wrong : List.of(true, false)) {
            Fixture f = new Fixture();
            f.workspace.wrongVerification = wrong;
            f.workspace.verificationFails = !wrong;
            assertThrows(RuntimeException.class, () -> f.useCase().execute(f.proposal("A")));
            assertEquals(EvolutionStepStatus.VERIFYING, f.store.plan.step(STEP).status());
            assertEquals("B", f.store.plan.step(STEP).executionResult().candidateRevision());
            assertEquals("A", f.store.copy.lastVerifiedRevision());
            assertNull(f.store.plan.step(STEP).verificationResult());
        }
    }
}
