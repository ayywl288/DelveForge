package com.ayywl.delveforge.application.evolution.planning;

import com.ayywl.delveforge.application.evolution.provisioning.*;
import com.ayywl.delveforge.application.opportunitydiscovery.direction.ProductDirectionNotFoundException;
import com.ayywl.delveforge.application.port.ai.*;
import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.application.port.workspace.*;
import com.ayywl.delveforge.application.repositoryanalysis.asset.SoftwareAssetNotFoundException;
import com.ayywl.delveforge.application.repositoryanalysis.profile.RepositoryProfileNotFoundException;
import com.ayywl.delveforge.domain.asset.*;
import com.ayywl.delveforge.domain.direction.*;
import com.ayywl.delveforge.domain.evidence.*;
import com.ayywl.delveforge.domain.evolution.*;
import com.ayywl.delveforge.domain.repositoryprofile.*;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenerateEvolutionPlanUseCaseTest {
    private static final SoftwareAssetId ASSET = new SoftwareAssetId("asset");
    private static final RepositoryProfileId PROFILE = new RepositoryProfileId("profile");
    private static final Evidence FACT = new Evidence(EvidenceSourceType.REPOSITORY, "src/report", "Existing report exports", 0.8, true);
    private static final EvidenceBasis BASIS = new EvidenceBasis(FACT, new RepositoryProfileEvidenceOrigin(PROFILE));
    private static SoftwareAsset asset(boolean read, String license, UsageAuthorization authorization) {
        return SoftwareAsset.create(ASSET, SoftwareAssetType.GIT_REPOSITORY, SoftwareAssetSource.USER_SPECIFIED,
                "never-open-this-path", read, license, authorization);
    }

    private static RepositoryProfile profile() {
        return RepositoryProfile.create(PROFILE, ASSET, "commit123", "Reporting", List.of("Java"),
                List.of("reports"), List.of("Export"), List.of("Render"), List.of("No scheduling"), List.of(), List.of(FACT));
    }

    private static ProductDirection direction(boolean selected) {
        ProductDirection direction = ProductDirection.create(new ProductDirectionId("direction"), new UserProfileId("user"), 1,
                List.of(PROFILE), "Personal reports", "Manual exports", "Scheduled reports", "Fits goals",
                List.of(ASSET), "Personal schedule", "Scheduling", "Small", List.of(),
                new DirectionEvidenceSupport(List.of(BASIS), List.of(BASIS), List.of(BASIS)));
        if (selected) {
            direction.select();
        }
        return direction;
    }

    private static PlanningProposal proposal() {
        return new PlanningProposal(new CurrentState("Existing exports with no scheduling", List.of("Export"),
                List.of("reports"), List.of("No scheduling")),
                new TargetState("Manual exports", "Scheduled reports", "Personal schedule"),
                List.of("Render", "Export"), List.of("Add scheduling"),
                List.of(new PlanningStepProposal("Schedule reports", "Reporting scheduling",
                        List.of("Add scheduling capability"), List.of(), List.of("Reports run at the user-selected time")),
                        new PlanningStepProposal("Expose schedule management", "Personal schedule controls",
                        List.of("Manage schedules"), List.of("Scheduling capability exists"), List.of("Users can change schedules"))),
                List.of("Missed reports"), List.of(BASIS));
    }

    private static final String JSON = """
{"currentState":{"summary":"Existing exports","capabilities":["Export"],"modules":["reports"],"limitations":["No scheduling"]},
"targetState":{"problem":"Manual exports","targetProduct":"Scheduled reports","differentiation":"Personal schedule"},
"reusableCapabilities":["Render"],"changes":["Add scheduling"],
"steps":[{"goal":"Schedule reports","scope":"Reporting scheduling","plannedChanges":["Add scheduling"],"preconditions":[],"verificationCriteria":["Reports run on schedule"]}],
"risks":[],"evidence":["R-E1","D-E1"]}
            """;
    private final Directions directions = new Directions();
    private final Assets assets = new Assets();
    private final Profiles profiles = new Profiles();
    private final Plans plans = new Plans();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger ids = new AtomicInteger();
    private String response = JSON;
    private RuntimeException aiFailure;
    private RuntimeException saveFailure;
    private Runnable duringAi = () -> {};
    private AiRequest sent;
    private int provisionCalls;
    private int cleanupCalls;
    private int activationCommits;
    private RuntimeException provisionFailure;
    private RuntimeException commitFailure;
    private RuntimeException cleanupFailure;
    private Runnable duringProvision = () -> {};
    private String preparedRevision = "commit123";
    private PrepareEvolutionPlanUseCase prepareUseCase() {
        return new PrepareEvolutionPlanUseCase(plans, directions, assets, profiles,
                new WorkingCopyProvisioningPort() {
                    public PreparedWorkspace provision(WorkspaceRef source, String revision, String name) {
                        provisionCalls++;
                        assertEquals("never-open-this-path", source.value());
                        assertEquals("commit123", revision);
                        assertEquals("copy", name);
                        duringProvision.run();
                        if (provisionFailure != null) {
                            throw provisionFailure;
                        }
                        return new PreparedWorkspace(new WorkspaceRef("managed-copy"), preparedRevision, "token");
                    }
                    public void discard(PreparedWorkspace candidate) {
                        cleanupCalls++;
                        if (cleanupFailure != null) {
                            throw cleanupFailure;
                        }
                    }
                }, new EvolutionLifecycleCommitPort() {
                    public void commitActivation(EvolutionPlan plan, WorkingCopy copy, SoftwareAsset basis) {
                        activationCommits++;
                        if (commitFailure != null) {
                            throw commitFailure;
                        }
                        assertEquals(WorkingCopyStatus.READY, copy.status());
                        assertEquals(copy.sourceRevision(), copy.currentRevision());
                        assertEquals(copy.sourceRevision(), copy.lastVerifiedRevision());
                        assertEquals(copy.id().value(), plan.workingCopyId());
                        plans.values.put(plan.id(), plan);
                    }
                    public void commitDirectionSelection(List<ProductDirectionTransition> directions, List<EvolutionPlanTransition> plans) {
                        throw new UnsupportedOperationException();
                    }
                }, new PlanActivationPolicy(new AssetUsagePolicy()), () -> new WorkingCopyId("copy"));
    }

    private EvolutionPlan seedProposed() {
        seed();

        EvolutionPlan plan = new EvolutionPlanningService(new AssetUsagePolicy(), () -> new EvolutionPlanId("plan"),
                () -> new EvolutionStepId("step-" + ids.incrementAndGet()))
                .plan(direction(true), assets.value, profiles.value, proposal());
        plans.save(plan);
        return plan;
    }

    @Test
    void preparesAndActivatesThroughTechnicalPortWithoutAiOrStepAuthorization() {
        EvolutionPlan loaded = seedProposed();
        EvolutionPlan active = prepareUseCase().prepare(loaded.id());
        assertEquals(EvolutionPlanStatus.ACTIVE, active.status());
        assertEquals("copy", active.workingCopyId());
        active.steps().forEach(step -> {
            assertEquals(EvolutionStepStatus.PENDING_CONFIRMATION, step.status());
            assertNull(step.baselineRevision());
        });
        assertEquals(EvolutionPlanStatus.PROPOSED, loaded.status());
        assertNull(loaded.workingCopyId());
        assertEquals(1, provisionCalls);
        assertEquals(1, activationCommits);
        assertEquals(0, cleanupCalls);
        assertEquals(0, calls.get());
        assertThrows(EvolutionPlanStateException.class, () -> prepareUseCase().prepare(loaded.id()));
        assertEquals(1, provisionCalls);
    }

    @Test
    void rejectsAuthorizationAndSupersededDirectionBeforeProvisioning() {
        EvolutionPlan loaded = seedProposed();
        assets.value = asset(true, "MIT", UsageAuthorization.DENIED);
        assertThrows(AssetEvolutionNotAllowedException.class, () -> prepareUseCase().prepare(loaded.id()));
        assets.value = asset(true, "MIT", UsageAuthorization.ALLOWED);
        directions.values.get(new ProductDirectionId("direction")).supersede();
        assertThrows(EvolutionPlanStateException.class, () -> prepareUseCase().prepare(loaded.id()));
        assertEquals(0, provisionCalls);
        assertEquals(0, activationCommits);
        assertEquals(EvolutionPlanStatus.PROPOSED, loaded.status());
        assertNull(loaded.workingCopyId());
    }

    @Test
    void rechecksAuthorizationAfterProvisionAndCleansUncommittedCandidate() {
        EvolutionPlan loaded = seedProposed();
        duringProvision = () -> assets.value = asset(true, "MIT", UsageAuthorization.DENIED);
        assertThrows(AssetEvolutionNotAllowedException.class, () -> prepareUseCase().prepare(loaded.id()));
        assertSame(loaded, plans.values.get(loaded.id()));
        assertEquals(EvolutionPlanStatus.PROPOSED, loaded.status());
        assertNull(loaded.workingCopyId());
        assertEquals(1, cleanupCalls);
        assertEquals(0, activationCommits);
    }

    @Test
    void externalAndDomainFailuresNeverMutateLoadedPlan() {
        EvolutionPlan loaded = seedProposed();
        provisionFailure = new WorkspaceException("failed clone");
        assertSame(provisionFailure, assertThrows(WorkspaceException.class, () -> prepareUseCase().prepare(loaded.id())));
        assertEquals(0, cleanupCalls); // provision 自身失败时，由 Adapter 负责清理其未完成的 clone。
        provisionFailure = null;
        preparedRevision = "unexpected";
        assertThrows(EvolutionPlanStateException.class, () -> prepareUseCase().prepare(loaded.id()));
        assertEquals(1, cleanupCalls);
        assertEquals(0, activationCommits);
        assertSame(loaded, plans.values.get(loaded.id()));
        assertEquals(EvolutionPlanStatus.PROPOSED, loaded.status());
        assertNull(loaded.workingCopyId());
    }

    @Test
    void commitFailurePreservesLoadedStateAndCleanupFailureCannotReplacePrimaryFailure() {
        EvolutionPlan loaded = seedProposed();
        commitFailure = new EvolutionLifecycleConflictException("changed during commit");
        cleanupFailure = new WorkspaceException("cleanup failed");
        assertSame(commitFailure, assertThrows(EvolutionLifecycleConflictException.class,
                () -> prepareUseCase().prepare(loaded.id())));
        assertEquals(List.of(cleanupFailure), List.of(commitFailure.getSuppressed()));
        assertSame(loaded, plans.values.get(loaded.id()));
        assertEquals(EvolutionPlanStatus.PROPOSED, loaded.status());
        assertNull(loaded.workingCopyId());
        assertEquals(1, cleanupCalls);
    }

    @Test
    void failedDirectionSwitchLeavesAllRepositoryLoadedInstancesUnchanged() {
        EvolutionPlan loadedPlan = seedProposed();
        ProductDirection previous = directions.values.get(new ProductDirectionId("direction"));
        ProductDirection target = ProductDirection.reconstitute(new ProductDirectionId("target"), previous.userProfileId(),
                previous.userProfileRevision(), previous.repositoryProfileIds(), previous.title(), previous.problem(),
                previous.targetProduct(), previous.userFit(), previous.candidateAssetIds(), previous.differentiation(),
                previous.technicalValue(), previous.estimatedComplexity(), previous.risks(), previous.evidenceSupport(),
                ProductDirectionStatus.CANDIDATE);
        directions.values.put(target.id(), target);
        var fault = new EvolutionLifecycleConflictException("failed atomic switch");
        var selection = new com.ayywl.delveforge.application.opportunitydiscovery.direction.SelectProductDirectionUseCase(
                directions, plans, new EvolutionLifecycleCommitPort() {
                    public void commitActivation(EvolutionPlan plan, WorkingCopy copy, SoftwareAsset asset) {
                        throw new UnsupportedOperationException();
                    }
                    public void commitDirectionSelection(List<ProductDirectionTransition> directionChanges,
                            List<EvolutionPlanTransition> planChanges) {
                        assertEquals(ProductDirectionStatus.SUPERSEDED, directionChanges.getFirst().direction().status());
                        assertEquals(ProductDirectionStatus.SELECTED, directionChanges.getLast().direction().status());
                        assertEquals(EvolutionPlanStatus.SUPERSEDED, planChanges.getFirst().plan().status());
                        throw fault;
                    }
                });
        assertSame(fault, assertThrows(EvolutionLifecycleConflictException.class, () -> selection.select(target.id())));
        assertEquals(ProductDirectionStatus.SELECTED, previous.status());
        assertEquals(ProductDirectionStatus.CANDIDATE, target.status());
        assertEquals(EvolutionPlanStatus.PROPOSED, loadedPlan.status());
        assertNull(loadedPlan.workingCopyId());
    }

    private GenerateEvolutionPlanUseCase useCase() {
        return new GenerateEvolutionPlanUseCase(directions, assets, profiles,
                new EvolutionPlanningExtraction(request -> {
                    calls.incrementAndGet();
                    sent = request;
                    duringAi.run();
                    if (aiFailure != null) {
                        throw aiFailure;
                    }
                    return response;
                }, new ObjectMapper()),
                new EvolutionPlanningService(new AssetUsagePolicy(),
                        () -> new EvolutionPlanId("plan-" + ids.incrementAndGet()),
                        () -> new EvolutionStepId("step-" + ids.incrementAndGet())), plans);
    }

    private void seed() {
        directions.values.put(new ProductDirectionId("direction"), direction(true));
        assets.value = asset(true, "MIT", UsageAuthorization.ALLOWED);
        profiles.value = profile();
    }

    private GenerateEvolutionPlanRequest request() {
        return new GenerateEvolutionPlanRequest(new ProductDirectionId("direction"), ASSET, PROFILE);
    }

    @Test
    void generatesAndRetrievesOneAtomicResultFromOneSemanticAiInvocation() throws Exception {
        seed();

        EvolutionPlan plan = useCase().generate(request());
        assertSame(plan, new GetEvolutionPlanUseCase(plans).get(plan.id()));
        assertEquals(1, calls.get());
        assertEquals(1, plans.values.size());
        assertEquals(EvolutionPlanStatus.PROPOSED, plan.status());
        assertEquals(EvolutionStepStatus.PENDING_CONFIRMATION, plan.steps().getFirst().status());
        assertEquals(List.of(BASIS, BASIS), plan.evidence());
        JsonNode payload = new ObjectMapper().readTree(sent.messages().get(1).content());
        assertTrue(payload.has("selectedProductDirection"));
        assertEquals("commit123", payload.path("baseRepositoryProfile").path("analyzedRevision").asText());
        assertFalse(payload.toString().contains("never-open-this-path"));
        assertFalse(payload.toString().contains("confidence"));
        assertEquals(AiResponseFormat.JSON, sent.responseFormat());
    }

    @Test
    void planningInstructionPreservesStructuredContract() throws Exception {
        seed();

        useCase().generate(request());
        assertEquals(1, calls.get());
        assertEquals(AiRole.SYSTEM, sent.messages().getFirst().role());
        assertEquals(AiResponseFormat.JSON, sent.responseFormat());

        // 固定原有 JSON 示例契约，让提示词语言调整不能悄悄改变字段或结构。
        String instruction = sent.messages().getFirst().content();
        String schema = instruction.substring(instruction.indexOf('{'), instruction.lastIndexOf('}') + 1);
        String expected = """
                {
                  "currentState": {"summary":"...", "capabilities":[], "modules":[], "limitations":[]},
                  "targetState": {"problem":"...", "targetProduct":"...", "differentiation":"..."},
                  "reusableCapabilities":[], "changes":["..."],
                  "steps":[{"goal":"...", "scope":"...", "plannedChanges":["..."],
                            "preconditions":[], "verificationCriteria":["..."]}],
                  "risks":[], "evidence":["..."]
                }
                """;
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(mapper.readTree(expected), mapper.readTree(schema));
    }

    @Test
    void rejectsMissingAndIneligibleBasisBeforeCallingAi() {
        assertThrows(ProductDirectionNotFoundException.class, () -> useCase().generate(request()));
        directions.values.put(new ProductDirectionId("direction"), direction(true));
        assertThrows(SoftwareAssetNotFoundException.class, () -> useCase().generate(request()));
        assets.value = asset(true, "MIT", UsageAuthorization.ALLOWED);
        assertThrows(RepositoryProfileNotFoundException.class, () -> useCase().generate(request()));
        profiles.value = profile();
        directions.values.put(new ProductDirectionId("direction"), direction(false));
        assertThrows(EvolutionPlanningPreconditionException.class, () -> useCase().generate(request()));
        directions.values.put(new ProductDirectionId("direction"), direction(true));
        assets.value = asset(true, "MIT", UsageAuthorization.UNCLEAR);
        assertThrows(AssetEvolutionNotAllowedException.class, () -> useCase().generate(request()));
        assertEquals(0, calls.get());
        assertTrue(plans.values.isEmpty());
    }

    @Test
    void allAiAndAcceptanceFailuresLeaveLoadedObjectsAndStorageUntouched() {
        seed();

        ProductDirection loaded = directions.values.get(new ProductDirectionId("direction"));
        aiFailure = new AiGatewayException("Failed");
        assertThrows(AiGatewayException.class, () -> useCase().generate(request()));
        aiFailure = null;
        for (String invalid : List.of("not json", JSON + " {}", JSON.replace("R-E1", "made-up"),
                JSON.replace("\"goal\":\"Schedule reports\"", "\"goal\":null"),
                JSON.replace("\"plannedChanges\":[\"Add scheduling\"]", "\"plannedChanges\":[]"))) {
            response = invalid;
            assertThrows(AiGatewayException.class, () -> useCase().generate(request()));
        }
        response = JSON.replace("Scheduled reports", "Weakened target");
        assertThrows(EvolutionPlanningRejectedException.class, () -> useCase().generate(request()));
        response = JSON;
        saveFailure = new RuntimeException("Persistence failed");
        assertThrows(RuntimeException.class, () -> useCase().generate(request()));
        assertTrue(plans.values.isEmpty());
        assertEquals(ProductDirectionStatus.SELECTED, loaded.status());
        assertEquals(UsageAuthorization.ALLOWED, assets.value.usageAuthorization());
        assertEquals(profile().evidence(), profiles.value.evidence());
    }

    @Test
    void modelAuthorityFieldsCannotOverrideDomainOwnedIdentityAndInitialState() {
        seed();
        response = JSON.substring(0, JSON.lastIndexOf('}'))
                + ",\"status\":\"ACTIVE\",\"workingCopyId\":\"forged\",\"id\":\"forged\"}";
        response = response.replace("\"verificationCriteria\":[\"Reports run on schedule\"]",
                "\"verificationCriteria\":[\"Reports run on schedule\"],"
                        + "\"status\":\"READY\",\"baselineRevision\":\"forged\",\"id\":\"forged\"");

        EvolutionPlan plan = useCase().generate(request());

        assertNotEquals("forged", plan.id().value());
        assertEquals(EvolutionPlanStatus.PROPOSED, plan.status());
        assertNull(plan.workingCopyId());
        assertEquals(EvolutionStepStatus.PENDING_CONFIRMATION, plan.steps().getFirst().status());
        assertNull(plan.steps().getFirst().baselineRevision());
        assertNotEquals("forged", plan.steps().getFirst().id().value());
    }

    @Test
    void resolvedReferencesDoNotMakeInventedCapabilitiesDomainAccepted() {
        seed();
        response = JSON.replace("\"capabilities\":[\"Export\"]", "\"capabilities\":[\"Invented\"]");

        AiPlanningProposal aiCandidate = new PlanningProposalParser(new ObjectMapper()).parse(response);
        PlanningProposal resolved = new PlanningProposalResolver().resolve(aiCandidate,
                Map.of("R-E1", BASIS, "D-E1", BASIS));

        assertEquals(List.of(BASIS, BASIS), resolved.evidence());
        assertEquals(List.of("Invented"), resolved.currentState().capabilities());
        assertThrows(EvolutionPlanningRejectedException.class, () -> useCase().generate(request()));
        assertTrue(plans.values.isEmpty());
    }

    @Test
    void rechecksEligibilityAfterAiWorkAndKeepsHistoricalPlansDistinct() {
        seed();

        EvolutionPlan first = useCase().generate(request());
        EvolutionPlan second = useCase().generate(request());
        assertNotEquals(first.id(), second.id());
        assertEquals(2, plans.values.size());
        duringAi = () -> directions.values.get(new ProductDirectionId("direction")).supersede();
        assertThrows(EvolutionPlanningPreconditionException.class, () -> useCase().generate(request()));
        assertEquals(2, plans.values.size());
    }

    @Test
    void parserRejectsMissingNullAndWrongTypesWithoutCoercion() throws Exception {
        var parser = new PlanningProposalParser(new ObjectMapper());
        for (String field : List.of("currentState", "targetState", "reusableCapabilities", "changes", "steps", "risks", "evidence")) {
            var root = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree(JSON);
            root.remove(field);
            assertThrows(AiGatewayException.class, () -> parser.parse(root.toString()));
        }
        assertThrows(AiGatewayException.class, () -> parser.parse(JSON.replace("\"risks\":[]", "\"risks\":null")));
        assertThrows(AiGatewayException.class, () -> parser.parse(JSON.replace("\"summary\":\"Existing exports\"", "\"summary\":4")));
    }

    private class Plans implements EvolutionPlanRepository {
        public List<EvolutionPlan> findByProductDirectionId(ProductDirectionId id) {
            return values.values().stream().filter(plan -> plan.productDirectionId().equals(id)).toList();
        }
        final Map<EvolutionPlanId, EvolutionPlan> values = new HashMap<>();
        public void save(EvolutionPlan plan) {
            if (saveFailure != null) {
                throw saveFailure;
            }
            values.put(plan.id(), plan);
        }
        public Optional<EvolutionPlan> findById(EvolutionPlanId id) {
            return Optional.ofNullable(values.get(id));
        }
    }

    private static class Assets implements SoftwareAssetRepository {
        SoftwareAsset value;
        public void save(SoftwareAsset value) {
            this.value = value;
        }
        public Optional<SoftwareAsset> findById(SoftwareAssetId id) {
            return Optional.ofNullable(value);
        }
    }

    private static class Profiles implements RepositoryProfileRepository {
        RepositoryProfile value;
        public void save(RepositoryProfile value) {
            this.value = value;
        }
        public Optional<RepositoryProfile> findById(RepositoryProfileId id) {
            return Optional.ofNullable(value);
        }
    }

    private static class Directions implements ProductDirectionRepository {
        final Map<ProductDirectionId, ProductDirection> values = new HashMap<>();
        public void save(ProductDirection value) {
            values.put(value.id(), value);
        }
        public void saveAll(List<ProductDirection> values) {
            values.forEach(this::save);
        }
        public void saveTransitions(List<ProductDirectionTransition> values) {
            throw new UnsupportedOperationException();
        }
        public Optional<ProductDirection> findById(ProductDirectionId id) {
            return Optional.ofNullable(values.get(id));
        }
        public Optional<ProductDirection> findCurrentSelected() {
            return values.values().stream().filter(d -> d.status() == ProductDirectionStatus.SELECTED).findFirst();
        }
    }
}
