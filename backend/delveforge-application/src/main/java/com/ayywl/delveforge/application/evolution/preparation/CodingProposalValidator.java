package com.ayywl.delveforge.application.evolution.preparation;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.workspace.*;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;
import com.ayywl.delveforge.domain.evolution.EvolutionStep;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class CodingProposalValidator {
    private final RepositorySecretPolicy secrets;
    private final WorkingCopyInspectionPort inspection;
    private final ExecutionPreparationLimits limits;

    public CodingProposalValidator(RepositorySecretPolicy secrets, WorkingCopyInspectionPort inspection,
            ExecutionPreparationLimits limits) {
        this.secrets = secrets;
        this.inspection = inspection;
        this.limits = limits;
    }

    public ValidatedExecutionProposal validate(CodingProposal proposal, ExecutionPreparationBasis basis,
            EvolutionStep step, WorkspaceRef workspace, Map<String, String> visibleOriginals,
            Set<String> sanitizedPaths, Set<String> existingPaths) {
        if (!basis.expectedRevision().equals(proposal.expectedRevision())
                || !basis.stepId().equals(step.id()) || !basis.planId().equals(step.planId())
                || proposal.summary() == null || proposal.summary().isBlank()
                || proposal.unifiedDiff() == null
                || proposal.unifiedDiff().getBytes(StandardCharsets.UTF_8).length > limits.maxProposalBytes()
                || proposal.declaredAffectedPaths().isEmpty()
                || proposal.declaredAffectedPaths().size() > limits.maxAffectedPaths()
                || proposal.verificationStrategy().isEmpty()
                || proposal.verificationStrategy().size() > limits.maxVerificationChecks()) {
            throw new AiGatewayException("Coding proposal is inconsistent with its bounded invocation basis");
        }
        ProposalPathPolicy paths = new ProposalPathPolicy(secrets);
        Set<String> declared = new LinkedHashSet<>();
        Set<String> aliases = new HashSet<>();
        for (String path : proposal.declaredAffectedPaths()) {
            paths.requireSafe(path);
            if (!declared.add(path) || !aliases.add(path.toLowerCase(Locale.ROOT)) || sanitizedPaths.contains(path)) {
                throw new AiGatewayException("Duplicate, ambiguous or secret-redacted modification target");
            }
            if (existingPaths.stream().anyMatch(existing -> !existing.equals(path)
                    && existing.equalsIgnoreCase(path))) {
                throw new AiGatewayException("Proposed target aliases an existing path");
            }
        }
        List<String> actual = new UnifiedDiffValidator(paths).validate(proposal.unifiedDiff(), visibleOriginals, existingPaths);
        if (!declared.equals(new LinkedHashSet<>(actual))) {
            throw new AiGatewayException("Declared affected paths differ from the actual patch paths");
        }
        if (secrets.sanitize(List.of(new RepositorySourceFile("proposal.diff", proposal.unifiedDiff()))).replacedSpans() != 0) {
            throw new AiGatewayException("Patch contains a recognized credential literal");
        }
        Set<Integer> covered = new HashSet<>();
        for (CodingProposal.VerificationAction check : proposal.verificationStrategy()) {
            if (check.action() == null || check.target() == null || check.target().length() > 256
                    || !check.target().matches("[A-Za-z0-9_./#$-]*")
                    || (check.action() == CodingProposal.Action.FOCUSED_TEST && check.target().isBlank())
                    || (check.action() != CodingProposal.Action.FOCUSED_TEST && !check.target().isEmpty())) {
                throw new AiGatewayException("Verification actions must remain bounded intent, not commands");
            }
            Set<Integer> seen = new HashSet<>();
            for (Integer index : check.criterionIndexes()) {
                if (index == null || index <= 0 || index > step.verificationCriteria().size() || !seen.add(index)) {
                    throw new AiGatewayException("Verification strategy references an unknown or duplicate criterion");
                }
                covered.add(index);
            }
        }
        if (covered.size() != step.verificationCriteria().size()) {
            throw new AiGatewayException("Verification strategy must address every existing criterion");
        }
        inspection.requireSafePaths(workspace, actual);
        return new ValidatedExecutionProposal(basis, proposal, new ArrayList<>(visibleOriginals.keySet()));
    }
}
