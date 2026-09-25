package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import java.util.List;

/**
 * AI 提出的关键判断 → 本次调用中的 Evidence 引用。
 *
 * <pre>
 * AI judgment
 *         ↓
 * invocation-local EvidenceReference
 * </pre>
 *
 * <p>它是 AI 通信协议的一部分：左边是模型给出的判断分类，右边是它在本次调用里引用过
 * 的临时编号。它不是领域概念——引用还没有被解析成真实依据，因此这里没有任何
 * 可追溯的领域事实。
 *
 * <p>三个槽位对应 INV-D06 点名的三类关键判断。同一个引用可以出现在多个槽位里，
 * 因为同一条依据可以同时支撑多个判断。
 *
 * @param userNeed           支撑「用户需求 / 要解决的问题」判断的引用
 * @param userFit            支撑「与用户的匹配关系」判断的引用
 * @param reusableCapability 支撑「可复用的软件能力 / 候选资产理由」判断的引用
 */
public record AiDirectionEvidenceLinkage(
        List<EvidenceReference> userNeed,
        List<EvidenceReference> userFit,
        List<EvidenceReference> reusableCapability) {

    public AiDirectionEvidenceLinkage {
        userNeed = copyOf(userNeed, "userNeed");
        userFit = copyOf(userFit, "userFit");
        reusableCapability = copyOf(reusableCapability, "reusableCapability");
    }

    private static List<EvidenceReference> copyOf(List<EvidenceReference> references, String slot) {
        if (references == null) {
            throw new IllegalArgumentException(
                    "AI Direction Evidence Linkage 的 " + slot + " 不能为 null");
        }
        for (EvidenceReference reference : references) {
            if (reference == null) {
                throw new IllegalArgumentException(
                        "AI Direction Evidence Linkage 的 " + slot + " 不能包含 null");
            }
        }
        return List.copyOf(references);
    }
}
