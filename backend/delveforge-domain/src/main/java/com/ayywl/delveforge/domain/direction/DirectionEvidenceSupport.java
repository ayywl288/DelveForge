package com.ayywl.delveforge.domain.direction;

import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 一条 Product Direction 的关键推荐判断，各自由哪些真实依据支撑。
 *
 * <pre>
 * key recommendation judgment
 *         ↓
 * actual traceable Evidence basis
 * </pre>
 *
 * <p>它回答的不是「这个方向有没有依据」，而是「它凭什么这么说」——
 * 三个槽位对应 INV-D06 特别点名的三类关键判断。
 *
 * <h2>为什么是一个结构而不是一个扁平列表</h2>
 *
 * <p>扁平列表只能表达「这个方向有一批依据」，无法回答「用户匹配关系这个判断是靠哪几条
 * 依据成立的」。把三组依据压在一起，恰好丢掉的正是 INV-D06 要的追溯能力。
 *
 * <p>同一个判断组内允许出现来源不同的依据（用户侧与 Repository 侧可以同时支撑一条判断），
 * 同一条依据也允许同时支撑多个判断——它们是「判断 → 依据」的对应关系，不是依据的分类。
 *
 * <p>当前只建模 INV-D06 明确要求的三类，不为 title / targetProduct / differentiation 等
 * 其余字段各建一份：文档没有要求它们，提前建出来只是没有依据的结构。
 *
 * <h2>空槽位</h2>
 *
 * <p>三个槽位都必须存在，但都可以为空列表。空表示「这一组判断没有可追溯的依据」，
 * 它是否可接受属于 {@code ProductDirectionDiscoveryService} 的领域判断，
 * 不由本类型决定。
 *
 * @param userNeed           支撑「用户需求 / 要解决的问题」判断的依据
 * @param userFit            支撑「与用户的匹配关系」判断的依据
 * @param reusableCapability 支撑「可复用的软件能力 / 候选资产理由」判断的依据
 */
public record DirectionEvidenceSupport(
        List<EvidenceBasis> userNeed,
        List<EvidenceBasis> userFit,
        List<EvidenceBasis> reusableCapability) {

    public DirectionEvidenceSupport {
        userNeed = copyOf(userNeed, "userNeed");
        userFit = copyOf(userFit, "userFit");
        reusableCapability = copyOf(reusableCapability, "reusableCapability");
    }

    /**
     * 三组依据的扁平只读视图。
     *
     * <p>纯派生，不构成第二份状态：它每次都从三个槽位算出，因此不存在与它们不一致的可能。
     * 同一个 {@link EvidenceBasis} 支撑多个判断时只出现一次——重复出现只会让调用方以为
     * 有两条依据。内容相同但出处不同的依据仍然是两条，它们本来就是两个不同的事实。
     *
     * <p>元素保持 {@link EvidenceBasis} 而不是剥成 {@code Evidence}：剥掉来源会让
     * 「两个 Repository Profile 各有一条 README.md / 使用 Spring Boot」这种情形
     * 在扁平视图里变成两条看起来一模一样的记录——那恰好丢掉了本类型要保留的东西。
     *
     * <p>顺序按槽位顺序（userNeed → userFit → reusableCapability）与组内顺序。
     */
    public List<EvidenceBasis> allBases() {
        LinkedHashSet<EvidenceBasis> distinct = new LinkedHashSet<>();
        distinct.addAll(userNeed);
        distinct.addAll(userFit);
        distinct.addAll(reusableCapability);
        return List.copyOf(distinct);
    }

    /** 三组依据是否一条都没有。 */
    public boolean isEmpty() {
        return userNeed.isEmpty() && userFit.isEmpty() && reusableCapability.isEmpty();
    }

    private static List<EvidenceBasis> copyOf(List<EvidenceBasis> bases, String slot) {
        if (bases == null) {
            throw new IllegalArgumentException(
                    "Direction Evidence Support 的 " + slot + " 不能为 null");
        }
        for (EvidenceBasis basis : bases) {
            if (basis == null) {
                throw new IllegalArgumentException(
                        "Direction Evidence Support 的 " + slot + " 不能包含 null");
            }
        }
        return List.copyOf(bases);
    }
}
