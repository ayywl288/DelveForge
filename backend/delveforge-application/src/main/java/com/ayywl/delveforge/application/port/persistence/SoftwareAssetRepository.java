package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;

import java.util.List;
import java.util.Optional;

/** 资产元数据持久化边界；不读取源码或推断授权。 */
public interface SoftwareAssetRepository {
    /** 兼容历史记录的低层保存；业务注册或授权修改不使用此入口。 */
    void save(SoftwareAsset asset);

    Optional<SoftwareAsset> findById(SoftwareAssetId id);

    /** 并发安全的新登记；重复位置拒绝，不覆盖原授权。 */
    default void registerUnique(SoftwareAsset asset) {
        throw new UnsupportedOperationException("Atomic registration is required");
    }

    /** 可能返回多个历史身份；不合并记录。 */
    default List<SoftwareAsset> findByLocation(String location) {
        throw new UnsupportedOperationException("Repository location lookup is required");
    }

    /** 仅更新授权，CAS 失败必须拒绝。 */
    default void updateAuthorization(SoftwareAsset candidate, long expectedRevision) {
        throw new UnsupportedOperationException("Authorization CAS is required");
    }
}
