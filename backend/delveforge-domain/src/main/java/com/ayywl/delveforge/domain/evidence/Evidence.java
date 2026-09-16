package com.ayywl.delveforge.domain.evidence;

/**
 * Evidence：支撑领域判断的可追溯依据（DOMAIN_MODEL.md §3.6）。
 *
 * <p>它用于回答「系统为什么得出这个结论」，把关键领域结论与其原始依据建立联系。
 * Evidence 可以来自用户明确提供的信息、Repository 中可定位的事实，也可以表示系统
 * 基于已有事实形成但尚未完全确认的推断——后者由 {@code confirmed} 与
 * {@code confidence} 表达。
 *
 * <p>Evidence 当前是 Value Object（DOMAIN_MODEL.md §4.2），不拥有独立身份与生命周期，
 * 随所属 Aggregate 一起保存。
 *
 * <p>本类型只定义 Evidence 自身的领域值。它不影响领域结论本身：Evidence 是否成立、
 * 是否足以支撑某个判断，由对应领域对象的规则决定。
 *
 * @param sourceType Evidence 的来源类型
 * @param sourceRef  能够追溯原始依据的引用；其具体形式取决于来源，
 *                   领域模型不规定格式，也不规定如何持久化
 * @param claim      该 Evidence 所支撑的事实或判断
 * @param confidence 对推断型 Evidence 的可信程度；{@code null} 表示未给出确定性判断。
 *                   数值刻度与有效范围尚未由领域模型规定，本类型不做范围校验
 * @param confirmed  是否已经经过用户或其他可靠方式确认
 */
public record Evidence(
        EvidenceSourceType sourceType,
        String sourceRef,
        String claim,
        Double confidence,
        boolean confirmed) {

    public Evidence {
        if (sourceType == null) {
            throw new IllegalArgumentException("Evidence 必须指定 sourceType");
        }
        if (sourceRef == null || sourceRef.isBlank()) {
            throw new IllegalArgumentException("Evidence 的 sourceRef 不能为空");
        }
        if (claim == null || claim.isBlank()) {
            throw new IllegalArgumentException("Evidence 的 claim 不能为空");
        }
        if (confidence != null && !Double.isFinite(confidence)) {
            throw new IllegalArgumentException("Evidence 的 confidence 必须是有限数值");
        }
    }
}
