package com.ayywl.delveforge.application.evolution.query;

import com.ayywl.delveforge.application.port.persistence.EvolutionPlanRepository;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.persistence.WorkingCopyRepository;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyTreeDiffPort;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.asset.SoftwareAssetNotReadableException;
import com.ayywl.delveforge.domain.asset.SoftwareAssetSource;
import com.ayywl.delveforge.domain.asset.SoftwareAssetType;
import com.ayywl.delveforge.domain.asset.UsageAuthorization;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.evidence.RepositoryProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.evolution.CurrentState;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanStateException;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanStatus;
import com.ayywl.delveforge.domain.evolution.EvolutionStep;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.EvolutionStepStatus;
import com.ayywl.delveforge.domain.evolution.ExecutionResult;
import com.ayywl.delveforge.domain.evolution.TargetState;
import com.ayywl.delveforge.domain.evolution.VerificationCriterion;
import com.ayywl.delveforge.domain.evolution.VerificationResult;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;
import com.ayywl.delveforge.domain.evolution.WorkingCopyId;
import com.ayywl.delveforge.domain.evolution.WorkingCopyStatus;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GetStepFinalChangesUseCaseTest {
    private static final EvolutionPlanId PLAN = new EvolutionPlanId("plan");
    private static final EvolutionStepId STEP = new EvolutionStepId("step");
    private static final SoftwareAssetId ASSET = new SoftwareAssetId("asset");
    private static final WorkingCopyId COPY = new WorkingCopyId("copy");
    private static final String A = "a".repeat(40);
    private static final String C = "c".repeat(40);

    private static EvolutionPlan plan(boolean succeeded) {
        var criterion = VerificationCriterion.technical(VerificationCriterion.Kind.DIFF_CHECK_PASSES);
        var execution = new ExecutionResult(C, List.of("last repair"), List.of("last.java"),
                List.of(), List.of(), Instant.EPOCH);
        var verification = new VerificationResult(C, List.of(new VerificationResult.Check(
                "DIFF_CHECK", "", List.of(1), true, "passed")), true, List.of(), "passed", Instant.EPOCH);
        var step = succeeded ? EvolutionStep.reconstitute(STEP, PLAN, "goal", "scope", List.of("change"),
                List.of(criterion), EvolutionStepStatus.SUCCEEDED, A, execution, verification)
                : EvolutionStep.reconstitute(STEP, PLAN, "goal", "scope", List.of("change"), List.of(criterion),
                        EvolutionStepStatus.READY, null, null, null);
        return EvolutionPlan.reconstitute(PLAN, new ProductDirectionId("direction"), ASSET,
                new RepositoryProfileId("profile"), new CurrentState("current", List.of(), List.of(), List.of()),
                new TargetState("problem", "target", "difference"), List.of(), List.of("change"),
                List.of(step), List.of(), List.of(new EvidenceBasis(new Evidence(EvidenceSourceType.REPOSITORY,
                        "source", "synthetic evidence", 0.8, true),
                        new RepositoryProfileEvidenceOrigin(new RepositoryProfileId("profile")))),
                COPY, EvolutionPlanStatus.ACTIVE);
    }

    private static final class Store implements EvolutionPlanRepository, WorkingCopyRepository, SoftwareAssetRepository {
        EvolutionPlan plan = plan(true);
        WorkingCopy copy = WorkingCopy.reconstitute(COPY, ASSET, A, "managed", "d".repeat(40),
                "d".repeat(40), WorkingCopyStatus.EVOLVING);
        SoftwareAsset asset = SoftwareAsset.create(ASSET, SoftwareAssetType.GIT_REPOSITORY,
                SoftwareAssetSource.USER_SPECIFIED, "original", true, "MIT", UsageAuthorization.ALLOWED);
        public Optional<EvolutionPlan> findById(EvolutionPlanId id) { return Optional.of(plan); }
        public Optional<WorkingCopy> findById(WorkingCopyId id) { return Optional.ofNullable(copy); }
        public Optional<SoftwareAsset> findById(SoftwareAssetId id) { return Optional.of(asset); }
        public List<EvolutionPlan> findByProductDirectionId(ProductDirectionId id) { return List.of(plan); }
        public void save(EvolutionPlan value) { throw new AssertionError("Read only"); }
        public void save(SoftwareAsset value) { throw new AssertionError("Read only"); }
        GetStepFinalChangesUseCase useCase(WorkingCopyTreeDiffPort trees) {
            return new GetStepFinalChangesUseCase(this, this, this, trees);
        }
    }

    @Test
    void historicalSuccessUsesItsOwnVerifiedRevisionAndRealTreeCounts() {
        Store store = new Store();
        var result = store.useCase((copy, baseline, verified) -> {
            assertEquals(A, baseline);
            assertEquals(C, verified);
            return new WorkingCopyTreeDiffPort.Difference(null, "tree-a", "tree-c", List.of(
                    new WorkingCopyTreeDiffPort.FileChange("first.java", "ADDED"),
                    new WorkingCopyTreeDiffPort.FileChange("removed.java", "DELETED")));
        }).get(PLAN, STEP);
        assertEquals("AVAILABLE", result.availability());
        assertEquals(new GetStepFinalChangesUseCase.Counts(1, 0, 1), result.counts());
        assertEquals(2, result.files().size());
        assertEquals("d".repeat(40), store.copy.lastVerifiedRevision());
    }

    @Test
    void notSucceededDoesNotQueryGit() {
        Store store = new Store();
        store.plan = plan(false);
        var result = store.useCase((copy, baseline, verified) -> { throw new AssertionError(); }).get(PLAN, STEP);
        assertEquals("NOT_APPLICABLE", result.availability());
        assertNull(result.files());
    }

    @Test
    void unavailableDoesNotMasqueradeAsZeroChanges() {
        var result = new Store().useCase((copy, baseline, verified) ->
                WorkingCopyTreeDiffPort.Difference.unavailable("HISTORICAL_OBJECT_MISSING")).get(PLAN, STEP);
        assertEquals("UNAVAILABLE", result.availability());
        assertNull(result.files());
        assertNull(result.counts());
    }

    @Test
    void zeroNetChangeIsAvailable() {
        var result = new Store().useCase((copy, baseline, verified) ->
                new WorkingCopyTreeDiffPort.Difference(null, "tree", "tree", List.of())).get(PLAN, STEP);
        assertEquals("AVAILABLE", result.availability());
        assertEquals(new GetStepFinalChangesUseCase.Counts(0, 0, 0), result.counts());
    }

    @Test
    void missingCopyDoesNotQueryGit() {
        Store store = new Store();
        store.copy = null;
        assertEquals("WORKSPACE_MISSING", store.useCase((copy, a, c) -> { throw new AssertionError(); })
                .get(PLAN, STEP).reason());
    }

    @Test
    void permissionRevokedDuringTreeReadDoesNotReturnFiles() {
        Store store = new Store();
        var query = store.useCase((copy, a, c) -> {
            store.asset = SoftwareAsset.create(ASSET, SoftwareAssetType.GIT_REPOSITORY,
                    SoftwareAssetSource.USER_SPECIFIED, "original", false, "MIT", UsageAuthorization.ALLOWED);
            return new WorkingCopyTreeDiffPort.Difference(null, "tree", "tree", List.of());
        });
        assertThrows(SoftwareAssetNotReadableException.class, () -> query.get(PLAN, STEP));
    }

    @Test
    void deniedReadPermissionAndWrongAssetBindingFailClosed() {
        Store store = new Store();
        store.asset = SoftwareAsset.create(ASSET, SoftwareAssetType.GIT_REPOSITORY,
                SoftwareAssetSource.USER_SPECIFIED, "original", false, "MIT", UsageAuthorization.ALLOWED);
        var query = store.useCase((copy, a, c) -> { throw new AssertionError(); });
        assertThrows(SoftwareAssetNotReadableException.class, () -> query.get(PLAN, STEP));
        store.asset = SoftwareAsset.create(ASSET, SoftwareAssetType.GIT_REPOSITORY,
                SoftwareAssetSource.USER_SPECIFIED, "original", true, "MIT", UsageAuthorization.ALLOWED);
        store.copy = WorkingCopy.reconstitute(COPY, new SoftwareAssetId("other"), A, "managed", C, C,
                WorkingCopyStatus.EVOLVING);
        assertThrows(EvolutionPlanStateException.class, () -> query.get(PLAN, STEP));
    }
}
