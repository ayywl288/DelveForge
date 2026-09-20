package com.ayywl.delveforge.domain.asset;

/**
 * Software Asset 的使用授权（DOMAIN_MODEL.md §3.2 的 {@code usageAuthorization}）。
 *
 * <p>用于表达「当前是否确认允许复用或二次开发」，以及该确认的结果。以下三种状态具有
 * 不同的业务含义，不能互相折叠：
 *
 * <pre>
 * ALLOWED  已明确允许复用或二次开发
 * DENIED   已明确不允许复用或二次开发
 * UNCLEAR  当前尚未确认是否允许复用或二次开发（§12.6 的 usageAuthorization = unclear）
 * </pre>
 *
 * <p>UNCLEAR 不等于 DENIED：「尚未确认」与「已明确不允许」是两种不同的领域状态，
 * 对后续应如何处理的要求也不同，因此本类型不把它们压成同一个取值。
 *
 * <p>本类型只表达这一事实本身。什么条件下允许复用、是否允许作为 Evolution Base，
 * 属于 AssetUsagePolicy（§12.6、INV-A02～INV-A04），当前尚未实现；本类型不提供
 * 这类判断，也不从取值推导任何结论。
 */
public enum UsageAuthorization {

    /** 已明确允许复用或二次开发。 */
    ALLOWED,

    /** 已明确不允许复用或二次开发。 */
    DENIED,

    /** 当前尚未确认是否允许复用或二次开发。 */
    UNCLEAR
}
