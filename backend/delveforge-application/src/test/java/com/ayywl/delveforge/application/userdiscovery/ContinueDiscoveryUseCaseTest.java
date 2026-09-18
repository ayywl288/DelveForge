package com.ayywl.delveforge.application.userdiscovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStateException;
import com.ayywl.delveforge.domain.user.UserProfileStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证用户在 Review 阶段选择继续探索的编排（REVIEWING → EXPLORING）。
 */
class ContinueDiscoveryUseCaseTest {

    private static final UserProfileId PROFILE_ID = new UserProfileId("user-profile-1");

    private final InMemoryUserProfileRepository repository = new InMemoryUserProfileRepository();

    private final ContinueDiscoveryUseCase useCase = new ContinueDiscoveryUseCase(repository);

    @Test
    void returnsReviewingProfileToExploringWithoutAdvancingRevision() {
        seedProfile(UserProfileStatus.REVIEWING, 3);
        int savesBefore = repository.saveCount();

        UserProfile returned = useCase.continueDiscovery(PROFILE_ID);

        assertEquals(UserProfileStatus.EXPLORING, returned.status());
        assertEquals(3, returned.revision(), "退回探索不推进 revision");

        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(UserProfileStatus.EXPLORING, reloaded.status(), "退回结果应被保存");
        assertEquals(List.of("兴趣"), reloaded.interests(), "退回探索不删除内容");
        assertEquals(savesBefore + 1, repository.saveCount());
    }

    @Test
    void rejectsContinueDiscoveryOutsideReviewing() {
        UserProfile exploring = seedProfile(UserProfileStatus.EXPLORING, 1);
        int savesBefore = repository.saveCount();

        assertThrows(UserProfileStateException.class, () -> useCase.continueDiscovery(PROFILE_ID));

        assertEquals(UserProfileStatus.EXPLORING, exploring.status());
        assertEquals(savesBefore, repository.saveCount(), "Domain 拒绝后不得写入");
    }

    @Test
    void rejectsContinueDiscoveryFromConfirmed() {
        seedProfile(UserProfileStatus.CONFIRMED, 3);

        assertThrows(UserProfileStateException.class, () -> useCase.continueDiscovery(PROFILE_ID));
    }

    @Test
    void failsWhenProfileDoesNotExist() {
        assertThrows(UserProfileNotFoundException.class,
                () -> useCase.continueDiscovery(new UserProfileId("unknown-profile")));
    }

    @Test
    void rejectsMissingRepository() {
        assertThrows(IllegalArgumentException.class, () -> new ContinueDiscoveryUseCase(null));
    }

    private UserProfile seedProfile(UserProfileStatus status, int revision) {
        UserProfile profile = UserProfile.reconstitute(
                PROFILE_ID, status, revision,
                List.of("兴趣"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        repository.save(profile);
        return profile;
    }
}
