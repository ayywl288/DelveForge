package com.ayywl.delveforge.application.evolution.preparation;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyInspectionPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;
import com.ayywl.delveforge.domain.evolution.EvolutionStep;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 校验提案的客观事实与修改边界；不证明业务语义，不修改 Workspace 或领域状态。 */
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

    /** 只有路径、旧内容、revision 和预算一致时才返回调用内结果；不替代执行前资格重查。 */
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

        // 声明路径也可能在整体上冲突，不能只验证每个路径单独合法。
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

        // 当前文本补丁不允许同一目标集合同时把一个路径作为文件和目录。
        for (String alias : aliases) {
            for (int separator = alias.indexOf('/'); separator >= 0; separator = alias.indexOf('/', separator + 1)) {
                if (aliases.contains(alias.substring(0, separator))) {
                    throw new AiGatewayException("Patch targets have a file-directory conflict");
                }
            }
        }

        List<String> actual = new UnifiedDiffValidator(paths).validate(proposal.unifiedDiff(), visibleOriginals,
                existingPaths);
        if (!declared.equals(new LinkedHashSet<>(actual))) {
            throw new AiGatewayException("Declared affected paths differ from the actual patch paths");
        }
        if (secrets.sanitize(List.of(new RepositorySourceFile("proposal.diff",
                proposal.unifiedDiff()))).replacedSpans() != 0) {
            throw new AiGatewayException("Patch contains a recognized credential literal");
        }

        // 索引覆盖只约束验证意图；UNSUPPORTED 标准仍由执行入口在 mutation 前拒绝。
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
                var criterion = step.verificationCriteria().get(index - 1);
                if (criterion.kind() != com.ayywl.delveforge.domain.evolution.VerificationCriterion.Kind.UNSUPPORTED
                        && !criterion.supportedBy(check.action().name(), check.target())) {
                    throw new AiGatewayException("Verification action cannot support the declared criterion kind");
                }
                covered.add(index);
            }
        }
        if (covered.size() != step.verificationCriteria().size()) {
            throw new AiGatewayException("Verification strategy must address every existing criterion");
        }

        // 字符串 hunk 校验之外，还要核对真实位置及 Git blob 的无损表示。
        inspection.requireSafePaths(workspace, actual);
        Map<String, String> modifiedOriginals = new LinkedHashMap<>();
        for (String path : actual) {
            if (visibleOriginals.containsKey(path)) {
                modifiedOriginals.put(path, visibleOriginals.get(path));
            }
        }
        inspection.requireExactSourceText(workspace, basis.expectedRevision(), modifiedOriginals);
        return new ValidatedExecutionProposal(basis, proposal, new ArrayList<>(visibleOriginals.keySet()));
    }
}
