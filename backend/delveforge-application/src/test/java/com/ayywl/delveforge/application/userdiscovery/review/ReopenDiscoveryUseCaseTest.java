package com.ayywl.delveforge.application.userdiscovery.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStateException;
import com.ayywl.delveforge.domain.user.UserProfileStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.ayywl.delveforge.application.userdiscovery.profile.UserProfileNotFoundException;
import com.ayywl.delveforge.application.userdiscovery.shared.InMemoryUserProfileRepository;

/**
 * 验证用户重新开启探索的编排（CONFIRMED → EXPLORING）。
 */
class ReopenDiscoveryUseCaseTest {

    private static final UserProfileId PROFILE_ID = new UserProfileId("user-profile-1");

    private final InMemoryUserProfileRepository repository = new InMemoryUserProfileRepository();

    private final ReopenDiscoveryUseCase useCase = new ReopenDiscoveryUseCase(repository);

    @Test
    void returnsConfirmedProfileToExploringWithoutAdvancingRevision() {
        seedProfile(UserProfileStatus.CONFIRMED, 3);
        int savesBefore = repository.saveCount();

        UserProfile returned = useCase.reopenDiscovery(PROFILE_ID);

        assertEquals(UserProfileStatus.EXPLORING, returned.status());
        assertEquals(3, returned.revision(), "重新开启探索不推进 revision");

        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(UserProfileStatus.EXPLORING, reloaded.status(), "重新开启结果应被保存");
        assertEquals(3, reloaded.revision());
        assertEquals(List.of("兴趣"), reloaded.interests(), "重新开启探索不删除内容");
        assertEquals(savesBefore + 1, repository.saveCount());
    }

    @Test
    void rejectsReopenDiscoveryOutsideConfirmed() {
        UserProfile reviewing = seedProfile(UserProfileStatus.REVIEWING, 3);
        int savesBefore = repository.saveCount();

        assertThrows(UserProfileStateException.class, () -> useCase.reopenDiscovery(PROFILE_ID));

        assertEquals(UserProfileStatus.REVIEWING, reviewing.status());
        assertEquals(savesBefore, repository.saveCount(), "Domain 拒绝后不得写入");
    }

    @Test
    void rejectsReopenDiscoveryFromExploring() {
        seedProfile(UserProfileStatus.EXPLORING, 1);

        assertThrows(UserProfileStateException.class, () -> useCase.reopenDiscovery(PROFILE_ID));
    }

    @Test
    void failsWhenProfileDoesNotExist() {
        assertThrows(UserProfileNotFoundException.class,
                () -> useCase.reopenDiscovery(new UserProfileId("unknown-profile")));
    }

    @Test
    void rejectsMissingRepository() {
        assertThrows(IllegalArgumentException.class, () -> new ReopenDiscoveryUseCase(null));
    }

    private UserProfile seedProfile(UserProfileStatus status, int revision) {
        UserProfile profile = UserProfile.reconstitute(
                PROFILE_ID, status, revision,
                List.of("兴趣"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        repository.save(profile);
        return profile;
    }
}
