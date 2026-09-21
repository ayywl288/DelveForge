package com.ayywl.delveforge.infrastructure.persistence.repositoryprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.persistence.RepositoryProfileAlreadyExistsException;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.infrastructure.persistence.SqliteDataSourceConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.nio.file.Path;
import java.util.List;
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
 * 验证 {@link SqliteRepositoryProfileRepository} 与真实 SQLite + Flyway + MyBatis-Plus 的集成。
 *
 * <p>使用测试专用的临时数据库，不接触开发者本地数据库。{@code @Transactional}
 * 使每个测试方法结束后回滚，保证方法之间状态隔离。
 *
 * <p>本类只使用生产迁移（{@code classpath:db/migration}），因此同时验证了
 * {@code V4__repository_profile.sql} 在真实 SQLite 上的可执行性。
 */
@SpringBootTest(
        classes = SqliteRepositoryProfileRepositoryIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class SqliteRepositoryProfileRepositoryIntegrationTest {

    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "delveforge.db");

    private static final RepositoryProfileId PROFILE_ID = new RepositoryProfileId("profile-1");

    private static final SoftwareAssetId ASSET_ID = new SoftwareAssetId("software-asset-1");

    private static final String REVISION = "abc123";

    private static final String PURPOSE = "个人记账工具";

    private static final Evidence BUILD_EVIDENCE = new Evidence(
            EvidenceSourceType.REPOSITORY, "pom.xml", "项目使用 Spring Boot", 0.9, true);

    private static final Evidence TEST_EVIDENCE = new Evidence(
            EvidenceSourceType.REPOSITORY, "src/test", "没有任何测试文件", 0.4, false);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE_FILE::toString);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SqliteDataSourceConfiguration.class, SqliteRepositoryProfileRepository.class})
    @MapperScan("com.ayywl.delveforge.infrastructure.persistence.repositoryprofile")
    static class TestApplication {
    }

    @Autowired
    private RepositoryProfileRepository repository;

    @Autowired
    private RepositoryProfileMapper profileMapper;

    @Autowired
    private RepositoryProfileSectionItemMapper sectionItemMapper;

    @Autowired
    private RepositoryProfileEvidenceMapper evidenceMapper;

    @Test
    void savesAndReloadsEveryDomainField() {
        repository.save(profile(PROFILE_ID, ASSET_ID, REVISION, PURPOSE));

        RepositoryProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();

        assertEquals(PROFILE_ID, reloaded.id());
        assertEquals(ASSET_ID, reloaded.assetId());
        assertEquals(REVISION, reloaded.analyzedRevision());
        assertEquals(PURPOSE, reloaded.purpose());
        assertEquals(List.of("Java 21", "Spring Boot"), reloaded.techStack());
        assertEquals(List.of("accounting", "reporting"), reloaded.modules());
        assertEquals(List.of("记账"), reloaded.capabilities());
        assertEquals(List.of("报表导出"), reloaded.reusableAssets());
        assertEquals(List.of("无自动化测试"), reloaded.limitations());
        assertEquals(List.of("模块耦合"), reloaded.risks());
        assertEquals(List.of(BUILD_EVIDENCE, TEST_EVIDENCE), reloaded.evidence());
    }

    /**
     * 内容列表的顺序具有领域含义，读取时必须按原顺序还原。
     */
    @Test
    void keepsOrderOfAnalysisContent() {
        repository.save(RepositoryProfile.create(
                PROFILE_ID, ASSET_ID, REVISION, PURPOSE,
                List.of("第三个", "第一个", "第二个"),
                List.of("模块 B", "模块 A"),
                List.of(), List.of(), List.of(), List.of(),
                List.of(TEST_EVIDENCE, BUILD_EVIDENCE)));

        RepositoryProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();

        assertEquals(List.of("第三个", "第一个", "第二个"), reloaded.techStack(),
                "顺序必须原样保留，而不是按内容排序");
        assertEquals(List.of("模块 B", "模块 A"), reloaded.modules());
        assertEquals(List.of(TEST_EVIDENCE, BUILD_EVIDENCE), reloaded.evidence());
    }

    @Test
    void restoresEmptyAnalysisContent() {
        repository.save(RepositoryProfile.create(
                PROFILE_ID, ASSET_ID, REVISION, PURPOSE,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));

        RepositoryProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();

        assertEquals(List.of(), reloaded.techStack());
        assertEquals(List.of(), reloaded.risks());
        assertEquals(List.of(), reloaded.evidence());
        assertEquals(PURPOSE, reloaded.purpose());
    }

    @Test
    void restoresEvidenceWithoutConfidenceAndUnconfirmed() {
        repository.save(profile(PROFILE_ID, ASSET_ID, REVISION, PURPOSE));

        RepositoryProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();

        Evidence reloadedEvidence = reloaded.evidence().get(1);
        assertEquals(0.4, reloadedEvidence.confidence());
        assertEquals(false, reloadedEvidence.confirmed());
    }

    @Test
    void returnsEmptyWhenProfileDoesNotExist() {
        assertTrue(repository.findById(new RepositoryProfileId("unknown-profile")).isEmpty());
    }

    @Test
    void writesExactlyOneRowPerProfileWithItsContent() {
        repository.save(profile(PROFILE_ID, ASSET_ID, REVISION, PURPOSE));

        assertEquals(1, profileMapper.selectCount(null).intValue());
        // techStack 2 + modules 2 + capabilities 1 + reusableAssets 1 + limitations 1 + risks 1
        assertEquals(8, sectionItems(PROFILE_ID).size());
        assertEquals(2, evidenceRows(PROFILE_ID).size());
    }

    /**
     * Snapshot 边界：同一个标识不允许写入第二次。
     *
     * <p>已有快照可能仍被历史 Product Direction 或 Evolution Plan 引用，因此
     * 覆盖它会破坏追溯（DOMAIN_MODEL.md §10.4、INV-D04）。
     */
    @Test
    void rejectsSavingAnAlreadySavedProfile() {
        repository.save(profile(PROFILE_ID, ASSET_ID, REVISION, PURPOSE));

        RepositoryProfile anotherAnalysis =
                profile(PROFILE_ID, ASSET_ID, "def456", "重新分析后的结论");

        assertThrows(RepositoryProfileAlreadyExistsException.class,
                () -> repository.save(anotherAnalysis));

        RepositoryProfile stored = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(REVISION, stored.analyzedRevision(), "已保存快照的 revision 不得被改写");
        assertEquals(PURPOSE, stored.purpose(), "已保存快照的内容不得被改写");
        assertEquals(List.of("记账"), stored.capabilities());
        assertEquals(1, profileMapper.selectCount(null).intValue());
        assertEquals(8, sectionItems(PROFILE_ID).size(), "失败后不得留下半写入的内容行");
        assertEquals(2, evidenceRows(PROFILE_ID).size(), "失败后不得留下半写入的 Evidence");
    }

    /**
     * 同一个 Software Asset 在不同 revision 上的分析是两个各自独立的快照：
     * 保存后者不影响前者，两者都能按各自的标识读回。
     */
    @Test
    void keepsProfilesOfTheSameAssetAtDifferentRevisionsSeparate() {
        RepositoryProfileId firstId = new RepositoryProfileId("profile-1");
        RepositoryProfileId secondId = new RepositoryProfileId("profile-2");

        repository.save(profile(firstId, ASSET_ID, "abc123", "旧版本：个人记账工具"));
        repository.save(profile(secondId, ASSET_ID, "def456", "新版本：个人记账工具 + 报表"));

        RepositoryProfile first = repository.findById(firstId).orElseThrow();
        RepositoryProfile second = repository.findById(secondId).orElseThrow();

        assertEquals(ASSET_ID, first.assetId());
        assertEquals(ASSET_ID, second.assetId());
        assertEquals("abc123", first.analyzedRevision());
        assertEquals("def456", second.analyzedRevision());
        assertEquals("旧版本：个人记账工具", first.purpose(), "旧快照不得被新快照影响");
        assertEquals("新版本：个人记账工具 + 报表", second.purpose());
        assertEquals(2, profileMapper.selectCount(null).intValue());
    }

    private static RepositoryProfile profile(
            RepositoryProfileId id,
            SoftwareAssetId assetId,
            String analyzedRevision,
            String purpose) {
        return RepositoryProfile.create(
                id,
                assetId,
                analyzedRevision,
                purpose,
                List.of("Java 21", "Spring Boot"),
                List.of("accounting", "reporting"),
                List.of("记账"),
                List.of("报表导出"),
                List.of("无自动化测试"),
                List.of("模块耦合"),
                List.of(BUILD_EVIDENCE, TEST_EVIDENCE));
    }

    private List<RepositoryProfileSectionItemDO> sectionItems(RepositoryProfileId id) {
        return sectionItemMapper.selectList(
                new LambdaQueryWrapper<RepositoryProfileSectionItemDO>()
                        .eq(RepositoryProfileSectionItemDO::getProfileId, id.value()));
    }

    private List<RepositoryProfileEvidenceDO> evidenceRows(RepositoryProfileId id) {
        return evidenceMapper.selectList(
                new LambdaQueryWrapper<RepositoryProfileEvidenceDO>()
                        .eq(RepositoryProfileEvidenceDO::getProfileId, id.value()));
    }
}
