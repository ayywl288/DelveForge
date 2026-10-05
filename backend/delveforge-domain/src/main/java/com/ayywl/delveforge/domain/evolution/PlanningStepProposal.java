package com.ayywl.delveforge.domain.evolution;
import java.util.List;

/** Engineering increment, without identity, authorization, execution or commands. */
public record PlanningStepProposal(String goal, String scope, List<String> plannedChanges,
                                   List<String> preconditions, List<String> verificationCriteria) {
    public PlanningStepProposal {
        goal = PlanningContent.text(goal, "step.goal");
        scope = PlanningContent.text(scope, "step.scope");
        plannedChanges = PlanningContent.section(plannedChanges, "step.plannedChanges", true);
        // A first independent increment can have no step-specific dependency.
        preconditions = PlanningContent.section(preconditions, "step.preconditions", false);
        verificationCriteria = PlanningContent.section(verificationCriteria, "step.verificationCriteria", true);
    }
}
