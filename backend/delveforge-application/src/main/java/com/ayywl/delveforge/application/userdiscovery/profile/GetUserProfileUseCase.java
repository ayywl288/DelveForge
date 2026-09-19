package com.ayywl.delveforge.application.userdiscovery.profile;

import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;

/**
 * 读取一个已存在的 User Profile。
 *
 * <p>返回的是当前（最新）revision 的 Profile。本 Use Case 不提供历史 revision 查询：
 * Port 目前只有按标识读取当前状态的能力，历史查询等出现真实消费者时再引入。
 *
 * <p>读取本身不做编排，因此这里只负责把「找不到」翻译成明确的失败语义，
 * 让调用方不必自行判断 {@code Optional}。
 */
public class GetUserProfileUseCase {

    private final UserProfileRepository userProfileRepository;

    public GetUserProfileUseCase(UserProfileRepository userProfileRepository) {
        if (userProfileRepository == null) {
            throw new IllegalArgumentException("GetUserProfileUseCase 必须指定 userProfileRepository");
        }
        this.userProfileRepository = userProfileRepository;
    }

    /**
     * 读取指定 User Profile。
     *
     * @param userProfileId Profile 标识
     * @return 当前 revision 的 Profile
     * @throws UserProfileNotFoundException Profile 不存在
     */
    public UserProfile get(UserProfileId userProfileId) {
        return userProfileRepository.findById(userProfileId)
                .orElseThrow(() -> new UserProfileNotFoundException(userProfileId));
    }
}
