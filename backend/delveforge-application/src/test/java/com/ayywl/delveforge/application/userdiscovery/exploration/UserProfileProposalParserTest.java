package com.ayywl.delveforge.application.userdiscovery.exploration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 AI 输出的解析：只接受「恰好一个、结构符合约定的 json 对象」。
 *
 * <p>结构不合法意味着模型没有按要求作答，属于外部 AI 能力失败，
 * 因此统一是 {@link AiGatewayException}。
 */
class UserProfileProposalParserTest {

    private final UserProfileProposalParser parser =
            new UserProfileProposalParser(new ObjectMapper());

    @Test
    void parsesFullProposal() {
        UserProfileProposal proposal = parser.parse("""
                {
                  "interests": ["兴趣 A", "兴趣 B"],
                  "behaviors": ["行为"],
                  "painPoints": ["痛点"],
                  "technicalCapabilities": ["能力"],
                  "projectGoals": ["目标"],
                  "constraints": ["约束"],
                  "evidenceClaims": ["判断"]
                }
                """);

        assertEquals(List.of("兴趣 A", "兴趣 B"), proposal.interests());
        assertEquals(List.of("行为"), proposal.behaviors());
        assertEquals(List.of("痛点"), proposal.painPoints());
        assertEquals(List.of("能力"), proposal.technicalCapabilities());
        assertEquals(List.of("目标"), proposal.projectGoals());
        assertEquals(List.of("约束"), proposal.constraints());
        assertEquals(List.of("判断"), proposal.evidenceClaims());
    }

    @Test
    void treatsMissingOrNullSectionAsNotProposed() {
        UserProfileProposal proposal = parser.parse(
                "{\"interests\":[\"兴趣\"],\"behaviors\":null}");

        assertEquals(List.of("兴趣"), proposal.interests());
        assertNull(proposal.behaviors(), "字段缺失表示本次不建议修改该区");
        assertNull(proposal.painPoints());
        assertNull(proposal.evidenceClaims());
    }

    /**
     * 回归：json 对象之后的解释文字必须被拒绝，不能只读第一个值就接受。
     */
    @Test
    void rejectsTrailingTextAfterJsonObject() {
        assertThrows(AiGatewayException.class,
                () -> parser.parse("{\"interests\":[\"changed\"]} THIS IS NOT JSON"));
    }

    /**
     * 回归：第二个 json 值同样属于契约外的内容。
     */
    @Test
    void rejectsSecondJsonValue() {
        assertThrows(AiGatewayException.class,
                () -> parser.parse("{\"interests\":[\"a\"]}{\"interests\":[\"b\"]}"));
    }

    @Test
    void rejectsEmptyContent() {
        assertThrows(AiGatewayException.class, () -> parser.parse("   "));
        assertThrows(AiGatewayException.class, () -> parser.parse(null));
    }

    @Test
    void rejectsContentThatIsNotJson() {
        assertThrows(AiGatewayException.class, () -> parser.parse("这不是 json"));
    }

    @Test
    void rejectsJsonThatIsNotAnObject() {
        assertThrows(AiGatewayException.class, () -> parser.parse("[\"兴趣\"]"));
        assertThrows(AiGatewayException.class, () -> parser.parse("\"兴趣\""));
    }

    @Test
    void rejectsSectionThatIsNotAnArray() {
        assertThrows(AiGatewayException.class, () -> parser.parse("{\"interests\":\"兴趣\"}"));
    }

    @Test
    void rejectsSectionElementThatIsNotText() {
        assertThrows(AiGatewayException.class, () -> parser.parse("{\"interests\":[1]}"));
        assertThrows(AiGatewayException.class, () -> parser.parse("{\"interests\":[null]}"));
    }
}
