package com.ayywl.delveforge.application.repositoryanalysis.asset;

import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@link SoftwareAssetRepository} 的测试替身。
 *
 * <p>Application 测试在 Port 边界使用替身，从而不依赖 Spring、SQLite、MyBatis-Plus
 * 或任何 Infrastructure 实现（AGENTS.md §10.2）。
 *
 * <p>Software Asset 是不可变的领域对象，没有「保存后再修改」的场景，
 * 因此这里直接保存实例本身即可，不存在
 * {@code InMemoryUserProfileRepository} 那种读写不分离的限制。
 */
public final class InMemorySoftwareAssetRepository implements SoftwareAssetRepository {

    private final Map<SoftwareAssetId, SoftwareAsset> assets = new HashMap<>();

    private int saveCount;

    @Override
    public void save(SoftwareAsset asset) {
        assets.put(asset.id(), asset);
        saveCount++;
    }

    @Override
    public Optional<SoftwareAsset> findById(SoftwareAssetId id) {
        return Optional.ofNullable(assets.get(id));
    }

    /** 累计调用 {@link #save} 的次数。 */
    public int saveCount() {
        return saveCount;
    }
}
