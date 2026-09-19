package com.ayywl.delveforge.application.userdiscovery.exploration;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.userdiscovery.profile.UserProfileNotFoundException;
import com.ayywl.delveforge.application.userdiscovery.shared.UserProfileCandidates;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStateException;

/**
 * 用一轮用户自然语言输入推进 User Profile（DOMAIN_MODEL.md §8.1 Explore User Profile）。
 *
 * <pre>
 * 当前 User Profile + 本轮用户输入
 *         ↓
 * AI Gateway（AI 只提出建议）
 *         ↓
 * 结构化 Proposal
 *         ↓
 * UserProfile Aggregate（决定这些建议是否被接受、revision 是否推进）
 *         ↓
 * Persistence
 * </pre>
 *
 * <p>「提取并应用」这一步由 {@link ProfileExtraction} 承担，本 Use Case 只负责
 * 加载候选副本、调用它、保存结果。需要把探索与充分性评估组合成一整轮时使用
 * {@code RunUserDiscoveryTurnUseCase}，而不是串联本 Use Case。
 *
 * <h2>AI Proposes, Domain Decides</h2>
 *
 * <p>本 Use Case 不判断内容是否合法、不在本地计算 revision、不做状态转换：状态限制
 * （例如 CONFIRMED 不允许修改）、revision 推进与 no-op 判定全部由 {@code UserProfile} 决定。
 *
 * <h2>失败时不留下半更新</h2>
 *
 * <p>改动落在隔离的候选副本上，写入只发生在最后一步。因此无论是 AI 调用失败、
 * 模型输出无法解析，还是 Aggregate 拒绝某一条建议，都不会把半更新的 Profile 写进存储，
 * 也不会把从 Repository 读到的对象留在半更新状态。
 *
 * <p>本 Use Case 不包含：Sufficiency Assessment、自动生成下一问题、
 * {@code EXPLORING → REVIEWING} 转换、Review / Correct / Confirm 流程，
 * 也不保存对话记录。
 */
public class ExploreUserProfileUseCase {

    private final UserProfileRepository userProfileRepository;
    private final ProfileExtraction profileExtraction;

    public ExploreUserProfileUseCase(UserProfileRepository userProfileRepository,
                                     ProfileExtraction profileExtraction) {
        if (userProfileRepository == null) {
            throw new IllegalArgumentException("ExploreUserProfileUseCase 必须指定 userProfileRepository");
        }
        if (profileExtraction == null) {
            throw new IllegalArgumentException("ExploreUserProfileUseCase 必须指定 profileExtraction");
        }
        this.userProfileRepository = userProfileRepository;
        this.profileExtraction = profileExtraction;
    }

    /**
     * 处理一轮用户输入，并按 AI 建议更新 User Profile。
     *
     * @param userProfileId 目标 Profile
     * @param userInput     本轮用户自然语言输入
     * @return 更新后的 Profile
     * @throws IllegalArgumentException     用户输入为空，或建议不满足 Aggregate 的内容约束
     * @throws UserProfileNotFoundException 目标 Profile 不存在
     * @throws AiGatewayException           AI 调用失败，或返回内容无法解析
     * @throws UserProfileStateException    当前状态不允许修改 Profile
     */
    public UserProfile explore(UserProfileId userProfileId, String userInput) {
        ProfileExtraction.requireUserInput(userInput);

        UserProfile candidate = UserProfileCandidates.loadCopy(userProfileRepository, userProfileId);

        profileExtraction.applyTo(candidate, userInput);

        userProfileRepository.save(candidate);
        return candidate;
    }
}
