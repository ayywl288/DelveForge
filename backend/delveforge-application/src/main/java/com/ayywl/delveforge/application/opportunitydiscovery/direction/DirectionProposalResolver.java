package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import com.ayywl.delveforge.domain.direction.DirectionEvidenceSupport;
import com.ayywl.delveforge.domain.direction.DirectionProposal;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 把 AI 侧的方向提议解析成领域侧的方向提议。
 *
 * <pre>
 * AiDirectionProposal + DirectionDiscoveryInputs
 *         ↓
 * U-E1 → 真实 UserProfile Evidence + 它的 UserProfileId / revision
 * R2-E3 → 真实 RepositoryProfile Evidence + 它的 RepositoryProfileId
 *         ↓
 * DirectionProposal
 * </pre>
 *
 * <p>它只做一件事：<b>恢复已经存在的事实</b>。引用指向的是本次调用真正提供过的依据，
 * 因此「解析」不是判断，只是把编号换回它本来对应的那条 {@link EvidenceBasis}。
 *
 * <h2>它不负责什么</h2>
 *
 * <p>Application 恢复事实，Domain 判断业务是否成立。以下都不属于本类：
 *
 * <pre>
 * 判断这些依据在业务上是否足以支撑那条推荐判断（INV-D06 的语义充分性）
 * 判断候选资产与 Repository Profile 的完整领域关系（INV-D10 的后半句）
 * 决定最终 ProductDirection.repositoryProfileIds
 * 创建 ProductDirection 或持久化任何东西
 * </pre>
 *
 * <p>因此它不会因为「这条依据看起来不够有力」而拒绝——那是
 * {@code ProductDirectionDiscoveryService} 的职责。它只会在引用无法解析时拒绝，
 * 而那意味着输入与提议对不上，属于调用方的错误而不是业务判断。
 */
public final class DirectionProposalResolver {

    /**
     * 解析一条 AI 侧提议。
     *
     * @param proposal AI 侧提议，不得为 {@code null}
     * @param inputs   产生该提议的那一次输入，不得为 {@code null}
     * @return 领域侧提议，其中每条依据都已带上真实内容与来源
     * @throws IllegalArgumentException 任一参数为 {@code null}，或提议引用了本次输入中
     *                                  不存在的依据（输入与提议对不上）
     */
    public DirectionProposal resolve(AiDirectionProposal proposal,
                                     DirectionDiscoveryInputs inputs) {
        if (proposal == null) {
            throw new IllegalArgumentException(
                    "DirectionProposalResolver 必须指定 proposal");
        }
        if (inputs == null) {
            throw new IllegalArgumentException(
                    "DirectionProposalResolver 必须指定 inputs");
        }

        AiDirectionEvidenceLinkage linkage = proposal.evidenceLinkage();

        return new DirectionProposal(
                proposal.title(),
                proposal.problem(),
                proposal.targetProduct(),
                proposal.userFit(),
                proposal.candidateAssetIds(),
                proposal.differentiation(),
                proposal.technicalValue(),
                proposal.estimatedComplexity(),
                proposal.risks(),
                new DirectionEvidenceSupport(
                        resolveBases(linkage.userNeed(), inputs),
                        resolveBases(linkage.userFit(), inputs),
                        resolveBases(linkage.reusableCapability(), inputs)));
    }

    /**
     * 解析一组 AI 侧提议，顺序保持不变。
     *
     * @throws IllegalArgumentException 任一参数为 {@code null}，或某条提议引用了本次输入中
     *                                  不存在的依据
     */
    public List<DirectionProposal> resolve(List<AiDirectionProposal> proposals,
                                           DirectionDiscoveryInputs inputs) {
        if (proposals == null) {
            throw new IllegalArgumentException(
                    "DirectionProposalResolver 必须指定 proposals");
        }
        if (inputs == null) {
            throw new IllegalArgumentException(
                    "DirectionProposalResolver 必须指定 inputs");
        }

        List<DirectionProposal> resolved = new ArrayList<>(proposals.size());
        for (AiDirectionProposal proposal : proposals) {
            resolved.add(resolve(proposal, inputs));
        }
        return List.copyOf(resolved);
    }

    /**
     * 断言这条引用一定解析得出来。
     *
     * <p>解析失败不是「模型引用了一个不存在的依据」——那种情况在 strict parsing 阶段
     * 就已经被拒绝（{@code DirectionDiscoveryProposalParser} 会核对每条引用是否存在于
     * 本次输入）。走到这里还解析不出来，说明提议与输入根本不是同一次调用的产物，
     * 属于调用方的错误。
     */
    private static List<EvidenceBasis> resolveBases(List<EvidenceReference> references,
                                               DirectionDiscoveryInputs inputs) {
        List<EvidenceBasis> bases = new ArrayList<>(references.size());
        for (EvidenceReference reference : references) {
            Optional<EvidenceBasis> basis = inputs.resolve(reference);
            if (basis.isEmpty()) {
                throw new IllegalArgumentException(
                        "该提议引用的 Evidence 不属于本次输入: " + reference.value());
            }
            bases.add(basis.get());
        }
        return List.copyOf(bases);
    }
}
