package com.ayywl.delveforge.domain.asset;

/**
 * Software Asset 的类型（DOMAIN_MODEL.md §3.2）。
 *
 * <p>MVP 中 Software Asset 的具体形式只能是本地 Git Repository（§3.2、§14.7）。
 *
 * <p>§3.2 说明未来可扩展到由用户指定或系统发现的其他软件资产，但没有列出任何具体类型，
 * 因此这里不预留占位取值：真正出现文档已明确的类型时再追加
 * （与 {@link com.ayywl.delveforge.domain.evidence.EvidenceSourceType} 的处理方式一致）。
 */
public enum SoftwareAssetType {

    /** 可分析的代码仓库。MVP 中只处理用户指定的本地 Git Repository。 */
    GIT_REPOSITORY
}
