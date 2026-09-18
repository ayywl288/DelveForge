package com.ayywl.delveforge.application.userdiscovery;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 AI 返回的原始文本解析为 {@link ProfileSufficiencyProposal}。
 *
 * <p>除结构之外还校验**结果是否自洽**：模型声明「已足够」却又列出缺失信息、
 * 或声明「不足」却说不出缺什么、也提不出下一个问题，都属于自相矛盾的输出，
 * 不能被当成合法的评估结果接受。
 *
 * <p>内容是否真的足够不由本类判断，也不由 Application 判断——那只影响「要不要尝试
 * EXPLORING → REVIEWING」，而那条转移是否被允许由 Domain 决定。
 */
public final class ProfileSufficiencyProposalParser {

    private static final String FIELD_SUFFICIENT = "sufficient";
    private static final String FIELD_MISSING_AREAS = "missingAreas";
    private static final String FIELD_NEXT_QUESTION = "nextQuestion";

    private final AiJsonObjectReader reader;

    public ProfileSufficiencyProposalParser(ObjectMapper objectMapper) {
        this.reader = new AiJsonObjectReader(objectMapper);
    }

    /**
     * @param rawAiOutput AI Gateway 返回的原始内容
     * @return 解析后的建议
     * @throws AiGatewayException 内容为空、不是合法 json 对象、缺少必要字段，
     *                            或 sufficient 与其余字段互相矛盾
     */
    public ProfileSufficiencyProposal parse(String rawAiOutput) {
        JsonNode root = reader.read(rawAiOutput);

        JsonNode sufficientNode = root.get(FIELD_SUFFICIENT);
        if (sufficientNode == null || sufficientNode.isNull()) {
            throw new AiGatewayException("AI 返回内容缺少 " + FIELD_SUFFICIENT);
        }
        if (!sufficientNode.isBoolean()) {
            throw new AiGatewayException("AI 返回的 " + FIELD_SUFFICIENT + " 不是布尔值");
        }

        List<String> missingAreas = textArray(root, FIELD_MISSING_AREAS);
        String nextQuestion = blankToNull(text(root, FIELD_NEXT_QUESTION));

        if (sufficientNode.booleanValue()) {
            if (missingAreas != null && !missingAreas.isEmpty()) {
                throw new AiGatewayException("结果矛盾：声明信息已足够，却给出了缺失信息");
            }
            if (nextQuestion != null) {
                throw new AiGatewayException("结果矛盾：声明信息已足够，却给出了下一个问题");
            }
            return new ProfileSufficiencyProposal(true, List.of(), null);
        }

        if (missingAreas == null || missingAreas.isEmpty()) {
            throw new AiGatewayException("结果矛盾：声明信息不足，却没有给出缺失信息");
        }
        if (nextQuestion == null) {
            throw new AiGatewayException("结果矛盾：声明信息不足，却没有给出下一个问题");
        }
        return new ProfileSufficiencyProposal(false, missingAreas, nextQuestion);
    }

    /**
     * @return 字段缺失或为 {@code null} 时返回 {@code null}
     */
    private static List<String> textArray(JsonNode root, String field) {
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
            if (element.asText().isBlank()) {
                throw new AiGatewayException("AI 返回的 " + field + " 含空条目");
            }
            values.add(element.asText());
        }
        return List.copyOf(values);
    }

    /**
     * @return 字段缺失、为 {@code null} 或不是字符串时返回 {@code null}
     */
    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new AiGatewayException("AI 返回的 " + field + " 不是字符串");
        }
        return node.asText();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
