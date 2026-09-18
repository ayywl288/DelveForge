package com.ayywl.delveforge.application.userdiscovery;

import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStateException;

/**
 * 用户确认当前 User Profile（DOMAIN_MODEL.md §6.1 的 REVIEWING → CONFIRMED）。
 *
 * <p>这是 DelveForge 中具有写权限意义的人类决策边界之一：确认之后 Profile 内容
 * 不再允许修改，直到用户显式重新开启探索。因此本 Use Case <b>只能由用户的显式请求
 * 触发</b>——它不接收 AI 输出，也不接受任何来源的「建议确认」；AI 流程（Explore、
 * Sufficiency）都没有调用它的路径。
 *
 * <h2>确认后的稳定基线</h2>
 *
 * <p>确认不推进 {@code revision}，因此确认结果就是
 * 「{@code CONFIRMED} @ 确认时的 {@code revision}」：
 *
 * <pre>
 * 该 revision 的结构化内容与 Evidence   已经由 Persistence 保存为内容快照（§10.3）
 * 确认之后的内容修改                  被域规则拒绝，直到重新开启探索
 * </pre>
 *
 * <p>这个组合构成后续 Product Direction Discovery 可以稳定引用的输入。
 *
 * <p>{@code UserProfileConfirmed} 是这一事实的领域表达（§9.1）。当前不引入事件机制，
 * 该语义由「状态被持久化 + revision 被保留」承载；事件如何传播属于尚未决定的
 * 实现问题（§14.10），不在本 Use Case 的职责内。
 *
 * <p>本 Use Case 不包含：确认历史的持久化、Product Direction Discovery、
 * 以及 REVIEWING → CONFIRMED 之外的任何状态转换。
 */
public class ConfirmUserProfileUseCase {

    private final UserProfileRepository userProfileRepository;

    public ConfirmUserProfileUseCase(UserProfileRepository userProfileRepository) {
        if (userProfileRepository == null) {
            throw new IllegalArgumentException("ConfirmUserProfileUseCase 必须指定 userProfileRepository");
        }
        this.userProfileRepository = userProfileRepository;
    }

    /**
     * 确认指定 User Profile。
     *
     * <p>调用方必须传入用户做出决定时所看的 revision。确认的意义是「用户同意了这一版
     * 内容」；如果在用户查看之后、提交确认之前内容又变化过，当前 revision 已经不是
     * 用户看过的那一版，此时确认不再代表用户的真实决定。一致性由
     * {@code UserProfile.confirm(int)} 校验，不一致时整个请求失败且不写入任何内容。
     *
     * @param userProfileId    目标 Profile
     * @param expectedRevision 用户确认时所依据的 revision
     * @return 确认后的 Profile，status 为 {@code CONFIRMED}，revision 与确认前一致
     * @throws UserProfileNotFoundException 目标 Profile 不存在
     * @throws UserProfileStateException    当前状态不是 {@code REVIEWING}，
     *                                      或 {@code expectedRevision} 已过期
     */
    public UserProfile confirm(UserProfileId userProfileId, int expectedRevision) {
        UserProfile candidate = UserProfileCandidates.loadCopy(userProfileRepository, userProfileId);
        candidate.confirm(expectedRevision);
        userProfileRepository.save(candidate);
        return candidate;
    }
}
