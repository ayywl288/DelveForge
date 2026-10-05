package com.ayywl.delveforge.domain.evolution;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import java.util.List;

/** Untrusted candidate content. No IDs, lifecycle state or invocation-local references.
 * Like DirectionProposal, this domain-facing value is not an Entity and is not persisted.
 */
public record PlanningProposal(CurrentState currentState, TargetState targetState,
                               List<String> reusableCapabilities, List<String> changes,
                               List<PlanningStepProposal> steps, List<String> risks,
                               List<EvidenceBasis> evidence) {
    public PlanningProposal {
        if (currentState == null || targetState == null || steps == null || evidence == null)
            throw new IllegalArgumentException("Planning proposal is incomplete");
        reusableCapabilities = PlanningContent.section(reusableCapabilities, "reusableCapabilities", false);
        changes = PlanningContent.section(changes, "changes", true);
        steps = List.copyOf(steps);
        risks = PlanningContent.section(risks, "risks", false);
        evidence = List.copyOf(evidence);
    }
}
