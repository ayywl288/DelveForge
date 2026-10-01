package com.ayywl.delveforge.application.repositoryanalysis.scout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 Scout 响应的严格解析。
 *
 * <p>核心口径：**缺失不等于「没有」**。模型没有回答某一部分时拒绝整次侦察，
 * 而不是补一个默认值——那会把模型的沉默记成一条它从未做过的判断。
 */
class RepositoryScoutProposalParserTest {

    private final RepositoryScoutProposalParser parser =
            new RepositoryScoutProposalParser(new ObjectMapper());

    @Test
    void parsesThreeFocusAreas() {
        AiRepositoryScoutProposal proposal = parser.parse(ScoutFixtures.validResponse());

        assertEquals(3, proposal.focusAreas().size());
        assertEquals(List.of("对外接口", "领域模型", "服务实现"),
                proposal.focusAreas().stream().map(AiRepositoryScoutFocusArea::label).toList());
        assertEquals(List.of("RF-4"), refsOf(proposal, 0));
        assertEquals(List.of("RF-5", "RF-6"), refsOf(proposal, 1));
        assertEquals(List.of("RF-7"), refsOf(proposal, 2));
    }

    @Test
    void acceptsTheMaximumNumberOfFocusAreas() {
        AiRepositoryScoutProposal proposal = parser.parse(areas(6));

        assertEquals(6, proposal.focusAreas().size());
    }

    @Test
    void acceptsMultiDigitReferences() {
        AiRepositoryScoutProposal proposal = parser.parse("""
                {
                  "focusAreas": [
                    { "label": "a", "fileRefs": ["RF-12"] },
                    { "label": "b", "fileRefs": ["RF-1"] },
                    { "label": "c", "fileRefs": ["RF-3"] }
                  ]
                }
                """);

        assertEquals(List.of("RF-12"), refsOf(proposal, 0));
    }

    // ---------------------------------------------------------------------
    // 区域数量
    // ---------------------------------------------------------------------

    @Test
    void rejectsTooFewFocusAreas() {
        assertRejected(areas(2), "2");
    }

    @Test
    void rejectsTooManyFocusAreas() {
        assertRejected(areas(7), "7");
    }

    // ---------------------------------------------------------------------
    // 标签
    // ---------------------------------------------------------------------

    @Test
    void rejectsMissingBlankOrNonTextualLabel() {
        assertRejected("""
                { "focusAreas": [
                    { "fileRefs": ["RF-1"] },
                    { "label": "b", "fileRefs": ["RF-1"] },
                    { "label": "c", "fileRefs": ["RF-1"] } ] }
                """, null);
        assertRejected("""
                { "focusAreas": [
                    { "label": null, "fileRefs": ["RF-1"] },
                    { "label": "b", "fileRefs": ["RF-1"] },
                    { "label": "c", "fileRefs": ["RF-1"] } ] }
                """, null);
        assertRejected("""
                { "focusAreas": [
                    { "label": "   ", "fileRefs": ["RF-1"] },
                    { "label": "b", "fileRefs": ["RF-1"] },
                    { "label": "c", "fileRefs": ["RF-1"] } ] }
                """, null);
        assertRejected("""
                { "focusAreas": [
                    { "label": 7, "fileRefs": ["RF-1"] },
                    { "label": "b", "fileRefs": ["RF-1"] },
                    { "label": "c", "fileRefs": ["RF-1"] } ] }
                """, null);
    }

    // ---------------------------------------------------------------------
    // 引用组
    // ---------------------------------------------------------------------

    @Test
    void rejectsMissingEmptyOrNonArrayFileRefs() {
        assertRejected("""
                { "focusAreas": [
                    { "label": "a" },
                    { "label": "b", "fileRefs": ["RF-1"] },
                    { "label": "c", "fileRefs": ["RF-1"] } ] }
                """, null);
        assertRejected("""
                { "focusAreas": [
                    { "label": "a", "fileRefs": null },
                    { "label": "b", "fileRefs": ["RF-1"] },
                    { "label": "c", "fileRefs": ["RF-1"] } ] }
                """, null);
        assertRejected("""
                { "focusAreas": [
                    { "label": "a", "fileRefs": [] },
                    { "label": "b", "fileRefs": ["RF-1"] },
                    { "label": "c", "fileRefs": ["RF-1"] } ] }
                """, null);
        assertRejected("""
                { "focusAreas": [
                    { "label": "a", "fileRefs": "RF-1" },
                    { "label": "b", "fileRefs": ["RF-1"] },
                    { "label": "c", "fileRefs": ["RF-1"] } ] }
                """, null);
    }

    /**
     * 引用格式：{@code RF-} 加正整数。路径、其它前缀、前置零、空白一律拒绝。
     *
     * <p>尤其是路径：模型必须**指认**编号，不能复述路径——复述无法与真实路径可靠比对，
     * 而且会让模型输出有机会直接影响将来的读取。
     */
    @Test
    void rejectsMalformedReferences() {
        for (String malformed : List.of(
                "\"RF-0\"",
                "\"RF-01\"",
                "\"RF-\"",
                "\"RF-x\"",
                "\"RF-1x\"",
                "\"RF1\"",
                "\"1\"",
                "\"U-E1\"",
                "\"src/main/App.java\"",
                "\"\"",
                "7")) {
            assertRejected("""
                    { "focusAreas": [
                        { "label": "a", "fileRefs": [%s] },
                        { "label": "b", "fileRefs": ["RF-1"] },
                        { "label": "c", "fileRefs": ["RF-1"] } ] }
                    """.formatted(malformed), null);
        }
    }

    @Test
    void rejectsDuplicateReferenceInsideOneFocusArea() {
        AiGatewayException failure = assertThrows(AiGatewayException.class, () -> parser.parse("""
                { "focusAreas": [
                    { "label": "a", "fileRefs": ["RF-1", "RF-2", "RF-1"] },
                    { "label": "b", "fileRefs": ["RF-1"] },
                    { "label": "c", "fileRefs": ["RF-1"] } ] }
                """));

        assertEquals(true, failure.getMessage().contains("RF-1"),
                "错误信息应当指出重复的编号: " + failure.getMessage());
    }

    /**
     * 跨区域重复是**合法**的：同一个文件可以从几个不同角度看。
     *
     * <p>解析层不做去重、不做合并——那属于后续阶段，这里只拒绝自相矛盾的区域内重复。
     */
    @Test
    void allowsTheSameReferenceInDifferentFocusAreas() {
        AiRepositoryScoutProposal proposal = parser.parse("""
                { "focusAreas": [
                    { "label": "a", "fileRefs": ["RF-1"] },
                    { "label": "b", "fileRefs": ["RF-1", "RF-2"] },
                    { "label": "c", "fileRefs": ["RF-1"] } ] }
                """);

        assertEquals(List.of("RF-1"), refsOf(proposal, 0));
        assertEquals(List.of("RF-1", "RF-2"), refsOf(proposal, 1));
        assertEquals(List.of("RF-1"), refsOf(proposal, 2));
    }

    // ---------------------------------------------------------------------
    // 整体结构
    // ---------------------------------------------------------------------

    @Test
    void rejectsMissingOrNonArrayFocusAreas() {
        assertRejected("{}", null);
        assertRejected("{ \"focusAreas\": null }", null);
        assertRejected("{ \"focusAreas\": [] }", "0");
        assertRejected("{ \"focusAreas\": { \"label\": \"a\" } }", null);
    }

    @Test
    void rejectsNonObjectFocusAreaElements() {
        assertRejected("""
                { "focusAreas": [ "a", "b", "c" ] }
                """, null);
        assertRejected("""
                { "focusAreas": [ null, {}, {} ] }
                """, null);
    }

    @Test
    void rejectsContentThatIsNotExactlyOneJsonObject() {
        assertRejected("", null);
        assertRejected("   ", null);
        assertRejected("{not json", null);
        assertRejected("[ { \"label\": \"a\", \"fileRefs\": [\"RF-1\"] } ]", null);
    }

    /**
     * json 对象之后不得有多余内容。
     *
     * <p>用例刻意用**本身完全合法**的三区域响应做前缀：只有这样才证明失败是由尾随内容
     * 引起的。若前缀本身就非法，即使实现把尾随内容整个忽略，用例也照样通过——
     * 那种用例看起来在测这条规则，实际上什么都没测。
     */
    @Test
    void rejectsTrailingContentAfterAValidJsonObject() {
        String valid = ScoutFixtures.validResponse();

        assertRejected(valid + "\n以下是解释文字", null);
        assertRejected(valid + " " + valid, null);
        assertRejected(valid + "\n```", null);
    }

    /**
     * 约定之外的字段被忽略：模型多给一个字段不会让整次侦察失败。
     */
    @Test
    void ignoresFieldsOutsideTheContract() {
        AiRepositoryScoutProposal proposal = parser.parse("""
                {
                  "confidence": 0.9,
                  "focusAreas": [
                    { "label": "a", "fileRefs": ["RF-1"], "note": "多余字段" },
                    { "label": "b", "fileRefs": ["RF-1"] },
                    { "label": "c", "fileRefs": ["RF-1"] } ]
                }
                """);

        assertEquals(3, proposal.focusAreas().size());
    }

    @Test
    void rejectsNullObjectMapper() {
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryScoutProposalParser(null));
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    private static String areas(int count) {
        StringBuilder json = new StringBuilder("{ \"focusAreas\": [");
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{ \"label\": \"area-").append(index)
                    .append("\", \"fileRefs\": [\"RF-").append(index + 1).append("\"] }");
        }
        return json.append("] }").toString();
    }

    private static List<String> refsOf(AiRepositoryScoutProposal proposal, int areaIndex) {
        return proposal.focusAreas().get(areaIndex).fileRefs().stream()
                .map(reference -> reference.value())
                .toList();
    }

    /** 断言被拒绝；{@code mustMention} 非空时还要求错误信息包含它。 */
    private void assertRejected(String rawAiOutput, String mustMention) {
        AiGatewayException failure = assertThrows(AiGatewayException.class,
                () -> parser.parse(rawAiOutput), "应当被拒绝: " + rawAiOutput);

        if (mustMention != null) {
            assertEquals(true, failure.getMessage().contains(mustMention),
                    "错误信息应当包含 " + mustMention + "，实际为: " + failure.getMessage());
        }
    }
}
