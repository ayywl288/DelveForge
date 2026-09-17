package com.ayywl.delveforge.application.userdiscovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class GetUserProfileUseCaseTest {

    private static final UserProfileId PROFILE_ID = new UserProfileId("user-profile-1");

    private final InMemoryUserProfileRepository repository = new InMemoryUserProfileRepository();

    private final GetUserProfileUseCase useCase = new GetUserProfileUseCase(repository);

    @Test
    void returnsStoredProfile() {
        UserProfile stored = UserProfile.create(PROFILE_ID);
        stored.updateInterests(List.of("兴趣"));
        repository.save(stored);
        int revisionBeforeRead = stored.revision();

        UserProfile loaded = useCase.get(PROFILE_ID);

        assertSame(stored, loaded);
        assertEquals(PROFILE_ID, loaded.id());
        assertEquals(UserProfileStatus.EXPLORING, loaded.status());
        assertEquals(List.of("兴趣"), loaded.interests());
        assertEquals(revisionBeforeRead, loaded.revision(), "读取不得推进 revision");
    }

    @Test
    void failsWhenProfileDoesNotExist() {
        assertThrows(UserProfileNotFoundException.class,
                () -> useCase.get(new UserProfileId("unknown-profile")));
    }

    @Test
    void rejectsMissingRepository() {
        assertThrows(IllegalArgumentException.class, () -> new GetUserProfileUseCase(null));
    }
}
