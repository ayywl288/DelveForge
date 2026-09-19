package com.ayywl.delveforge.application.userdiscovery.review;

import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStateException;
import com.ayywl.delveforge.application.userdiscovery.profile.UserProfileNotFoundException;
import com.ayywl.delveforge.application.userdiscovery.shared.UserProfileCandidates;

/**
 * 用户在 Review 阶段选择继续探索（DOMAIN_MODEL.md §6.1 的 REVIEWING → EXPLORING）。
 *
 * <p>用户看过 Profile 之后认为还不够，于是回到探索阶段继续补充信息。
 * 这只是取消「等待确认」这一状态：不删除任何内容，也不改变 {@code revision}——
 * 回到 EXPLORING 之后产生的第一个内容变化才会推进出新版本。
 *
 * <p>与 {@link ReopenDiscoveryUseCase} 的区别只在起点：那条是从 {@code CONFIRMED}
 * 退回，对应「已经确认过，但想重新开始」。两者是不同的状态转移，因此是各自独立的
 * Use Case，而不是一个按当前状态分支的入口。
 *
 * <p>本 Use Case 只能由用户的显式请求触发，不接收 AI 输出。
 */
public class ContinueDiscoveryUseCase {

    private final UserProfileRepository userProfileRepository;

    public ContinueDiscoveryUseCase(UserProfileRepository userProfileRepository) {
        if (userProfileRepository == null) {
            throw new IllegalArgumentException("ContinueDiscoveryUseCase 必须指定 userProfileRepository");
        }
        this.userProfileRepository = userProfileRepository;
    }

    /**
     * 把指定 Profile 退回探索阶段。
     *
     * @param userProfileId 目标 Profile
     * @return 退回后的 Profile，status 为 {@code EXPLORING}
     * @throws UserProfileNotFoundException 目标 Profile 不存在
     * @throws UserProfileStateException    当前状态不是 {@code REVIEWING}
     */
    public UserProfile continueDiscovery(UserProfileId userProfileId) {
        UserProfile candidate = UserProfileCandidates.loadCopy(userProfileRepository, userProfileId);
        candidate.continueDiscovery();
        userProfileRepository.save(candidate);
        return candidate;
    }
}
