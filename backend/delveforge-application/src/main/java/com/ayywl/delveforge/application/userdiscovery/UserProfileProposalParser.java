package com.ayywl.delveforge.application.userdiscovery;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 AI 返回的原始文本解析为 {@link UserProfileProposal}。
 *
 * <p>{@code AiGateway} 的契约是「返回未经解析的原始内容，由 Application 完成解析与校验」
 * （RULE-DOM-003），本类承担其中的解析部分。
 *
 * <p>只校验结构：整体必须是 json 对象、字段缺失或为 {@code null} 表示本次不建议修改该区、
 * 出现的字段必须是字符串数组。内容本身是否合法（空值、超出领域规则等）仍由 Aggregate
 * 判定，这里不重复领域规则。
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

    private final ObjectMapper objectMapper;

    public UserProfileProposalParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param rawAiOutput AI Gateway 返回的原始内容
     * @return 解析后的建议
     * @throws AiGatewayException 内容为空、不是合法 json 对象，或字段结构不符合约定
     */
    public UserProfileProposal parse(String rawAiOutput) {
        JsonNode root = readObject(rawAiOutput);

        return new UserProfileProposal(
                section(root, SECTION_INTERESTS),
                section(root, SECTION_BEHAVIORS),
                section(root, SECTION_PAIN_POINTS),
                section(root, SECTION_TECHNICAL_CAPABILITIES),
                section(root, SECTION_PROJECT_GOALS),
                section(root, SECTION_CONSTRAINTS),
                section(root, FIELD_EVIDENCE_CLAIMS));
    }

    /**
     * 读取整个输入，并要求它是一个 json 对象且没有多余内容。
     *
     * <p>{@code readTree(String)} 只读第一个 json 值就返回，后面的内容会被静默忽略，
     * 因此这里显式检查解析器是否已经到输入末尾：模型附带解释文字，或输出第二个 json
     * 值时都必须被拒绝，否则「只输出一个 json 对象」的契约就没有被真正校验。
     */
    private JsonNode readObject(String rawAiOutput) {
        if (rawAiOutput == null || rawAiOutput.isBlank()) {
            throw new AiGatewayException("AI 返回内容为空");
        }

        JsonNode root;
        try (JsonParser parser = objectMapper.getFactory().createParser(rawAiOutput)) {
            root = objectMapper.readTree(parser);
            if (parser.nextToken() != null) {
                throw new AiGatewayException("AI 返回内容在 json 对象之后还有多余内容");
            }
        } catch (JsonProcessingException exception) {
            throw new AiGatewayException("AI 返回内容不是合法 json", exception);
        } catch (IOException exception) {
            throw new AiGatewayException("无法读取 AI 返回内容", exception);
        }

        if (root == null || !root.isObject()) {
            throw new AiGatewayException("AI 返回内容不是 json 对象");
        }
        return root;
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
