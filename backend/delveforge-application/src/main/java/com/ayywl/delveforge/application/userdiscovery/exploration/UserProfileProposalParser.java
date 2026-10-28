package com.ayywl.delveforge.application.userdiscovery.exploration;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiJsonObjectReader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 AI 返回的原始文本解析为 {@link UserProfileProposal}。
 *
 * <p>{@code AiGateway} 的契约是「返回未经解析的原始内容，由 Application 完成解析与校验」
 * （RULE-DOM-003），本类承担其中的解析部分。
 *
 * <p>只校验结构：整体必须是恰好一个 json 对象（见 {@link AiJsonObjectReader}）、
 * 字段缺失或为 {@code null} 表示本次不建议修改该区、出现的字段必须是字符串数组。
 * 内容本身是否合法（空值、超出领域规则等）仍由 Aggregate 判定，这里不重复领域规则。
 *
 * <p>结构不合法意味着模型没有按要求作答，属于外部 AI 能力的失败，
 * 因此统一抛 {@link AiGatewayException}，与调用失败走同一条失败路径。
 */
public final class UserProfileProposalParser {

    private static final String SECTION_INTERESTS = "interests";
    private static final String SECTION_BEHAVIORS = "behaviors";
    private static final String SECTION_PAIN_POINTS = "painPoints";
    private static final String SECTION_TECHNICAL_CAPABILITIES = "technicalCapabilities";
    private static final String SECTION_PROJECT_GOALS = "projectGoals";
    private static final String SECTION_CONSTRAINTS = "constraints";
    private static final String FIELD_EVIDENCE_CLAIMS = "evidenceClaims";

    private final AiJsonObjectReader reader;

    public UserProfileProposalParser(ObjectMapper objectMapper) {
        this.reader = new AiJsonObjectReader(objectMapper);
    }

    /**
     * @param rawAiOutput AI Gateway 返回的原始内容
     * @return 解析后的建议
     * @throws AiGatewayException 内容为空、不是合法 json 对象，或字段结构不符合约定
     */
    public UserProfileProposal parse(String rawAiOutput) {
        JsonNode root = reader.read(rawAiOutput);

        return new UserProfileProposal(
                section(root, SECTION_INTERESTS),
                section(root, SECTION_BEHAVIORS),
                section(root, SECTION_PAIN_POINTS),
                section(root, SECTION_TECHNICAL_CAPABILITIES),
                section(root, SECTION_PROJECT_GOALS),
                section(root, SECTION_CONSTRAINTS),
                section(root, FIELD_EVIDENCE_CLAIMS));
    }

    /** 整轮提取必须明确是否有新事实；无事实与修改/撤回/新增依据不能同时成立。 */
    public TurnProposal parseTurn(String rawAiOutput, int evidenceCount) {
        JsonNode root = reader.read(rawAiOutput);
        JsonNode facts = root.get("hasNewFacts");
        if (facts == null || !facts.isBoolean()) {
            throw new AiGatewayException("提取建议缺少布尔值 hasNewFacts");
        }
        UserProfileProposal proposal = parse(rawAiOutput);
        List<Integer> retracted = new ArrayList<>();
        JsonNode indices = root.get("retractedEvidenceIndices");
        if (indices != null) {
            if (!indices.isArray()) {
                throw new AiGatewayException("retractedEvidenceIndices 必须是数组");
            }
            for (JsonNode index : indices) {
                if (!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() < 0
                        || index.intValue() >= evidenceCount || retracted.contains(index.intValue())) {
                    throw new AiGatewayException("撤回依据必须唯一且指向当前 Evidence");
                }
                retracted.add(index.intValue());
            }
        }
        boolean changes = proposal.interests() != null || proposal.behaviors() != null
                || proposal.painPoints() != null || proposal.technicalCapabilities() != null
                || proposal.projectGoals() != null || proposal.constraints() != null
                || !retracted.isEmpty()
                || proposal.evidenceClaims() != null && !proposal.evidenceClaims().isEmpty();
        if (!facts.booleanValue() && changes) {
            throw new AiGatewayException("无新事实的提取不能修改画像或依据");
        }
        if (facts.booleanValue() && (proposal.evidenceClaims() == null || proposal.evidenceClaims().isEmpty())) {
            throw new AiGatewayException("新事实或纠正必须提供本轮输入支持的依据");
        }
        return new TurnProposal(proposal, List.copyOf(retracted));
    }

    public record TurnProposal(UserProfileProposal profile, List<Integer> retractedEvidenceIndices) {
    }

    /**
     * 读取一个字符串数组字段。
     *
     * @return 字段缺失或为 {@code null} 时返回 {@code null}，表示本次不建议修改该区
     */
    private static List<String> section(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new AiGatewayException("AI 返回的 " + field + " 不是数组");
        }

        List<String> values = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                throw new AiGatewayException("AI 返回的 " + field + " 含非字符串元素");
            }
            values.add(element.asText());
        }
        return List.copyOf(values);
    }
}
