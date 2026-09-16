package com.ayywl.delveforge.domain.evidence;

/**
 * Evidence 的来源类型（DOMAIN_MODEL.md §3.6）。
 *
 * <p>当前只建模领域模型中已经具体列出的来源。§3.6 同时写到 Evidence「也可以来自
 * 其他来源」，但该说法尚未对应任何真实用例，因此这里不预留兜底取值；
 * 真正出现新的、文档已明确的来源时再追加。
 */
public enum EvidenceSourceType {

    /** 用户明确提供的信息。 */
    USER_INPUT,

    /** Repository 中可定位的事实。 */
    REPOSITORY
}
