package com.ayywl.delveforge.application.userdiscovery.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStatus;
import com.ayywl.delveforge.domain.user.UserProfileStateException;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.ayywl.delveforge.application.userdiscovery.shared.InMemoryUserProfileRepository;

class UpdateUserProfileUseCaseTest {

    private static final UserProfileId PROFILE_ID = new UserProfileId("user-profile-1");

    private static final UserProfileId OTHER_PROFILE_ID = new UserProfileId("user-profile-2");

    /** DOMAIN_MODEL.md §10.3 的 revision 序列自 1 开始。 */
    private static final int INITIAL_REVISION = 1;

    private final InMemoryUserProfileRepository repository = new InMemoryUserProfileRepository();

    private final UpdateUserProfileUseCase useCase = new UpdateUserProfileUseCase(repository);

    @Test
    void appliesOnlyProvidedSections() {
        UserProfile seeded = seedProfile();
        seeded.updateInterests(List.of("兴趣"));
        seeded.updateBehaviors(List.of("行为"));
        int revisionBefore = seeded.revision();

        UserProfile updated = useCase.update(interestsOnly(List.of("新兴趣")));

        assertEquals(List.of("新兴趣"), updated.interests());
        assertEquals(List.of("行为"), updated.behaviors(), "未提供的区必须保持原值");
        assertEquals(revisionBefore + 1, updated.revision());
    }

    @Test
    void replacesProvidedSectionInsteadOfMerging() {
        UserProfile seeded = seedProfile();
        seeded.updateInterests(List.of("兴趣 A", "兴趣 B"));
        int revisionBefore = seeded.revision();

        UserProfile updated = useCase.update(interestsOnly(List.of("兴趣 C")));

        assertEquals(List.of("兴趣 C"), updated.interests(), "提供的内容是该区更新后的完整状态，不是增量");
        assertEquals(revisionBefore + 1, updated.revision());
    }

    @Test
    void clearsSectionWhenProvidedContentIsEmpty() {
        seedProfile();
        useCase.update(interestsOnly(List.of("兴趣")));

        UserProfile updated = useCase.update(interestsOnly(List.of()));

        assertEquals(List.of(), updated.interests());
    }

    @Test
    void doesNotAdvanceRevisionWhenProvidedContentIsUnchanged() {
        seedProfile();
        UserProfile afterFirstUpdate = useCase.update(painPointsOnly(List.of("痛点")));
        int revisionAfterUpdate = afterFirstUpdate.revision();

        UserProfile updated = useCase.update(allSectionsOf(afterFirstUpdate));

        assertEquals(revisionAfterUpdate, updated.revision(),
                "提供与当前完全相同的值时，Application 不得人为推进 revision");
    }

    @Test
    void advancesRevisionOncePerActuallyChangedSection() {
        UserProfile seeded = seedProfile();
        int revisionBefore = seeded.revision();

        UserProfile updated = useCase.update(new UpdateUserProfileRequest(PROFILE_ID,
                List.of("兴趣"), null, List.of("痛点"), null, List.of("目标"), null, null));

        assertEquals(revisionBefore + 3, updated.revision(),
                "revision 按实际变化的区数推进，由 Aggregate 决定而非 Application 每次调用加一");
    }

    @Test
    void loadsProfileByIdentityAndLeavesOtherProfilesUntouched() {
        seedProfile();
        UserProfile other = UserProfile.create(OTHER_PROFILE_ID);
        repository.save(other);

        UserProfile updated = useCase.update(painPointsOnly(List.of("痛点")));

        assertEquals(List.of("痛点"), updated.painPoints());
        assertEquals(List.of(), other.painPoints());
        assertEquals(INITIAL_REVISION, other.revision());
    }

    @Test
    void persistsUpdatedProfileThroughPersistencePort() {
        seedProfile();
        int savesBefore = repository.saveCount();

        useCase.update(painPointsOnly(List.of("痛点")));

        UserProfile saved = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(List.of("痛点"), saved.painPoints());
        assertEquals(savesBefore + 1, repository.saveCount());
    }

    @Test
    void recordsAdditionalEvidenceThroughAggregate() {
        seedProfile();
        Evidence evidence = new Evidence(
                EvidenceSourceType.USER_INPUT, "user-answer-1", "用户关注图片处理", null, true);

        UserProfile updated = useCase.update(evidenceOnly(List.of(evidence)));

        assertEquals(List.of(evidence), updated.evidence());
        assertEquals(INITIAL_REVISION + 1, updated.revision());
    }

    @Test
    void failsWhenProfileDoesNotExist() {
        int savesBefore = repository.saveCount();

        assertThrows(UserProfileNotFoundException.class,
                () -> useCase.update(painPointsOnly(List.of("痛点"))));

        assertEquals(savesBefore, repository.saveCount(), "目标 Profile 不存在时不得写入");
    }

    @Test
    void propagatesDomainRejectionInsteadOfBypassingIt() {
        UserProfile profile = seedProfile();

        assertThrows(IllegalArgumentException.class,
                () -> useCase.update(projectGoalsOnly(List.of("目标", "  "))));

        assertEquals(List.of(), profile.projectGoals());
        assertEquals(INITIAL_REVISION, profile.revision());
        assertEquals(1, repository.saveCount(), "Domain 拒绝后不得保存");
    }

    /**
     * 回归：前面几个内容区合法、后面一个被 Aggregate 拒绝时，从 Repository 读到的对象
     * 不得留下半更新——第一个区已经被应用到内存对象上，正是这里要防住的情况。
     */
    @Test
    void doesNotLeaveHalfUpdatedProfileWhenSectionIsRejectedMidway() {
        UserProfile profile = seedProfile();
        int revisionBefore = profile.revision();
        int savesBefore = repository.saveCount();

        assertThrows(IllegalArgumentException.class, () -> useCase.update(
                new UpdateUserProfileRequest(PROFILE_ID,
                        List.of("这个区会先被应用"), List.of("  "), null, null, null, null, null)));

        assertNothingChanged(profile, revisionBefore, savesBefore);
    }

    /**
     * 回归：六个内容区都合法、最后由 Evidence 触发拒绝时同样不得留下半更新。
     */
    @Test
    void doesNotLeaveHalfUpdatedProfileWhenEvidenceIsRejected() {
        UserProfile profile = seedProfile();
        int revisionBefore = profile.revision();
        int savesBefore = repository.saveCount();

        assertThrows(IllegalArgumentException.class, () -> useCase.update(
                new UpdateUserProfileRequest(PROFILE_ID,
                        List.of("这个区会先被应用"), null, null, null, null, null,
                        List.of(new Evidence(
                                EvidenceSourceType.USER_INPUT, "user-answer-1", "  ", null, true)))));

        assertNothingChanged(profile, revisionBefore, savesBefore);
    }

    /**
     * CONFIRMED 只能通过重建得到：本 Task 之前不存在合法的构造入口，
     * 因此该分支当时无法覆盖（见 Task 2 的已知未验证项）。
     */
    @Test
    void rejectsUpdateOfConfirmedProfile() {
        UserProfile confirmed = UserProfile.reconstitute(
                PROFILE_ID, UserProfileStatus.CONFIRMED, 3,
                List.of("兴趣"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        repository.save(confirmed);
        int savesBefore = repository.saveCount();

        assertThrows(UserProfileStateException.class,
                () -> useCase.update(painPointsOnly(List.of("痛点"))));

        assertEquals(List.of("兴趣"), confirmed.interests());
        assertEquals(3, confirmed.revision());
        assertEquals(savesBefore, repository.saveCount(), "Domain 拒绝后不得保存");
    }

    @Test
    void rejectsRequestWithoutProfileId() {
        assertThrows(IllegalArgumentException.class, () -> new UpdateUserProfileRequest(
                null, null, null, null, null, null, null, null));
    }

    @Test
    void rejectsMissingRepository() {
        assertThrows(IllegalArgumentException.class, () -> new UpdateUserProfileUseCase(null));
    }

    private UserProfile seedProfile() {
        UserProfile profile = UserProfile.create(PROFILE_ID);
        repository.save(profile);
        return profile;
    }

    /**
     * 断言 Profile 完全没有被改动：原对象与重新读取的内容都保持原状，也没有写入。
     */
    private void assertNothingChanged(UserProfile original, int revisionBefore, int savesBefore) {
        assertEquals(List.of(), original.interests(), "原对象不得被修改");
        assertEquals(revisionBefore, original.revision(), "原对象的 revision 不得变化");

        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(List.of(), reloaded.interests(), "重新读取的内容不得变化");
        assertEquals(revisionBefore, reloaded.revision(), "重新读取的 revision 不得变化");
        assertEquals(savesBefore, repository.saveCount(), "不得写入");
    }

    private static UpdateUserProfileRequest interestsOnly(List<String> interests) {
        return new UpdateUserProfileRequest(PROFILE_ID, interests, null, null, null, null, null, null);
    }

    private static UpdateUserProfileRequest painPointsOnly(List<String> painPoints) {
        return new UpdateUserProfileRequest(PROFILE_ID, null, null, painPoints, null, null, null, null);
    }

    private static UpdateUserProfileRequest projectGoalsOnly(List<String> projectGoals) {
        return new UpdateUserProfileRequest(PROFILE_ID, null, null, null, null, projectGoals, null, null);
    }

    private static UpdateUserProfileRequest evidenceOnly(List<Evidence> additionalEvidence) {
        return new UpdateUserProfileRequest(PROFILE_ID, null, null, null, null, null, null, additionalEvidence);
    }

    /** 提供与 Profile 当前内容完全一致的六个区，用于验证“无实际变化不推进 revision”。 */
    private static UpdateUserProfileRequest allSectionsOf(UserProfile profile) {
        return new UpdateUserProfileRequest(PROFILE_ID,
                profile.interests(), profile.behaviors(), profile.painPoints(),
                profile.technicalCapabilities(), profile.projectGoals(), profile.constraints(),
                List.of());
    }
}
