package com.ayywl.delveforge.app.api.evidence;

import com.ayywl.delveforge.domain.evidence.EvidenceBasis;

/**
 * {@code EvidenceBasis} 在 HTTP 接口上的表示：一条依据，以及它出自哪里。
 *
 * <pre>
 * evidence   这条依据说了什么
 * origin     它出自哪一份分析 / 哪一版用户画像
 * </pre>
 *
 * <p>两者一起给出，调用方才能回答「这条推荐理由的依据是谁的分析结论」。只回传
 * {@link EvidencePayload} 会把这个信息丢掉——两个 Repository Profile 完全可能各有一条
 * 内容相同的依据（§3.6）。
 *
 * <p>本类型是 Interface Adapter 的 DTO，不是领域对象。它不承载 Evidence 的任何身份：
 * 领域侧 Evidence 仍然是没有独立生命周期的 Value Object。
 *
 * @param evidence 依据本身，不得为 {@code null}
 * @param origin   该依据的出处，不得为 {@code null}
 */
public record EvidenceBasisPayload(EvidencePayload evidence, EvidenceOriginPayload origin) {

    public EvidenceBasisPayload {
        if (evidence == null) {
            throw new IllegalArgumentException("Evidence Basis 负载必须指定 evidence");
        }
        if (origin == null) {
            throw new IllegalArgumentException("Evidence Basis 负载必须指定 origin");
        }
    }

    /**
     * 把领域依据映射为它的接口表示。
     *
     * @param basis 领域侧依据，不得为 {@code null}
     */
    public static EvidenceBasisPayload from(EvidenceBasis basis) {
        return new EvidenceBasisPayload(
                EvidencePayload.from(basis.evidence()),
                EvidenceOriginPayload.from(basis.origin()));
    }
}
