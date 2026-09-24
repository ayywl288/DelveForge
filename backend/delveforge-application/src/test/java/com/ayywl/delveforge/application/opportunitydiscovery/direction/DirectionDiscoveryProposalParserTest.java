package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.DirectionEvidenceLinkage;
import com.ayywl.delveforge.domain.direction.DirectionProposal;
import com.ayywl.delveforge.domain.direction.EvidenceReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 验证 Direction Discovery 的业务输出解析（RULE-DOM-003 的「Parse / Validate」一步）。
 *
 * <p>本类只依赖 Jackson 与 {@link DirectionDiscoveryInputs}，不需要 Spring、数据库或
 * 真实 LLM（AGENTS.md §10.1、§10.6）。
 *
 * <p>解析的输入是模型返回的 content 本身；Provider 响应信封不属于这一层。
 */
class DirectionDiscoveryProposalParserTest {

    private static final DirectionDiscoveryInputs INPUTS = DirectionDiscoveryFixtures.inputs();

    private final DirectionDiscoveryProposalParser parser =
            new DirectionDiscoveryProposalParser(new ObjectMapper());

    // ---------------------------------------------------------------------
    // 基线：负向用例都从这一份出发
    // ---------------------------------------------------------------------

    /**
     * 未做任何替换的响应必须能成功解析。
     *
     * <p>下面所有负向用例都是「从这一份出发、只改目标字段」。如果基线本身不合法，
     * 它们就会因为别的原因抛异常，测不到真正想验证的那条规则——本 Task 的第一版测试
     * 正是这样漏掉了「空 candidateAssetIds 泄漏错误异常类型」。
     */
    @Test
    void baselineResponseParsesSuccessfully() {
        assertEquals(1, parser.parse(response(Map.of()), INPUTS).size());
    }

    // ---------------------------------------------------------------------
    // 每个字段都能解析
    // ---------------------------------------------------------------------

    @Test
    void parsesEveryFieldOfADirection() {
        DirectionProposal proposal = parseSingleDirection(Map.of());

        assertEquals("个人记账 + 报表导出", proposal.title());
        assertEquals("现有记账工具缺少可导出的报表", proposal.problem());
        assertEquals("单用户桌面记账工具 + 报表导出", proposal.targetProduct());
        assertEquals("用户已经在用记账工具，且技术栈匹配", proposal.userFit());
        assertEquals(List.of(new SoftwareAssetId("software-asset-1")), proposal.candidateAssetIds());
        assertEquals("相比现有工具增加了自定义报表", proposal.differentiation());
        assertEquals("可复用现有报表模块的渲染能力", proposal.technicalValue());
        assertEquals("中等：主要在导出与模板部分", proposal.estimatedComplexity());
        assertEquals(List.of("模板格式复杂度可能超预期"), proposal.risks());
    }

    /**
     * 三个关键判断槽位各自解析成引用，互不合并：同一条 Evidence 可以同时支撑多个判断。
     */
    @Test
    void parsesEvidenceLinkageForEachKeyJudgement() {
        DirectionProposal proposal = parseSingleDirection(Map.of(
                "evidence", """
                        {
                          "userNeed": ["U-E2", "U-E1"],
                          "userFit": ["U-E1"],
                          "reusableCapability": ["R1-E2", "R2-E1"]
                        }"""));

        DirectionEvidenceLinkage linkage = proposal.evidenceLinkage();

        assertEquals(
                List.of(new EvidenceReference("U-E2"), new EvidenceReference("U-E1")),
                linkage.userNeed(), "顺序按模型给出的原样保留");
        assertEquals(List.of(new EvidenceReference("U-E1")), linkage.userFit());
        assertEquals(
                List.of(new EvidenceReference("R1-E2"), new EvidenceReference("R2-E1")),
                linkage.reusableCapability());
    }

    @Test
    void parsesEveryDirectionInOrder() {
        List<DirectionProposal> proposals = parser.parse(
                "{\"directions\": [" + direction(Map.of("title", "\"第一个\""))
                        + ", " + direction(Map.of("title", "\"第二个\"")) + "]}",
                INPUTS);

        assertEquals(2, proposals.size());
        assertEquals("第一个", proposals.get(0).title());
        assertEquals("第二个", proposals.get(1).title());
    }

    @Test
    void parsesMultipleCandidateAssetsInOrder() {
        DirectionProposal proposal = parseSingleDirection(Map.of(
                "candidateAssetIds", "[\"software-asset-2\", \"software-asset-1\"]"));

        assertEquals(
                List.of(new SoftwareAssetId("software-asset-2"), new SoftwareAssetId("software-asset-1")),
                proposal.candidateAssetIds());
    }

    /**
     * 空的 evidence 槽位是合法输出：它是「模型认为这条判断没有可引用的依据」这一业务事实，
     * 是否可接受由下一阶段的领域校验判断，本层不替它决定。
     */
    @Test
    void acceptsEmptyEvidenceSlots() {
        DirectionProposal proposal = parseSingleDirection(Map.of(
                "evidence", """
                        { "userNeed": [], "userFit": [], "reusableCapability": [] }"""));

        DirectionEvidenceLinkage linkage = proposal.evidenceLinkage();

        assertEquals(List.of(), linkage.userNeed());
        assertEquals(List.of(), linkage.userFit());
        assertEquals(List.of(), linkage.reusableCapability());
    }

    /** risks 可以为空（Task 1 已确定的语义），但字段本身必须出现。 */
    @Test
    void acceptsEmptyRisks() {
        assertEquals(List.of(), parseSingleDirection(Map.of("risks", "[]")).risks());
    }

    // ---------------------------------------------------------------------
    // Evidence 引用必须存在于本次输入
    // ---------------------------------------------------------------------

    @Test
    void acceptsReferencesThatExistInTheInputs() {
        DirectionProposal proposal = parseSingleDirection(Map.of(
                "evidence", """
                        {
                          "userNeed": ["U-E1", "U-E2"],
                          "userFit": ["R1-E1"],
                          "reusableCapability": ["R1-E2", "R2-E1"]
                        }"""));

        assertEquals(2, proposal.evidenceLinkage().userNeed().size());
    }

    /**
     * 未知引用必须失败，而不是被静默丢弃：丢掉那一条会让「模型引用了不存在的东西」
     * 变成一份看起来正常的结果。
     */
    @Test
    void rejectsUnknownEvidenceReference() {
        assertThrows(AiGatewayException.class, () -> parser.parse(
                responseWithEvidence(Map.of("userNeed", "[\"U-E9\"]")), INPUTS),
                "输入里只有 U-E1 / U-E2");

        assertThrows(AiGatewayException.class, () -> parser.parse(
                responseWithEvidence(Map.of("reusableCapability", "[\"R2-E2\"]")), INPUTS),
                "第 2 个 Profile 只有一条依据");

        assertThrows(AiGatewayException.class, () -> parser.parse(
                responseWithEvidence(Map.of("userFit", "[\"R3-E1\"]")), INPUTS),
                "本次只有 R1 / R2");
    }

    /** 引用是精确匹配：大小写不同、或带空格，都不是同一个引用。 */
    @Test
    void requiresExactEvidenceReferenceMatch() {
        assertThrows(AiGatewayException.class, () -> parser.parse(
                responseWithEvidence(Map.of("userNeed", "[\"u-e1\"]")), INPUTS));

        assertThrows(AiGatewayException.class, () -> parser.parse(
                responseWithEvidence(Map.of("userNeed", "[\"U-E1 \"]")), INPUTS));
    }

    // ---------------------------------------------------------------------
    // candidateAssetIds
    // ---------------------------------------------------------------------

    /** 只能标识本次提供过的资产：模型可以指出用哪个，但不能凭空造一个 id。 */
    @Test
    void rejectsCandidateAssetThatWasNotProvided() {
        assertThrows(AiGatewayException.class, () -> parser.parse(
                response(Map.of("candidateAssetIds", "[\"software-asset-999\"]")), INPUTS));
    }

    /** INV-D10：每个方向至少标识一个候选资产。 */
    @Test
    void rejectsEmptyCandidateAssets() {
        assertThrows(AiGatewayException.class, () -> parser.parse(
                response(Map.of("candidateAssetIds", "[]")), INPUTS));
    }

    // ---------------------------------------------------------------------
    // 结构不合法
    // ---------------------------------------------------------------------

    @Test
    void rejectsMalformedJson() {
        assertThrows(AiGatewayException.class, () -> parser.parse("{ not json", INPUTS));
    }

    /** 根节点必须是对象：契约是 {@code {"directions": [...]}}，不是裸数组。 */
    @Test
    void rejectsJsonThatIsNotAnObject() {
        assertThrows(AiGatewayException.class, () -> parser.parse("[ ]", INPUTS));
    }

    /** json 之后还有解释文字属于「模型没有按要求作答」，不能被静默忽略。 */
    @Test
    void rejectsJsonFollowedByTrailingContent() {
        assertThrows(AiGatewayException.class, () -> parser.parse(
                "{\"directions\": []}\n以上是本次分析结果。", INPUTS));
    }

    @Test
    void rejectsMissingDirectionsField() {
        assertThrows(AiGatewayException.class, () -> parser.parse("{}", INPUTS));
    }

    @Test
    void rejectsDirectionsThatIsNotAnArray() {
        assertThrows(AiGatewayException.class, () -> parser.parse("{\"directions\": {}}", INPUTS));
    }

    @Test
    void rejectsDirectionThatIsNotAnObject() {
        assertThrows(AiGatewayException.class,
                () -> parser.parse("{\"directions\": [\"...\"]}", INPUTS));
    }

    /** 字段缺失不等于「该区为空」：模型没有回答的部分不能被当成它做过的结论。 */
    @Test
    void rejectsMissingRequiredField() {
        assertThrows(AiGatewayException.class,
                () -> parser.parse(response(omitting("problem")), INPUTS));
        assertThrows(AiGatewayException.class,
                () -> parser.parse(response(omitting("risks")), INPUTS));
    }

    @Test
    void rejectsNullRequiredField() {
        assertThrows(AiGatewayException.class,
                () -> parser.parse(response(Map.of("title", "null")), INPUTS));
    }

    @Test
    void rejectsBlankRequiredField() {
        assertThrows(AiGatewayException.class,
                () -> parser.parse(response(Map.of("title", "\"  \"")), INPUTS));
    }

    @Test
    void rejectsWrongJsonType() {
        assertThrows(AiGatewayException.class,
                () -> parser.parse(response(Map.of("title", "[\"不是字符串\"]")), INPUTS));

        assertThrows(AiGatewayException.class,
                () -> parser.parse(response(Map.of("risks", "\"不是数组\"")), INPUTS));

        assertThrows(AiGatewayException.class,
                () -> parser.parse(response(Map.of("candidateAssetIds", "\"software-asset-1\"")), INPUTS));
    }

    // ---------------------------------------------------------------------
    // Evidence linkage 结构
    // ---------------------------------------------------------------------

    @Test
    void rejectsMissingEvidenceLinkage() {
        assertThrows(AiGatewayException.class,
                () -> parser.parse(response(omitting("evidence")), INPUTS));
    }

    /** 三个槽位缺一不可：少一个说明模型没有回答那条关键判断。 */
    @Test
    void rejectsIncompleteEvidenceLinkage() {
        assertThrows(AiGatewayException.class, () -> parser.parse(
                responseWithoutEvidenceSlot("reusableCapability"), INPUTS));

        assertThrows(AiGatewayException.class, () -> parser.parse(
                responseWithoutEvidenceSlot("userNeed"), INPUTS));
    }

    @Test
    void rejectsEvidenceLinkageThatIsNotAnObject() {
        assertThrows(AiGatewayException.class,
                () -> parser.parse(response(Map.of("evidence", "[\"U-E1\"]")), INPUTS));
    }

    @Test
    void rejectsEvidenceSlotThatIsNotAnArray() {
        assertThrows(AiGatewayException.class,
                () -> parser.parse(responseWithEvidence(Map.of("userNeed", "\"U-E1\"")), INPUTS));
    }

    @Test
    void rejectsBlankEvidenceReference() {
        assertThrows(AiGatewayException.class,
                () -> parser.parse(responseWithEvidence(Map.of("userNeed", "[\"  \"]")), INPUTS));
    }

    // ---------------------------------------------------------------------
    // 模型无权决定的字段
    // ---------------------------------------------------------------------

    /**
     * 模型多给的 {@code id} / {@code status} / {@code userProfileId} /
     * {@code confidence} / {@code confirmed} 被忽略：契约里没有它们的位置，
     * 解析结果也不体现它们。
     *
     * <p>换句话说，模型即使写上 {@code "status": "SELECTED"}，也不可能通过解析让任何
     * 东西变成已选择——状态只能由 {@code ProductDirection} Aggregate 在用户明确选择后
     * 改变（INV-D07）。
     */
    @Test
    void ignoresFieldsTheModelHasNoAuthorityToDecide() {
        String withUnexpectedFields = """
                {
                  "directions": [
                    {
                      "id": "direction-1",
                      "status": "SELECTED",
                      "userProfileId": "另一个用户",
                      "userProfileRevision": 99,
                      "repositoryProfileIds": ["repository-profile-9"],
                      "confidence": 0.99,
                      "confirmed": true,
                      "title": "个人记账 + 报表导出",
                      "problem": "现有记账工具缺少可导出的报表",
                      "targetProduct": "单用户桌面记账工具 + 报表导出",
                      "userFit": "用户已经在用记账工具，且技术栈匹配",
                      "candidateAssetIds": ["software-asset-1"],
                      "differentiation": "相比现有工具增加了自定义报表",
                      "technicalValue": "可复用现有报表模块的渲染能力",
                      "estimatedComplexity": "中等：主要在导出与模板部分",
                      "risks": ["模板格式复杂度可能超预期"],
                      "evidence": {
                        "userNeed": ["U-E1"],
                        "userFit": ["U-E1"],
                        "reusableCapability": ["U-E1"]
                      }
                    }
                  ]
                }
                """;

        DirectionProposal proposal = parser.parse(withUnexpectedFields, INPUTS).get(0);

        assertEquals(
                parseSingleDirection(Map.of()),
                proposal,
                "多给的字段不影响解析结果：Proposal 上根本没有承载它们的位置");
    }

    // ---------------------------------------------------------------------
    // 依赖
    // ---------------------------------------------------------------------

    @Test
    void rejectsMissingDependencies() {
        assertThrows(IllegalArgumentException.class,
                () -> new DirectionDiscoveryProposalParser(null));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("{\"directions\": []}", null));
    }

    // ---------------------------------------------------------------------
    // 模型输出构造
    // ---------------------------------------------------------------------

    /**
     * 从一份完整的合法响应出发，只替换或省略 {@code overrides} 指定的字段。
     *
     * <p>负向用例必须走这条路：直接把一个残缺的方向对象丢给解析器，会因为缺少外层
     * {@code directions} 就已经失败，验证不到真正想验证的那个字段。
     */
    private String response(Map<String, String> overrides) {
        return "{\"directions\": [" + direction(overrides) + "]}";
    }

    /** 与 {@link #response} 相同，只替换 evidence 内部的槽位。 */
    private String responseWithEvidence(Map<String, String> evidenceOverrides) {
        return response(Map.of("evidence", evidence(evidenceOverrides)));
    }

    /** 与 {@link #response} 相同，但指定的 evidence 槽位整个不出现。 */
    private String responseWithoutEvidenceSlot(String omittedSlot) {
        Map<String, String> without = new LinkedHashMap<>();
        without.put(omittedSlot, null);
        return response(Map.of("evidence", evidence(without)));
    }

    /** 一份「这些字段整个不出现」的覆盖表；{@code Map.of} 不接受 null 值，因此单独构造。 */
    private static Map<String, String> omitting(String... fieldNames) {
        Map<String, String> omitted = new LinkedHashMap<>();
        for (String fieldName : fieldNames) {
            omitted.put(fieldName, null);
        }
        return omitted;
    }

    private static Map<String, String> fields(Map<String, String> overrides) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", "\"个人记账 + 报表导出\"");
        fields.put("problem", "\"现有记账工具缺少可导出的报表\"");
        fields.put("targetProduct", "\"单用户桌面记账工具 + 报表导出\"");
        fields.put("userFit", "\"用户已经在用记账工具，且技术栈匹配\"");
        fields.put("candidateAssetIds", "[\"software-asset-1\"]");
        fields.put("differentiation", "\"相比现有工具增加了自定义报表\"");
        fields.put("technicalValue", "\"可复用现有报表模块的渲染能力\"");
        fields.put("estimatedComplexity", "\"中等：主要在导出与模板部分\"");
        fields.put("risks", "[\"模板格式复杂度可能超预期\"]");
        fields.put("evidence", """
                {
                  "userNeed": ["U-E1"],
                  "userFit": ["U-E1"],
                  "reusableCapability": ["U-E1"]
                }""");
        fields.putAll(overrides);
        return fields;
    }

    /** 依据字段表拼出一个 json 对象；值为 {@code null} 的字段整个不出现。 */
    private static String jsonObject(Map<String, String> fields) {
        return fields.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> "  \"" + entry.getKey() + "\": " + entry.getValue())
                .collect(Collectors.joining(",\n", "{\n", "\n}"));
    }

    /** 依据字段表拼出一个方向对象；{@code overrides} 中的 {@code null} 表示省略该字段。 */
    private static String direction(Map<String, String> overrides) {
        return jsonObject(fields(overrides));
    }

    /** 三个槽位齐全的 evidence 对象，可覆盖其中某些取值；值为 {@code null} 表示省略该槽位。 */
    private static String evidence(Map<String, String> overrides) {
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("userNeed", "[\"U-E1\"]");
        evidence.put("userFit", "[\"U-E1\"]");
        evidence.put("reusableCapability", "[\"U-E1\"]");
        evidence.putAll(overrides);
        return jsonObject(evidence);
    }

    private DirectionProposal parseSingleDirection(Map<String, String> overrides) {
        List<DirectionProposal> proposals = parser.parse(response(overrides), INPUTS);
        assertEquals(1, proposals.size());
        return proposals.get(0);
    }
}
