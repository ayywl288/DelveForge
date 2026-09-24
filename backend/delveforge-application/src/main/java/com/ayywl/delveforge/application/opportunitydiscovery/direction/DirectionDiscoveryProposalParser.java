package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiJsonObjectReader;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.DirectionEvidenceLinkage;
import com.ayywl.delveforge.domain.direction.DirectionProposal;
import com.ayywl.delveforge.domain.direction.EvidenceReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 AI 返回的原始文本解析为 {@link DirectionProposal} 列表。
 *
 * <p>{@code AiGateway} 的契约是「返回未经解析的原始内容，由 Application 完成解析与校验」
 * （RULE-DOM-003），本类承担其中的解析部分。Provider 响应信封的解析不在这里——
 * 那属于 Infrastructure，本类的输入已经是模型返回的 content 本身。
 *
 * <p>「恰好一个 json 对象、其后没有多余内容」这条契约与 User Discovery、Repository
 * Analysis 的解析器共用 AI 边界所在包中的同一个实现（{@link AiJsonObjectReader}）。
 *
 * <h2>缺失不等于「没有」</h2>
 *
 * <p>与 Repository Analysis 的解析器同一个口径：一次 Direction Discovery 没有上一版
 * 可以合并，字段缺失只说明模型没有回答那一部分。把它当作空值，系统就会把模型的沉默
 * 记录成一条它从未做过的结论。因此所有字段都必须出现；某个区确实没有内容时应当给出
 * 空数组。
 *
 * <p>{@code candidateAssetIds} 是例外中的例外：它不得为空，因为 Task 1 已经确定
 * 「每个方向至少标识一个 Candidate Software Asset」（INV-D10）。这里只是照该契约
 * 检查结构，不判断这些资产在业务上是否恰当。
 *
 * <h2>AI 只能引用本次输入里给过的东西</h2>
 *
 * <p>每条 Evidence 引用都必须存在于 {@link DirectionDiscoveryInputs} 中，每个
 * Software Asset 都必须是本次提供给模型的资产之一。未知引用一律失败，而不是静默忽略
 * 或丢弃那一条——那会让「模型引用了不存在的东西」变成一条看起来正常的结果。
 *
 * <p>反过来，本类不判断「这条 Evidence 在业务上是否真的足以支撑这个判断」，也不判断
 * 两个方向之间是否足够不同：那些是 {@code ProductDirectionDiscoveryService} 的领域校验。
 *
 * <p>约定之外的字段被忽略：模型多给一个字段不会让整次发现失败，系统只读取契约内的
 * 字段。这也是与其它两个解析器一致的选择。
 *
 * <p>不满足契约意味着模型没有按要求作答，属于外部 AI 能力的失败，
 * 因此统一抛 {@link AiGatewayException}，与调用失败走同一条失败路径。
 */
public final class DirectionDiscoveryProposalParser {

    private static final String FIELD_DIRECTIONS = "directions";

    private static final String FIELD_TITLE = "title";
    private static final String FIELD_PROBLEM = "problem";
    private static final String FIELD_TARGET_PRODUCT = "targetProduct";
    private static final String FIELD_USER_FIT = "userFit";
    private static final String FIELD_CANDIDATE_ASSET_IDS = "candidateAssetIds";
    private static final String FIELD_DIFFERENTIATION = "differentiation";
    private static final String FIELD_TECHNICAL_VALUE = "technicalValue";
    private static final String FIELD_ESTIMATED_COMPLEXITY = "estimatedComplexity";
    private static final String FIELD_RISKS = "risks";
    private static final String FIELD_EVIDENCE = "evidence";

    private static final String FIELD_USER_NEED = "userNeed";
    private static final String FIELD_EVIDENCE_USER_FIT = "userFit";
    private static final String FIELD_REUSABLE_CAPABILITY = "reusableCapability";

    private final AiJsonObjectReader reader;

    public DirectionDiscoveryProposalParser(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException(
                    "DirectionDiscoveryProposalParser 必须指定 objectMapper");
        }
        this.reader = new AiJsonObjectReader(objectMapper);
    }

    /**
     * @param rawAiOutput AI Gateway 返回的原始内容
     * @param inputs      本次提供给模型的输入，用于核对模型引用的是不是它真正拿到过的东西
     * @return 解析后的候选方向提议，顺序与模型给出的一致
     * @throws AiGatewayException 内容为空、不是合法 json 对象，或结构与约定不符
     */
    public List<DirectionProposal> parse(String rawAiOutput, DirectionDiscoveryInputs inputs) {
        if (inputs == null) {
            throw new IllegalArgumentException(
                    "DirectionDiscoveryProposalParser 必须指定 inputs");
        }

        JsonNode directions = reader.read(rawAiOutput).get(FIELD_DIRECTIONS);
        if (directions == null || directions.isNull()) {
            throw new AiGatewayException("AI 返回缺少字段 " + FIELD_DIRECTIONS);
        }
        if (!directions.isArray()) {
            throw new AiGatewayException("AI 返回的 " + FIELD_DIRECTIONS + " 不是数组");
        }

        List<DirectionProposal> proposals = new ArrayList<>(directions.size());
        for (JsonNode direction : directions) {
            if (!direction.isObject()) {
                throw new AiGatewayException(
                        "AI 返回的 " + FIELD_DIRECTIONS + " 含非对象元素");
            }
            proposals.add(direction(direction, inputs));
        }
        return List.copyOf(proposals);
    }

    private static DirectionProposal direction(JsonNode node, DirectionDiscoveryInputs inputs) {
        return new DirectionProposal(
                requiredText(node, FIELD_TITLE),
                requiredText(node, FIELD_PROBLEM),
                requiredText(node, FIELD_TARGET_PRODUCT),
                requiredText(node, FIELD_USER_FIT),
                candidateAssetIds(node, inputs),
                requiredText(node, FIELD_DIFFERENTIATION),
                requiredText(node, FIELD_TECHNICAL_VALUE),
                requiredText(node, FIELD_ESTIMATED_COMPLEXITY),
                requiredSection(node, FIELD_RISKS),
                evidenceLinkage(node, inputs));
    }

    /** 读取一个必须出现、且必须是有内容的字符串字段。 */
    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new AiGatewayException("AI 返回缺少字段 " + field);
        }
        if (!value.isTextual()) {
            throw new AiGatewayException("AI 返回的 " + field + " 不是字符串");
        }
        if (value.asText().isBlank()) {
            throw new AiGatewayException("AI 返回的 " + field + " 为空");
        }
        return value.asText();
    }

    /**
     * 读取一个必须出现、必须非空、且每一项都必须是本次提供过的资产的数组。
     *
     * <p>「至少一个」是 Task 1 已经确定的契约（INV-D10）；「必须是提供过的资产」是 AI
     * 边界上的核对——模型可以指出用哪个资产，但不能指出一个本次没有给它、或根本不存在的
     * 资产 id。
     *
     * <p>这里证明的是「模型没有凭空造出一个资产 id」。至于「这些资产是否真的适合这个方向」，
     * 或者说 INV-D10 在领域上的完整语义，仍然由 {@code ProductDirectionDiscoveryService}
     * 结合 Repository Profile 判定。
     */
    private static List<SoftwareAssetId> candidateAssetIds(JsonNode node,
                                                           DirectionDiscoveryInputs inputs) {
        JsonNode value = node.get(FIELD_CANDIDATE_ASSET_IDS);
        if (value == null || value.isNull()) {
            throw new AiGatewayException("AI 返回缺少字段 " + FIELD_CANDIDATE_ASSET_IDS);
        }
        if (!value.isArray()) {
            throw new AiGatewayException("AI 返回的 " + FIELD_CANDIDATE_ASSET_IDS + " 不是数组");
        }
        if (value.isEmpty()) {
            throw new AiGatewayException(
                    "AI 返回的 " + FIELD_CANDIDATE_ASSET_IDS + " 为空");
        }

        List<SoftwareAssetId> assetIds = new ArrayList<>(value.size());
        for (JsonNode element : value) {
            if (!element.isTextual() || element.asText().isBlank()) {
                throw new AiGatewayException(
                        "AI 返回的 " + FIELD_CANDIDATE_ASSET_IDS + " 含空值");
            }
            SoftwareAssetId assetId = new SoftwareAssetId(element.asText());
            if (!inputs.containsAsset(assetId)) {
                throw new AiGatewayException(
                        "AI 提出候选方向时引用了本次没有提供的 Software Asset: " + assetId.value());
            }
            assetIds.add(assetId);
        }
        return List.copyOf(assetIds);
    }

    /**
     * 读取一个必须出现的字符串数组字段。
     *
     * <p>允许空数组（该区确实没有内容），但不允许缺少该字段。
     */
    private static List<String> requiredSection(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new AiGatewayException("AI 返回缺少字段 " + field);
        }
        if (!value.isArray()) {
            throw new AiGatewayException("AI 返回的 " + field + " 不是数组");
        }

        List<String> values = new ArrayList<>(value.size());
        for (JsonNode element : value) {
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

    /**
     * 读取必须出现的 evidence 对象：三个关键判断槽位都要在，且各自只能引用本次提供过的依据。
     *
     * <p>槽位本身可以为空数组——那是「模型认为这条判断没有可引用的依据」这一业务事实，
     * 是否可接受由后续的领域校验判断，本层不替它决定。
     */
    private static DirectionEvidenceLinkage evidenceLinkage(JsonNode node,
                                                            DirectionDiscoveryInputs inputs) {
        JsonNode evidence = node.get(FIELD_EVIDENCE);
        if (evidence == null || evidence.isNull()) {
            throw new AiGatewayException("AI 返回缺少字段 " + FIELD_EVIDENCE);
        }
        if (!evidence.isObject()) {
            throw new AiGatewayException("AI 返回的 " + FIELD_EVIDENCE + " 不是对象");
        }

        return new DirectionEvidenceLinkage(
                references(evidence, FIELD_USER_NEED, inputs),
                references(evidence, FIELD_EVIDENCE_USER_FIT, inputs),
                references(evidence, FIELD_REUSABLE_CAPABILITY, inputs));
    }

    /** 读取一个必须出现的引用数组，并核对每一条都是本次提供给模型的依据。 */
    private static List<EvidenceReference> references(JsonNode evidence,
                                                      String field,
                                                      DirectionDiscoveryInputs inputs) {
        JsonNode value = evidence.get(field);
        if (value == null || value.isNull()) {
            throw new AiGatewayException("AI 返回的 " + FIELD_EVIDENCE + " 缺少 " + field);
        }
        if (!value.isArray()) {
            throw new AiGatewayException(
                    "AI 返回的 " + FIELD_EVIDENCE + "." + field + " 不是数组");
        }

        List<EvidenceReference> references = new ArrayList<>(value.size());
        for (JsonNode element : value) {
            if (!element.isTextual() || element.asText().isBlank()) {
                throw new AiGatewayException(
                        "AI 返回的 " + FIELD_EVIDENCE + "." + field + " 含空值");
            }
            EvidenceReference reference = new EvidenceReference(element.asText());
            if (!inputs.containsEvidence(reference)) {
                throw new AiGatewayException(
                        "AI 引用了本次输入中不存在的 Evidence reference: " + reference.value());
            }
            references.add(reference);
        }
        return List.copyOf(references);
    }
}
