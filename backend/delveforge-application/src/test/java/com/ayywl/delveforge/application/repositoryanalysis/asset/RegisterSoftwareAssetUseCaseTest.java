package com.ayywl.delveforge.application.repositoryanalysis.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetSource;
import com.ayywl.delveforge.domain.asset.SoftwareAssetType;
import com.ayywl.delveforge.domain.asset.UsageAuthorization;
import org.junit.jupiter.api.Test;

class RegisterSoftwareAssetUseCaseTest {

    private static final String LOCATION = "E:/projects/legacy-tool";

    private static final String LICENSE_INFO = "MIT";

    private final InMemorySoftwareAssetRepository repository = new InMemorySoftwareAssetRepository();

    private final RegisterSoftwareAssetUseCase useCase = new RegisterSoftwareAssetUseCase(repository);

    @Test
    void registersUserSpecifiedLocalGitRepository() {
        SoftwareAsset asset = useCase.register(request(LOCATION, true, LICENSE_INFO,
                UsageAuthorization.ALLOWED));

        assertNotNull(asset.id());
        assertEquals(SoftwareAssetType.GIT_REPOSITORY, asset.type());
        assertEquals(SoftwareAssetSource.USER_SPECIFIED, asset.source());
        assertEquals(LOCATION, asset.location());
        assertTrue(asset.readPermissionAllowed());
        assertEquals(LICENSE_INFO, asset.licenseInfo().orElseThrow());
        assertEquals(UsageAuthorization.ALLOWED, asset.usageAuthorization());
    }

    @Test
    void savesRegisteredAssetThroughPersistencePort() {
        SoftwareAsset asset = useCase.register(request(LOCATION, true, null,
                UsageAuthorization.UNCLEAR));

        assertEquals(asset, repository.findById(asset.id()).orElseThrow());
        assertEquals(1, repository.saveCount());
    }

    @Test
    void assignsDistinctIdentityToEachAsset() {
        SoftwareAsset first = useCase.register(request("E:/projects/one", true, null,
                UsageAuthorization.UNCLEAR));
        SoftwareAsset second = useCase.register(request("E:/projects/two", true, null,
                UsageAuthorization.UNCLEAR));

        assertNotEquals(first.id(), second.id());
        assertTrue(repository.findById(first.id()).isPresent());
        assertTrue(repository.findById(second.id()).isPresent());
        assertEquals(2, repository.saveCount());
    }

    /**
     * 同一个 location 登记两次是两个独立的 Software Asset：
     * 领域模型没有定义 location 唯一性，登记也不做去重。
     */
    @Test
    void allowsRegisteringTwoAssetsForTheSameLocation() {
        SoftwareAsset first = useCase.register(request(LOCATION, true, null,
                UsageAuthorization.UNCLEAR));
        SoftwareAsset second = useCase.register(request(LOCATION, true, null,
                UsageAuthorization.UNCLEAR));

        assertNotEquals(first.id(), second.id());
        assertEquals(LOCATION, second.location());
        assertEquals(2, repository.saveCount());
    }

    /**
     * 登记只记录资产元数据：不判断 location 是否真实存在，也不判断它是否是 Git Repository，
     * 因此一个并不存在的路径同样可以登记成功（RULE-ARCH-009：登记不访问文件系统）。
     */
    @Test
    void registersWithoutCheckingThatLocationExists() {
        SoftwareAsset asset = useCase.register(request("E:/does/not/exist", true, null,
                UsageAuthorization.UNCLEAR));

        assertEquals("E:/does/not/exist", asset.location());
        assertEquals(1, repository.saveCount());
    }

    @Test
    void registersAssetWhoseLicenseInformationIsUnknown() {
        SoftwareAsset asset = useCase.register(request(LOCATION, true, null,
                UsageAuthorization.UNCLEAR));

        assertTrue(asset.licenseInfo().isEmpty());
    }

    /**
     * 使用授权由输入原样带给领域，登记不替用户确认或升级它：
     * 传 UNCLEAR 得到的就是 UNCLEAR，不会因为资产可读就变成 ALLOWED（RULE-DOM-004）。
     */
    @Test
    void keepsUsageAuthorizationExactlyAsGiven() {
        SoftwareAsset unclear = useCase.register(request(LOCATION, true, LICENSE_INFO,
                UsageAuthorization.UNCLEAR));
        SoftwareAsset denied = useCase.register(request(LOCATION, true, LICENSE_INFO,
                UsageAuthorization.DENIED));

        assertEquals(UsageAuthorization.UNCLEAR, unclear.usageAuthorization());
        assertEquals(UsageAuthorization.DENIED, denied.usageAuthorization());
    }

    @Test
    void rejectsMissingRepository() {
        assertThrows(IllegalArgumentException.class, () -> new RegisterSoftwareAssetUseCase(null));
    }

    @Test
    void rejectsMissingRequest() {
        assertThrows(IllegalArgumentException.class, () -> useCase.register(null));
        assertEquals(0, repository.saveCount());
    }

    @Test
    void rejectsBlankLocationThroughDomainRule() {
        assertThrows(IllegalArgumentException.class,
                () -> useCase.register(request("  ", true, null, UsageAuthorization.UNCLEAR)));

        assertEquals(0, repository.saveCount(), "被领域拒绝的资产不得写入");
    }

    @Test
    void rejectsMissingUsageAuthorizationThroughDomainRule() {
        assertThrows(IllegalArgumentException.class,
                () -> useCase.register(request(LOCATION, true, LICENSE_INFO, null)));

        assertEquals(0, repository.saveCount(), "被领域拒绝的资产不得写入");
    }

    /**
     * 登记不判断读取权限是否合理，只如实保存：不因为使用授权已确认就假定可读。
     */
    @Test
    void keepsReadPermissionExactlyAsGiven() {
        SoftwareAsset notReadable = useCase.register(request(LOCATION, false, LICENSE_INFO,
                UsageAuthorization.ALLOWED));

        assertFalse(notReadable.readPermissionAllowed());
    }

    private static RegisterSoftwareAssetRequest request(
            String location,
            boolean readPermissionAllowed,
            String licenseInfo,
            UsageAuthorization usageAuthorization) {
        return new RegisterSoftwareAssetRequest(
                location, readPermissionAllowed, licenseInfo, usageAuthorization);
    }
}
