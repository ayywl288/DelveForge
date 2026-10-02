package com.ayywl.delveforge.application.repositoryanalysis.region;

/**
 * Region Catalog 的序列化载荷超过配置的上限。
 *
 * <p>与 File Catalog 的 {@code SCOUT_CATALOG_TOO_LARGE} 是同一条边界在分层导航一侧的对应物
 * （ADR-0005）：目录一旦过大，说明这一层不是本版本能处理的形状，如实失败比悄悄截断更可信。
 *
 * <p>本类型只表达「这一层超限」这一事实，不决定怎么处置——失败语义属于调用这条链路的编排层。
 */
public class RepositoryRegionCatalogTooLargeException extends RuntimeException {

    public RepositoryRegionCatalogTooLargeException(String detail) {
        super(detail);
    }
}
