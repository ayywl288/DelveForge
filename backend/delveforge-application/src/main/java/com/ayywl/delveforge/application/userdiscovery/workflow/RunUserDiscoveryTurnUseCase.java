package com.ayywl.delveforge.application.userdiscovery.workflow;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.userdiscovery.exploration.ProfileExtraction;
import com.ayywl.delveforge.application.userdiscovery.profile.UserProfileNotFoundException;
import com.ayywl.delveforge.application.userdiscovery.shared.UserProfileCandidates;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.ProfileSufficiencyEvaluator;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.SufficiencyAssessment;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStateException;

/**
 * 面向产品的一轮 User Discovery：一次请求完成「提取 → 更新 → 评估 → 必要时进入 Review」。
 *
 * <pre>
 * 本轮用户输入
 *         ↓
 * ProfileExtraction        当前 Profile + 输入 → AI 提出建议 → 更新候选 Profile
 *         ↓
 * ProfileSufficiencyEvaluator  对「已经更新过的候选 Profile」评估信息是否足够
 *         ↓
 * insufficient  保存更新后的 Profile，返回 missingAreas + nextQuestion，仍为 EXPLORING
 * sufficient    Domain 执行 EXPLORING → REVIEWING，保存 Profile，返回 REVIEWING
 * </pre>
 *
 * <h2>一轮只保存一次</h2>
 *
 * <p>整轮都作用在同一个候选 Profile 上，只在最后写入一次。这不是把
 * {@code ExploreUserProfileUseCase} 与 {@code AssessProfileSufficiencyUseCase} 串起来——
 * 那样会在探索结束后先落一次库，随后的评估失败就会留下「本轮只完成了一半」的持久化状态。
 *
 * <p>充分性评估针对的是**本轮已经更新过的候选 Profile**：用户这一轮说的信息必须先进入
 * Profile，再判断够不够；对旧 Profile 做评估会得出与用户当前状态无关的结论。
 *
 * <h2>失败时不留下半轮结果</h2>
 *
 * <p>四个失败点都不会产生持久化副作用：
 *
 * <pre>
 * 提取的 AI 调用 / 解析失败   候选 Profile 还没被改动，也没有写入
 * 内容更新被 Aggregate 拒绝   改动留在候选副本上，写入还没发生
 * 评估的 AI 调用 / 解析失败   同上
 * beginReview 被 Domain 拒绝  同上
 * </pre>
 *
 * <h2>跨请求的循环</h2>
 *
 * <p>「继续探索」不是服务端的循环：一轮返回 {@code nextQuestion}，用户回答后由调用方发起
 * 下一轮。本 Use Case 不做 {@code while}、不等待用户、也不保存对话记录。
 *
 * <h2>AI Proposes, Domain Decides</h2>
 *
 * <p>本 Use Case 只负责编排：内容是否合法、revision 是否推进、状态转换是否允许，
 * 全部由 {@code UserProfile} 判定。模型既不能设置状态，也不能构造无法追溯的 Evidence。
 */
public class RunUserDiscoveryTurnUseCase {

    private final UserProfileRepository userProfileRepository;
    private final ProfileExtraction profileExtraction;
    private final ProfileSufficiencyEvaluator profileSufficiencyEvaluator;

    public RunUserDiscoveryTurnUseCase(
            UserProfileRepository userProfileRepository,
            ProfileExtraction profileExtraction,
            ProfileSufficiencyEvaluator profileSufficiencyEvaluator) {
        if (userProfileRepository == null) {
            throw new IllegalArgumentException(
                    "RunUserDiscoveryTurnUseCase 必须指定 userProfileRepository");
        }
        if (profileExtraction == null) {
            throw new IllegalArgumentException(
                    "RunUserDiscoveryTurnUseCase 必须指定 profileExtraction");
        }
        if (profileSufficiencyEvaluator == null) {
            throw new IllegalArgumentException(
                    "RunUserDiscoveryTurnUseCase 必须指定 profileSufficiencyEvaluator");
        }
        this.userProfileRepository = userProfileRepository;
        this.profileExtraction = profileExtraction;
        this.profileSufficiencyEvaluator = profileSufficiencyEvaluator;
    }

    /**
     * 处理一轮 User Discovery。
     *
     * @param userProfileId 目标 Profile
     * @param userInput     本轮用户自然语言输入
     * @return 本轮结束时的 Profile 与同一轮的充分性结论
     * @throws IllegalArgumentException     用户输入为空，或建议不满足 Aggregate 的内容约束
     * @throws UserProfileNotFoundException 目标 Profile 不存在
     * @throws AiGatewayException           任一 AI 调用失败，或返回内容无法解析
     * @throws UserProfileStateException    当前状态不允许本轮修改或状态转移
     */
    public UserDiscoveryTurn run(UserProfileId userProfileId, String userInput) {
        ProfileExtraction.requireUserInput(userInput);

        UserProfile candidate = UserProfileCandidates.loadCopy(userProfileRepository, userProfileId);

        // 先由 Domain 判定「能不能开始一轮探索」：放在提取之前，
        // 因此非法状态下既不会产生 AI 调用，也不会改动候选 Profile。
        // 这道约束只属于「整轮」入口 —— ExploreUserProfileUseCase 保持原有行为不变。
        candidate.requireExplorationAllowed();

        profileExtraction.applyTo(candidate, userInput);
        SufficiencyAssessment sufficiency =
                profileSufficiencyEvaluator.evaluateAndApply(candidate);

        // 一轮只保存一次：整轮成功后写入，因此任何一步失败都不会留下半轮结果。
        userProfileRepository.save(candidate);
        return new UserDiscoveryTurn(candidate, sufficiency);
    }
}
