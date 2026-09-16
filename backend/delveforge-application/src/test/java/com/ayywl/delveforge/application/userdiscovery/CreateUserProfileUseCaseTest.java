package com.ayywl.delveforge.application.userdiscovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreateUserProfileUseCaseTest {

    /** DOMAIN_MODEL.md §10.3 的 revision 序列自 1 开始。 */
    private static final int INITIAL_REVISION = 1;

    private final InMemoryUserProfileRepository repository = new InMemoryUserProfileRepository();

    private final CreateUserProfileUseCase useCase = new CreateUserProfileUseCase(repository);

    @Test
    void createsProfileInInitialDomainState() {
        UserProfile profile = useCase.create();

        assertNotNull(profile.id());
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
    void savesCreatedProfileThroughPersistencePort() {
        UserProfile profile = useCase.create();

        assertSame(profile, repository.findById(profile.id()).orElseThrow());
        assertEquals(1, repository.saveCount());
    }

    @Test
    void assignsDistinctIdentityToEachProfile() {
        UserProfile first = useCase.create();
        UserProfile second = useCase.create();

        assertNotEquals(first.id(), second.id());
        assertTrue(repository.findById(first.id()).isPresent());
        assertTrue(repository.findById(second.id()).isPresent());
        assertEquals(2, repository.saveCount());
    }

    @Test
    void rejectsMissingRepository() {
        assertThrows(IllegalArgumentException.class, () -> new CreateUserProfileUseCase(null));
    }
}
