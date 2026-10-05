package com.ayywl.delveforge.domain.evolution;

import com.ayywl.delveforge.domain.asset.*;
import com.ayywl.delveforge.domain.direction.*;
import com.ayywl.delveforge.domain.evidence.*;
import com.ayywl.delveforge.domain.evolution.*;
import com.ayywl.delveforge.domain.repositoryprofile.*;
import com.ayywl.delveforge.domain.user.UserProfileId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvolutionPlanningServiceTest {
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

    private EvolutionPlanningService service() {
        AtomicInteger ids = new AtomicInteger();
        return new EvolutionPlanningService(new AssetUsagePolicy(),
                () -> new EvolutionPlanId("plan"), () -> new EvolutionStepId("step-" + ids.incrementAndGet()));
    }

    @Test
    void preparesAndActivatesReplannedBasisWithoutReplacingDirectionDiscoveryHistory() {
        ProductDirection selected = direction(true);
        SoftwareAsset allowed = asset(true, "MIT", UsageAuthorization.ALLOWED);
        var freshId = new RepositoryProfileId("fresh-profile");
        RepositoryProfile fresh = RepositoryProfile.create(freshId, ASSET, "fresh-commit", "Reporting", List.of("Java"),
                List.of("reports"), List.of("Export"), List.of("Render"), List.of("No scheduling"), List.of(), List.of(FACT));
        PlanningProposal original = proposal();
        PlanningProposal freshProposal = new PlanningProposal(original.currentState(), original.targetState(),
                original.reusableCapabilities(), original.changes(), original.steps(), original.risks(),
                List.of(new EvidenceBasis(FACT, new RepositoryProfileEvidenceOrigin(freshId)), BASIS));
        EvolutionPlan plan = service().plan(selected, allowed, fresh, freshProposal);
        var policy = new PlanActivationPolicy(new AssetUsagePolicy());
        assertEquals(List.of(PROFILE), selected.repositoryProfileIds());
        assertEquals(freshId, plan.baseRepositoryProfileId());
        policy.requirePreparationAllowed(plan, selected, allowed, fresh);
        WorkingCopy copy = WorkingCopy.create(new WorkingCopyId("fresh-copy"), ASSET, "fresh-commit", "managed-copy");
        copy.markReady("fresh-commit");
        plan.bindWorkingCopy(copy);
        plan.activate(policy, copy, selected, allowed, fresh);
        assertEquals(EvolutionPlanStatus.ACTIVE, plan.status());
        assertEquals(List.of(PROFILE), selected.repositoryProfileIds());
        assertEquals(ProductDirectionStatus.SELECTED, selected.status());
        assertEquals("fresh-commit", copy.currentRevision());
        assertEquals(copy.sourceRevision(), copy.lastVerifiedRevision());
        plan.steps().forEach(step -> assertEquals(EvolutionStepStatus.PENDING_CONFIRMATION, step.status()));
    }

    @Test
    void activationBindsReadyCopyWithoutAuthorizingStepsOrMutatingLoadedPlan() {
        SoftwareAsset asset = asset(true, "MIT", UsageAuthorization.ALLOWED);
        ProductDirection direction = direction(true);
        EvolutionPlan original = service().plan(direction, asset, profile(), proposal());
        EvolutionPlan candidate = original.copy();
        WorkingCopy copy = WorkingCopy.create(new WorkingCopyId("copy"), ASSET, "commit123", "managed-copy");
        assertEquals(WorkingCopyStatus.CREATING, copy.status());
        assertThrows(EvolutionPlanStateException.class, () -> candidate.bindWorkingCopy(copy));
        assertThrows(EvolutionPlanStateException.class, () -> copy.markReady("other"));
        assertNull(copy.currentRevision());
        copy.markReady("commit123");
        candidate.bindWorkingCopy(copy);
        candidate.activate(new PlanActivationPolicy(new AssetUsagePolicy()), copy, direction, asset, profile());
        assertEquals(EvolutionPlanStatus.ACTIVE, candidate.status());
        assertEquals("copy", candidate.workingCopyId());
        assertEquals("commit123", copy.sourceRevision());
        assertEquals(copy.sourceRevision(), copy.currentRevision());
        assertEquals(copy.sourceRevision(), copy.lastVerifiedRevision());
        assertEquals(EvolutionPlanStatus.PROPOSED, original.status());
        assertNull(original.workingCopyId());
        candidate.steps().forEach(step -> {
            assertEquals(EvolutionStepStatus.PENDING_CONFIRMATION, step.status());
            assertNull(step.baselineRevision());
        });
        assertThrows(EvolutionPlanStateException.class, () -> candidate.bindWorkingCopy(copy));
        assertThrows(EvolutionPlanStateException.class, () -> candidate.activate(
                new PlanActivationPolicy(new AssetUsagePolicy()), copy, direction, asset, profile()));
        candidate.supersede();
        assertEquals(EvolutionPlanStatus.SUPERSEDED, candidate.status());
        assertEquals(original.evidence(), candidate.evidence());
        assertEquals("copy", candidate.workingCopyId());
        assertThrows(EvolutionPlanStateException.class, candidate::supersede);
    }

    @Test
    void activationPolicyRejectsUnboundUnreadyWrongCopyRevisionAndRevokedAuthorization() {
        SoftwareAsset allowed = asset(true, "MIT", UsageAuthorization.ALLOWED);
        ProductDirection selected = direction(true);
        EvolutionPlan plan = service().plan(selected, allowed, profile(), proposal());
        var policy = new PlanActivationPolicy(new AssetUsagePolicy());
        WorkingCopy copy = WorkingCopy.create(new WorkingCopyId("copy"), ASSET, "commit123", "managed-copy");
        assertThrows(EvolutionPlanStateException.class,
                () -> policy.requireActivationAllowed(plan, copy, selected, allowed, profile()));
        copy.markReady("commit123");
        assertThrows(EvolutionPlanStateException.class,
                () -> policy.requireActivationAllowed(plan, copy, selected, allowed, profile()));
        plan.bindWorkingCopy(copy);
        assertThrows(AssetEvolutionNotAllowedException.class, () -> plan.activate(policy, copy, selected,
                asset(true, "MIT", UsageAuthorization.DENIED), profile()));
        assertEquals(EvolutionPlanStatus.PROPOSED, plan.status());
        WorkingCopy wrong = WorkingCopy.create(new WorkingCopyId("other"), ASSET, "commit123", "other-copy");
        wrong.markReady("commit123");
        assertThrows(EvolutionPlanStateException.class,
                () -> policy.requireActivationAllowed(plan, wrong, selected, allowed, profile()));
        WorkingCopy stale = WorkingCopy.create(new WorkingCopyId("copy"), ASSET, "other", "other-copy");
        stale.markReady("other");
        assertThrows(EvolutionPlanStateException.class,
                () -> policy.requireActivationAllowed(plan, stale, selected, allowed, profile()));
        selected.supersede();
        assertThrows(EvolutionPlanStateException.class,
                () -> plan.activate(policy, copy, selected, allowed, profile()));
    }

    @Test
    void preparationPolicyRejectsMismatchedBasisAndHistoricalPlan() {
        SoftwareAsset allowed = asset(true, "MIT", UsageAuthorization.ALLOWED);
        ProductDirection selected = direction(true);
        EvolutionPlan plan = service().plan(selected, allowed, profile(), proposal());
        var policy = new PlanActivationPolicy(new AssetUsagePolicy());
        SoftwareAsset otherAsset = SoftwareAsset.create(new SoftwareAssetId("other"), SoftwareAssetType.GIT_REPOSITORY,
                SoftwareAssetSource.USER_SPECIFIED, "other", true, "MIT", UsageAuthorization.ALLOWED);
        assertThrows(EvolutionPlanStateException.class,
                () -> policy.requirePreparationAllowed(plan, selected, otherAsset, profile()));
        RepositoryProfile otherProfile = RepositoryProfile.create(new RepositoryProfileId("other"), ASSET, "commit123", "Other",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(FACT));
        assertThrows(EvolutionPlanStateException.class,
                () -> policy.requirePreparationAllowed(plan, selected, allowed, otherProfile));
        assertThrows(EvolutionPlanStateException.class,
                () -> policy.requirePreparationAllowed(plan, direction(false), allowed, profile()));
        plan.supersede();
        assertThrows(EvolutionPlanStateException.class,
                () -> policy.requirePreparationAllowed(plan, selected, allowed, profile()));
    }

    @Test
    void acceptsAnIsolatedProposedPlanWithOrderedUnconfirmedSteps() {
        EvolutionPlan plan = service().plan(direction(true), asset(true, "MIT", UsageAuthorization.ALLOWED), profile(), proposal());
        assertEquals(EvolutionPlanStatus.PROPOSED, plan.status());
        assertNull(plan.workingCopyId());
        assertEquals(new ProductDirectionId("direction"), plan.productDirectionId());
        assertEquals(ASSET, plan.baseAssetId());
        assertEquals(PROFILE, plan.baseRepositoryProfileId());
        assertEquals(proposal().currentState(), plan.currentState());
        assertEquals(proposal().targetState(), plan.targetState());
        assertEquals(proposal().changes(), plan.changes());
        assertEquals(proposal().risks(), plan.risks());
        assertEquals(proposal().evidence(), plan.evidence());
        assertEquals(List.of("Schedule reports", "Expose schedule management"),
                plan.steps().stream().map(EvolutionStep::goal).toList());
        for (EvolutionStep step : plan.steps()) {
            assertEquals(plan.id(), step.planId());
            assertEquals(EvolutionStepStatus.PENDING_CONFIRMATION, step.status());
            assertNull(step.baselineRevision());
        }
        assertThrows(UnsupportedOperationException.class, () -> plan.steps().clear());
    }

    @Test
    void rejectsUnselectedOrNoncandidateOrMismatchedInputs() {
        SoftwareAsset allowed = asset(true, "MIT", UsageAuthorization.ALLOWED);
        assertThrows(EvolutionPlanningPreconditionException.class,
                () -> service().plan(direction(false), allowed, profile(), proposal()));
        SoftwareAsset other = SoftwareAsset.create(new SoftwareAssetId("other"), SoftwareAssetType.GIT_REPOSITORY,
                SoftwareAssetSource.USER_SPECIFIED, "path", true, "MIT", UsageAuthorization.ALLOWED);
        assertThrows(EvolutionPlanningPreconditionException.class,
                () -> service().plan(direction(true), other, profile(), proposal()));
        RepositoryProfile mismatched = RepositoryProfile.create(PROFILE, new SoftwareAssetId("other"), "revision", "Purpose",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(FACT));
        assertThrows(EvolutionPlanningPreconditionException.class,
                () -> service().plan(direction(true), allowed, mismatched, proposal()));
        assertThrows(IllegalArgumentException.class, () -> RepositoryProfile.create(PROFILE, ASSET, " ", "Purpose",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(FACT)));
    }

    @Test
    void rejectsIneligibleAssetAuthorizationAndUnknownLicense() {
        for (var authorization : List.of(UsageAuthorization.DENIED, UsageAuthorization.UNCLEAR)) {
            assertThrows(AssetEvolutionNotAllowedException.class,
                    () -> service().plan(direction(true), asset(true, "MIT", authorization), profile(), proposal()));
        }
        assertThrows(AssetEvolutionNotAllowedException.class,
                () -> service().plan(direction(true), asset(false, "MIT", UsageAuthorization.ALLOWED), profile(), proposal()));
        assertThrows(AssetEvolutionNotAllowedException.class,
                () -> service().plan(direction(true), asset(true, null, UsageAuthorization.ALLOWED), profile(), proposal()));
    }

    @Test
    void rejectsChangedIntentFabricatedFactsAndForeignEvidence() {
        PlanningProposal p = proposal();
        SoftwareAsset allowed = asset(true, "MIT", UsageAuthorization.ALLOWED);
        assertThrows(EvolutionPlanningRejectedException.class, () -> service().plan(direction(true), allowed, profile(),
                new PlanningProposal(p.currentState(), new TargetState("Other", "Other", "Other"),
                        p.reusableCapabilities(), p.changes(), p.steps(), p.risks(), p.evidence())));
        assertThrows(EvolutionPlanningRejectedException.class, () -> service().plan(direction(true), allowed, profile(),
                new PlanningProposal(new CurrentState("Invented", List.of("Invented"), List.of(), List.of()), p.targetState(),
                        p.reusableCapabilities(), p.changes(), p.steps(), p.risks(), p.evidence())));
        assertThrows(EvolutionPlanningRejectedException.class, () -> service().plan(direction(true), allowed, profile(),
                new PlanningProposal(p.currentState(), p.targetState(), List.of("Invented"),
                        p.changes(), p.steps(), p.risks(), p.evidence())));
        assertThrows(EvolutionPlanningRejectedException.class, () -> service().plan(direction(true), allowed, profile(),
                new PlanningProposal(p.currentState(), p.targetState(), p.reusableCapabilities(),
                        p.changes(), p.steps(), p.risks(), List.of(new EvidenceBasis(FACT,
                                new RepositoryProfileEvidenceOrigin(new RepositoryProfileId("foreign")))))));
        assertThrows(EvolutionPlanningRejectedException.class, () -> service().plan(direction(true), allowed, profile(),
                new PlanningProposal(p.currentState(), p.targetState(), p.reusableCapabilities(),
                        p.changes(), List.of(), p.risks(), p.evidence())));
    }

    @Test
    void rejectsDuplicateStepIdentitiesAndEmptyDefinitions() {
        var service = new EvolutionPlanningService(new AssetUsagePolicy(), () -> new EvolutionPlanId("plan"),
                () -> new EvolutionStepId("same"));
        assertThrows(IllegalArgumentException.class,
                () -> service.plan(direction(true), asset(true, "MIT", UsageAuthorization.ALLOWED), profile(), proposal()));
        assertThrows(IllegalArgumentException.class, () -> new PlanningStepProposal("Goal", " ",
                List.of("Change"), List.of(), List.of("Criterion")));
        assertThrows(IllegalArgumentException.class, () -> new PlanningStepProposal("Goal", "Scope",
                List.of(), List.of(), List.of("Criterion")));
        assertThrows(IllegalArgumentException.class, () -> new PlanningStepProposal("Goal", "Scope",
                List.of("Change"), List.of(), List.of()));
    }
}
