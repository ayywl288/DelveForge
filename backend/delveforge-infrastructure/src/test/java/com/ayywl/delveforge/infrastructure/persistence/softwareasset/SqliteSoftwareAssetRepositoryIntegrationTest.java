package com.ayywl.delveforge.infrastructure.persistence.softwareasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.repositoryanalysis.asset.GetSoftwareAssetUseCase;
import com.ayywl.delveforge.application.repositoryanalysis.asset.RegisterSoftwareAssetRequest;
import com.ayywl.delveforge.application.repositoryanalysis.asset.RegisterSoftwareAssetUseCase;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.asset.SoftwareAssetSource;
import com.ayywl.delveforge.domain.asset.SoftwareAssetType;
import com.ayywl.delveforge.domain.asset.UsageAuthorization;
import com.ayywl.delveforge.infrastructure.persistence.SqliteDataSourceConfiguration;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验证 {@link SqliteSoftwareAssetRepository} 与真实 SQLite + Flyway + MyBatis-Plus 的集成。
 *
 * <p>使用测试专用的临时数据库，不接触开发者本地数据库。{@code @Transactional}
 * 使每个测试方法结束后回滚，保证方法之间状态隔离。
 *
 * <p>本类只使用生产迁移（{@code classpath:db/migration}），因此同时验证了
 * {@code V3__software_asset.sql} 在真实 SQLite 上的可执行性。
 */
@SpringBootTest(
        classes = SqliteSoftwareAssetRepositoryIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class SqliteSoftwareAssetRepositoryIntegrationTest {

    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "delveforge.db");

    private static final SoftwareAssetId ASSET_ID = new SoftwareAssetId("software-asset-1");

    private static final String LOCATION = "E:/projects/legacy-tool";

    private static final String LICENSE_INFO = "MIT";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE_FILE::toString);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SqliteDataSourceConfiguration.class, SqliteSoftwareAssetRepository.class})
    @MapperScan("com.ayywl.delveforge.infrastructure.persistence.softwareasset")
    static class TestApplication {
    }

    @Autowired
    private SoftwareAssetRepository repository;

    @Autowired
    private SoftwareAssetMapper softwareAssetMapper;

    @Test
    void savesAndReloadsEveryDomainField() {
        repository.save(asset(ASSET_ID, LOCATION, true, LICENSE_INFO, UsageAuthorization.DENIED));

        SoftwareAsset reloaded = repository.findById(ASSET_ID).orElseThrow();

        assertEquals(ASSET_ID, reloaded.id());
        assertEquals(SoftwareAssetType.GIT_REPOSITORY, reloaded.type());
        assertEquals(SoftwareAssetSource.USER_SPECIFIED, reloaded.source());
        assertEquals(LOCATION, reloaded.location());
        assertTrue(reloaded.readPermissionAllowed());
        assertEquals(LICENSE_INFO, reloaded.licenseInfo().orElseThrow());
        assertEquals(UsageAuthorization.DENIED, reloaded.usageAuthorization());
    }

    /**
     * 三种使用授权状态都必须能够原样往返，包括「尚未确认」与「已明确不允许」之间的区别：
     * 若存储层把两者合并，本测试会失败。
     */
    @Test
    void keepsAllThreeUsageAuthorizationStatesDistinct() {
        SoftwareAssetId allowedId = new SoftwareAssetId("asset-allowed");
        SoftwareAssetId deniedId = new SoftwareAssetId("asset-denied");
        SoftwareAssetId unclearId = new SoftwareAssetId("asset-unclear");

        repository.save(asset(allowedId, LOCATION, true, LICENSE_INFO, UsageAuthorization.ALLOWED));
        repository.save(asset(deniedId, LOCATION, true, LICENSE_INFO, UsageAuthorization.DENIED));
        repository.save(asset(unclearId, LOCATION, true, LICENSE_INFO, UsageAuthorization.UNCLEAR));

        UsageAuthorization reloadedAllowed =
                repository.findById(allowedId).orElseThrow().usageAuthorization();
        UsageAuthorization reloadedDenied =
                repository.findById(deniedId).orElseThrow().usageAuthorization();
        UsageAuthorization reloadedUnclear =
                repository.findById(unclearId).orElseThrow().usageAuthorization();

        assertEquals(UsageAuthorization.ALLOWED, reloadedAllowed);
        assertEquals(UsageAuthorization.DENIED, reloadedDenied);
        assertEquals(UsageAuthorization.UNCLEAR, reloadedUnclear);
        assertNotEquals(reloadedDenied, reloadedUnclear,
                "「已明确不允许」与「尚未确认」不得在存储层被合并");
    }

    @Test
    void reloadsReadPermissionDeniedWithoutSubstitutingUsageAuthorization() {
        repository.save(asset(ASSET_ID, LOCATION, false, LICENSE_INFO, UsageAuthorization.ALLOWED));

        SoftwareAsset reloaded = repository.findById(ASSET_ID).orElseThrow();

        assertFalse(reloaded.readPermissionAllowed(), "读取权限不得因为使用授权已确认而被改写");
        assertEquals(UsageAuthorization.ALLOWED, reloaded.usageAuthorization());
    }

    @Test
    void reloadsUnknownLicenseInformationAsAbsent() {
        repository.save(asset(ASSET_ID, LOCATION, true, null, UsageAuthorization.UNCLEAR));

        SoftwareAsset reloaded = repository.findById(ASSET_ID).orElseThrow();

        assertTrue(reloaded.licenseInfo().isEmpty(),
                "未保存的许可证信息应恢复为「未知」，而不是空字符串");
    }

    @Test
    void returnsEmptyWhenAssetDoesNotExist() {
        assertTrue(repository.findById(new SoftwareAssetId("unknown-asset")).isEmpty());
    }

    /**
     * 按标识覆盖：同一 id 重复保存更新同一行，不产生第二行，也不保留旧值。
     */
    @Test
    void savingTheSameAssetAgainUpdatesTheSingleRow() {
        repository.save(asset(ASSET_ID, LOCATION, true, null, UsageAuthorization.UNCLEAR));
        repository.save(asset(ASSET_ID, "E:/projects/moved-tool", false, LICENSE_INFO,
                UsageAuthorization.ALLOWED));

        assertEquals(1, softwareAssetMapper.selectCount(null).intValue());

        SoftwareAsset reloaded = repository.findById(ASSET_ID).orElseThrow();
        assertEquals("E:/projects/moved-tool", reloaded.location());
        assertFalse(reloaded.readPermissionAllowed());
        assertEquals(LICENSE_INFO, reloaded.licenseInfo().orElseThrow());
        assertEquals(UsageAuthorization.ALLOWED, reloaded.usageAuthorization());
    }

    /**
     * 回归：「已知许可证 → 未知」的覆盖保存必须真正清空该列。
     *
     * <p>MyBatis-Plus 默认不会把 null 字段放进 {@code UPDATE} 的 SET 子句。若沿用默认
     * 更新策略，这里读回仍然会得到旧许可证，与刚刚保存的 Aggregate 不一致。
     */
    @Test
    void clearsLicenseInformationWhenSavedAsUnknown() {
        repository.save(asset(ASSET_ID, LOCATION, true, LICENSE_INFO, UsageAuthorization.UNCLEAR));
        SoftwareAsset withLicense = repository.findById(ASSET_ID).orElseThrow();
        assertEquals(LICENSE_INFO, withLicense.licenseInfo().orElseThrow());

        repository.save(asset(ASSET_ID, LOCATION, true, null, UsageAuthorization.UNCLEAR));

        SoftwareAsset withoutLicense = repository.findById(ASSET_ID).orElseThrow();
        assertTrue(withoutLicense.licenseInfo().isEmpty(),
                "保存为「未知」之后不得仍然读到旧许可证");
        assertEquals(1, softwareAssetMapper.selectCount(null).intValue());
    }

    /**
     * 同一个 location 的两个资产是两个独立记录：存储层没有 location 唯一约束。
     */
    @Test
    void keepsAssetsWithTheSameLocationSeparate() {
        SoftwareAssetId firstId = new SoftwareAssetId("asset-1");
        SoftwareAssetId secondId = new SoftwareAssetId("asset-2");

        repository.save(asset(firstId, LOCATION, true, null, UsageAuthorization.UNCLEAR));
        repository.save(asset(secondId, LOCATION, true, null, UsageAuthorization.UNCLEAR));

        assertEquals(2, softwareAssetMapper.selectCount(null).intValue());
        assertEquals(LOCATION, repository.findById(firstId).orElseThrow().location());
        assertEquals(LOCATION, repository.findById(secondId).orElseThrow().location());
    }

    /**
     * Register / Get Use Case 在真实 Persistence 上的完整链路。
     */
    @Test
    void persistsThroughRegisterAndGetUseCases() {
        SoftwareAsset registered = new RegisterSoftwareAssetUseCase(repository).register(
                new RegisterSoftwareAssetRequest(LOCATION, true, LICENSE_INFO,
                        UsageAuthorization.UNCLEAR));

        SoftwareAsset loaded = new GetSoftwareAssetUseCase(repository).get(registered.id());

        assertEquals(registered.id(), loaded.id());
        assertEquals(SoftwareAssetType.GIT_REPOSITORY, loaded.type());
        assertEquals(SoftwareAssetSource.USER_SPECIFIED, loaded.source());
        assertEquals(LOCATION, loaded.location());
        assertTrue(loaded.readPermissionAllowed());
        assertEquals(LICENSE_INFO, loaded.licenseInfo().orElseThrow());
        assertEquals(UsageAuthorization.UNCLEAR, loaded.usageAuthorization());
    }

    private static SoftwareAsset asset(
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
}
