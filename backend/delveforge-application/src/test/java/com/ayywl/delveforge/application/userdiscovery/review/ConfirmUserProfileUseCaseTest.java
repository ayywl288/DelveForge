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
 * 验证用户确认 Profile 的编排。
 *
 * <p>本 Use Case 不接受任何 AI 输出，只能由显式用户请求触发；这里验证的是它确实
 * 只做一次状态转移，且完全交由 Domain 判断该转移是否被允许。
 */
class ConfirmUserProfileUseCaseTest {

    private static final UserProfileId PROFILE_ID = new UserProfileId("user-profile-1");

    private final InMemoryUserProfileRepository repository = new InMemoryUserProfileRepository();

    private final ConfirmUserProfileUseCase useCase = new ConfirmUserProfileUseCase(repository);

    @Test
    void confirmsReviewingProfileWithoutAdvancingRevision() {
        seedProfile(UserProfileStatus.REVIEWING, 3);
        int savesBefore = repository.saveCount();

        UserProfile confirmed = useCase.confirm(PROFILE_ID, 3);

        assertEquals(UserProfileStatus.CONFIRMED, confirmed.status());
        assertEquals(3, confirmed.revision(), "确认不推进 revision");

        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(UserProfileStatus.CONFIRMED, reloaded.status(), "确认结果应被保存");
        assertEquals(3, reloaded.revision(), "确认后仍是 CONFIRMED @ 确认时的 revision");
        assertEquals(List.of("兴趣"), reloaded.interests());
        assertEquals(savesBefore + 1, repository.saveCount());
    }

    @Test
    void rejectsConfirmFromExploring() {
        UserProfile exploring = seedProfile(UserProfileStatus.EXPLORING, 1);
        int savesBefore = repository.saveCount();

        assertThrows(UserProfileStateException.class, () -> useCase.confirm(PROFILE_ID, 1));

        assertEquals(UserProfileStatus.EXPLORING, exploring.status(), "原对象不得被改动");
        assertEquals(UserProfileStatus.EXPLORING,
                repository.findById(PROFILE_ID).orElseThrow().status());
        assertEquals(savesBefore, repository.saveCount(), "Domain 拒绝后不得写入");
    }

    @Test
    void rejectsConfirmFromConfirmed() {
        seedProfile(UserProfileStatus.CONFIRMED, 3);

        assertThrows(UserProfileStateException.class, () -> useCase.confirm(PROFILE_ID, 3));
    }

    /**
     * 回归：用户查看之后内容又变化过时，基于旧 revision 的确认必须被拒绝，
     * 且不写入任何内容。
     */
    @Test
    void rejectsConfirmWithStaleRevision() {
        UserProfile reviewing = seedProfile(UserProfileStatus.REVIEWING, 3);
        int savesBefore = repository.saveCount();

        assertThrows(UserProfileStateException.class, () -> useCase.confirm(PROFILE_ID, 2));

        assertEquals(UserProfileStatus.REVIEWING, reviewing.status(), "原对象不得被改动");
        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(UserProfileStatus.REVIEWING, reloaded.status(), "不得确认");
        assertEquals(3, reloaded.revision());
        assertEquals(savesBefore, repository.saveCount(), "Domain 拒绝后不得写入");
    }

    @Test
    void failsWhenProfileDoesNotExist() {
        assertThrows(UserProfileNotFoundException.class,
                () -> useCase.confirm(new UserProfileId("unknown-profile"), 1));
    }

    @Test
    void rejectsMissingRepository() {
        assertThrows(IllegalArgumentException.class, () -> new ConfirmUserProfileUseCase(null));
    }

    private UserProfile seedProfile(UserProfileStatus status, int revision) {
        UserProfile profile = UserProfile.reconstitute(
                PROFILE_ID, status, revision,
                List.of("兴趣"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        repository.save(profile);
        return profile;
    }
}
