package com.ayywl.delveforge.application.evolution.planning;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evolution.PlanningProposal;
import com.ayywl.delveforge.domain.evolution.PlanningStepProposal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 只将临时引用换回本次输入中的既有 EvidenceBasis，不创造依据或判断业务有效性。
 * 已还原的 PlanningProposal 仍须交给 EvolutionPlanningService 接受。
 */
public final class PlanningProposalResolver {
    public PlanningProposal resolve(AiPlanningProposal proposal, Map<String, EvidenceBasis> catalog) {
        Objects.requireNonNull(proposal);
        Objects.requireNonNull(catalog);

        List<EvidenceBasis> evidence = new ArrayList<>();
        for (String reference : proposal.evidenceReferences()) {
            EvidenceBasis basis = catalog.get(reference);
            if (basis == null) {
                throw new AiGatewayException("Unknown planning Evidence reference");
            }
            evidence.add(basis);
        }

        List<PlanningStepProposal> steps = proposal.steps().stream()
                .map(step -> new PlanningStepProposal(step.goal(), step.scope(), step.plannedChanges(),
                        step.preconditions(), step.verificationCriteria()))
                .toList();
        return new PlanningProposal(proposal.currentState(), proposal.targetState(),
                proposal.reusableCapabilities(), proposal.changes(), steps, proposal.risks(), evidence);
    }
}
