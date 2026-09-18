package com.ayywl.delveforge.domain.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class UserProfileTest {

    private static final UserProfileId PROFILE_ID = new UserProfileId("user-profile-1");

    private static final int INITIAL_REVISION = 1;

    private static final List<String> VALUES = List.of("结构化内容");

    private static final Evidence EVIDENCE = new Evidence(
            EvidenceSourceType.USER_INPUT, "user-answer-1", "用户长期自己找图片做头像", null, true);

    @Test
    void createsProfileWithInitialIdentityRevisionAndStatus() {
        UserProfile profile = UserProfile.create(PROFILE_ID);

        assertEquals(PROFILE_ID, profile.id());
        assertEquals(UserProfileStatus.EXPLORING, profile.status());
        assertEquals(INITIAL_REVISION, profile.revision());

        assertEquals(List.of(), profile.interests());
        assertEquals(List.of(), profile.behaviors());
        assertEquals(List.of(), profile.painPoints());
        assertEquals(List.of(), profile.technicalCapabilities());
        assertEquals(List.of(), profile.projectGoals());
        assertEquals(List.of(), profile.constraints());
        assertEquals(List.of(), profile.evidence());
    }

    @Test
    void rejectsMissingIdentity() {
        assertThrows(IllegalArgumentException.class, () -> UserProfile.create(null));
    }

    @Test
    void advancesRevisionOnlyWhenInterestsActuallyChange() {
        assertContentUpdateAdvancesRevision(
                profile -> profile.updateInterests(VALUES), UserProfile::interests);
    }

    @Test
    void advancesRevisionOnlyWhenBehaviorsActuallyChange() {
        assertContentUpdateAdvancesRevision(
                profile -> profile.updateBehaviors(VALUES), UserProfile::behaviors);
    }

    @Test
    void advancesRevisionOnlyWhenPainPointsActuallyChange() {
        assertContentUpdateAdvancesRevision(
                profile -> profile.updatePainPoints(VALUES), UserProfile::painPoints);
    }

    @Test
    void advancesRevisionOnlyWhenTechnicalCapabilitiesActuallyChange() {
        assertContentUpdateAdvancesRevision(
                profile -> profile.updateTechnicalCapabilities(VALUES),
                UserProfile::technicalCapabilities);
    }

    @Test
    void advancesRevisionOnlyWhenProjectGoalsActuallyChange() {
        assertContentUpdateAdvancesRevision(
                profile -> profile.updateProjectGoals(VALUES), UserProfile::projectGoals);
    }

    @Test
    void advancesRevisionOnlyWhenConstraintsActuallyChange() {
        assertContentUpdateAdvancesRevision(
                profile -> profile.updateConstraints(VALUES), UserProfile::constraints);
    }

    @Test
    void keepsIdentityWhenRevisionAdvances() {
        UserProfile profile = UserProfile.create(PROFILE_ID);

        profile.updateInterests(List.of("兴趣"));
        profile.updateBehaviors(List.of("行为"));
        profile.updatePainPoints(List.of("痛点"));

        assertEquals(PROFILE_ID, profile.id());
        assertEquals(INITIAL_REVISION + 3, profile.revision());
    }

    @Test
    void returnsToPreviouslyHeldContentWhenUpdatedBack() {
        UserProfile profile = UserProfile.create(PROFILE_ID);

        profile.updateInterests(List.of("兴趣 A"));
        profile.updateInterests(List.of("兴趣 B"));
        profile.updateInterests(List.of("兴趣 A"));

        assertEquals(List.of("兴趣 A"), profile.interests());
        assertEquals(INITIAL_REVISION + 3, profile.revision());
    }

    @Test
    void rejectsNullContentSection() {
        UserProfile profile = UserProfile.create(PROFILE_ID);

        assertThrows(IllegalArgumentException.class, () -> profile.updateInterests(null));

        assertEquals(INITIAL_REVISION, profile.revision());
        assertEquals(List.of(), profile.interests());
    }

    @Test
    void rejectsContentSectionContainingNull() {
        UserProfile profile = UserProfile.create(PROFILE_ID);
        List<String> withNull = new ArrayList<>();
        withNull.add("兴趣");
        withNull.add(null);

        assertThrows(IllegalArgumentException.class, () -> profile.updateInterests(withNull));

        assertEquals(INITIAL_REVISION, profile.revision());
        assertEquals(List.of(), profile.interests());
    }

    @Test
    void rejectsContentSectionContainingBlankEntry() {
        UserProfile profile = UserProfile.create(PROFILE_ID);

        assertThrows(IllegalArgumentException.class,
                () -> profile.updateProjectGoals(List.of("目标", "  ")));

        assertEquals(INITIAL_REVISION, profile.revision());
        assertEquals(List.of(), profile.projectGoals());
    }

    @Test
    void doesNotRetainCallerOwnedContentList() {
        List<String> callerOwned = new ArrayList<>(VALUES);
        UserProfile profile = UserProfile.create(PROFILE_ID);

        profile.updateInterests(callerOwned);
        callerOwned.add("调用方后续追加");

        assertEquals(VALUES, profile.interests());
    }

    @Test
    void exposesContentAsUnmodifiableList() {
        UserProfile profile = UserProfile.create(PROFILE_ID);
        profile.updateInterests(VALUES);

        List<String> exposed = profile.interests();

        assertThrows(UnsupportedOperationException.class, () -> exposed.add("追加"));
    }

    @Test
    void advancesRevisionWhenEvidenceActuallyChanges() {
        UserProfile profile = UserProfile.create(PROFILE_ID);
        Evidence evidence = new Evidence(
                EvidenceSourceType.USER_INPUT, "user-answer-1", "用户长期自己找图片做头像", null, true);

        profile.recordEvidence(evidence);

        assertEquals(List.of(evidence), profile.evidence());
        assertEquals(INITIAL_REVISION + 1, profile.revision());
    }

    @Test
    void doesNotAdvanceRevisionWhenSameEvidenceIsRecordedAgain() {
        UserProfile profile = UserProfile.create(PROFILE_ID);
        Evidence evidence = new Evidence(
                EvidenceSourceType.USER_INPUT, "user-answer-1", "用户长期自己找图片做头像", null, true);

        profile.recordEvidence(evidence);
        profile.recordEvidence(evidence);

        assertEquals(List.of(evidence), profile.evidence());
        assertEquals(INITIAL_REVISION + 1, profile.revision(), "重复记录相同 Evidence 不得推进 revision");
    }

    @Test
    void recordsDistinctEvidenceSeparately() {
        UserProfile profile = UserProfile.create(PROFILE_ID);
        Evidence fromUser = new Evidence(
                EvidenceSourceType.USER_INPUT, "user-answer-1", "用户关注图片处理", null, true);
        Evidence fromRepository = new Evidence(
                EvidenceSourceType.REPOSITORY, "README.md", "用户关注图片处理", null, true);

        profile.recordEvidence(fromUser);
        profile.recordEvidence(fromRepository);

        assertEquals(List.of(fromUser, fromRepository), profile.evidence());
        assertEquals(INITIAL_REVISION + 2, profile.revision());
    }

    @Test
    void rejectsNullEvidence() {
        UserProfile profile = UserProfile.create(PROFILE_ID);

        assertThrows(IllegalArgumentException.class, () -> profile.recordEvidence(null));

        assertEquals(List.of(), profile.evidence());
        assertEquals(INITIAL_REVISION, profile.revision());
    }

    @Test
    void reconstitutesSavedStateWithoutAdvancingRevision() {
        UserProfile profile = UserProfile.reconstitute(
                PROFILE_ID,
                UserProfileStatus.REVIEWING,
                5,
                List.of("兴趣 A", "兴趣 B"),
                List.of("行为"),
                List.of("痛点"),
                List.of("能力"),
                List.of("目标"),
                List.of("约束"),
                List.of(EVIDENCE));

        assertEquals(PROFILE_ID, profile.id());
        assertEquals(UserProfileStatus.REVIEWING, profile.status());
        assertEquals(5, profile.revision(), "重建恢复的是保存时的 revision，不得推进");
        assertEquals(List.of("兴趣 A", "兴趣 B"), profile.interests());
        assertEquals(List.of("行为"), profile.behaviors());
        assertEquals(List.of("痛点"), profile.painPoints());
        assertEquals(List.of("能力"), profile.technicalCapabilities());
        assertEquals(List.of("目标"), profile.projectGoals());
        assertEquals(List.of("约束"), profile.constraints());
        assertEquals(List.of(EVIDENCE), profile.evidence());
    }

    @Test
    void reconstitutesConfirmedProfileAndKeepsItsStateRules() {
        UserProfile profile = reconstituteSections(
                UserProfileStatus.CONFIRMED, 2, List.of("兴趣"));

        assertEquals(UserProfileStatus.CONFIRMED, profile.status());

        assertThrows(UserProfileStateException.class,
                () -> profile.updateInterests(List.of("新兴趣")));

        assertEquals(List.of("兴趣"), profile.interests());
        assertEquals(2, profile.revision());
    }

    @Test
    void reconstitutedProfileContinuesFromRestoredRevision() {
        UserProfile profile = reconstituteSections(
                UserProfileStatus.EXPLORING, 3, List.of("兴趣"));

        profile.updateInterests(List.of("新兴趣"));

        assertEquals(4, profile.revision(), "重建后的 Profile 从恢复的 revision 继续，而不是从 1 重新开始");
    }

    @Test
    void rejectsReconstitutionWithoutStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> reconstituteSections(null, 1, List.of()));
    }

    @Test
    void rejectsReconstitutionWithRevisionBelowInitial() {
        assertThrows(IllegalArgumentException.class,
                () -> reconstituteSections(UserProfileStatus.EXPLORING, 0, List.of()));
    }

    @Test
    void rejectsReconstitutionWithBlankSectionEntry() {
        assertThrows(IllegalArgumentException.class,
                () -> reconstituteSections(UserProfileStatus.EXPLORING, 1, List.of("兴趣", " ")));
    }

    @Test
    void rejectsReconstitutionWithNullEvidence() {
        assertThrows(IllegalArgumentException.class,
                () -> UserProfile.reconstitute(PROFILE_ID, UserProfileStatus.EXPLORING, 1,
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null));
    }

    @Test
    void rejectsReconstitutionWithoutIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> UserProfile.reconstitute(null, UserProfileStatus.EXPLORING, 1,
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void beginsReviewFromExploringWithoutAdvancingRevision() {
        UserProfile profile = UserProfile.create(PROFILE_ID);
        profile.updateInterests(List.of("兴趣"));
        int revisionBefore = profile.revision();

        profile.beginReview();

        assertEquals(UserProfileStatus.REVIEWING, profile.status());
        assertEquals(revisionBefore, profile.revision(), "状态变化不推进 revision");
    }

    /**
     * §6.1 里「信息足够 → REVIEWING」只从 EXPLORING 出发，因此其余状态一律拒绝。
     */
    @Test
    void rejectsBeginReviewFromReviewing() {
        UserProfile profile = reconstituteSections(
                UserProfileStatus.REVIEWING, 2, List.of("兴趣"));

        assertThrows(UserProfileStateException.class, profile::beginReview);

        assertEquals(UserProfileStatus.REVIEWING, profile.status());
        assertEquals(2, profile.revision());
    }

    @Test
    void rejectsBeginReviewFromConfirmed() {
        UserProfile profile = reconstituteSections(
                UserProfileStatus.CONFIRMED, 2, List.of("兴趣"));

        assertThrows(UserProfileStateException.class, profile::beginReview);

        assertEquals(UserProfileStatus.CONFIRMED, profile.status());
        assertEquals(List.of("兴趣"), profile.interests());
    }

    /**
     * REVIEWING 允许继续修改内容（§6.1 的 REVIEWING → REVIEWING：用户纠正 Profile）。
     */
    @Test
    void allowsContentUpdateAfterBeginReview() {
        UserProfile profile = UserProfile.create(PROFILE_ID);
        profile.beginReview();

        profile.updateInterests(List.of("兴趣"));

        assertEquals(UserProfileStatus.REVIEWING, profile.status());
        assertEquals(List.of("兴趣"), profile.interests());
    }

    @Test
    void continuesDiscoveryFromReviewingWithoutAdvancingRevision() {
        UserProfile profile = reconstituteSections(UserProfileStatus.REVIEWING, 2, List.of("兴趣"));

        profile.continueDiscovery();

        assertEquals(UserProfileStatus.EXPLORING, profile.status());
        assertEquals(2, profile.revision(), "状态变化不推进 revision");
        assertEquals(List.of("兴趣"), profile.interests(), "退回探索不删除内容");
    }

    @Test
    void confirmsFromReviewingWithoutAdvancingRevision() {
        UserProfile profile = reconstituteSections(UserProfileStatus.REVIEWING, 2, List.of("兴趣"));

        profile.confirm(2);

        assertEquals(UserProfileStatus.CONFIRMED, profile.status());
        assertEquals(2, profile.revision(), "状态变化不推进 revision");
        assertEquals(List.of("兴趣"), profile.interests());
    }

    /**
     * 确认必须绑定用户实际查看的版本：期间内容又变化过时拒绝，而不是默默确认最新版本。
     */
    @Test
    void rejectsConfirmWithStaleRevision() {
        UserProfile profile = reconstituteSections(UserProfileStatus.REVIEWING, 3, List.of("兴趣"));

        assertThrows(UserProfileStateException.class, () -> profile.confirm(2));

        assertEquals(UserProfileStatus.REVIEWING, profile.status());
        assertEquals(3, profile.revision());
    }

    @Test
    void reopensDiscoveryFromConfirmedWithoutAdvancingRevision() {
        UserProfile profile = reconstituteSections(UserProfileStatus.CONFIRMED, 2, List.of("兴趣"));

        profile.reopenDiscovery();

        assertEquals(UserProfileStatus.EXPLORING, profile.status());
        assertEquals(2, profile.revision(), "状态变化不推进 revision");
        assertEquals(List.of("兴趣"), profile.interests(), "重新开启探索不删除内容");
    }

    /**
     * 确认必须来自一次 Review：不能从 EXPLORING 直接跳到已确认。
     */
    @Test
    void rejectsConfirmFromExploring() {
        UserProfile profile = UserProfile.create(PROFILE_ID);

        assertThrows(UserProfileStateException.class, () -> profile.confirm(INITIAL_REVISION));

        assertEquals(UserProfileStatus.EXPLORING, profile.status());
        assertEquals(INITIAL_REVISION, profile.revision());
    }

    @Test
    void rejectsConfirmFromConfirmed() {
        UserProfile profile = reconstituteSections(UserProfileStatus.CONFIRMED, 2, List.of("兴趣"));

        assertThrows(UserProfileStateException.class, () -> profile.confirm(2));

        assertEquals(UserProfileStatus.CONFIRMED, profile.status());
    }

    @Test
    void rejectsContinueDiscoveryOutsideReviewing() {
        UserProfile exploring = UserProfile.create(PROFILE_ID);
        assertThrows(UserProfileStateException.class, exploring::continueDiscovery);
        assertEquals(UserProfileStatus.EXPLORING, exploring.status());

        UserProfile confirmed = reconstituteSections(UserProfileStatus.CONFIRMED, 2, List.of("兴趣"));
        assertThrows(UserProfileStateException.class, confirmed::continueDiscovery);
        assertEquals(UserProfileStatus.CONFIRMED, confirmed.status());
    }

    @Test
    void rejectsReopenDiscoveryOutsideConfirmed() {
        UserProfile exploring = UserProfile.create(PROFILE_ID);
        assertThrows(UserProfileStateException.class, exploring::reopenDiscovery);

        UserProfile reviewing = reconstituteSections(UserProfileStatus.REVIEWING, 2, List.of("兴趣"));
        assertThrows(UserProfileStateException.class, reviewing::reopenDiscovery);
        assertEquals(UserProfileStatus.REVIEWING, reviewing.status());
    }

    /**
     * 重新开启探索之后，内容重新允许修改，并按实际变化推进 revision。
     */
    @Test
    void allowsContentUpdateAgainAfterReopeningDiscovery() {
        UserProfile profile = reconstituteSections(UserProfileStatus.CONFIRMED, 2, List.of("兴趣"));
        profile.reopenDiscovery();

        profile.updateInterests(List.of("新兴趣"));

        assertEquals(List.of("新兴趣"), profile.interests());
        assertEquals(3, profile.revision());
    }

    /** 只提供 interests，其余内容区与 Evidence 为空的重建输入。 */
    private static UserProfile reconstituteSections(UserProfileStatus status, int revision,
                                                    List<String> interests) {
        return UserProfile.reconstitute(PROFILE_ID, status, revision, interests,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * 更新一次内容必须推进 revision；用相同内容再次更新必须不推进。
     */
    private static void assertContentUpdateAdvancesRevision(
            Consumer<UserProfile> update, Function<UserProfile, List<String>> readContent) {

        UserProfile profile = UserProfile.create(PROFILE_ID);

        update.accept(profile);

        assertEquals(INITIAL_REVISION + 1, profile.revision(), "内容实际变化后 revision 必须推进");
        assertEquals(VALUES, readContent.apply(profile));

        update.accept(profile);

        assertEquals(INITIAL_REVISION + 1, profile.revision(), "内容未发生实际变化时不得推进 revision");
        assertEquals(VALUES, readContent.apply(profile));
    }
}
