package com.ayywl.delveforge.application.userdiscovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class UpdateUserProfileUseCaseTest {

    private static final UserProfileId PROFILE_ID = new UserProfileId("user-profile-1");

    private static final UserProfileId OTHER_PROFILE_ID = new UserProfileId("user-profile-2");

    /** DOMAIN_MODEL.md §10.3 的 revision 序列自 1 开始。 */
    private static final int INITIAL_REVISION = 1;

    private final InMemoryUserProfileRepository repository = new InMemoryUserProfileRepository();

    private final UpdateUserProfileUseCase useCase = new UpdateUserProfileUseCase(repository);

    @Test
    void appliesOnlyProvidedSections() {
        UserProfile profile = seedProfile();
        profile.updateInterests(List.of("兴趣"));
        profile.updateBehaviors(List.of("行为"));
        int revisionBefore = profile.revision();

        useCase.update(interestsOnly(List.of("新兴趣")));

        assertEquals(List.of("新兴趣"), profile.interests());
        assertEquals(List.of("行为"), profile.behaviors(), "未提供的区必须保持原值");
        assertEquals(revisionBefore + 1, profile.revision());
    }

    @Test
    void replacesProvidedSectionInsteadOfMerging() {
        UserProfile profile = seedProfile();
        profile.updateInterests(List.of("兴趣 A", "兴趣 B"));
        int revisionBefore = profile.revision();

        useCase.update(interestsOnly(List.of("兴趣 C")));

        assertEquals(List.of("兴趣 C"), profile.interests(), "提供的内容是该区更新后的完整状态，不是增量");
        assertEquals(revisionBefore + 1, profile.revision());
    }

    @Test
    void clearsSectionWhenProvidedContentIsEmpty() {
        UserProfile profile = seedProfile();
        useCase.update(interestsOnly(List.of("兴趣")));

        useCase.update(interestsOnly(List.of()));

        assertEquals(List.of(), profile.interests());
    }

    @Test
    void doesNotAdvanceRevisionWhenProvidedContentIsUnchanged() {
        UserProfile profile = seedProfile();
        useCase.update(painPointsOnly(List.of("痛点")));
        int revisionAfterUpdate = profile.revision();

        useCase.update(allSectionsOf(profile));

        assertEquals(revisionAfterUpdate, profile.revision(),
                "提供与当前完全相同的值时，Application 不得人为推进 revision");
    }

    @Test
    void advancesRevisionOncePerActuallyChangedSection() {
        UserProfile profile = seedProfile();
        int revisionBefore = profile.revision();

        useCase.update(new UpdateUserProfileRequest(PROFILE_ID,
                List.of("兴趣"), null, List.of("痛点"), null, List.of("目标"), null, null));

        assertEquals(revisionBefore + 3, profile.revision(),
                "revision 按实际变化的区数推进，由 Aggregate 决定而非 Application 每次调用加一");
    }

    @Test
    void loadsProfileByIdentityAndLeavesOtherProfilesUntouched() {
        UserProfile target = seedProfile();
        UserProfile other = UserProfile.create(OTHER_PROFILE_ID);
        repository.save(other);

        useCase.update(painPointsOnly(List.of("痛点")));

        assertEquals(List.of("痛点"), target.painPoints());
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
        UserProfile profile = seedProfile();
        Evidence evidence = new Evidence(
                EvidenceSourceType.USER_INPUT, "user-answer-1", "用户关注图片处理", null, true);

        useCase.update(evidenceOnly(List.of(evidence)));

        assertEquals(List.of(evidence), profile.evidence());
        assertEquals(INITIAL_REVISION + 1, profile.revision());
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

        assertThrows(IllegalStateException.class,
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
