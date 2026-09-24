package com.ayywl.delveforge.domain.direction;

/**
 * Product Direction 的生命周期状态（DOMAIN_MODEL.md §6.2）。
 *
 * <p>本类型只表达状态本身。§6.2 定义的三条转换全部由 Aggregate Root 提供入口：
 *
 * <pre>
 * CANDIDATE → SELECTED     用户选择该方向     ProductDirection.select
 * CANDIDATE → REJECTED     用户不选择该方向   ProductDirection.reject
 * SELECTED  → SUPERSEDED   用户切换到其他方向 ProductDirection.supersede
 * </pre>
 *
 * <p>转换入口一律放在 Aggregate Root 上，本类型不提供转换方法，也不提供
 * 「哪些状态可以做什么」之外的判断——§6.2 中不出现在上述清单里的组合
 * （例如从 {@code REJECTED} 重新选择）没有定义，因此由 Aggregate 拒绝，
 * 而不是在本类型上补一条默认规则。
 */
public enum ProductDirectionStatus {

    /** 系统生成、等待用户判断的候选产品方向。 */
    CANDIDATE,

    /** 用户明确选择该方向继续进入 Evolution Planning。 */
    SELECTED,

    /** 用户明确不选择该方向。 */
    REJECTED,

    /** 该方向曾经被选择，但之后用户切换到其他方向。 */
    SUPERSEDED
}
