package com.ayywl.delveforge.application.userdiscovery;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiMessage;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.ai.AiResponseFormat;
import com.ayywl.delveforge.application.port.ai.AiRole;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
 * <h2>AI Proposes, Domain Decides</h2>
 *
 * <p>本 Use Case 不判断内容是否合法、不在本地计算 revision、不做状态转换：它只把模型
 * 提出的建议转发给 Aggregate 已公开的行为。状态限制（例如 CONFIRMED 不允许修改）、
 * revision 推进与 no-op 判定，全部继续由 {@code UserProfile} 决定。
 *
 * <h2>失败时不留下半更新</h2>
 *
 * <p>AI 调用与解析都发生在任何领域修改之前；领域修改发生在隔离的候选副本上，
 * 写入只发生在最后一步。因此无论是调用失败、模型输出无法解析，还是 Aggregate 拒绝
 * 某一条建议，都不会把半更新的 Profile 写进存储，也不会把从 Repository 读到的对象
 * 留在半更新状态。
 *
 * <p>本 Use Case 不包含：Sufficiency Assessment、自动生成下一问题、
 * {@code EXPLORING → REVIEWING} 转换、Review / Correct / Confirm 流程，
 * 也不保存对话记录。
 */
public class ExploreUserProfileUseCase {

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

    private final UserProfileRepository userProfileRepository;
    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final UserProfileProposalParser proposalParser;

    public ExploreUserProfileUseCase(UserProfileRepository userProfileRepository,
                                     AiGateway aiGateway,
                                     ObjectMapper objectMapper) {
        if (userProfileRepository == null) {
            throw new IllegalArgumentException("ExploreUserProfileUseCase 必须指定 userProfileRepository");
        }
        if (aiGateway == null) {
            throw new IllegalArgumentException("ExploreUserProfileUseCase 必须指定 aiGateway");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("ExploreUserProfileUseCase 必须指定 objectMapper");
        }
        this.userProfileRepository = userProfileRepository;
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.proposalParser = new UserProfileProposalParser(objectMapper);
    }

    /**
     * 处理一轮用户输入，并按 AI 建议更新 User Profile。
     *
     * @param userProfileId 目标 Profile
     * @param userInput     本轮用户自然语言输入
     * @return 更新后的 Profile
     * @throws IllegalArgumentException     用户输入为空
     * @throws UserProfileNotFoundException 目标 Profile 不存在
     * @throws AiGatewayException           AI 调用失败，或返回内容无法解析
     * @throws IllegalArgumentException     建议不满足 Aggregate 的内容约束
     * @throws IllegalStateException        当前状态不允许修改 Profile
     */
    public UserProfile explore(UserProfileId userProfileId, String userInput) {
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalArgumentException("User Discovery 的用户输入不能为空");
        }

        UserProfile profile = userProfileRepository.findById(userProfileId)
                .orElseThrow(() -> new UserProfileNotFoundException(userProfileId));

        UserProfileProposal proposal = proposalParser.parse(
                aiGateway.generate(buildRequest(profile, userInput)));

        // 先应用到隔离的候选副本：Aggregate 拒绝任何一条建议时，从 Repository 读到的
        // 那个对象不会被留下半更新状态，即使调用方随后重新读取同一实例也看到原状。
        UserProfile candidate = isolatedCandidateOf(profile);
        applyProposal(candidate, proposal, userInput);

        userProfileRepository.save(candidate);
        return candidate;
    }

    /**
     * 按当前状态构造一个隔离的候选副本。
     *
     * <p>使用 {@code reconstitute} 而不是逐字段复制：它就是「按已知状态重建一个
     * User Profile」，重建出的对象拥有完整的领域语义与规则，后续更新行为与普通
     * Aggregate 完全一致。
     */
    private static UserProfile isolatedCandidateOf(UserProfile profile) {
        return UserProfile.reconstitute(
                profile.id(),
                profile.status(),
                profile.revision(),
                profile.interests(),
                profile.behaviors(),
                profile.painPoints(),
                profile.technicalCapabilities(),
                profile.projectGoals(),
                profile.constraints(),
                profile.evidence());
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
        payload.put("currentUserProfile", currentContent(profile));
        payload.put("currentUserInput", userInput);

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new AiGatewayException("无法构造 AI 请求内容", exception);
        }
    }

    private static Map<String, Object> currentContent(UserProfile profile) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("interests", profile.interests());
        content.put("behaviors", profile.behaviors());
        content.put("painPoints", profile.painPoints());
        content.put("technicalCapabilities", profile.technicalCapabilities());
        content.put("projectGoals", profile.projectGoals());
        content.put("constraints", profile.constraints());
        return content;
    }

    private static void applyProposal(UserProfile profile,
                                      UserProfileProposal proposal,
                                      String userInput) {
        applySection(proposal.interests(), profile::updateInterests);
        applySection(proposal.behaviors(), profile::updateBehaviors);
        applySection(proposal.painPoints(), profile::updatePainPoints);
        applySection(proposal.technicalCapabilities(), profile::updateTechnicalCapabilities);
        applySection(proposal.projectGoals(), profile::updateProjectGoals);
        applySection(proposal.constraints(), profile::updateConstraints);

        if (proposal.evidenceClaims() != null) {
            for (String claim : proposal.evidenceClaims()) {
                profile.recordEvidence(toEvidence(claim, userInput));
            }
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
