package com.ayywl.delveforge.domain.asset;

/**
 * Software Asset 的来源（DOMAIN_MODEL.md §3.2 的 {@code source}、§14.7）。
 *
 * <p>MVP 中 Software Asset 由用户指定（§14.7）。「由系统发现」属于后续产品方向，
 * §14.7 明确它尚未进入当前领域模型，因此这里不预留取值。
 */
public enum SoftwareAssetSource {

    /** 由用户明确指定。MVP 中唯一的 Software Asset 来源。 */
    USER_SPECIFIED
}
