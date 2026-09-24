package com.ayywl.delveforge.infrastructure.persistence.userprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStatus;
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
import com.ayywl.delveforge.application.userdiscovery.profile.CreateUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.profile.UpdateUserProfileRequest;
import com.ayywl.delveforge.application.userdiscovery.profile.UpdateUserProfileUseCase;

/**
 * 验证 {@link SqliteUserProfileRepository} 与真实 SQLite + Flyway + MyBatis-Plus 的集成。
 *
 * <p>使用测试专用的临时数据库，不接触开发者本地数据库。{@code @Transactional}
 * 使每个测试方法结束后回滚，保证方法之间状态隔离。
 *
 * <p>本类只使用生产迁移（{@code classpath:db/migration}），因此同时验证了
 * {@code V2__user_profile.sql} 在真实 SQLite 上的可执行性。
 */
@SpringBootTest(
        classes = SqliteUserProfileRepositoryIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class SqliteUserProfileRepositoryIntegrationTest {

    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "delveforge.db");

    private static final UserProfileId PROFILE_ID = new UserProfileId("user-profile-1");

    private static final Evidence USER_EVIDENCE = new Evidence(
            EvidenceSourceType.USER_INPUT, "user-answer-1", "用户长期自己找图片做头像", null, true);

    private static final Evidence REPOSITORY_EVIDENCE = new Evidence(
            EvidenceSourceType.REPOSITORY, "README.md", "项目使用 Spring Boot", 0.4, false);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE_FILE::toString);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SqliteDataSourceConfiguration.class, SqliteUserProfileRepository.class})
    @MapperScan("com.ayywl.delveforge.infrastructure.persistence.userprofile")
    static class TestApplication {
    }

    @Autowired
    private UserProfileRepository repository;

    @Autowired
    private UserProfileMapper userProfileMapper;

    @Autowired
    private UserProfileRevisionMapper revisionMapper;

    @Autowired
    private UserProfileSectionItemMapper sectionItemMapper;

    @Autowired
    private UserProfileEvidenceMapper evidenceMapper;

    @Test
    void savesAndReloadsEmptyProfileAtInitialRevision() {
        UserProfile profile = UserProfile.create(PROFILE_ID);

        repository.save(profile);

        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(PROFILE_ID, reloaded.id());
        assertEquals(UserProfileStatus.EXPLORING, reloaded.status());
        assertEquals(1, reloaded.revision());
        assertEquals(List.of(), reloaded.interests());
        assertEquals(List.of(), reloaded.behaviors());
        assertEquals(List.of(), reloaded.painPoints());
        assertEquals(List.of(), reloaded.technicalCapabilities());
        assertEquals(List.of(), reloaded.projectGoals());
        assertEquals(List.of(), reloaded.constraints());
        assertEquals(List.of(), reloaded.evidence());
    }

    @Test
    void reconstructsAllSectionsAndEvidenceInOrder() {
        UserProfile profile = UserProfile.reconstitute(
                PROFILE_ID, UserProfileStatus.REVIEWING, 4,
                List.of("兴趣 A", "兴趣 B"),
                List.of("行为 A", "行为 B", "行为 C"),
                List.of("痛点"),
                List.of("能力"),
                List.of("目标"),
                List.of("约束"),
                List.of(USER_EVIDENCE, REPOSITORY_EVIDENCE));

        repository.save(profile);

        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(UserProfileStatus.REVIEWING, reloaded.status());
        assertEquals(4, reloaded.revision());
        assertEquals(List.of("兴趣 A", "兴趣 B"), reloaded.interests());
        assertEquals(List.of("行为 A", "行为 B", "行为 C"), reloaded.behaviors());
        assertEquals(List.of("痛点"), reloaded.painPoints());
        assertEquals(List.of("能力"), reloaded.technicalCapabilities());
        assertEquals(List.of("目标"), reloaded.projectGoals());
        assertEquals(List.of("约束"), reloaded.constraints());
        assertEquals(List.of(USER_EVIDENCE, REPOSITORY_EVIDENCE), reloaded.evidence());
    }

    @Test
    void reloadsConfirmedProfileWithItsStatus() {
        UserProfile profile = UserProfile.reconstitute(
                PROFILE_ID, UserProfileStatus.CONFIRMED, 2,
                List.of("兴趣"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        repository.save(profile);

        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(UserProfileStatus.CONFIRMED, reloaded.status());
        assertEquals(2, reloaded.revision());
        assertEquals(List.of("兴趣"), reloaded.interests());
    }

    @Test
    void returnsEmptyWhenProfileDoesNotExist() {
        assertTrue(repository.findById(new UserProfileId("unknown-profile")).isEmpty());
    }

    @Test
    void savingTheSameRevisionTwiceDoesNotDuplicateSnapshotRows() {
        UserProfile profile = UserProfile.reconstitute(
                PROFILE_ID, UserProfileStatus.EXPLORING, 1,
                List.of("兴趣 A", "兴趣 B"), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(USER_EVIDENCE));

        repository.save(profile);
        repository.save(profile);

        assertEquals(1, userProfileMapper.selectCount(null).intValue());
        assertEquals(1, revisionAnchorCount(PROFILE_ID, 1));
        assertEquals(2, sectionItemRows(PROFILE_ID, 1).size());
        assertEquals(1, evidenceRows(PROFILE_ID, 1).size());

        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(List.of("兴趣 A", "兴趣 B"), reloaded.interests());
        assertEquals(List.of(USER_EVIDENCE), reloaded.evidence());
    }

    /**
     * 保存更晚的 revision 不得覆盖更早版本的快照。
     *
     * <p>本 Task 不暴露按 revision 查询的 Port 方法，因此这里在存储层直接核对
     * 历史 revision 的快照内容。
     */
    @Test
    void keepsCompleteSnapshotOfEveryRevision() {
        repository.save(UserProfile.reconstitute(
                PROFILE_ID, UserProfileStatus.EXPLORING, 1,
                List.of("旧兴趣"), List.of("旧行为"), List.of(), List.of(), List.of(), List.of(),
                List.of(USER_EVIDENCE)));

        repository.save(UserProfile.reconstitute(
                PROFILE_ID, UserProfileStatus.EXPLORING, 2,
                List.of("新兴趣"), List.of("旧行为"), List.of("新痛点"), List.of(), List.of(), List.of(),
                List.of(USER_EVIDENCE, REPOSITORY_EVIDENCE)));

        assertEquals(2, sectionItemRows(PROFILE_ID, 1).size(), "revision 1 的内容区快照应完整保留");
        assertEquals(List.of("旧兴趣"), storedSection(PROFILE_ID, 1, "interests"));
        assertEquals(List.of("旧行为"), storedSection(PROFILE_ID, 1, "behaviors"));
        assertEquals(List.of(), storedSection(PROFILE_ID, 1, "painPoints"),
                "revision 1 当时没有该区内容，不应被 revision 2 补上");

        List<UserProfileEvidenceDO> revisionOneEvidence = evidenceRows(PROFILE_ID, 1);
        assertEquals(1, revisionOneEvidence.size(), "revision 1 的 Evidence 快照应完整保留");
        assertEquals("用户长期自己找图片做头像", revisionOneEvidence.get(0).getClaim());

        assertEquals(3, sectionItemRows(PROFILE_ID, 2).size());
        assertEquals(List.of("新兴趣"), storedSection(PROFILE_ID, 2, "interests"));
        assertEquals(List.of("新痛点"), storedSection(PROFILE_ID, 2, "painPoints"));
        assertEquals(2, evidenceRows(PROFILE_ID, 2).size());

        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(2, reloaded.revision(), "findById 返回当前（最新）revision");
        assertEquals(List.of("新兴趣"), reloaded.interests());
    }

    /**
     * Create / Update Use Case 在真实 Persistence 上的完整链路。
     */
    @Test
    void persistsThroughCreateAndUpdateUseCases() {
        UserProfile created = new CreateUserProfileUseCase(repository).create();
        assertEquals(1, created.revision());

        new UpdateUserProfileUseCase(repository).update(new UpdateUserProfileRequest(
                created.id(),
                List.of("兴趣 A"), List.of("行为"), null, null, null, null,
                List.of(USER_EVIDENCE)));

        UserProfile reloaded = repository.findById(created.id()).orElseThrow();
        assertEquals(UserProfileStatus.EXPLORING, reloaded.status());
        assertEquals(List.of("兴趣 A"), reloaded.interests());
        assertEquals(List.of("行为"), reloaded.behaviors());
        assertEquals(List.of(), reloaded.painPoints());
        assertEquals(List.of(USER_EVIDENCE), reloaded.evidence());
        // 创建得到 revision 1；两个内容区各推进一次、记录一条 Evidence 再推进一次
        assertEquals(4, reloaded.revision());
    }

    /**
     * 只保存实际提交给 Repository 的 revision：版本号之间可以有间隔。
     *
     * <p>一次 Update 在内存中连续推进了 revision 2、3、4，但只提交了一次 save，
     * 因此 2、3 从未被保存，不能声称它们存在。
     */
    @Test
    void recordsOnlyRevisionsActuallySubmittedForSaving() {
        UserProfile created = new CreateUserProfileUseCase(repository).create();

        new UpdateUserProfileUseCase(repository).update(new UpdateUserProfileRequest(
                created.id(),
                List.of("兴趣 A"), List.of("行为"), null, null, null, null,
                List.of(USER_EVIDENCE)));

        assertEquals(1, revisionAnchorCount(created.id(), 1), "创建时提交过 revision 1");
        assertEquals(0, revisionAnchorCount(created.id(), 2), "revision 2 从未提交，不应存在");
        assertEquals(0, revisionAnchorCount(created.id(), 3), "revision 3 从未提交，不应存在");
        assertEquals(1, revisionAnchorCount(created.id(), 4), "Update 提交的是 revision 4");
    }

    /**
     * 已保存但内容全空的版本，与从未保存过的版本必须可区分。
     *
     * <p>前者没有任何子表行，因此不能靠子表是否有行来判断版本是否存在。
     */
    @Test
    void distinguishesSavedEmptyRevisionFromUnsavedRevision() {
        repository.save(UserProfile.create(PROFILE_ID));

        assertTrue(sectionItemRows(PROFILE_ID, 1).isEmpty(), "已保存的空版本本身没有内容行");
        assertTrue(evidenceRows(PROFILE_ID, 1).isEmpty());
        assertEquals(1, revisionAnchorCount(PROFILE_ID, 1), "revision 1 确实已保存");
        assertEquals(0, revisionAnchorCount(PROFILE_ID, 2), "revision 2 从未保存");
    }

    /**
     * 回归：过期保存会令当前 revision 倒退，并覆盖更早版本的内容快照。
     *
     * <p>两个实例都从 revision 1 加载，其中一个先推进到 revision 3 并保存；
     * 另一个基于过期状态提交 revision 2，必须被拒绝。
     */
    @Test
    void rejectsStaleRevisionSave() {
        UserProfileId id = new CreateUserProfileUseCase(repository).create().id();

        UserProfile first = repository.findById(id).orElseThrow();
        UserProfile second = repository.findById(id).orElseThrow();

        first.updateInterests(List.of("兴趣 A"));
        first.updateBehaviors(List.of("行为 A"));
        repository.save(first);
        assertEquals(3, first.revision());

        second.updatePainPoints(List.of("基于过期状态的痛点"));

        assertThrows(IllegalStateException.class, () -> repository.save(second));

        UserProfile reloaded = repository.findById(id).orElseThrow();
        assertEquals(3, reloaded.revision(), "当前 revision 不得倒退");
        assertEquals(List.of("兴趣 A"), reloaded.interests());
        assertEquals(List.of("行为 A"), reloaded.behaviors());
        assertEquals(List.of(), reloaded.painPoints(), "过期实例的内容不得写入");
        assertEquals(0, revisionAnchorCount(id, 2), "不得产生 revision 2 的锚点");
    }

    /**
     * 回归：同一 revision 已保存后，不得用不同内容覆盖它的历史快照。
     */
    @Test
    void rejectsConflictingContentForAnAlreadySavedRevision() {
        repository.save(UserProfile.reconstitute(
                PROFILE_ID, UserProfileStatus.EXPLORING, 2,
                List.of("原兴趣"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));

        UserProfile conflicting = UserProfile.reconstitute(
                PROFILE_ID, UserProfileStatus.EXPLORING, 2,
                List.of("另一个兴趣"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        assertThrows(IllegalStateException.class, () -> repository.save(conflicting));

        assertEquals(List.of("原兴趣"), storedSection(PROFILE_ID, 2, "interests"),
                "同一 revision 的已保存内容不得被覆盖");
    }

    /**
     * 相同 revision、相同内容的重复保存是幂等的；status 不同不影响该判断，
     * 因为 status 不参与 revision 口径（DOMAIN_MODEL.md §6.1）。
     */
    @Test
    void acceptsIdenticalResaveWithDifferentStatus() {
        repository.save(UserProfile.reconstitute(
                PROFILE_ID, UserProfileStatus.EXPLORING, 2,
                List.of("兴趣"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));

        repository.save(UserProfile.reconstitute(
                PROFILE_ID, UserProfileStatus.REVIEWING, 2,
                List.of("兴趣"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));

        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(UserProfileStatus.REVIEWING, reloaded.status());
        assertEquals(2, reloaded.revision());
        assertEquals(List.of("兴趣"), reloaded.interests());
    }

    /**
     * 回归：Evidence 的 confidence 为负零时，内容完全没变的重复保存不得被误判成内容冲突。
     *
     * <p>SQLite 的 REAL 不保留负零：{@code -0.0} 写入后读回是 {@code 0.0}。
     * 而 {@code Evidence} 是 record，它的相等性按 {@code Double.equals} 比较 confidence，
     * 会区分正零与负零。若两侧不统一比较语义，下面的第二次 save 会抛
     * {@code IllegalStateException}——调用方没有改动任何内容，revision 也没有推进。
     */
    @Test
    void acceptsIdenticalResaveWhenEvidenceConfidenceIsNegativeZero() {
        UserProfile profile = profileWithEvidenceConfidence(-0.0);

        repository.save(profile);
        repository.save(profile);

        assertEquals(1, userProfileMapper.selectCount(null).intValue());
        assertEquals(1, revisionAnchorCount(PROFILE_ID, 1));
        assertEquals(1, evidenceRows(PROFILE_ID, 1).size(), "重复保存不得产生第二条 Evidence");

        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(1, reloaded.revision());
        assertEquals(1, reloaded.evidence().size());
    }

    /**
     * 存储不保留负零，读回的是正零。
     *
     * <p>把正负零统一成正零是本 Adapter 针对 SQLite REAL 往返行为做的实现选择，
     * 不是领域模型定义的等价语义（§3.6 没有规定正负零是否等价）。
     */
    @Test
    void restoresNegativeZeroConfidenceAsPositiveZero() {
        repository.save(profileWithEvidenceConfidence(-0.0));

        Double restored = repository.findById(PROFILE_ID).orElseThrow()
                .evidence().get(0).confidence();

        assertEquals(0, Double.compare(0.0, restored), "读回的 confidence 应是正零");
    }

    /**
     * 归一化只覆盖零的符号，不得放宽对真实内容变化的拒绝。
     *
     * <p>两条 Evidence 都带负零 confidence，但 claim 不同，仍必须被当作不同内容拒绝。
     */
    @Test
    void stillRejectsRealContentChangeWhenConfidenceIsNegativeZero() {
        repository.save(profileWithEvidenceConfidence(-0.0));

        UserProfile conflicting = UserProfile.reconstitute(
                PROFILE_ID, UserProfileStatus.EXPLORING, 1,
                List.of("兴趣"),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new Evidence(EvidenceSourceType.USER_INPUT, "user-answer-1",
                        "换了一条完全不同的依据", -0.0, true)));

        assertThrows(IllegalStateException.class, () -> repository.save(conflicting));

        assertEquals("用户长期自己找图片做头像",
                repository.findById(PROFILE_ID).orElseThrow().evidence().get(0).claim(),
                "同一 revision 的已保存内容不得被覆盖");
    }

    /**
     * {@code null}（未给出确定性判断）与任何数值仍是不同的内容。
     *
     * <p>归一化不会把 null 与零混同：已保存 null 时，0.0 与 0.5 各自都被视为内容变化。
     */
    @Test
    void stillRejectsConfidenceChangeBetweenNullAndNumber() {
        repository.save(profileWithEvidenceConfidence(null));

        assertThrows(IllegalStateException.class,
                () -> repository.save(profileWithEvidenceConfidence(0.0)),
                "null 与 0.0 是不同内容");
        assertThrows(IllegalStateException.class,
                () -> repository.save(profileWithEvidenceConfidence(0.5)),
                "null 与 0.5 也是不同内容");

        assertNull(repository.findById(PROFILE_ID).orElseThrow()
                .evidence().get(0).confidence(), "已保存的 confidence 仍是 null");
    }

    /**
     * 归一化只统一零的符号，不会把所有非空数值视为同一件事。
     *
     * <p>先成功保存 0.0 建立基线，再提交 0.5：若比较逻辑把「非空即相同」当成相等，
     * 这次保存会成功，本用例即失败。
     */
    @Test
    void stillRejectsConfidenceChangeBetweenNonZeroNumbers() {
        repository.save(profileWithEvidenceConfidence(0.0));

        assertThrows(IllegalStateException.class,
                () -> repository.save(profileWithEvidenceConfidence(0.5)),
                "0.0 与 0.5 是不同内容");

        assertEquals(0, Double.compare(0.0,
                evidenceRows(PROFILE_ID, 1).get(0).getConfidence()),
                "已保存的 confidence 仍是 0.0");
    }

    /**
     * 归一化不得掩盖历史 revision 的差异：不同 revision 的快照照常各自保留。
     */
    @Test
    void keepsHistoricalRevisionsApartWhenConfidenceIsNegativeZero() {
        repository.save(profileWithEvidenceConfidence(-0.0));

        repository.save(UserProfile.reconstitute(
                PROFILE_ID, UserProfileStatus.EXPLORING, 2,
                List.of("兴趣"),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new Evidence(EvidenceSourceType.USER_INPUT, "user-answer-1",
                        "用户长期自己找图片做头像", 0.5, true))));

        assertEquals(1, evidenceRows(PROFILE_ID, 1).size(), "revision 1 的快照不应被覆盖");
        assertEquals(1, evidenceRows(PROFILE_ID, 2).size());
        assertEquals(0, Double.compare(0.5, evidenceRows(PROFILE_ID, 2).get(0).getConfidence()));
    }

    /** 一条 Evidence 的 confidence 由调用方给定的 Profile，其余内容固定。 */
    private static UserProfile profileWithEvidenceConfidence(Double confidence) {
        return UserProfile.reconstitute(
                PROFILE_ID, UserProfileStatus.EXPLORING, 1,
                List.of("兴趣"),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new Evidence(EvidenceSourceType.USER_INPUT, "user-answer-1",
                        "用户长期自己找图片做头像", confidence, true)));
    }

    @Test
    void keepsSnapshotRowsOfNonCurrentRevisions() {
        repository.save(UserProfile.reconstitute(
                PROFILE_ID, UserProfileStatus.EXPLORING, 5,
                List.of("兴趣"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        repository.save(UserProfile.reconstitute(
                PROFILE_ID, UserProfileStatus.EXPLORING, 6,
                List.of("兴趣"), List.of("行为"), List.of(), List.of(), List.of(), List.of(), List.of()));

        assertEquals(1, sectionItemRows(PROFILE_ID, 5).size(), "revision 5 的快照不应被 revision 6 覆盖");
        assertEquals(2, sectionItemRows(PROFILE_ID, 6).size());
    }

    private int revisionAnchorCount(UserProfileId id, int revision) {
        return revisionMapper.count(id.value(), revision);
    }

    private List<UserProfileSectionItemDO> sectionItemRows(UserProfileId id, int revision) {
        return sectionItemMapper.selectList(new LambdaQueryWrapper<UserProfileSectionItemDO>()
                .eq(UserProfileSectionItemDO::getProfileId, id.value())
                .eq(UserProfileSectionItemDO::getRevision, revision)
                .orderByAsc(UserProfileSectionItemDO::getSection)
                .orderByAsc(UserProfileSectionItemDO::getPosition));
    }

    /** 取某个 revision 下、某个内容区按位置排列的已存内容。 */
    private List<String> storedSection(UserProfileId id, int revision, String section) {
        return sectionItemMapper.selectList(new LambdaQueryWrapper<UserProfileSectionItemDO>()
                        .eq(UserProfileSectionItemDO::getProfileId, id.value())
                        .eq(UserProfileSectionItemDO::getRevision, revision)
                        .eq(UserProfileSectionItemDO::getSection, section)
                        .orderByAsc(UserProfileSectionItemDO::getPosition))
                .stream()
                .map(UserProfileSectionItemDO::getValue)
                .toList();
    }

    private List<UserProfileEvidenceDO> evidenceRows(UserProfileId id, int revision) {
        return evidenceMapper.selectList(new LambdaQueryWrapper<UserProfileEvidenceDO>()
                .eq(UserProfileEvidenceDO::getProfileId, id.value())
                .eq(UserProfileEvidenceDO::getRevision, revision)
                .orderByAsc(UserProfileEvidenceDO::getPosition));
    }
}
