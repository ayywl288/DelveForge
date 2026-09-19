package com.ayywl.delveforge.application.userdiscovery.review;

import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStateException;
import com.ayywl.delveforge.application.userdiscovery.profile.UserProfileNotFoundException;
import com.ayywl.delveforge.application.userdiscovery.shared.UserProfileCandidates;

/**
 * 用户重新开启探索（DOMAIN_MODEL.md §6.1 的 CONFIRMED → EXPLORING）。
 *
 * <p>用户已经确认过 Profile，之后决定重新开始探索。这只是取消「已确认」这一状态：
 * 不删除任何内容，也不改变 {@code revision}——已经确认过的那个 revision 对应的
 * 内容快照仍由 Persistence 保留（§10.3），已经引用过它的历史分析不受影响
 * （§6.1：已经生成的 Product Direction 保留其原始 {@code userProfileRevision}）。
 *
 * <p>与 {@link ContinueDiscoveryUseCase} 的区别只在起点：那条是从 {@code REVIEWING}
 * 退回，这条是从 {@code CONFIRMED} 退回。
 *
 * <p>本 Use Case 只能由用户的显式请求触发，不接收 AI 输出。
 */
public class ReopenDiscoveryUseCase {

    private final UserProfileRepository userProfileRepository;

    public ReopenDiscoveryUseCase(UserProfileRepository userProfileRepository) {
        if (userProfileRepository == null) {
            throw new IllegalArgumentException("ReopenDiscoveryUseCase 必须指定 userProfileRepository");
        }
        this.userProfileRepository = userProfileRepository;
    }

    /**
     * 把指定 Profile 重新开启为探索状态。
     *
     * @param userProfileId 目标 Profile
     * @return 重新开启后的 Profile，status 为 {@code EXPLORING}
     * @throws UserProfileNotFoundException 目标 Profile 不存在
     * @throws UserProfileStateException    当前状态不是 {@code CONFIRMED}
     */
    public UserProfile reopenDiscovery(UserProfileId userProfileId) {
        UserProfile candidate = UserProfileCandidates.loadCopy(userProfileRepository, userProfileId);
        candidate.reopenDiscovery();
        userProfileRepository.save(candidate);
        return candidate;
    }
}
