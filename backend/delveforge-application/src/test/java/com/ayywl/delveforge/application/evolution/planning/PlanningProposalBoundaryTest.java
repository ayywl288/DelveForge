package com.ayywl.delveforge.application.evolution.planning;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.evidence.RepositoryProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.evolution.PlanningProposal;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanningProposalBoundaryTest {
    private static final String JSON = """
            {
              "currentState":{"summary":"Exports","capabilities":["Export"],"modules":[],"limitations":[]},
              "targetState":{"problem":"Manual exports","targetProduct":"Scheduled reports","differentiation":"Personal"},
              "reusableCapabilities":["Export"],"changes":["Add scheduling"],
              "steps":[{"goal":"Schedule reports","scope":"Reporting","plannedChanges":["Add scheduling"],
                        "preconditions":[],"verificationCriteria":["Reports run on schedule"]}],
              "risks":[],"evidence":["R-E1"]
            }
            """;
    private final PlanningProposalParser parser = new PlanningProposalParser(new ObjectMapper());
    private final PlanningProposalResolver resolver = new PlanningProposalResolver();
    private final EvidenceBasis basis = new EvidenceBasis(
            new Evidence(EvidenceSourceType.REPOSITORY, "src/report", "Existing exports", 0.8, true),
            new RepositoryProfileEvidenceOrigin(new RepositoryProfileId("profile")));

    @Test
    void parserReturnsAiCandidateWithTemporaryReferencesAndNoDomainEvidence() {
        AiPlanningProposal candidate = parser.parse(JSON);

        assertEquals(List.of("R-E1"), candidate.evidenceReferences());
        assertEquals("Schedule reports", candidate.steps().getFirst().goal());
        assertEquals(List.of(), candidate.steps().getFirst().preconditions());
        assertThrows(UnsupportedOperationException.class, () -> candidate.evidenceReferences().clear());
    }

    @Test
    void parserRejectsMalformedJsonTrailingExplanationAndSecondValue() {
        for (String malformed : List.of("not json", "[]", JSON + " explanation", JSON + " {}")) {
            assertThrows(AiGatewayException.class, () -> parser.parse(malformed));
        }
    }

    @Test
    void unsupportedAuthorityAndEvidenceFactsAreIgnoredInsteadOfRestored() {
        String injected = JSON.substring(0, JSON.lastIndexOf('}'))
                + ",\"status\":\"ACTIVE\",\"workingCopyId\":\"forged\",\"id\":\"forged\","
                + "\"claim\":\"Invented\",\"confidence\":1.0,\"confirmed\":false}";
        AiPlanningProposal candidate = parser.parse(injected);
        PlanningProposal resolved = resolver.resolve(candidate, Map.of("R-E1", basis));

        assertEquals(parser.parse(JSON), candidate);
        // 还原的是输入中的同一份既有依据，模型附带的事实和确认字段不能覆盖它。
        assertSame(basis, resolved.evidence().getFirst());
        assertSame(basis.evidence(), resolved.evidence().getFirst().evidence());
    }

    @Test
    void resolverRestoresKnownReferencesInOrderWithoutAcceptingTheCandidate() {
        AiPlanningProposal candidate = parser.parse(JSON.replace("[\"R-E1\"]", "[\"R-E1\",\"D-E1\"]"));
        PlanningProposal resolved = resolver.resolve(candidate, Map.of("R-E1", basis, "D-E1", basis));

        assertEquals(List.of(basis, basis), resolved.evidence());
        assertSame(basis, resolved.evidence().getFirst());
        assertEquals(candidate.currentState(), resolved.currentState());
        assertEquals(candidate.steps().getFirst().goal(), resolved.steps().getFirst().goal());
    }

    @Test
    void unknownReferenceIsStillAiCandidateDataUntilResolverRejectsIt() {
        AiPlanningProposal candidate = parser.parse(JSON.replace("R-E1", "unknown"));

        assertEquals(List.of("unknown"), candidate.evidenceReferences());
        assertThrows(AiGatewayException.class, () -> resolver.resolve(candidate, Map.of("R-E1", basis)));
    }
}
