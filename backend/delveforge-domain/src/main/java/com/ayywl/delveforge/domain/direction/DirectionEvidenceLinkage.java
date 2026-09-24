package com.ayywl.delveforge.domain.direction;

import java.util.List;

/**
 * 一条 Direction Proposal 中，关键推荐判断与已有 Evidence 之间的对应关系。
 *
 * <p>它回答的是：
 *
 * <pre>
 * 某个关键 recommendation judgment
 *         ↓
 * 由哪些已有 Evidence 支撑
 * </pre>
 *
 * <p>三个槽位对应 INV-D06 特别点名的三类关键判断：用户需求、用户匹配关系、
 * 可复用软件能力。它们不是「Evidence 的分类」——同一条 Evidence 可以同时支撑
 * 多个判断，因此三个槽位之间允许出现重复引用。
 *
 * <h2>本类型不判断依据是否充分</h2>
 *
 * <p>它只承载「模型认为哪些依据支撑了这条判断」这一事实。这些依据在业务语义上
 * 是否真的足以证明该判断，不由这里判定——那属于
 * {@code ProductDirectionDiscoveryService} 的领域校验。
 *
 * <p>槽位允许为空列表：那是「模型认为这条判断没有可引用的依据」这一业务事实，
 * 与「模型没有回答这条判断」不同——后者是缺失字段，在解析层就会被拒绝。
 *
 * @param userNeed           支撑「用户需求 / 要解决的问题」判断的依据引用
 * @param userFit            支撑「与用户的匹配关系」判断的依据引用
 * @param reusableCapability 支撑「可复用的软件能力 / 候选资产理由」判断的依据引用
 */
public record DirectionEvidenceLinkage(
        List<EvidenceReference> userNeed,
        List<EvidenceReference> userFit,
        List<EvidenceReference> reusableCapability) {

    public DirectionEvidenceLinkage {
        userNeed = copyOf(userNeed, "userNeed");
        userFit = copyOf(userFit, "userFit");
        reusableCapability = copyOf(reusableCapability, "reusableCapability");
    }

    private static List<EvidenceReference> copyOf(List<EvidenceReference> references, String slot) {
        if (references == null) {
            throw new IllegalArgumentException(
                    "Direction Evidence Linkage 的 " + slot + " 不能为 null");
        }
        for (EvidenceReference reference : references) {
            if (reference == null) {
                throw new IllegalArgumentException(
                        "Direction Evidence Linkage 的 " + slot + " 不能包含 null");
            }
        }
        return List.copyOf(references);
    }
}
