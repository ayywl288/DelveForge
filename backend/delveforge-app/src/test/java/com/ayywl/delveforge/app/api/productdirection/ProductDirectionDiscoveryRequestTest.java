package com.ayywl.delveforge.app.api.productdirection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证发现请求的形状约束由本类型自己拒绝。
 *
 * <h2>为什么需要这一层</h2>
 *
 * <p>「请求体不合法 → 400」这一点已经由 HTTP 层的测试覆盖，但那不足以证明<b>每一条</b>
 * 约束都真的在起作用：Jackson 会把构造函数抛出的任何异常都包成反序列化失败，Spring 再把它
 * 映射成 400。也就是说，即使某条检查被删掉、字段缺失时改用 NaN 或 {@code null} 继续往下走，
 * 只要后面某处又抛了异常，HTTP 层看到的仍然是 400——测试照样通过，缺陷却被掩盖了。
 *
 * <p>因此这里直接构造请求体，逐条确认每个不合法的形状都由 {@link IllegalArgumentException}
 * 拒绝：那是这一层自己的判断，而不是下游某处偶然抛出的结果。
 */
class ProductDirectionDiscoveryRequestTest {

    private static final String USER_PROFILE_ID = "user-profile-1";

    private static final int REVISION = 3;

    private static final List<String> REPOSITORY_PROFILE_IDS = List.of("repository-profile-1");

    @Test
    void acceptsAWellFormedRequest() {
        ProductDirectionDiscoveryRequest request =
                new ProductDirectionDiscoveryRequest(USER_PROFILE_ID, REVISION, REPOSITORY_PROFILE_IDS);

        assertEquals(USER_PROFILE_ID, request.userProfileId());
        assertEquals(REVISION, request.expectedRevision());
        assertEquals(REPOSITORY_PROFILE_IDS, request.repositoryProfileIds());
    }

    @Test
    void rejectsMissingUserProfileId() {
        assertRejected(null, REVISION, REPOSITORY_PROFILE_IDS);
    }

    @Test
    void rejectsBlankUserProfileId() {
        assertRejected("   ", REVISION, REPOSITORY_PROFILE_IDS);
    }

    /**
     * 缺失的 {@code expectedRevision} 不得被当成 0。
     *
     * <p>那不是「第 0 版」，而是调用方根本没有说出它依据的是哪一版——两者必须区分开。
     */
    @Test
    void rejectsMissingExpectedRevision() {
        assertRejected(USER_PROFILE_ID, null, REPOSITORY_PROFILE_IDS);
    }

    @Test
    void rejectsExpectedRevisionBelowOne() {
        assertRejected(USER_PROFILE_ID, 0, REPOSITORY_PROFILE_IDS);
        assertRejected(USER_PROFILE_ID, -1, REPOSITORY_PROFILE_IDS);
    }

    @Test
    void rejectsMissingRepositoryProfileIds() {
        assertRejected(USER_PROFILE_ID, REVISION, null);
    }

    /**
     * 空的 {@code repositoryProfileIds} 不是「这次没有依据」。
     *
     * <p>每个方向必须至少能追溯到一个 Repository Profile（INV-D05），空列表不构成一次发现。
     */
    @Test
    void rejectsEmptyRepositoryProfileIds() {
        assertRejected(USER_PROFILE_ID, REVISION, List.of());
    }

    @Test
    void rejectsRepositoryProfileIdsContainingEmptyValues() {
        assertRejected(USER_PROFILE_ID, REVISION, Arrays.asList("repository-profile-1", null));
        assertRejected(USER_PROFILE_ID, REVISION, List.of("   "));
    }

    /**
     * 标识列表在构造时固化：调用方之后修改自己传入的列表不影响这次请求。
     */
    @Test
    void doesNotShareStateWithTheCallerList() {
        List<String> mutable = new ArrayList<>(REPOSITORY_PROFILE_IDS);

        ProductDirectionDiscoveryRequest request =
                new ProductDirectionDiscoveryRequest(USER_PROFILE_ID, REVISION, mutable);
        mutable.add("repository-profile-2");

        assertEquals(List.of("repository-profile-1"), request.repositoryProfileIds());
    }

    private static void assertRejected(String userProfileId, Integer expectedRevision,
                                       List<String> repositoryProfileIds) {
        assertThrows(IllegalArgumentException.class,
                () -> new ProductDirectionDiscoveryRequest(
                        userProfileId, expectedRevision, repositoryProfileIds));
    }
}
