package com.ayywl.delveforge.application.userdiscovery;

import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileStateException;
import java.util.List;
import java.util.function.Consumer;

/**
 * 更新一个已存在的 User Profile。
 *
 * <p>本 Use Case 只做三件事：按标识取得 Aggregate、把结构化更新输入转发给 Aggregate
 * 已公开的领域行为、保存结果。
 *
 * <p>它不判断“哪些内容发生了变化”、不计算 revision、不修改 Aggregate 内部状态：
 * 是否推进 revision、是否拒绝本次更新，完全由 {@code UserProfile} 依据自身状态与内容
 * 决定（RULE-DOM-002、DOMAIN_MODEL.md §6.1）。因此即使本次输入没有带来任何实际变化，
 * 本层也不会人为制造一次 revision 推进——它同样不会试图跳过保存，
 * 因为“是否有实际变化”本就属于 Domain 的判断。
 *
 * <p>本 Use Case 不包含：用户输入如何被转换为结构化更新、Sufficiency Assessment、
 * Review / Correct / Confirm 流程。
 */
public class UpdateUserProfileUseCase {

    private final UserProfileRepository userProfileRepository;

    public UpdateUserProfileUseCase(UserProfileRepository userProfileRepository) {
        if (userProfileRepository == null) {
            throw new IllegalArgumentException("UpdateUserProfileUseCase 必须指定 userProfileRepository");
        }
        this.userProfileRepository = userProfileRepository;
    }

    /**
     * 更新并保存指定 User Profile。
     *
     * @param request 结构化更新输入
     * @return 更新后的 Profile
     * @throws UserProfileNotFoundException 目标 Profile 不存在
     * @throws IllegalArgumentException     输入不满足 Aggregate 的内容约束
     * @throws UserProfileStateException   当前状态不允许修改 Profile 内容
     */
    public UserProfile update(UpdateUserProfileRequest request) {
        UserProfile profile = userProfileRepository.findById(request.profileId())
                .orElseThrow(() -> new UserProfileNotFoundException(request.profileId()));

        applySection(request.interests(), profile::updateInterests);
        applySection(request.behaviors(), profile::updateBehaviors);
        applySection(request.painPoints(), profile::updatePainPoints);
        applySection(request.technicalCapabilities(), profile::updateTechnicalCapabilities);
        applySection(request.projectGoals(), profile::updateProjectGoals);
        applySection(request.constraints(), profile::updateConstraints);

        if (request.additionalEvidence() != null) {
            for (Evidence evidence : request.additionalEvidence()) {
                profile.recordEvidence(evidence);
            }
        }

        userProfileRepository.save(profile);
        return profile;
    }

    /**
     * 把一个内容区转发给对应的 Aggregate 行为。
     *
     * <p>调用方未提供（{@code null}）时保持原值；提供时交给 Aggregate 做整体替换，
     * 是否推进 revision 由 Aggregate 判断。
     */
    private static void applySection(List<String> provided, Consumer<List<String>> aggregateUpdate) {
        if (provided == null) {
            return;
        }
        aggregateUpdate.accept(provided);
    }
}
