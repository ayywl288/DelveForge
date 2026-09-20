package com.ayywl.delveforge.application.repositoryanalysis.asset;

import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;

/**
 * 读取一个已登记的 Software Asset。
 *
 * <p>只读取元数据，不访问 Repository 本身：本 Use Case 不判断 location 是否存在，
 * 也不执行 Git / Filesystem 操作（RULE-ARCH-009）。
 *
 * <p>读取本身不做编排，因此这里只负责把「找不到」翻译成明确的失败语义，
 * 让调用方不必自行判断 {@code Optional}。
 */
public class GetSoftwareAssetUseCase {

    private final SoftwareAssetRepository softwareAssetRepository;

    public GetSoftwareAssetUseCase(SoftwareAssetRepository softwareAssetRepository) {
        if (softwareAssetRepository == null) {
            throw new IllegalArgumentException(
                    "GetSoftwareAssetUseCase 必须指定 softwareAssetRepository");
        }
        this.softwareAssetRepository = softwareAssetRepository;
    }

    /**
     * 读取指定 Software Asset。
     *
     * @param softwareAssetId 资产标识
     * @return 对应的资产
     * @throws SoftwareAssetNotFoundException 资产不存在
     */
    public SoftwareAsset get(SoftwareAssetId softwareAssetId) {
        return softwareAssetRepository.findById(softwareAssetId)
                .orElseThrow(() -> new SoftwareAssetNotFoundException(softwareAssetId));
    }
}
