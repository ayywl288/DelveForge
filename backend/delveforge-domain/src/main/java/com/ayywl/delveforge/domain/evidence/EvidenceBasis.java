package com.ayywl.delveforge.domain.evidence;

/**
 * 一条可追溯的领域依据：Evidence 本身，加上它来自哪里。
 *
 * <pre>
 * Evidence       这条依据说了什么
 * EvidenceOrigin 它出自哪一份分析 / 哪一版用户画像
 * </pre>
 *
 * <p>两者必须一起使用才成立：单独一条 {@link Evidence} 无法回答「这是谁的分析结论」，
 * 而 Product Direction 的推荐理由恰恰需要回答这一点（§3.6）。
 *
 * <p>它是一个值：同一份 Probe 里读出来的两条内容相同的依据，来自不同 Profile 时是两个
 * 不同的 {@code EvidenceBasis}，来自同一 Profile 时才是同一个。
 *
 * <p>本类型不持久化成一个独立的领域对象，也不意味着 Evidence 获得了 identity：
 * 它只是「依据 + 出处」这一对事实的载体。
 *
 * @param evidence 依据本身，不得为 {@code null}
 * @param origin   该依据的来源，不得为 {@code null}
 */
public record EvidenceBasis(Evidence evidence, EvidenceOrigin origin) {

    public EvidenceBasis {
        if (evidence == null) {
            throw new IllegalArgumentException("Evidence Basis 必须指定 evidence");
        }
        if (origin == null) {
            throw new IllegalArgumentException("Evidence Basis 必须指定 origin");
        }
    }
}
