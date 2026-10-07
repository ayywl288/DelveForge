package com.ayywl.delveforge.application.evolution.preparation;

import com.ayywl.delveforge.application.port.ai.*;
import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.application.port.workspace.*;
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryMaterialBudget;
import com.ayywl.delveforge.application.repositoryanalysis.region.*;
import com.ayywl.delveforge.application.repositoryanalysis.secret.*;
import com.ayywl.delveforge.domain.asset.*;
import com.ayywl.delveforge.domain.direction.*;
import com.ayywl.delveforge.domain.evidence.*;
import com.ayywl.delveforge.domain.evolution.*;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionPreparationUseCaseTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final EvolutionPlanId PLAN = new EvolutionPlanId("plan");
    private static final EvolutionStepId STEP = new EvolutionStepId("step");
    private static final WorkingCopyId COPY = new WorkingCopyId("copy");
    private static final SoftwareAssetId ASSET = new SoftwareAssetId("asset");
    private static final RepositoryProfileId PROFILE = new RepositoryProfileId("profile");
    private static final String PATCH = "--- a/src/A.java\n+++ b/src/A.java\n@@ -1 +1 @@\n-old\n+new\n";
    private static final ExecutionPreparationLimits LIMITS = new ExecutionPreparationLimits(
            2, 24, 48, 65536, 524288, 786432, 262144, 16, 16);

    private static EvolutionPlan plan() {
        EvidenceBasis basis = new EvidenceBasis(new Evidence(EvidenceSourceType.REPOSITORY,
                "src/A.java", "existing", 0.8, true), new RepositoryProfileEvidenceOrigin(PROFILE));
        return EvolutionPlan.reconstitute(PLAN, new ProductDirectionId("direction"), ASSET, PROFILE,
                new CurrentState("current", List.of(), List.of(), List.of()),
                new TargetState("problem", "target", "difference"), List.of(), List.of("change"),
                List.of(EvolutionStep.reconstitute(STEP, PLAN, "Schedule reports", "Reports only",
                        List.of("Add scheduling"), List.of(), List.of("Reports run on schedule"))),
                List.of(), List.of(basis), COPY, EvolutionPlanStatus.ACTIVE);
    }

    private static final class Store implements EvolutionPlanRepository, WorkingCopyRepository,
            SoftwareAssetRepository, ProductDirectionRepository, StepConfirmationCommitPort {
        private EvolutionPlan plan = ExecutionPreparationUseCaseTest.plan();
        private WorkingCopy copy = WorkingCopy.reconstitute(COPY, ASSET, "historical", "managed",
                "trusted", "trusted", WorkingCopyStatus.EVOLVING);
        private SoftwareAsset asset = SoftwareAsset.create(ASSET, SoftwareAssetType.GIT_REPOSITORY,
                SoftwareAssetSource.USER_SPECIFIED, "original", true, "MIT", UsageAuthorization.ALLOWED);
        private ProductDirection direction;
        private boolean failCommit;

        Store() {
            EvidenceBasis basis = plan.evidence().getFirst();
            direction = ProductDirection.create(new ProductDirectionId("direction"), new UserProfileId("user"), 1,
                    List.of(PROFILE), "title", "problem", "target", "match", List.of(ASSET), "difference",
                    "value", "small", List.of(), new DirectionEvidenceSupport(List.of(basis), List.of(basis), List.of(basis)));
            direction.select();
        }

        public void save(EvolutionPlan value) { throw new AssertionError("Preparation cannot save a Plan"); }
        public Optional<EvolutionPlan> findById(EvolutionPlanId id) { return Optional.of(plan); }
        public List<EvolutionPlan> findByProductDirectionId(ProductDirectionId id) { return List.of(plan); }
        public Optional<WorkingCopy> findById(WorkingCopyId id) { return Optional.of(copy); }
        public void save(SoftwareAsset value) { asset = value; }
        public Optional<SoftwareAsset> findById(SoftwareAssetId id) { return Optional.of(asset); }
        public void save(ProductDirection value) { direction = value; }
        public void saveAll(List<ProductDirection> values) { throw new AssertionError(); }
        public void saveTransitions(List<ProductDirectionTransition> values) { throw new AssertionError(); }
        public Optional<ProductDirection> findById(ProductDirectionId id) { return Optional.of(direction); }
        public Optional<ProductDirection> findCurrentSelected() { return Optional.of(direction); }
        public void confirm(EvolutionPlan candidate, EvolutionStepId id, WorkingCopy workingCopy, SoftwareAsset basis) {
            if (failCommit) {
                throw new EvolutionLifecycleConflictException("injected commit failure");
            }
            plan = candidate;
        }
    }

    private static final class Workspace implements WorkspaceReadPort, WorkingCopyInspectionPort {
        private final Map<String, String> files = new TreeMap<>(Map.of(
                "src/A.java", "old\n", "src/Dependency.java", "dependency\n",
                "src/test/ThingTest.java", "test\n",
                "application.yml", "password: CANARY-DB-PASSWORD-0001\n",
                ".env", "SECRET=never-read\n"));
        private final List<String> reads = new ArrayList<>();
        private String head = "trusted";
        public boolean isReadableRepository(WorkspaceRef ref) { return true; }
        public String headRevision(WorkspaceRef ref) { return head; }
        public List<WorkspaceEntry> listEntries(WorkspaceRef ref, String revision, String path, int depth) {
            assertEquals("managed", ref.value());
            assertEquals("trusted", revision);
            return files.entrySet().stream().map(entry -> new WorkspaceEntry(entry.getKey(), false,
                    entry.getValue().getBytes(StandardCharsets.UTF_8).length)).toList();
        }
        public String readFile(WorkspaceRef ref, String revision, String path) {
            assertEquals("managed", ref.value());
            assertEquals("trusted", revision);
            reads.add(path);
            return files.get(path);
        }
        public void requireTrustedState(WorkspaceRef ref, WorkspaceRef source, String revision) {
            assertEquals("original", source.value());
            if (!head.equals(revision)) {
                throw new WorkingCopyBasisChangedException("drift");
            }
        }
        public void requireSafePaths(WorkspaceRef ref, List<String> paths) { assertEquals("managed", ref.value()); }
        public void requireExactSourceText(WorkspaceRef ref, String revision, Map<String, String> originals) {
            assertEquals("managed", ref.value());
            assertEquals("trusted", revision);
            originals.forEach((path, content) -> assertEquals(files.get(path), content));
        }
    }

    private static final class Gateway implements AiGateway {
        private final List<AiRequest> requests = new ArrayList<>();
        private final Deque<String> coding = new ArrayDeque<>();
        private Consumer<AiRequest> afterCall = request -> {};
        private boolean unknownScoutRef;
        private int fileCalls;
        private int regionCalls;
        public String generate(AiRequest request) {
            requests.add(request);
            JsonNode payload = json(request.messages().stream().filter(message -> message.role() == AiRole.USER)
                    .findFirst().orElseThrow().content());
            String result;
            if (payload.has("sourceFiles")) {
                result = coding.removeFirst();
            } else if (payload.has("regionCatalog")) {
                regionCalls++;
                String ref = payload.path("regionCatalog").get(0).path("reference").asText();
                result = "{\"regionRefs\":[\"" + ref + "\"]}";
            } else {
                fileCalls++;
                String ref = "";
                for (JsonNode file : payload.path("fileCatalog")) {
                    if (file.path("path").asText().equals("src/A.java")) {
                        ref = file.path("reference").asText();
                    }
                }
                if (ref.isEmpty()) {
                    ref = payload.path("fileCatalog").get(0).path("reference").asText();
                }
                if (unknownScoutRef) {
                    ref = "RF-9999";
                }
                result = "{\"focusAreas\":["
                        + "{\"label\":\"implementation\",\"fileRefs\":[\"" + ref + "\"]},"
                        + "{\"label\":\"dependencies\",\"fileRefs\":[\"" + ref + "\"]},"
                        + "{\"label\":\"verification\",\"fileRefs\":[\"" + ref + "\"]}]}";
            }
            afterCall.accept(request);
            return result;
        }
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String proposal(String patch, List<String> paths) {
        try {
            return MAPPER.writeValueAsString(Map.of("type", "CHANGE_PROPOSAL", "expectedRevision", "trusted",
                    "summary", "Schedule reports", "declaredAffectedPaths", paths, "unifiedDiff", patch,
                    "verificationStrategy", List.of(Map.of("action", "TEST", "target", "", "criterionIndexes", List.of(1)))));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String more(String path) {
        return "{\"type\":\"NEED_MORE_CONTEXT\",\"expectedRevision\":\"trusted\","
                + "\"missingDependency\":\"Need the existing test contract\",\"pathPrefix\":\"" + path + "\"}";
    }

    private static final class Fixture {
        private final Store store = new Store();
        private final Workspace workspace = new Workspace();
        private final Gateway gateway = new Gateway();
        private final RepositorySecretPolicy secrets = new DeterministicRepositorySecretPolicy();
        private final ExecutionPreparationState state = new ExecutionPreparationState(
                store, store, store, store, workspace, new StepExecutionPolicy());
        private final ExecutionPreparationLimits limits;
        private final int catalogBytes;

        Fixture() { this(LIMITS, 65536); }
        Fixture(ExecutionPreparationLimits limits, int catalogBytes) {
            this.limits = limits;
            this.catalogBytes = catalogBytes;
        }

        private void confirm() {
            new ConfirmEvolutionStepUseCase(state, store, new StepExecutionPolicy()).confirm(PLAN, STEP);
        }

        private PrepareEvolutionStepUseCase useCase() {
            RepositoryMaterialBudget budget = new RepositoryMaterialBudget(12, 65536, 262144);
            return new PrepareEvolutionStepUseCase(state,
                    new StepRepositoryUnderstanding(workspace, gateway, MAPPER, secrets, limits, budget, budget,
                            new RegionNavigationLimits(65536, 4), new RegionRecursionBudget(8, 12), catalogBytes),
                    new CodingProposalParser(MAPPER, limits),
                    new CodingProposalValidator(secrets, workspace, limits), MAPPER, limits);
        }

        private void assertReady() {
            assertEquals(EvolutionStepStatus.READY, store.plan.step(STEP).status());
            assertNull(store.plan.step(STEP).baselineRevision());
            assertEquals("trusted", store.copy.currentRevision());
            assertEquals("trusted", store.copy.lastVerifiedRevision());
            assertEquals("old\n", workspace.files.get("src/A.java"));
        }
    }

    @Test void explicitConfirmationIsAtomicAndNeverCallsAIOrReadsSource() {
        Fixture f = new Fixture();
        EvolutionPlan loaded = f.store.plan;
        f.store.failCommit = true;
        assertThrows(EvolutionLifecycleConflictException.class, f::confirm);
        assertEquals(EvolutionStepStatus.PENDING_CONFIRMATION, loaded.step(STEP).status());
        assertEquals(EvolutionStepStatus.PENDING_CONFIRMATION, f.store.plan.step(STEP).status());
        f.store.failCommit = false;
        f.confirm();
        f.assertReady();
        assertEquals(EvolutionStepStatus.PENDING_CONFIRMATION, loaded.step(STEP).status());
        assertTrue(f.gateway.requests.isEmpty());
        assertTrue(f.workspace.reads.isEmpty());
    }

    @Test void producesOneValidatedProposalAtCurrentTrustedRevisionAndPreservesSecretBoundary() {
        Fixture f = new Fixture();
        f.confirm();
        f.gateway.coding.add(proposal(PATCH, List.of("src/A.java")));
        ValidatedExecutionProposal result = f.useCase().prepare(PLAN, STEP);
        assertEquals("trusted", result.basis().expectedRevision());
        assertEquals(PATCH, result.proposal().unifiedDiff());
        assertEquals(List.of("src/A.java"), result.proposal().declaredAffectedPaths());
        assertTrue(result.modelVisiblePaths().contains("src/A.java"));
        assertFalse(result.modelVisiblePaths().contains("src/Dependency.java"));
        assertFalse(f.workspace.reads.contains(".env"));
        assertEquals(2, f.gateway.requests.size());
        String requests = f.gateway.requests.toString();
        assertTrue(requests.contains("Schedule reports"));
        assertTrue(requests.contains("Reports only"));
        assertFalse(requests.contains("CANARY-DB-PASSWORD-0001"));
        assertFalse(requests.contains("SECRET=never-read"));
        f.assertReady();
    }

    @Test void expandsNarrowExistingTestContextAndIncludesItInTheSameImplementationPatch() {
        Fixture f = new Fixture();
        f.confirm();
        f.gateway.coding.add(more("src/test/ThingTest.java"));
        String patch = PATCH + "--- a/src/test/ThingTest.java\n+++ b/src/test/ThingTest.java\n"
                + "@@ -1 +1 @@\n-test\n+updated test\n";
        f.gateway.coding.add(proposal(patch, List.of("src/A.java", "src/test/ThingTest.java")));
        ValidatedExecutionProposal result = f.useCase().prepare(PLAN, STEP);
        assertTrue(result.modelVisiblePaths().contains("src/test/ThingTest.java"));
        assertFalse(f.workspace.reads.contains("src/Dependency.java"));
        assertEquals(4, f.gateway.requests.size());
        f.assertReady();
    }

    @Test void unconfirmedStepDoesNotScoutOrCode() {
        Fixture f = new Fixture();
        assertThrows(EvolutionPlanStateException.class, () -> f.useCase().prepare(PLAN, STEP));
        assertTrue(f.gateway.requests.isEmpty());
        assertTrue(f.workspace.reads.isEmpty());
    }

    @Test void unknownScoutRefRejectsBeforeAnySourceRead() {
        Fixture f = new Fixture();
        f.confirm();
        f.gateway.unknownScoutRef = true;
        assertThrows(AiGatewayException.class, () -> f.useCase().prepare(PLAN, STEP));
        assertTrue(f.workspace.reads.isEmpty());
        f.assertReady();
    }

    @Test void AIAndParsingFailuresLeaveReadyStateAndSourceIntact() {
        for (String bad : List.of("not json", "{}", "{\"type\":\"CHANGE_PROPOSAL\"}")) {
            Fixture f = new Fixture();
            f.confirm();
            f.gateway.coding.add(bad);
            assertThrows(AiGatewayException.class, () -> f.useCase().prepare(PLAN, STEP));
            f.assertReady();
        }
    }

    @Test void revisionOrAuthorizationDriftDuringModelCallFailsClosed() {
        for (boolean revisionDrift : List.of(true, false)) {
            Fixture f = new Fixture();
            f.confirm();
            f.gateway.afterCall = request -> {
                if (revisionDrift) {
                    f.workspace.head = "changed";
                } else {
                    f.store.asset = SoftwareAsset.create(ASSET, SoftwareAssetType.GIT_REPOSITORY,
                            SoftwareAssetSource.USER_SPECIFIED, "original", true, "MIT", UsageAuthorization.DENIED);
                }
            };
            Class<? extends RuntimeException> expected = revisionDrift
                    ? WorkingCopyBasisChangedException.class : AssetEvolutionNotAllowedException.class;
            assertThrows(expected,
                    () -> f.useCase().prepare(PLAN, STEP));
            assertEquals(1, f.gateway.requests.size());
            f.assertReady();
        }
    }

    @Test void expansionRoundsAndCumulativeScoutCallsAreNotReset() {
        ExecutionPreparationLimits noExpansion = new ExecutionPreparationLimits(0, 24, 48, 65536, 524288,
                786432, 262144, 16, 16);
        Fixture f = new Fixture(noExpansion, 65536);
        f.confirm();
        f.gateway.coding.add(more("src/test"));
        assertThrows(ExecutionPreparationBudgetExceededException.class, () -> f.useCase().prepare(PLAN, STEP));
        assertEquals(2, f.gateway.requests.size());
        f.assertReady();

        ExecutionPreparationLimits oneScout = new ExecutionPreparationLimits(2, 1, 48, 65536, 524288,
                786432, 262144, 16, 16);
        Fixture g = new Fixture(oneScout, 65536);
        g.confirm();
        g.gateway.coding.add(more("src/test"));
        assertThrows(ExecutionPreparationBudgetExceededException.class, () -> g.useCase().prepare(PLAN, STEP));
        assertEquals(2, g.gateway.requests.size());
        g.assertReady();
    }

    @Test void cumulativeFileAndByteBudgetsStopBeforeCoding() {
        for (ExecutionPreparationLimits limit : List.of(
                new ExecutionPreparationLimits(2, 24, 1, 65536, 524288, 786432, 262144, 16, 16),
                new ExecutionPreparationLimits(2, 24, 48, 10, 10, 786432, 262144, 16, 16))) {
            Fixture f = new Fixture(limit, 65536);
            f.confirm();
            assertThrows(ExecutionPreparationBudgetExceededException.class, () -> f.useCase().prepare(PLAN, STEP));
            assertEquals(1, f.gateway.requests.size());
            f.assertReady();
        }
    }

    @Test void invalidOrRepeatedContextRequestsAreRejectedWithoutUnrestrictedReads() {
        for (String path : List.of("../", "missing", ".env", "/src", "src/A.java")) {
            Fixture f = new Fixture();
            f.confirm();
            f.gateway.coding.add(more(path));
            assertThrows(AiGatewayException.class, () -> f.useCase().prepare(PLAN, STEP));
            assertFalse(f.workspace.reads.contains("src/Dependency.java"));
            f.assertReady();
        }
    }

    @Test void queryAwareHierarchicalScoutReusesValidatedRegionReferences() {
        Fixture f = new Fixture(LIMITS, 210);
        f.workspace.files.put("src/dependency/Dependency.java", f.workspace.files.remove("src/Dependency.java"));
        f.confirm();
        f.gateway.coding.add(proposal(PATCH, List.of("src/A.java")));
        ValidatedExecutionProposal result = f.useCase().prepare(PLAN, STEP);
        assertTrue(f.gateway.regionCalls > 0);
        assertTrue(f.gateway.fileCalls > 0);
        assertEquals("trusted", result.basis().expectedRevision());
        assertTrue(f.gateway.requests.stream().allMatch(request -> request.messages().toString().contains("Schedule reports")));
        f.assertReady();
    }

    @Test void repeatedExpansionScopeCannotStartAnotherScoutRound() {
        Fixture f = new Fixture();
        f.confirm();
        f.gateway.coding.add(more("src/test"));
        f.gateway.coding.add(more("src/test"));
        assertThrows(AiGatewayException.class, () -> f.useCase().prepare(PLAN, STEP));
        assertEquals(4, f.gateway.requests.size());
        f.assertReady();
    }

    @Test void requestAndResponseBudgetsFailAtTheAIInvocationBoundary() {
        ExecutionPreparationLimits requestLimit = new ExecutionPreparationLimits(2, 24, 48, 65536,
                524288, 10, 262144, 16, 16);
        Fixture f = new Fixture(requestLimit, 65536);
        f.confirm();
        assertThrows(ExecutionPreparationBudgetExceededException.class, () -> f.useCase().prepare(PLAN, STEP));
        assertTrue(f.gateway.requests.isEmpty());
        f.assertReady();

        ExecutionPreparationLimits responseLimit = new ExecutionPreparationLimits(2, 24, 48, 65536,
                524288, 786432, 10, 16, 16);
        Fixture g = new Fixture(responseLimit, 65536);
        g.confirm();
        assertThrows(AiGatewayException.class, () -> g.useCase().prepare(PLAN, STEP));
        assertEquals(1, g.gateway.requests.size());
        assertTrue(g.workspace.reads.isEmpty());
        g.assertReady();
    }
}
