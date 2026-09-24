package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiMessage;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.ai.AiResponseFormat;
import com.ayywl.delveforge.application.port.ai.AiRole;
import com.ayywl.delveforge.domain.direction.DirectionProposal;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把已确认的用户侧与资产侧输入交给 AI，得到结构化的候选方向提议。
 *
 * <pre>
 * Confirmed UserProfile + RepositoryProfile 1..N
 *         ↓
 * AI Gateway → 原始模型输出 → 解析 / 校验 → DirectionProposal[]
 * </pre>
 *
 * <p>本类只做这一步：
 *
 * <pre>
 * 不访问 Workspace，不重新读取或分析 Repository
 * 不读取其他 Aggregate 的存储
 * 不创建、不保存 ProductDirection
 * </pre>
 *
 * <p>它的协作者只有 {@link AiGateway} 与解析器，因此一次失败不可能留下领域或持久化
 * 副作用：失败只以异常结束。提议如何变成合法的 Product Direction，由下一 Task 的
 * {@code ProductDirectionDiscoveryService} 与调用方决定。
 *
 * <h2>AI Proposes, Domain Decides</h2>
 *
 * <p>模型提出的方向不构成合法领域状态：它到这里为止只是 {@link DirectionProposal}。
 * 模型可以指出「哪条已有依据支撑了这条判断」，但那条依据的 {@code confidence} 与
 * {@code confirmed} 不由它给出，{@code ProductDirectionId}、{@code userProfileId}、
 * {@code userProfileRevision}、{@code repositoryProfileIds} 与 {@code status} 也都不
 * 出现在提议里——那些是系统才有资格决定的事实。
 *
 * <p>同样地，本类不判断方向的数量与差异是否满足 M2 的要求（「3–5 个明显不同」只是
 * Prompt 里的一项要求，不是本层能验证的事实）。Prompt 里写了要求，不等于模型一定满足。
 *
 * <h2>引用只在本次调用中有效</h2>
 *
 * <p>给模型的每条 Evidence 都带一个本次调用内的引用（{@code U-E1}、{@code R1-E2}…），
 * 模型只能引用其中的条目。引用如何分配见 {@link DirectionDiscoveryInputs#of}；
 * 解析回来的引用由 {@link DirectionDiscoveryProposalParser} 对照同一份输入校验，
 * 未知引用一律失败。这些引用不是 Evidence 的持久身份。
 */
public final class DirectionDiscoveryExtraction {

    /**
     * 系统指令。
     *
     * <p>要求模型只输出 json：DeepSeek 的 JSON 模式要求提示词中出现 "json" 字样，
     * 同时这也让模型清楚不要附带解释文本。
     */
    private static final String SYSTEM_INSTRUCTION = """
            你是 DelveForge 的产品方向发现组件。你的任务不是泛泛地推荐「适合程序员做的新项目」,
            而是根据当前用户的真实兴趣、行为、痛点、技术能力、项目目标和约束,
            结合已有 Repository Profile 中已经确认的软件能力与可复用资产,
            提出可以基于这些已有软件资产演化得到、对这个用户有个人相关性、与原项目有差异、
            并且现实可实施的产品方向。

            只输出一个 json 对象, 不要输出解释、Markdown 代码块或任何其他文字。格式如下:

            {
              "directions": [
                {
                  "title": "...",
                  "problem": "...",
                  "targetProduct": "...",
                  "userFit": "...",
                  "candidateAssetIds": ["..."],
                  "differentiation": "...",
                  "technicalValue": "...",
                  "estimatedComplexity": "...",
                  "risks": ["..."],
                  "evidence": {
                    "userNeed": ["..."],
                    "userFit": ["..."],
                    "reusableCapability": ["..."]
                  }
                }
              ]
            }

            规则:
            - 每个字段都必须出现。risks 确实没有内容时给出空数组, 不要省略字段。
            - candidateAssetIds 至少给出一个, 且只能使用输入里给出的 Software Asset id。
            - 提出 3 到 5 个明显不同的方向: 它们在目标产品、解决的问题或所依赖的资产上应当有实质差别,
              不要把一个方向换几种说法重复输出。
            - 只依据输入中已确认的事实。不要臆测输入里没有的信息, 也不要为了填满字段而编造内容。
            - 不要输出 id、status、userProfileId、userProfileRevision、repositoryProfileIds,
              也不要为任何依据给出 confidence 或 confirmed: 这些不是由你决定的。
            - evidence 的每条引用只能使用输入中该条 Evidence 旁边的 reference 值,
              不要自己创造引用, 也不要引用输入里没有出现过的 reference。
            - evidence.userNeed 写支撑「这个用户有什么需求 / 要解决什么问题」的判断所依据的引用;
              evidence.userFit 写支撑「为什么这个方向适合这个用户」的判断所依据的引用;
              evidence.reusableCapability 写支撑「可复用哪些软件能力 / 为什么选这些候选资产」的判断所依据的引用。
              一条 Evidence 可以同时出现在多个槽位里。
            """;

    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final DirectionDiscoveryProposalParser proposalParser;

    public DirectionDiscoveryExtraction(AiGateway aiGateway, ObjectMapper objectMapper) {
        if (aiGateway == null) {
            throw new IllegalArgumentException(
                    "DirectionDiscoveryExtraction 必须指定 aiGateway");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException(
                    "DirectionDiscoveryExtraction 必须指定 objectMapper");
        }
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.proposalParser = new DirectionDiscoveryProposalParser(objectMapper);
    }

    /**
     * 依据给定的输入提取候选方向提议。
     *
     * <p>AI 调用与解析都发生在同一步内，两者任一失败都以异常结束，不返回半成品。
     *
     * @param inputs 本次发现的可信输入，不得为 {@code null}；构造时已保证至少有一个
     *               Repository Profile
     * @return 模型提出、并已通过结构与引用校验的候选方向提议，顺序与模型给出的一致
     * @throws IllegalArgumentException inputs 为 {@code null}
     * @throws AiGatewayException       AI 调用失败，或返回内容不满足约定
     */
    public List<DirectionProposal> extract(DirectionDiscoveryInputs inputs) {
        if (inputs == null) {
            throw new IllegalArgumentException(
                    "DirectionDiscoveryExtraction 必须指定 inputs");
        }
        return proposalParser.parse(aiGateway.generate(buildRequest(inputs)), inputs);
    }

    private AiRequest buildRequest(DirectionDiscoveryInputs inputs) {
        return new AiRequest(
                List.of(
                        new AiMessage(AiRole.SYSTEM, SYSTEM_INSTRUCTION),
                        new AiMessage(AiRole.USER, describeContext(inputs))),
                AiResponseFormat.JSON);
    }

    /**
     * 请求内容：用户侧已确认的事实 + 资产侧已确认的事实 + 两边已有的 Evidence。
     *
     * <p>只发 Profile 里已经确认的内容，不发 {@code status} 与 {@code revision}（那是
     * 调用方决定并记录的事实，不需要模型参与），也不发宿主机路径、源码或其他 Aggregate 的数据。
     *
     * <p>每条 Evidence 都带上它在本次调用中的引用，模型据此填写 {@code evidence} 槽位。
     */
    private String describeContext(DirectionDiscoveryInputs inputs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("confirmedUserProfile", describeUserProfile(inputs));

        List<Map<String, Object>> profiles = new ArrayList<>(inputs.repositoryProfiles().size());
        for (int index = 0; index < inputs.repositoryProfiles().size(); index++) {
            profiles.add(describeRepositoryProfile(inputs, index));
        }
        payload.put("repositoryProfiles", profiles);

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new AiGatewayException("无法构造 AI 请求内容", exception);
        }
    }

    private static Map<String, Object> describeUserProfile(DirectionDiscoveryInputs inputs) {
        DirectionDiscoveryInputs.UserProfileSnapshot profile = inputs.userProfileSnapshot();

        Map<String, Object> described = new LinkedHashMap<>();
        described.put("interests", profile.interests());
        described.put("behaviors", profile.behaviors());
        described.put("painPoints", profile.painPoints());
        described.put("technicalCapabilities", profile.technicalCapabilities());
        described.put("projectGoals", profile.projectGoals());
        described.put("constraints", profile.constraints());
        described.put("evidence", describeEvidence(profile.evidence()));
        return described;
    }

    private static Map<String, Object> describeRepositoryProfile(DirectionDiscoveryInputs inputs,
                                                                 int index) {
        RepositoryProfile profile = inputs.repositoryProfiles().get(index);

        Map<String, Object> described = new LinkedHashMap<>();
        described.put("reference", "R" + (index + 1));
        described.put("repositoryProfileId", profile.id().value());
        described.put("assetId", profile.assetId().value());
        described.put("analyzedRevision", profile.analyzedRevision());
        described.put("purpose", profile.purpose());
        described.put("techStack", profile.techStack());
        described.put("modules", profile.modules());
        described.put("capabilities", profile.capabilities());
        described.put("reusableAssets", profile.reusableAssets());
        described.put("limitations", profile.limitations());
        described.put("risks", profile.risks());
        described.put("evidence", describeEvidence(inputs.evidenceOf(index)));
        return described;
    }

    /**
     * 把一组被本次调用引用的 Evidence 渲染成 Prompt 里的条目。
     *
     * <p>每条都带上模型要用的引用，模型据此填写方向里的 {@code evidence} 槽位。
     *
     * <p>给模型的是 Evidence 自身的值（来源、位置、判断），不包含 {@code confidence}
     * 与 {@code confirmed}：模型既不决定这两项，也不需要看到系统对它们的当前取值。
     */
    private static List<Map<String, Object>> describeEvidence(
            List<DirectionDiscoveryInputs.ReferencedEvidence> referenced) {

        List<Map<String, Object>> entries = new ArrayList<>(referenced.size());
        for (DirectionDiscoveryInputs.ReferencedEvidence item : referenced) {
            Evidence evidence = item.evidence();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("reference", item.reference().value());
            entry.put("sourceType", evidence.sourceType().name());
            entry.put("sourceRef", evidence.sourceRef());
            entry.put("claim", evidence.claim());
            entries.add(entry);
        }
        return entries;
    }
}
