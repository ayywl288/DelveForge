package com.ayywl.delveforge.application.userdiscovery.sufficiency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证充分性评估输出的解析：结构与结果自洽性都要满足。
 *
 * <p>模型声明「已足够」却列出缺失信息、或声明「不足」却说不出缺什么也提不出问题，
 * 都属于自相矛盾的输出，必须被拒绝而不是被当成合法结论。
 */
class ProfileSufficiencyProposalParserTest {

    private final ProfileSufficiencyProposalParser parser =
            new ProfileSufficiencyProposalParser(new ObjectMapper());

    @Test
    void parsesInsufficientResult() {
        ProfileSufficiencyProposal proposal = parser.parse("""
                {
                  "sufficient": false,
                  "missingAreas": ["缺少真实行为", "缺少技术能力"],
                  "nextQuestion": "你最近有没有反复做过、觉得麻烦的事情？"
                }
                """);

        assertFalse(proposal.sufficient());
        assertEquals(List.of("缺少真实行为", "缺少技术能力"), proposal.missingAreas());
        assertEquals("你最近有没有反复做过、觉得麻烦的事情？", proposal.nextQuestion());
    }

    @Test
    void parsesSufficientResultWithEmptyFields() {
        ProfileSufficiencyProposal proposal = parser.parse(
                "{\"sufficient\":true,\"missingAreas\":[],\"nextQuestion\":\"\"}");

        assertTrue(proposal.sufficient());
        assertEquals(List.of(), proposal.missingAreas());
        assertNull(proposal.nextQuestion());
    }

    @Test
    void parsesSufficientResultWithoutOptionalFields() {
        ProfileSufficiencyProposal proposal = parser.parse("{\"sufficient\":true}");

        assertTrue(proposal.sufficient());
        assertEquals(List.of(), proposal.missingAreas());
        assertNull(proposal.nextQuestion());
    }

    /**
     * 模型即使输出状态字段也不会被解析进来——AI 无法表达、更无法设置 UserProfileStatus。
     */
    @Test
    void ignoresUnknownFieldsIncludingStatus() {
        ProfileSufficiencyProposal proposal = parser.parse(
                "{\"sufficient\":true,\"status\":\"CONFIRMED\",\"revision\":99}");

        assertTrue(proposal.sufficient());
    }

    @Test
    void rejectsMissingSufficientField() {
        assertThrows(AiGatewayException.class,
                () -> parser.parse("{\"missingAreas\":[\"缺少行为\"],\"nextQuestion\":\"问什么？\"}"));
    }

    @Test
    void rejectsNonBooleanSufficientField() {
        assertThrows(AiGatewayException.class, () -> parser.parse("{\"sufficient\":\"true\"}"));
        assertThrows(AiGatewayException.class, () -> parser.parse("{\"sufficient\":1}"));
    }

    @Test
    void rejectsSufficientResultThatListsMissingAreas() {
        assertThrows(AiGatewayException.class, () -> parser.parse(
                "{\"sufficient\":true,\"missingAreas\":[\"缺少行为\"],\"nextQuestion\":\"\"}"));
    }

    @Test
    void rejectsSufficientResultThatAsksNextQuestion() {
        assertThrows(AiGatewayException.class, () -> parser.parse(
                "{\"sufficient\":true,\"missingAreas\":[],\"nextQuestion\":\"你平时喜欢什么？\"}"));
    }

    @Test
    void rejectsInsufficientResultWithoutMissingAreas() {
        assertThrows(AiGatewayException.class, () -> parser.parse(
                "{\"sufficient\":false,\"missingAreas\":[],\"nextQuestion\":\"你平时喜欢什么？\"}"));
    }

    @Test
    void rejectsInsufficientResultWithoutNextQuestion() {
        assertThrows(AiGatewayException.class, () -> parser.parse(
                "{\"sufficient\":false,\"missingAreas\":[\"缺少行为\"]}"));
        assertThrows(AiGatewayException.class, () -> parser.parse(
                "{\"sufficient\":false,\"missingAreas\":[\"缺少行为\"],\"nextQuestion\":\"  \"}"));
    }

    @Test
    void rejectsBlankOrNonTextEntriesInMissingAreas() {
        assertThrows(AiGatewayException.class, () -> parser.parse(
                "{\"sufficient\":false,\"missingAreas\":[\"  \"],\"nextQuestion\":\"问什么？\"}"));
        assertThrows(AiGatewayException.class, () -> parser.parse(
                "{\"sufficient\":false,\"missingAreas\":[1],\"nextQuestion\":\"问什么？\"}"));
    }

    @Test
    void rejectsMissingAreasThatIsNotAnArray() {
        assertThrows(AiGatewayException.class, () -> parser.parse(
                "{\"sufficient\":false,\"missingAreas\":\"缺少行为\",\"nextQuestion\":\"问什么？\"}"));
    }

    @Test
    void rejectsTrailingContentAfterJsonObject() {
        assertThrows(AiGatewayException.class,
                () -> parser.parse("{\"sufficient\":true} 以上就是我的判断"));
    }

    @Test
    void rejectsContentThatIsNotAJsonObject() {
        assertThrows(AiGatewayException.class, () -> parser.parse("这不是 json"));
        assertThrows(AiGatewayException.class, () -> parser.parse("[true]"));
        assertThrows(AiGatewayException.class, () -> parser.parse("   "));
    }
}
