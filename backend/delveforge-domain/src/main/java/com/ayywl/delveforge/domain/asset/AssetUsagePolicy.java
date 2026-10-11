package com.ayywl.delveforge.domain.asset;

import static com.ayywl.delveforge.domain.asset.AssetEvolutionNotAllowedException.Reason.ASSET_TYPE_UNSUPPORTED;
import static com.ayywl.delveforge.domain.asset.AssetEvolutionNotAllowedException.Reason.LICENSE_INFORMATION_REQUIRED;
import static com.ayywl.delveforge.domain.asset.AssetEvolutionNotAllowedException.Reason.READ_PERMISSION_REQUIRED;
import static com.ayywl.delveforge.domain.asset.AssetEvolutionNotAllowedException.Reason.USAGE_AUTHORIZATION_REQUIRED;

/** Evolution Base 的最低授权门槛（§12.6），不推断法律许可。 */
public final class AssetUsagePolicy {
    public void requireEvolutionBaseAllowed(SoftwareAsset asset) {
        if (asset == null) {
            throw new IllegalArgumentException("Asset is required");
        }
        if (asset.type() != SoftwareAssetType.GIT_REPOSITORY) {
            throw new AssetEvolutionNotAllowedException(ASSET_TYPE_UNSUPPORTED);
        }
        if (!asset.readPermissionAllowed()) {
            throw new AssetEvolutionNotAllowedException(READ_PERMISSION_REQUIRED);
        }
        if (asset.usageAuthorization() != UsageAuthorization.ALLOWED) {
            throw new AssetEvolutionNotAllowedException(USAGE_AUTHORIZATION_REQUIRED);
        }
        if (asset.licenseInfo().isEmpty()) {
            throw new AssetEvolutionNotAllowedException(LICENSE_INFORMATION_REQUIRED);
        }
    }
}
