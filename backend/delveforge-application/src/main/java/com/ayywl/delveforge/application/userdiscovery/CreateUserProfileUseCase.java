package com.ayywl.delveforge.application.userdiscovery;

import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import java.util.UUID;

/**
 * 创建一个新的 User Profile。
 *
 * <p>新 Profile 是空的：创建只建立身份与初始领域状态，不接收任何结构化内容。
 * 兴趣、行为、痛点、技术能力、项目目标与约束在后续的
 * {@link UpdateUserProfileUseCase} 中逐步形成，这与 §6.1 中
 * {@code EXPLORING} 阶段持续收集用户信息的语义一致。
 *
 * <p>因此本 Use Case 不为了“一次创建就带上内容”而扩展 Domain：它只调用
 * {@code UserProfile.create(id)} 这一既有 Aggregate 行为，初始 status 与 revision
 * 完全由 Domain 决定，Application 不参与判断。
 *
 * <p>本 Use Case 不包含：用户输入如何被转换成结构化内容、Sufficiency Assessment、
 * Review / Confirm 流程。
 */
public class CreateUserProfileUseCase {

    private final UserProfileRepository userProfileRepository;

    public CreateUserProfileUseCase(UserProfileRepository userProfileRepository) {
        if (userProfileRepository == null) {
            throw new IllegalArgumentException("CreateUserProfileUseCase 必须指定 userProfileRepository");
        }
        this.userProfileRepository = userProfileRepository;
    }

    /**
     * 创建并保存一个新的 User Profile。
     *
     * @return 已保存的 Profile，其标识由本次创建生成
     */
    public UserProfile create() {
        UserProfile profile = UserProfile.create(generateProfileId());
        userProfileRepository.save(profile);
        return profile;
    }

    /**
     * 生成新 Profile 的标识。
     *
     * <p>标识由 Application 在创建时分配，不由调用方提供：
     * Create Use Case 的输入中没有 Profile 标识。
     */
    private static UserProfileId generateProfileId() {
        return new UserProfileId(UUID.randomUUID().toString());
    }
}
