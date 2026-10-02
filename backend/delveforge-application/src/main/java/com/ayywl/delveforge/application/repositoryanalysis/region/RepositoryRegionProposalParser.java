package com.ayywl.delveforge.application.repositoryanalysis.region;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiJsonObjectReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把 AI 返回的原始文本严格解析为 {@link AiRegionSelectionProposal}。
 *
 * <p>{@code AiGateway} 的契约是「返回未经解析的原始内容，由 Application 完成解析与校验」
 * （RULE-DOM-003），本类承担其中的解析部分。与 {@code RepositoryScoutProposalParser}
 * 同一个口径：缺失不等于「没有」，不做任何补全与规范化。
 *
 * <h2>只校验与模型约定的部分</h2>
 *
 * <p>本类检查的是**形状**：字段存在、是数组、非空、数量在允许范围内、引用格式正确、
 * 列表内不重复。它**不**检查引用是否真的存在于本次 Catalog 中——那需要本次调用的输入，
 * 属于 {@link RepositoryRegionProposalResolver}。
 *
 * <p>数量上限来自配置（ADR-0005 的导航守卫），因此由构造参数传入，而不是写死在这里。
 */
public final class RepositoryRegionProposalParser {

    private static final String FIELD_REGION_REFS = "regionRefs";

    private final AiJsonObjectReader reader;
    private final int maxSelectedRegions;

    /**
     * @param objectMapper       读取 json 的映射器，不得为 {@code null}
     * @param maxSelectedRegions 一次 Region Scout 最多可选多少个区域，必须大于 0
     */
    public RepositoryRegionProposalParser(ObjectMapper objectMapper, int maxSelectedRegions) {
        if (objectMapper == null) {
            throw new IllegalArgumentException(
                    "RepositoryRegionProposalParser 必须指定 objectMapper");
        }
        if (maxSelectedRegions < AiRegionSelectionProposal.MIN_SELECTED_REGIONS) {
            throw new IllegalArgumentException(
                    "一次区域选择的上限不能小于 " + AiRegionSelectionProposal.MIN_SELECTED_REGIONS
                            + ": " + maxSelectedRegions);
        }
        this.reader = new AiJsonObjectReader(objectMapper);
        this.maxSelectedRegions = maxSelectedRegions;
    }

    /**
     * @param rawAiOutput AI Gateway 返回的原始内容
     * @return 解析后的区域选择提议，顺序与模型给出的一致
     * @throws AiGatewayException 内容为空、不是合法 json 对象，或结构与约定不符
     */
    public AiRegionSelectionProposal parse(String rawAiOutput) {
        JsonNode regionRefs = reader.read(rawAiOutput).get(FIELD_REGION_REFS);
        if (regionRefs == null || regionRefs.isNull()) {
            throw new AiGatewayException("AI 返回缺少字段 " + FIELD_REGION_REFS);
        }
        if (!regionRefs.isArray()) {
            throw new AiGatewayException("AI 返回的 " + FIELD_REGION_REFS + " 不是数组");
        }
        if (regionRefs.size() < AiRegionSelectionProposal.MIN_SELECTED_REGIONS) {
            throw new AiGatewayException(
                    "一次区域选择至少需要 " + AiRegionSelectionProposal.MIN_SELECTED_REGIONS
                            + " 个区域，实际为: " + regionRefs.size());
        }
        if (regionRefs.size() > maxSelectedRegions) {
            throw new AiGatewayException(
                    "一次区域选择最多 " + maxSelectedRegions + " 个区域，实际为: "
                            + regionRefs.size());
        }

        List<RepositoryRegionReference> references = new ArrayList<>(regionRefs.size());
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode element : regionRefs) {
            if (element == null || !element.isTextual() || element.asText().isBlank()) {
                throw new AiGatewayException("AI 返回的 " + FIELD_REGION_REFS + " 含空值");
            }
            String text = element.asText();
            if (!RepositoryRegionReference.isWellFormed(text)) {
                throw new AiGatewayException(
                        "AI 返回的区域引用格式不正确: " + text
                                + "（期望形如 RR-3f1a9c02-1 的编号，而不是路径或裸序号）");
            }
            if (!seen.add(text)) {
                throw new AiGatewayException("AI 返回的区域引用重复: " + text);
            }
            references.add(new RepositoryRegionReference(text));
        }
        return new AiRegionSelectionProposal(references);
    }
}
