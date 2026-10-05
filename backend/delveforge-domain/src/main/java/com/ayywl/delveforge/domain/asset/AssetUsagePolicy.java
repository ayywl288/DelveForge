package com.ayywl.delveforge.domain.asset;

/** Minimal local-Repository policy (DOMAIN_MODEL.md §12.6).
 * Current MVP choice: require explicit usage approval and recorded license information.
 * Approval is the asset-level declaration that the intended reuse/modification respects
 * that information; this policy does not infer legal permission from license prose.
 */
public final class AssetUsagePolicy {
    public void requireEvolutionBaseAllowed(SoftwareAsset asset) {
        if (asset == null) throw new IllegalArgumentException("Asset is required");
        if (asset.type() != SoftwareAssetType.GIT_REPOSITORY
                || !asset.readPermissionAllowed()
                || asset.usageAuthorization() != UsageAuthorization.ALLOWED
                || asset.licenseInfo().isEmpty()) {
            throw new AssetEvolutionNotAllowedException("Asset is not authorized as an Evolution Base: " + asset.id().value());
        }
    }
}
