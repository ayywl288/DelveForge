package com.ayywl.delveforge.application.userdiscovery.sufficiency;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiMessage;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.ai.AiResponseFormat;
import com.ayywl.delveforge.application.port.ai.AiRole;
import com.ayywl.delveforge.application.userdiscovery.shared.ProfilePromptContext;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileStateException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评估一个候选 Profile 的信息是否足够，并在结论为「足够」时推进状态。
 *
 * <pre>
 * 候选 Profile
 *         ↓
 * AI Gateway（只提出「够不够、缺什么、下一问」）
 *         ↓
 * insufficient → 候选 Profile 原样不动
 * sufficient   → UserProfile.beginReview()，由 Domain 决定是否允许
 * </pre>
 *
 * <p>这是 Sufficiency 的协作单元：{@code AssessProfileSufficiencyUseCase} 与
 * {@code RunUserDiscoveryTurnUseCase} 都用它，从而不复制 Prompt、Parser 或领域规则。
 * 它自己不加载、不保存——持久化由调用方决定。
 *
 * <h2>AI Proposes, Domain Decides</h2>
 *
 * <p>{@link ProfileSufficiencyProposal} 里没有状态字段：模型无法表达、更无法设置
 * {@code UserProfileStatus}。是否真的进入 REVIEWING 完全由
 * {@code UserProfile.beginReview()} 依据 §6.1 判断。
 */
public final class ProfileSufficiencyEvaluator {

    /**
     * 系统指令。
     *
     * <p>要求模型只输出 json（DeepSeek 的 JSON 模式要求提示词里出现 "json"），
     * 并把「足够 / 不足」两种情况下各字段必须满足的自洽条件写清楚——解析器会据此
     * 拒绝自相矛盾的输出。
     */
    private static final String SYSTEM_INSTRUCTION = """
            你是 DelveForge 的用户信息充分性评估组件。你的任务是判断「当前 User Profile」
            是否已经包含足够的信息，可以交给用户检查并确认。

            只输出一个 json 对象，不要输出解释、Markdown 代码块或任何其他文字。格式如下：

            {
              "sufficient": false,
              "missingAreas": ["...", "..."],
              "nextQuestion": "..."
            }

            判断标准是：这些信息是否足以支撑「为这位用户发现值得开发的项目方向」。
            重点考察六个维度：
            - interests               兴趣与长期关注的方向
            - behaviors               真实存在的行为与使用场景
            - painPoints              希望解决的问题或不满意之处
            - technicalCapabilities   当前具备的开发与技术能力
            - projectGoals            希望通过项目实现的目标
            - constraints             会影响方向选择的重要约束

            规则：
            - sufficient 为 true 时，missingAreas 必须是空数组，nextQuestion 必须是空字符串。
            - sufficient 为 false 时，missingAreas 至少给出一项，nextQuestion 给出最有价值的下一个问题。
            - missingAreas 每一项是一句话，说明还缺哪一类信息，不要写成长段解释。
            - nextQuestion 只问一个问题，直接问用户本人，语气自然。
            - 不要修改、不要输出、也不要提及 User Profile 的状态。
            """;

    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final ProfileSufficiencyProposalParser proposalParser;

    public ProfileSufficiencyEvaluator(AiGateway aiGateway, ObjectMapper objectMapper) {
        if (aiGateway == null) {
            throw new IllegalArgumentException("ProfileSufficiencyEvaluator 必须指定 aiGateway");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("ProfileSufficiencyEvaluator 必须指定 objectMapper");
        }
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.proposalParser = new ProfileSufficiencyProposalParser(objectMapper);
    }

    /**
     * 评估候选 Profile，并在结论为「足够」时调用 Domain 的状态转移。
     *
     * <p>AI 调用与解析都发生在任何领域修改之前；状态转移由 Aggregate 判定，
     * 拒绝时异常向上传播，候选 Profile 保持原状。
     *
     * @param candidate 本轮工作的候选 Profile
     * @return 本次评估结果，其中 {@code profileStatus} 是评估后的实际状态
     * @throws AiGatewayException        AI 调用失败，或返回内容无法解析
     * @throws UserProfileStateException Domain 拒绝这条状态转移
     */
    public SufficiencyAssessment evaluateAndApply(UserProfile candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("ProfileSufficiencyEvaluator 必须指定 candidate");
        }

        ProfileSufficiencyProposal proposal = proposalParser.parse(
                aiGateway.generate(buildRequest(candidate)));

        if (!proposal.sufficient()) {
            // 信息不足：候选 Profile 保持原状，是否写入由调用方决定。
            return new SufficiencyAssessment(
                    false, proposal.missingAreas(), proposal.nextQuestion(), candidate.status());
        }

        candidate.beginReview();
        return new SufficiencyAssessment(true, List.of(), null, candidate.status());
    }

    private AiRequest buildRequest(UserProfile profile) {
        return new AiRequest(
                List.of(
                        new AiMessage(AiRole.SYSTEM, SYSTEM_INSTRUCTION),
                        new AiMessage(AiRole.USER, describeProfile(profile))),
                AiResponseFormat.JSON);
    }

    private String describeProfile(UserProfile profile) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("currentUserProfile", ProfilePromptContext.currentContent(profile));

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new AiGatewayException("无法构造 AI 请求内容", exception);
        }
    }
}
