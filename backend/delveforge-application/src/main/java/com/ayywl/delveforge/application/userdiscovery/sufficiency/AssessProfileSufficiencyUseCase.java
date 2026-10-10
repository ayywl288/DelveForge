package com.ayywl.delveforge.application.userdiscovery.sufficiency;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.userdiscovery.profile.UserProfileNotFoundException;
import com.ayywl.delveforge.application.userdiscovery.shared.UserProfileCandidates;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStateException;

/**
 * 用户显式请求进入 Review：复用本版服务端充分性；无记录才评估，充分才推进状态（DOMAIN_MODEL.md §6.1）。
 *
 * <pre>
 * 当前 User Profile
 *         ↓
 * AI Gateway（只提出「够不够、缺什么、下一问」）
 *         ↓
 * insufficient → 返回缺失信息，不插入探索问题，Profile 保持 EXPLORING
 * sufficient   → Domain 执行状态转移，Profile 进入 REVIEWING 并被保存
 * </pre>
 *
 * <p>评估本身由 {@link ProfileSufficiencyEvaluator} 承担，本 Use Case 只负责加载候选副本、
 * 调用它、在结论为「足够」时保存。需要把探索与评估组合成一整轮时使用
 * {@code RunUserDiscoveryTurnUseCase}，而不是串联本 Use Case 与
 * {@code ExploreUserProfileUseCase}——那样会中途先落一次库。
 *
 * <h2>AI Proposes, Domain Decides</h2>
 *
 * <p>{@link ProfileSufficiencyProposal} 里没有状态字段：模型无法表达、更无法设置
 * {@code UserProfileStatus}。是否真的进入 REVIEWING 完全由
 * {@code UserProfile.beginReview()} 依据 §6.1 判断。
 *
 * <h2>不产生无意义的改动</h2>
 *
 * <p>信息不足时本 Use Case 不写入任何内容，也不推进 revision——{@code revision} 只由
 * 六个内容区与 Evidence 的实际变化推进（§6.1），状态本身不在其中。
 *
 * <p>本 Use Case 不包含：内容纠正 / Confirm、REVIEWING → CONFIRMED、
 * CONFIRMED → EXPLORING，也不保存评估历史。
 */
public class AssessProfileSufficiencyUseCase {

    private final UserProfileRepository userProfileRepository;
    private final ProfileSufficiencyEvaluator profileSufficiencyEvaluator;

    public AssessProfileSufficiencyUseCase(
            UserProfileRepository userProfileRepository,
            ProfileSufficiencyEvaluator profileSufficiencyEvaluator) {
        if (userProfileRepository == null) {
            throw new IllegalArgumentException(
                    "AssessProfileSufficiencyUseCase 必须指定 userProfileRepository");
        }
        if (profileSufficiencyEvaluator == null) {
            throw new IllegalArgumentException(
                    "AssessProfileSufficiencyUseCase 必须指定 profileSufficiencyEvaluator");
        }
        this.userProfileRepository = userProfileRepository;
        this.profileSufficiencyEvaluator = profileSufficiencyEvaluator;
    }

    /**
     * 评估指定 User Profile 的信息是否足够。
     *
     * @param userProfileId 目标 Profile
     * @return 本次评估结果，其中 {@code profileStatus} 是评估结束后的实际状态
     * @throws UserProfileNotFoundException 目标 Profile 不存在
     * @throws AiGatewayException           AI 调用失败，或返回内容无法解析
     * @throws UserProfileStateException    Domain 拒绝这条状态转移
     */
    public SufficiencyAssessment assess(UserProfileId userProfileId) {
        UserProfile current = UserProfileCandidates.loadCopy(userProfileRepository, userProfileId);
        return assess(userProfileId, current.revision());
    }

    public SufficiencyAssessment assess(UserProfileId userProfileId, int expectedRevision) {
        UserProfile expected = UserProfileCandidates.loadCopy(userProfileRepository, userProfileId);
        if (expected.revision() != expectedRevision) {
            throw new UserProfileStateException("审阅所依据的画像版本已经变化");
        }
        UserProfile candidate = UserProfileCandidates.copyOf(expected);

        // 这是用户显式请求进入审阅的入口，普通对话轮不调用此状态转换。
        candidate.requireExplorationAllowed();
        boolean reused = userProfileRepository.hasSufficientAssessment(userProfileId, expectedRevision);
        SufficiencyAssessment assessment = reused
                ? new SufficiencyAssessment(true, java.util.List.of(), null, candidate.status())
                : profileSufficiencyEvaluator.evaluate(candidate);

        UserProfile current = UserProfileCandidates.loadCopy(userProfileRepository, userProfileId);
        if (current.revision() != expected.revision() || current.status() != expected.status()) {
            throw new UserProfileStateException("评估期间画像已经变化，请重新读取");
        }

        // 只有真的发生了状态转移才写入：信息不足时不产生任何持久化副作用。
        if (assessment.sufficient()) {
            candidate.beginReview();
            userProfileRepository.saveWithSufficiency(candidate, expected, true);
        }
        return new SufficiencyAssessment(assessment.sufficient(), assessment.missingAreas(),
                null, candidate.status());
    }
}
