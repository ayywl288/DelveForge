package com.ayywl.delveforge.application.repositoryanalysis.extraction;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiJsonObjectReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 AI 返回的原始文本解析为 {@link RepositoryAnalysisProposal}。
 *
 * <p>{@code AiGateway} 的契约是「返回未经解析的原始内容，由 Application 完成解析与校验」
 * （RULE-DOM-003），本类承担其中的解析部分。
 *
 * <p>「恰好一个 json 对象、其后没有多余内容」这条契约与 User Discovery 的解析器共用 AI 边界
 * 所在的包中的同一个实现（{@link AiJsonObjectReader}），而不是各写一份：它是同一件事，
 * 两处实现迟早会分叉。
 *
 * <h2>与 User Profile 解析的差别：缺失不等于「不涉及」</h2>
 *
 * <p>User Profile 的提议是增量：某个字段缺失表示「本次不建议修改该区」，
 * 因为存在一个上一版 Profile 可以合并。
 *
 * <p>一次 Repository 分析没有上一版可以合并，字段缺失只说明模型没有回答那一部分。
 * 若把它当作「该区为空」，系统就会把模型的沉默记录成一条它从未做过的结论
 * （「没有风险」与「没有回答风险」是两件事）。因此这里要求所有字段必须出现，
 * 某个区确实没有内容时应当给出空数组。
 *
 * <h2>校验到哪一层</h2>
 *
 * <p>本类只校验结构与「给出的值是不是一个真实的值」：字段必须出现、类型必须正确、
 * 字符串不得为空白。内容层面的领域规则不在这一层判断——例如 purpose 能否被接受，
 * 仍由 {@code RepositoryProfile} Aggregate 决定（RULE-DOM-002）。
 *
 * <p>约定之外的字段被忽略：模型多给一个字段不会让整次分析失败，
 * 系统只读取契约内的字段。这是与 User Profile 解析器一致的选择。
 *
 * <p>不满足契约意味着模型没有按要求作答，属于外部 AI 能力的失败，
 * 因此统一抛 {@link AiGatewayException}，与调用失败走同一条失败路径。
 */
public final class RepositoryAnalysisProposalParser {

    private static final String FIELD_PURPOSE = "purpose";
    private static final String FIELD_TECH_STACK = "techStack";
    private static final String FIELD_MODULES = "modules";
    private static final String FIELD_CAPABILITIES = "capabilities";
    private static final String FIELD_REUSABLE_ASSETS = "reusableAssets";
    private static final String FIELD_LIMITATIONS = "limitations";
    private static final String FIELD_RISKS = "risks";
    private static final String FIELD_EVIDENCE = "evidence";

    private static final String EVIDENCE_FIELD_CLAIM = "claim";
    private static final String EVIDENCE_FIELD_SOURCE_REF = "sourceRef";

    private final AiJsonObjectReader reader;

    public RepositoryAnalysisProposalParser(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException(
                    "RepositoryAnalysisProposalParser 必须指定 objectMapper");
        }
        this.reader = new AiJsonObjectReader(objectMapper);
    }

    /**
     * @param rawAiOutput AI Gateway 返回的原始内容
     * @return 解析后的分析提议
     * @throws AiGatewayException 内容为空、不是合法 json 对象，或字段结构不符合约定
     */
    public RepositoryAnalysisProposal parse(String rawAiOutput) {
        JsonNode root = reader.read(rawAiOutput);

        return new RepositoryAnalysisProposal(
                requiredText(root, FIELD_PURPOSE),
                requiredSection(root, FIELD_TECH_STACK),
                requiredSection(root, FIELD_MODULES),
                requiredSection(root, FIELD_CAPABILITIES),
                requiredSection(root, FIELD_REUSABLE_ASSETS),
                requiredSection(root, FIELD_LIMITATIONS),
                requiredSection(root, FIELD_RISKS),
                evidence(root));
    }

    /** 读取一个必须出现、且必须是有内容的字符串字段。 */
    private static String requiredText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            throw new AiGatewayException("AI 返回缺少字段 " + field);
        }
        if (!node.isTextual()) {
            throw new AiGatewayException("AI 返回的 " + field + " 不是字符串");
        }
        String value = node.asText();
        if (value.isBlank()) {
            throw new AiGatewayException("AI 返回的 " + field + " 为空");
        }
        return value;
    }

    /**
     * 读取一个必须出现的字符串数组字段。
     *
     * <p>允许空数组（该区确实没有内容），但不允许缺少该字段。
     */
    private static List<String> requiredSection(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            throw new AiGatewayException("AI 返回缺少字段 " + field);
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
                throw new AiGatewayException("AI 返回的 " + field + " 含空值");
            }
            values.add(element.asText());
        }
        return List.copyOf(values);
    }

    /** 读取必须出现的 evidence 数组：每一项都必须给出判断与它在 Repository 中的位置。 */
    private static List<RepositoryEvidenceProposal> evidence(JsonNode root) {
        JsonNode node = root.get(FIELD_EVIDENCE);
        if (node == null || node.isNull()) {
            throw new AiGatewayException("AI 返回缺少字段 " + FIELD_EVIDENCE);
        }
        if (!node.isArray()) {
            throw new AiGatewayException("AI 返回的 " + FIELD_EVIDENCE + " 不是数组");
        }

        List<RepositoryEvidenceProposal> evidence = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            if (!element.isObject()) {
                throw new AiGatewayException("AI 返回的 evidence 含非对象元素");
            }
            evidence.add(new RepositoryEvidenceProposal(
                    requiredText(element, EVIDENCE_FIELD_CLAIM),
                    requiredText(element, EVIDENCE_FIELD_SOURCE_REF)));
        }
        return List.copyOf(evidence);
    }
}
