package com.ayywl.delveforge.app.api.productdirection;

import com.ayywl.delveforge.app.api.evidence.EvidenceBasisPayload;
import com.ayywl.delveforge.domain.direction.DirectionEvidenceSupport;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import java.util.List;

/**
 * Product Direction 的关键推荐判断与依据的对应关系在 HTTP 接口上的表示。
 *
 * <p>三类槽位原样保留（INV-D06）：它们回答的不是「这个方向有没有依据」，
 * 而是「它凭什么这么说」。
 *
 * <pre>
 * userNeed            这条方向要解决的用户问题，凭什么这么说
 * userFit             为什么适合这个用户，凭什么这么说
 * reusableCapability  可复用哪些软件能力，凭什么这么说
 * </pre>
 *
 * <p>压成一个扁平列表会丢掉「哪条依据支撑哪一类判断」，那正是这个结构要表达的东西。
 *
 * <p>三个槽位都可能为空列表——领域侧的 {@code DirectionEvidenceSupport} 允许空槽位。
 * 出现空槽位说明这条方向在那一类判断上没有可追溯依据，调用方应当知道，
 * 而不是由接口层替它隐藏。
 *
 * <p>本类型是 Interface Adapter 的 DTO，不是领域对象，也不引入新的领域概念。
 *
 * @param userNeed           支撑「用户需求 / 要解决的问题」判断的依据
 * @param userFit            支撑「与用户的匹配关系」判断的依据
 * @param reusableCapability 支撑「可复用的软件能力 / 候选资产理由」判断的依据
 */
public record ProductDirectionEvidenceSupportResponse(
        List<EvidenceBasisPayload> userNeed,
        List<EvidenceBasisPayload> userFit,
        List<EvidenceBasisPayload> reusableCapability) {

    public ProductDirectionEvidenceSupportResponse {
        userNeed = copyOf(userNeed, "userNeed");
        userFit = copyOf(userFit, "userFit");
        reusableCapability = copyOf(reusableCapability, "reusableCapability");
    }

    /**
     * 把领域依据结构映射为它的接口表示。
     *
     * @param support 领域侧依据结构，不得为 {@code null}
     */
    public static ProductDirectionEvidenceSupportResponse from(DirectionEvidenceSupport support) {
        if (support == null) {
            throw new IllegalArgumentException(
                    "Product Direction 的 evidenceSupport 不能为 null");
        }
        return new ProductDirectionEvidenceSupportResponse(
                toPayloads(support.userNeed()),
                toPayloads(support.userFit()),
                toPayloads(support.reusableCapability()));
    }

    private static List<EvidenceBasisPayload> toPayloads(List<EvidenceBasis> bases) {
        return bases.stream().map(EvidenceBasisPayload::from).toList();
    }

    private static List<EvidenceBasisPayload> copyOf(List<EvidenceBasisPayload> bases, String slot) {
        if (bases == null) {
            throw new IllegalArgumentException(
                    "Product Direction Evidence Support 响应的 " + slot + " 不能为 null");
        }
        for (EvidenceBasisPayload basis : bases) {
            if (basis == null) {
                throw new IllegalArgumentException(
                        "Product Direction Evidence Support 响应的 " + slot + " 不能包含 null");
            }
        }
        return List.copyOf(bases);
    }
}
