package com.ayywl.delveforge.application.repositoryanalysis.region;

/**
 * Region Catalog 的序列化载荷超过配置的上限。
 *
 * <p>与 flat File Catalog 的字节守卫是同一条边界在分层导航一侧的对应物（ADR-0005）：
 * File Catalog 超限时改走分层（把它压进预算），而分层自己看到的这一层目录如果再超限，
 * 说明这个形状连缩范围都做不到，如实失败比悄悄截断更可信。
 *
 * <p>本类型只表达「这一层超限」这一事实，不决定怎么处置——失败语义属于调用这条链路的编排层。
 */
public class RepositoryRegionCatalogTooLargeException extends RuntimeException {

    public RepositoryRegionCatalogTooLargeException(String detail) {
        super(detail);
    }
}
