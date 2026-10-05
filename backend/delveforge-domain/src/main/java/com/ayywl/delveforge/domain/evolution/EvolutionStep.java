package com.ayywl.delveforge.domain.evolution;
import java.util.List;

/** Internal Entity of EvolutionPlan. There is no public authorization/mutation entry point. */
public final class EvolutionStep {
    private final EvolutionStepId id;
    private final EvolutionPlanId planId;
    private final PlanningStepProposal definition;

    private EvolutionStep(EvolutionStepId id, EvolutionPlanId planId, PlanningStepProposal definition) {
        if (id == null || planId == null || definition == null)
            throw new IllegalArgumentException("Step identity, owner and definition are required");
        this.id = id;
        this.planId = planId;
        this.definition = definition;
    }

    static EvolutionStep planned(EvolutionStepId id, EvolutionPlanId planId, PlanningStepProposal definition) {
        return new EvolutionStep(id, planId, definition);
    }
    public EvolutionStepId id() { return id; }
    public EvolutionPlanId planId() { return planId; }
    public String goal() { return definition.goal(); }
    public String scope() { return definition.scope(); }
    public List<String> plannedChanges() { return definition.plannedChanges(); }
    public List<String> preconditions() { return definition.preconditions(); }
    public List<String> verificationCriteria() { return definition.verificationCriteria(); }
    public EvolutionStepStatus status() { return EvolutionStepStatus.PENDING_CONFIRMATION; }
    public String baselineRevision() { return null; }
}
