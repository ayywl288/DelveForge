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
