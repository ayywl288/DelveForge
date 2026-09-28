package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;
import java.util.List;

/**
 * 一次 Product Direction Discovery 的调用参数。
 *
 * <p>它把「这次发现依据什么」明确写下来，而不是让 Use Case 自己去猜当前状态：
 *
 * <pre>
 * userProfileId          用哪一份用户画像
 * expectedRevision       调用方认为它停在哪一版
 * repositoryProfileIds   用哪几份 Repository 分析快照
 * </pre>
 *
 * <h2>为什么要求调用方给出 expectedRevision</h2>
 *
 * <p>发现的结果会被持久化成一条能追溯到「确定的一版用户画像」的方向（INV-D01、
 * INV-D08）。如果 Use Case 默默用当前最新版本，那么用户看到的输入与系统实际依据的输入
 * 可能已经不是同一份——用户确认的是他当时看的那一版画像，而系统拿它之后的版本去推荐。
 *
 * <p>因此调用方必须说出它依据的是哪一版；Use Case 发现存储中的版本不是这一版时拒绝执行，
 * 而不是改用另一个 revision。这与 User Profile 的确认必须绑定 revision 是同一个道理
 * （§6.1）。
 *
 * <p>它不含 HTTP 语义，也不需要额外的 DTO 层：接口层如果出现，构造它就是它自己的事。
 *
 * @param userProfileId        本次发现所依据的 User Profile，不得为 {@code null}
 * @param expectedRevision     调用方认为该 Profile 停在哪一版，不得小于 1
 * @param repositoryProfileIds 本次发现可见的 Repository Profile；不得为 {@code null} 或空
 *                             （INV-D05 要求每个方向至少能追溯到一个 Repository Profile）
 */
public record DiscoverProductDirectionsRequest(
        UserProfileId userProfileId,
        int expectedRevision,
        List<RepositoryProfileId> repositoryProfileIds) {

    public DiscoverProductDirectionsRequest {
        if (userProfileId == null) {
            throw new IllegalArgumentException(
                    "Product Direction Discovery 必须指定 userProfileId");
        }
        if (expectedRevision < 1) {
            throw new IllegalArgumentException(
                    "Product Direction Discovery 的 expectedRevision 必须是确定的版本: "
                            + expectedRevision);
        }
        if (repositoryProfileIds == null || repositoryProfileIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Product Direction Discovery 至少需要一个 Repository Profile");
        }
        for (RepositoryProfileId profileId : repositoryProfileIds) {
            if (profileId == null) {
                throw new IllegalArgumentException(
                        "Product Direction Discovery 的 repositoryProfileIds 不能包含 null");
            }
        }
        repositoryProfileIds = List.copyOf(repositoryProfileIds);
    }
}
