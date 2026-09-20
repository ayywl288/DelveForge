package com.ayywl.delveforge.domain.asset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SoftwareAssetTest {

    private static final SoftwareAssetId ASSET_ID = new SoftwareAssetId("software-asset-1");

    private static final String LOCATION = "E:/projects/legacy-tool";

    private static final String LICENSE_INFO = "MIT";

    @Test
    void createsAssetWithStableIdentityAndCoreFields() {
        SoftwareAsset asset = createAsset(ASSET_ID, LOCATION, true, LICENSE_INFO, false);

        assertEquals(ASSET_ID, asset.id());
        assertEquals(SoftwareAssetType.GIT_REPOSITORY, asset.type());
        assertEquals(SoftwareAssetSource.USER_SPECIFIED, asset.source());
        assertEquals(LOCATION, asset.location());
        assertTrue(asset.readPermissionAllowed());
        assertEquals(LICENSE_INFO, asset.licenseInfo().orElseThrow());
        assertFalse(asset.usageAuthorized());
    }

    @Test
    void expressesMvpGitRepositoryAssetFromUserSpecifiedSource() {
        SoftwareAsset asset = asset(true, null, false);

        assertEquals(SoftwareAssetType.GIT_REPOSITORY, asset.type());
        assertEquals(SoftwareAssetSource.USER_SPECIFIED, asset.source());
        assertEquals(LOCATION, asset.location());
    }

    /**
     * Software Asset 的身份不随位置等属性变化（DOMAIN_MODEL.md §4.1）：
     * 两个位置不同的资产仍然是各自独立、身份稳定的软件资产。
     */
    @Test
    void keepsIdentityWhenOtherAttributesDiffer() {
        SoftwareAsset first = createAsset(ASSET_ID, "E:/projects/one", true, null, false);
        SoftwareAsset second = createAsset(ASSET_ID, "E:/projects/two", false, LICENSE_INFO, true);

        assertEquals(ASSET_ID, first.id());
        assertEquals(ASSET_ID, second.id());
        assertEquals("E:/projects/two", second.location());
        assertFalse(second.readPermissionAllowed());
        assertTrue(second.usageAuthorized());
    }

    @Test
    void expressesUnknownLicenseInformationAsAbsent() {
        SoftwareAsset asset = asset(true, null, false);

        assertTrue(asset.licenseInfo().isEmpty(), "未给出的许可证信息表示未知，而不是没有许可证限制");
    }

    @Test
    void rejectsMissingIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> createAsset(null, LOCATION, true, null, false));
    }

    @Test
    void rejectsMissingType() {
        assertThrows(IllegalArgumentException.class,
                () -> SoftwareAsset.create(ASSET_ID, null, SoftwareAssetSource.USER_SPECIFIED,
                        LOCATION, true, null, false));
    }

    @Test
    void rejectsMissingSource() {
        assertThrows(IllegalArgumentException.class,
                () -> SoftwareAsset.create(ASSET_ID, SoftwareAssetType.GIT_REPOSITORY, null,
                        LOCATION, true, null, false));
    }

    @Test
    void rejectsBlankLocation() {
        assertThrows(IllegalArgumentException.class,
                () -> createAsset(ASSET_ID, "  ", true, null, false));
    }

    @Test
    void rejectsBlankLicenseInfo() {
        assertThrows(IllegalArgumentException.class,
                () -> createAsset(ASSET_ID, LOCATION, true, "  ", false));
    }

    @Test
    void allowsAnalysisWhenReadPermissionIsAllowed() {
        assertAnalysisAllowed(asset(true, LICENSE_INFO, true));
    }

    @Test
    void rejectsAnalysisWhenReadPermissionIsDenied() {
        assertAnalysisRejected(asset(false, LICENSE_INFO, true));
    }

    /**
     * Repository Analysis 的读取前置条件只取决于 readPermission：
     * 使用授权与许可证信息都不能替代它，也不能让它更容易通过
     * （DOMAIN_MODEL.md §8.3 前置条件、§12.6 的 Analysis 判定、INV-A01）。
     */
    @Test
    void analysisPermissionDependsOnlyOnReadPermission() {
        assertAnalysisAllowed(asset(true, LICENSE_INFO, true));
        assertAnalysisAllowed(asset(true, null, false));

        assertAnalysisRejected(asset(false, LICENSE_INFO, true));
        assertAnalysisRejected(asset(false, LICENSE_INFO, false));
        assertAnalysisRejected(asset(false, null, true));
    }

    @Test
    void keepsAssetIntactWhenAnalysisIsRejected() {
        SoftwareAsset asset = asset(false, LICENSE_INFO, true);

        assertAnalysisRejected(asset);

        assertEquals(ASSET_ID, asset.id());
        assertEquals(LOCATION, asset.location());
        assertFalse(asset.readPermissionAllowed(), "校验不得改写读取权限");
        assertEquals(LICENSE_INFO, asset.licenseInfo().orElseThrow(), "校验不得改写许可证信息");
        assertTrue(asset.usageAuthorized(), "校验不得改写使用授权");
    }

    /**
     * 通过读取前置条件只说明该资产可以被读取和分析：本类不因此推导出「已经允许复用或二次开发」，
     * 使用授权仍然是它自己声明的事实（DOMAIN_MODEL.md §12.6 的 Readable ≠ Reusable）。
     */
    @Test
    void analysisAllowedDoesNotImplyUsageAuthorization() {
        SoftwareAsset notAuthorizedForReuse = asset(true, null, false);

        notAuthorizedForReuse.requireAnalysisAllowed();

        assertFalse(notAuthorizedForReuse.usageAuthorized(),
                "可读取不代表已确认允许复用或二次开发");
    }

    private static SoftwareAsset asset(
            boolean readPermissionAllowed, String licenseInfo, boolean usageAuthorized) {
        return createAsset(
                ASSET_ID, LOCATION, readPermissionAllowed, licenseInfo, usageAuthorized);
    }

    private static SoftwareAsset createAsset(
            SoftwareAssetId id,
            String location,
            boolean readPermissionAllowed,
            String licenseInfo,
            boolean usageAuthorized) {
        return SoftwareAsset.create(
                id,
                SoftwareAssetType.GIT_REPOSITORY,
                SoftwareAssetSource.USER_SPECIFIED,
                location,
                readPermissionAllowed,
                licenseInfo,
                usageAuthorized);
    }

    private static void assertAnalysisAllowed(SoftwareAsset asset) {
        assertDoesNotThrow(asset::requireAnalysisAllowed);
    }

    private static void assertAnalysisRejected(SoftwareAsset asset) {
        assertThrows(SoftwareAssetNotReadableException.class, asset::requireAnalysisAllowed);
    }
}
