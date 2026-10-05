package com.ayywl.delveforge.domain.asset;

/**
 * 本地 Repository 的最小使用策略（DOMAIN_MODEL.md §12.6）。
 * 当前 MVP 要求明确使用许可声明和已记录的 license 信息。
 * 声明表示复用或修改符合该信息；策略不会从 license 文本自动推断法律许可。
 */
public final class AssetUsagePolicy {
    public void requireEvolutionBaseAllowed(SoftwareAsset asset) {
        if (asset == null) {
            throw new IllegalArgumentException("Asset is required");
        }
        if (asset.type() != SoftwareAssetType.GIT_REPOSITORY
                || !asset.readPermissionAllowed()
                || asset.usageAuthorization() != UsageAuthorization.ALLOWED
                || asset.licenseInfo().isEmpty()) {
            throw new AssetEvolutionNotAllowedException("Asset is not authorized as an Evolution Base: " + asset.id().value());
        }
    }
}
