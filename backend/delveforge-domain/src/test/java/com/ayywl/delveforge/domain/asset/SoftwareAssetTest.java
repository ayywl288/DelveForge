package com.ayywl.delveforge.domain.asset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SoftwareAssetTest {

    private static final SoftwareAssetId ASSET_ID = new SoftwareAssetId("software-asset-1");

    private static final String LOCATION = "E:/projects/legacy-tool";

    private static final String LICENSE_INFO = "MIT";

    @Test
    void createsAssetWithStableIdentityAndCoreFields() {
        SoftwareAsset asset = createAsset(
                ASSET_ID, LOCATION, true, LICENSE_INFO, UsageAuthorization.ALLOWED);

        assertEquals(ASSET_ID, asset.id());
        assertEquals(SoftwareAssetType.GIT_REPOSITORY, asset.type());
        assertEquals(SoftwareAssetSource.USER_SPECIFIED, asset.source());
        assertEquals(LOCATION, asset.location());
        assertTrue(asset.readPermissionAllowed());
        assertEquals(LICENSE_INFO, asset.licenseInfo().orElseThrow());
        assertEquals(UsageAuthorization.ALLOWED, asset.usageAuthorization());
    }

    @Test
    void expressesMvpGitRepositoryAssetFromUserSpecifiedSource() {
        SoftwareAsset asset = asset(true, null, UsageAuthorization.UNCLEAR);

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
        SoftwareAsset first = createAsset(ASSET_ID, "E:/projects/one", true, null,
                UsageAuthorization.UNCLEAR);
        SoftwareAsset second = createAsset(ASSET_ID, "E:/projects/two", false, LICENSE_INFO,
                UsageAuthorization.DENIED);

        assertEquals(ASSET_ID, first.id());
        assertEquals(ASSET_ID, second.id());
        assertEquals("E:/projects/two", second.location());
        assertFalse(second.readPermissionAllowed());
        assertEquals(UsageAuthorization.DENIED, second.usageAuthorization());
    }

    @Test
    void expressesUnknownLicenseInformationAsAbsent() {
        SoftwareAsset asset = asset(true, null, UsageAuthorization.UNCLEAR);

        assertTrue(asset.licenseInfo().isEmpty(), "未给出的许可证信息表示未知，而不是没有许可证限制");
    }

    @Test
    void expressesUsageAuthorizationAllowed() {
        SoftwareAsset asset = asset(true, LICENSE_INFO, UsageAuthorization.ALLOWED);

        assertEquals(UsageAuthorization.ALLOWED, asset.usageAuthorization());
    }

    @Test
    void expressesUsageAuthorizationDenied() {
        SoftwareAsset asset = asset(true, LICENSE_INFO, UsageAuthorization.DENIED);

        assertEquals(UsageAuthorization.DENIED, asset.usageAuthorization());
    }

    @Test
    void expressesUsageAuthorizationUnclear() {
        SoftwareAsset asset = asset(true, LICENSE_INFO, UsageAuthorization.UNCLEAR);

        assertEquals(UsageAuthorization.UNCLEAR, asset.usageAuthorization());
    }

    /**
     * 「已明确不允许」与「尚未确认」是两种不同的领域状态：
     * Software Asset 必须能够如实区分它们，而不是把两者压成同一个取值。
     */
    @Test
    void distinguishesUnclearFromDenied() {
        SoftwareAsset unclear = asset(true, null, UsageAuthorization.UNCLEAR);
        SoftwareAsset denied = asset(true, null, UsageAuthorization.DENIED);

        assertNotEquals(denied.usageAuthorization(), unclear.usageAuthorization());
        assertEquals(UsageAuthorization.UNCLEAR, unclear.usageAuthorization(),
                "尚未确认不得被表达成已明确不允许");
        assertEquals(UsageAuthorization.DENIED, denied.usageAuthorization(),
                "已明确不允许不得被表达成尚未确认");
    }

    @Test
    void rejectsMissingIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> createAsset(null, LOCATION, true, null, UsageAuthorization.UNCLEAR));
    }

    @Test
    void rejectsMissingType() {
        assertThrows(IllegalArgumentException.class,
                () -> SoftwareAsset.create(ASSET_ID, null, SoftwareAssetSource.USER_SPECIFIED,
                        LOCATION, true, null, UsageAuthorization.UNCLEAR));
    }

    @Test
    void rejectsMissingSource() {
        assertThrows(IllegalArgumentException.class,
                () -> SoftwareAsset.create(ASSET_ID, SoftwareAssetType.GIT_REPOSITORY, null,
                        LOCATION, true, null, UsageAuthorization.UNCLEAR));
    }

    @Test
    void rejectsBlankLocation() {
        assertThrows(IllegalArgumentException.class,
                () -> createAsset(ASSET_ID, "  ", true, null, UsageAuthorization.UNCLEAR));
    }

    @Test
    void rejectsBlankLicenseInfo() {
        assertThrows(IllegalArgumentException.class,
                () -> createAsset(ASSET_ID, LOCATION, true, "  ", UsageAuthorization.UNCLEAR));
    }

    /**
     * 「尚未确认」必须由调用方显式给出，而不是由缺失参数默认得到——
     * 否则无法区分「系统尚未确认」与「系统漏填了」。
     */
    @Test
    void rejectsMissingUsageAuthorization() {
        assertThrows(IllegalArgumentException.class,
                () -> createAsset(ASSET_ID, LOCATION, true, LICENSE_INFO, null));
    }

    @Test
    void allowsAnalysisWhenReadPermissionIsAllowed() {
        assertAnalysisAllowed(asset(true, LICENSE_INFO, UsageAuthorization.ALLOWED));
    }

    @Test
    void rejectsAnalysisWhenReadPermissionIsDenied() {
        assertAnalysisRejected(asset(false, LICENSE_INFO, UsageAuthorization.ALLOWED));
    }

    /**
     * Repository Analysis 的读取前置条件只取决于 readPermission：
     * 使用授权与许可证信息都不能替代它，也不能让它更容易通过
     * （DOMAIN_MODEL.md §8.3 前置条件、§12.6 的 Analysis 判定、INV-A01）。
     */
    @Test
    void analysisPermissionDependsOnlyOnReadPermission() {
        assertAnalysisAllowed(asset(true, LICENSE_INFO, UsageAuthorization.ALLOWED));
        assertAnalysisAllowed(asset(true, null, UsageAuthorization.DENIED));
        assertAnalysisAllowed(asset(true, null, UsageAuthorization.UNCLEAR));

        assertAnalysisRejected(asset(false, LICENSE_INFO, UsageAuthorization.ALLOWED));
        assertAnalysisRejected(asset(false, LICENSE_INFO, UsageAuthorization.DENIED));
        assertAnalysisRejected(asset(false, null, UsageAuthorization.UNCLEAR));
    }

    @Test
    void keepsAssetIntactWhenAnalysisIsRejected() {
        SoftwareAsset asset = asset(false, LICENSE_INFO, UsageAuthorization.DENIED);

        assertAnalysisRejected(asset);

        assertEquals(ASSET_ID, asset.id());
        assertEquals(LOCATION, asset.location());
        assertFalse(asset.readPermissionAllowed(), "校验不得改写读取权限");
        assertEquals(LICENSE_INFO, asset.licenseInfo().orElseThrow(), "校验不得改写许可证信息");
        assertEquals(UsageAuthorization.DENIED, asset.usageAuthorization(), "校验不得改写使用授权");
    }

    /**
     * 通过读取前置条件只说明该资产可以被读取和分析：本类不因此推导出「已经允许复用或二次开发」，
     * 使用授权仍然是它自己声明的那个状态（DOMAIN_MODEL.md §12.6 的 Readable ≠ Reusable）。
     */
    @Test
    void analysisAllowedDoesNotImplyUsageAuthorization() {
        SoftwareAsset notAuthorizedForReuse = asset(true, null, UsageAuthorization.UNCLEAR);

        notAuthorizedForReuse.requireAnalysisAllowed();

        assertEquals(UsageAuthorization.UNCLEAR, notAuthorizedForReuse.usageAuthorization(),
                "可读取不代表已确认允许复用或二次开发");
    }

    /**
     * 按已保存的状态重建：恢复出的资产在身份与全部领域字段上与保存前一致
     * （这是 Persistence 能够完整还原 Software Asset 的前提）。
     */
    @Test
    void reconstitutesSavedAssetWithoutLosingAnyField() {
        SoftwareAsset reconstituted = SoftwareAsset.reconstitute(
                ASSET_ID,
                SoftwareAssetType.GIT_REPOSITORY,
                SoftwareAssetSource.USER_SPECIFIED,
                LOCATION,
                true,
                LICENSE_INFO,
                UsageAuthorization.DENIED);

        assertEquals(ASSET_ID, reconstituted.id());
        assertEquals(SoftwareAssetType.GIT_REPOSITORY, reconstituted.type());
        assertEquals(SoftwareAssetSource.USER_SPECIFIED, reconstituted.source());
        assertEquals(LOCATION, reconstituted.location());
        assertTrue(reconstituted.readPermissionAllowed());
        assertEquals(LICENSE_INFO, reconstituted.licenseInfo().orElseThrow());
        assertEquals(UsageAuthorization.DENIED, reconstituted.usageAuthorization());
    }

    @Test
    void reconstitutesAssetWithUnknownLicenseInformation() {
        SoftwareAsset reconstituted = SoftwareAsset.reconstitute(
                ASSET_ID,
                SoftwareAssetType.GIT_REPOSITORY,
                SoftwareAssetSource.USER_SPECIFIED,
                LOCATION,
                false,
                null,
                UsageAuthorization.UNCLEAR);

        assertTrue(reconstituted.licenseInfo().isEmpty(), "未知许可证信息仍以缺失表示");
        assertFalse(reconstituted.readPermissionAllowed());
        assertEquals(UsageAuthorization.UNCLEAR, reconstituted.usageAuthorization());
    }

    @Test
    void rejectsReconstitutionWithoutIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> SoftwareAsset.reconstitute(null,
                        SoftwareAssetType.GIT_REPOSITORY, SoftwareAssetSource.USER_SPECIFIED,
                        LOCATION, true, null, UsageAuthorization.UNCLEAR));
    }

    @Test
    void rejectsReconstitutionWithBlankLocation() {
        assertThrows(IllegalArgumentException.class,
                () -> SoftwareAsset.reconstitute(ASSET_ID,
                        SoftwareAssetType.GIT_REPOSITORY, SoftwareAssetSource.USER_SPECIFIED,
                        "  ", true, null, UsageAuthorization.UNCLEAR));
    }

    @Test
    void rejectsReconstitutionWithoutUsageAuthorization() {
        assertThrows(IllegalArgumentException.class,
                () -> SoftwareAsset.reconstitute(ASSET_ID,
                        SoftwareAssetType.GIT_REPOSITORY, SoftwareAssetSource.USER_SPECIFIED,
                        LOCATION, true, null, null));
    }

    private static SoftwareAsset asset(
            boolean readPermissionAllowed,
            String licenseInfo,
            UsageAuthorization usageAuthorization) {
        return createAsset(
                ASSET_ID, LOCATION, readPermissionAllowed, licenseInfo, usageAuthorization);
    }

    private static SoftwareAsset createAsset(
            SoftwareAssetId id,
            String location,
            boolean readPermissionAllowed,
            String licenseInfo,
            UsageAuthorization usageAuthorization) {
        return SoftwareAsset.create(
                id,
                SoftwareAssetType.GIT_REPOSITORY,
                SoftwareAssetSource.USER_SPECIFIED,
                location,
                readPermissionAllowed,
                licenseInfo,
                usageAuthorization);
    }

    private static void assertAnalysisAllowed(SoftwareAsset asset) {
        assertDoesNotThrow(asset::requireAnalysisAllowed);
    }

    private static void assertAnalysisRejected(SoftwareAsset asset) {
        assertThrows(SoftwareAssetNotReadableException.class, asset::requireAnalysisAllowed);
    }
}
