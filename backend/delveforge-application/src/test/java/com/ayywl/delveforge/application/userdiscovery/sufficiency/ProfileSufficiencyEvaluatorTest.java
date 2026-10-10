package com.ayywl.delveforge.application.userdiscovery.sufficiency;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileSufficiencyEvaluatorTest {

    private static final String SUFFICIENT =
            "{\"sufficient\":true,\"missingAreas\":[],\"nextQuestion\":\"\"}";

    @Test
    void sixFilledSectionsWithoutUserEvidenceCannotPassEvenIfAiSaysYes() {
        UserProfile profile = profile(List.of("集卡活动麻烦"), "准备实习", List.of());
        SufficiencyAssessment result = evaluator(request -> SUFFICIENT).evaluate(profile);

        assertFalse(result.sufficient());
        assertEquals(List.of("缺少可核对的用户输入依据"), result.missingAreas());
        assertEquals(UserProfileStatus.EXPLORING, profile.status());
        assertEquals(1, profile.revision());
    }

    @Test
    void vagueNeedIsAssessedAgainstOriginalEvidenceInsteadOfSectionCounts() {
        String input = "守望先锋、Java和C++；集卡麻烦、周期太长；大三准备实习，时间有限";
        UserProfile profile = profile(List.of("集卡麻烦、周期太长"), "准备实习", evidence(input));
        SufficiencyAssessment result = evaluator(request -> {
            String context = request.messages().get(1).content();
            assertTrue(context.contains("currentEvidence"));
            assertTrue(context.contains(input));
            String instruction = request.messages().getFirst().content();
            assertTrue(instruction.contains("不是六区填满"));
            assertTrue(instruction.contains("具体环节、场景和期待改善"));
            return """
                    {"sufficient":false,"missingAreas":["集卡具体哪个环节需要改善尚不明确"],
                     "nextQuestion":"最近一次集卡活动，哪个环节最让你觉得麻烦？"}
                    """;
        }).evaluate(profile);

        assertFalse(result.sufficient());
        assertTrue(result.nextQuestion().contains("哪个环节"));
        assertEquals(UserProfileStatus.EXPLORING, result.profileStatus());
    }

    @Test
    void concreteInterestOrCareerGoalsCanPassWithoutPainPoints() {
        for (String goal : List.of("为每周战队练习做一个个人战绩趋势工具",
                "六周完成Java后端作品，展示REST接口、SQLite持久化和自动化测试以准备实习")) {
            UserProfile profile = profile(List.of(), goal, evidence(goal + "，每周五小时，会写Java小项目"));
            SufficiencyAssessment result = evaluator(request -> SUFFICIENT).evaluate(profile);

            assertTrue(result.sufficient());
            assertEquals(List.of(), result.missingAreas());
            assertEquals(UserProfileStatus.EXPLORING, profile.status(), "最低条件不等于结束探索");
        }
    }

    @Test
    void missingGoalCapabilityOrConstraintsCannotBeFilledByAiVerdict() {
        UserProfile profile = UserProfile.create(new UserProfileId("empty"));
        profile.recordEvidence(evidence("喜欢编程").getFirst());
        SufficiencyAssessment result = evaluator(request -> SUFFICIENT).evaluate(profile);

        assertFalse(result.sufficient());
        assertEquals(3, result.missingAreas().size());
        assertEquals("你希望这个项目帮助你完成什么成果，或展示什么能力？", result.nextQuestion());
    }

    @Test
    void repositoryEvidenceAloneDoesNotEstablishPersonalNeed() {
        UserProfile profile = profile(List.of(), "学习接口开发", List.of(
                new Evidence(EvidenceSourceType.REPOSITORY, "README.md", "项目是Java服务", null, true)));
        assertFalse(evaluator(request -> SUFFICIENT).evaluate(profile).sufficient());
    }

    private ProfileSufficiencyEvaluator evaluator(AiGateway gateway) {
        return new ProfileSufficiencyEvaluator(gateway, new ObjectMapper());
    }

    private UserProfile profile(List<String> painPoints, String goal, List<Evidence> evidence) {
        return UserProfile.reconstitute(new UserProfileId("profile"), UserProfileStatus.EXPLORING, 1,
                List.of("守望先锋、Java后端"), List.of("每周战队练习"), painPoints,
                List.of("完成过Java接口项目"), List.of(goal), List.of("每周五小时"), evidence);
    }

    private List<Evidence> evidence(String input) {
        return List.of(new Evidence(EvidenceSourceType.USER_INPUT, input, input, null, false));
    }
}
