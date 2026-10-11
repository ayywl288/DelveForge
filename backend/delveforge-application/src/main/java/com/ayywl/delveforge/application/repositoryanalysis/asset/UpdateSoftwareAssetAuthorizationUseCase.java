package com.ayywl.delveforge.application.repositoryanalysis.asset;

import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.asset.UsageAuthorization;

import java.util.Objects;

/** 显式更新授权元数据，保留资产身份和所有已存在的业务绑定。 */
public final class UpdateSoftwareAssetAuthorizationUseCase {
    private final SoftwareAssetRepository assets;

    public UpdateSoftwareAssetAuthorizationUseCase(SoftwareAssetRepository assets) {
        this.assets = Objects.requireNonNull(assets);
    }

    public SoftwareAsset update(SoftwareAssetId id, long expectedRevision, boolean readPermissionAllowed,
            String licenseInfo, UsageAuthorization usageAuthorization) {
        SoftwareAsset current = assets.findById(id)
                .orElseThrow(() -> new SoftwareAssetNotFoundException(id));
        SoftwareAsset candidate = current.updateAuthorization(
                expectedRevision, readPermissionAllowed, licenseInfo, usageAuthorization);
        assets.updateAuthorization(candidate, expectedRevision);
        return candidate;
    }
}
