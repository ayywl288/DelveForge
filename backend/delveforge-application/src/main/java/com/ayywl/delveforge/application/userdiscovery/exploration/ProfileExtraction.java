package com.ayywl.delveforge.application.userdiscovery.exploration;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiMessage;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.ai.AiResponseFormat;
import com.ayywl.delveforge.application.port.ai.AiRole;
import com.ayywl.delveforge.application.userdiscovery.shared.ProfilePromptContext;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 从一轮用户输入中提取 Profile 更新建议，并应用到给定的候选 Profile 上。
 *
 * <pre>
 * 候选 Profile + 本轮用户输入
 *         ↓
 * AI Gateway → 结构化 UserProfileProposal
 *         ↓
 * 候选 Profile 的六个内容区与 Evidence（由 Aggregate 决定是否真的变化）
 * </pre>
 *
 * <p>这是 Exploration 的协作单元：{@code ExploreUserProfileUseCase} 与
 * {@code RunUserDiscoveryTurnUseCase} 都用它完成「提取并应用」这一步，
 * 从而不复制 Prompt、Parser 或领域规则。它自己不加载、不保存——持久化由调用方决定，
 * 一轮工作流因此可以在最后统一写入一次。
 *
 * <h2>AI Proposes, Domain Decides</h2>
 *
 * <p>模型提出的建议不构成合法领域状态：内容是否被接受、revision 是否推进、
 * 状态是否允许修改，全部由 {@code UserProfile} 判定。
 *
 * <p>Evidence 的可追溯信息由本类补齐，不采用模型的说法：来源固定为本轮用户输入，
 * 模型无法构造无法追溯的条目。
 */
public final class ProfileExtraction {

    /**
     * 系统指令。
     *
     * <p>要求模型只输出 json：DeepSeek 的 JSON 模式要求提示词中出现 "json" 字样，
     * 同时这也让模型清楚不要附带解释文本。
     */
    private static final String SYSTEM_INSTRUCTION = """
            你是 DelveForge 的用户理解组件。你的任务是根据「当前 User Profile」与「本轮用户输入」,
            提出对 User Profile 的更新建议。

            只输出一个 json 对象，不要输出解释、Markdown 代码块或任何其他文字。格式如下：

            {
              "interests": ["..."],
              "behaviors": ["..."],
              "painPoints": ["..."],
              "technicalCapabilities": ["..."],
              "projectGoals": ["..."],
              "constraints": ["..."],
              "evidenceClaims": ["..."]
            }

            规则：
            - 六个内容区表示「建议替换成的新内容」，必须给出该区的完整列表，不是增量。
              本次不建议修改的区请直接省略，不要输出空数组来清空它。
            - 只依据本轮用户输入，以及当前 User Profile 中已经存在的事实。
              不要臆测用户没有表达过的信息，也不要为了填充而编造内容。
            - evidenceClaims 只写本轮用户输入直接支撑的判断，每条一句话。
              不要写入本轮输入之外、也无法从当前 Profile 读到的依据。
            """;

    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final UserProfileProposalParser proposalParser;

    public ProfileExtraction(AiGateway aiGateway, ObjectMapper objectMapper) {
        if (aiGateway == null) {
            throw new IllegalArgumentException("ProfileExtraction 必须指定 aiGateway");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("ProfileExtraction 必须指定 objectMapper");
        }
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.proposalParser = new UserProfileProposalParser(objectMapper);
    }

    /**
     * 校验一轮用户输入非空。
     *
     * <p>调用方应在加载 Profile <b>之前</b>调用它：非法请求应当先于资源查找被拒绝，
     * 否则同一个空输入会因为目标是否存在而返回不同的失败类别。
     *
     * @throws IllegalArgumentException 输入为 {@code null} 或空白
     */
    public static void requireUserInput(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalArgumentException("User Discovery 的用户输入不能为空");
        }
    }

    /**
     * 提取本轮建议并应用到候选 Profile。
     *
     * <p>失败时不会抛下「应用了一半」的状态：AI 调用与解析都发生在任何领域修改之前，
     * 而 Aggregate 拒绝某条建议时异常会直接向上传播——调用方只要不保存，候选 Profile
     * 上的改动就不会进入存储。
     *
     * @param candidate 本轮工作的候选 Profile
     * @param userInput 本轮用户自然语言输入
     * @throws IllegalArgumentException 用户输入为空，或建议不满足 Aggregate 的内容约束
     * @throws AiGatewayException       AI 调用失败，或返回内容无法解析
     */
    public void applyTo(UserProfile candidate, String userInput) {
        if (candidate == null) {
            throw new IllegalArgumentException("ProfileExtraction 必须指定 candidate");
        }
        requireUserInput(userInput);

        UserProfileProposal proposal = proposalParser.parse(
                aiGateway.generate(buildRequest(candidate, userInput)));

        applySection(proposal.interests(), candidate::updateInterests);
        applySection(proposal.behaviors(), candidate::updateBehaviors);
        applySection(proposal.painPoints(), candidate::updatePainPoints);
        applySection(proposal.technicalCapabilities(), candidate::updateTechnicalCapabilities);
        applySection(proposal.projectGoals(), candidate::updateProjectGoals);
        applySection(proposal.constraints(), candidate::updateConstraints);

        if (proposal.evidenceClaims() != null) {
            for (String claim : proposal.evidenceClaims()) {
                candidate.recordEvidence(toEvidence(claim, userInput));
            }
        }
    }

    private AiRequest buildRequest(UserProfile profile, String userInput) {
        return new AiRequest(
                List.of(
                        new AiMessage(AiRole.SYSTEM, SYSTEM_INSTRUCTION),
                        new AiMessage(AiRole.USER, describeContext(profile, userInput))),
                AiResponseFormat.JSON);
    }

    /**
     * 本轮请求的上下文：当前 Profile 内容 + 本轮用户输入。
     *
     * <p>Profile 以结构化数据给出，而不是把领域对象直接序列化：模型看到的是内容本身，
     * 不依赖领域类的字段形状。
     */
    private String describeContext(UserProfile profile, String userInput) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("currentUserProfile", ProfilePromptContext.currentContent(profile));
        payload.put("currentUserInput", userInput);

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new AiGatewayException("无法构造 AI 请求内容", exception);
        }
    }

    /**
     * 未提供（{@code null}）时保持原值；提供时交给 Aggregate 做整区替换，
     * 是否推进 revision 由 Aggregate 判断。
     */
    private static void applySection(List<String> proposed, Consumer<List<String>> aggregateUpdate) {
        if (proposed == null) {
            return;
        }
        aggregateUpdate.accept(proposed);
    }

    /**
     * 把模型提出的判断转成 Evidence。
     *
     * <p>可追溯信息由本层补齐，不采用模型的说法：来源固定为本轮用户输入，
     * 因此每条 Evidence 都指向真实存在的原始依据，模型无法构造无法追溯的条目。
     *
     * <p>{@code confirmed} 固定为 {@code false}：从用户输入中提取结论属于系统推断，
     * 不等于「用户确认的事实」（DOMAIN_MODEL.md §8.1）。{@code confidence} 留空——
     * 领域模型尚未规定其数值口径，不在这里臆造一个刻度。
     */
    private static Evidence toEvidence(String claim, String userInput) {
        return new Evidence(EvidenceSourceType.USER_INPUT, userInput, claim, null, false);
    }
}
