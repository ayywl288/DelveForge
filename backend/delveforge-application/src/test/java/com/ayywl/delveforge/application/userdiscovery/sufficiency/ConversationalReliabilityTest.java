package com.ayywl.delveforge.application.userdiscovery.sufficiency;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.persistence.DiscoveryConversationRepository.Memory;
import com.ayywl.delveforge.application.port.persistence.DiscoveryConversationRepository.Topic;
import com.ayywl.delveforge.application.userdiscovery.conversation.DiscoveryReadiness;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationalReliabilityTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void explicitQuestionOnlyPresentsTheQuestionAndDoesNotReanswerPreviousInput() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var evaluator = new ProfileSufficiencyEvaluator(request -> {
            calls.incrementAndGet();
            assertEquals(AiRequest.Thinking.DISABLED, request.thinking());
            assertEquals(AiRequest.DiscoveryStage.DIALOGUE, request.discoveryStage());
            try {
                var payload = json.readTree(request.messages().getLast().content());
                assertEquals("QUESTION", payload.path("operation").asText());
                assertFalse(payload.has("currentUserInput"), "操作指令不能伪装成本轮用户原话");
            } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
            }
            return """
                    {"sufficient":true,"missingAreas":[],"assistantReply":"当然可以，聊生活完全可以。",
                     "nextQuestion":"你平时空闲时会做什么？","questionArea":"behaviors",
                     "previousQuestionState":"NONE","nextAction":"ASK"}
                    """;
        }, json);
        var result = evaluator.evaluateRemembered(profile(), "请提出下一个有价值的问题。", memory(null),
                null, true);
        assertEquals("", result.assistantReply());
        assertEquals("你平时空闲时会做什么？", result.nextQuestion());
        assertEquals(1, calls.get());
    }

    @Test
    void questionWithoutAUsableNextQuestionKeepsItsUsefulExplanation() {
        var evaluator = new ProfileSufficiencyEvaluator(request -> """
                {"sufficient":true,"missingAreas":[],"assistantReply":"可以先想一想，之后再接着聊。",
                 "nextQuestion":null,"previousQuestionState":"NONE","nextAction":"PAUSE"}
                """, json);
        var result = evaluator.evaluateRemembered(profile(), "请提出下一个有价值的问题。", memory(null),
                null, true);
        assertEquals("可以先想一想，之后再接着聊。", result.assistantReply());
        assertNull(result.nextQuestion());
    }

    @Test
    void dialoguePromptExamplesAreCompleteJsonAcceptedByTheActualPlannerParser() {
        var evaluator = new ProfileSufficiencyEvaluator(request -> {
            assertEquals(AiRequest.Thinking.DISABLED, request.thinking());
            assertEquals(AiRequest.DiscoveryStage.DIALOGUE, request.discoveryStage());
            String system = request.messages().getFirst().content();
            var matcher = java.util.regex.Pattern.compile("(?m)^\\s*(\\{[^\\n]+\\})\\s*$").matcher(system);
            List<String> examples = new ArrayList<>();
            while (matcher.find()) {
                String example = matcher.group(1);
                new ProfileSufficiencyProposalParser(json).parseGuided(example);
                examples.add(example);
            }
            assertTrue(examples.size() >= 3, "普通回复、待批和独立提问都需要完整 json 示例");
            return examples.getFirst();
        }, json);
        evaluator.evaluateRemembered(profile(), "不能问生活中的问题吗", memory(null), null, false);
    }

    @Test
    void conflictingControlsDoNotDiscardUsefulReplyOrSpendAnotherCall() {
        var result = evaluate("""
                {"sufficient":true,"missingAreas":["旧缺口"],"assistantReply":"可以从阅读场景继续。",
                 "previousQuestionState":"NONE","nextAction":"PAUSE","nextQuestion":"你最近想整理什么资料？"}
                """, memory(null), new DiscoveryReadiness(true, List.of()), false);
        assertTrue(result.sufficient());
        assertEquals(List.of(), result.missingAreas());
        assertEquals("可以从阅读场景继续。", result.assistantReply());
        assertEquals(DialogueAction.ASK, result.nextAction());
        assertEquals("exploration", result.questionArea());
    }

    @Test
    void unreliablePreviousStatePreservesPendingAndDropsCompetingQuestion() {
        var result = evaluate("""
                {"sufficient":true,"missingAreas":[],"assistantReply":"这个问题用于了解使用场景。",
                 "previousQuestionState":"NONE","nextAction":"ASK","nextQuestion":"能投入多久？",
                 "questionArea":"constraints","deferredAreas":["behaviors"]}
                """, memory("最近怎样整理资料？"), null, false);
        assertEquals("ASKED", result.previousQuestionState());
        assertEquals(DialogueAction.KEEP_PENDING, result.nextAction());
        assertNull(result.nextQuestion());
        assertFalse(result.deferredAreas().contains("behaviors"));
    }

    @Test
    void pendingProposalNormalizesActionButKeepsNaturalAcknowledgementAndPivot() {
        var result = evaluate("""
                {"sufficient":true,"missingAreas":[],"assistantReply":"可以，我们先放下 Coding Agent，探索别的方向。",
                 "previousQuestionState":"REPLACED","nextAction":"ASK","nextQuestion":"想做哪类工具？",
                 "questionArea":"projectGoals","deferredAreas":[]}
                """, memory("Coding Agent 准备多久完成？"), null, true);
        assertEquals(DialogueAction.PAUSE, result.nextAction());
        assertEquals("REPLACED", result.previousQuestionState());
        assertTrue(result.assistantReply().contains("放下 Coding Agent"));
        assertNull(result.nextQuestion());
        assertFalse(result.reviewInvitation());
    }

    @Test
    void repeatedOptionalQuestionAndInventedInvitationAreOmittedWithoutFailingReply() {
        var result = evaluate("""
                {"sufficient":false,"missingAreas":["还想了解场景"],"assistantReply":"可以先想想日常用的软件。",
                 "previousQuestionState":"NONE","nextAction":"ASK","nextQuestion":"你最近用什么工具？",
                 "questionArea":"behaviors","deferredAreas":[],"reviewInvitation":true}
                """, new Memory(List.of(), List.of("你最近用什么工具？"), List.of(), List.of(), null, true),
                new DiscoveryReadiness(false, List.of("还想了解场景")), false);
        assertNull(result.nextQuestion());
        assertEquals(DialogueAction.PAUSE, result.nextAction());
        assertFalse(result.reviewInvitation());
        assertEquals("可以先想想日常用的软件。", result.assistantReply());
    }

    @Test
    void unknownTimeDoesNotRequireInventingAConstraintForReview() {
        var result = evaluate("""
                {"sufficient":true,"missingAreas":[],"assistantReply":"投入时间可以稍后再定。",
                 "previousQuestionState":"REPLACED","nextAction":"PAUSE","nextQuestion":null,
                 "questionArea":null,"deferredAreas":[]}
                """, new Memory(List.of(), List.of(), List.of(), List.of(), null, false), null, false);
        assertTrue(result.sufficient());
        assertTrue(result.reviewInvitation());
        assertEquals(List.of(), profile().constraints());
    }

    @Test
    void completeMalformedOutputFailsOnceWithoutHiddenRepairCall() {
        AtomicInteger calls = new AtomicInteger();
        var evaluator = new ProfileSufficiencyEvaluator(request -> {
            calls.incrementAndGet();
            return "{\"assistantReply\":\"截断";
        }, json);
        assertThrows(AiGatewayException.class, () -> evaluator.evaluateRemembered(profile(), "新想法", memory(null), null, false));
        assertEquals(1, calls.get());
    }

    @Test
    void requestedExplanationCanStayDetailedWithoutANewShortReplyFailureOrExtraCall() throws Exception {
        String explanation = "先把使用场景讲清楚，是为了找到对你有用、也能按现有技术做出来的范围。".repeat(8);
        String output = json.writeValueAsString(java.util.Map.of("sufficient", true, "missingAreas", List.of(),
                "assistantReply", explanation, "previousQuestionState", "ASKED", "nextAction", "KEEP_PENDING"));
        AtomicInteger calls = new AtomicInteger();
        var evaluator = new ProfileSufficiencyEvaluator(request -> {
            calls.incrementAndGet();
            return output;
        }, json);
        var result = evaluator.evaluateRemembered(profile(), "能详细解释为什么要问这个吗？",
                memory("最近怎样整理资料？"), null, false);
        assertEquals(explanation, result.assistantReply());
        assertNull(result.nextQuestion());
        assertEquals(DialogueAction.KEEP_PENDING, result.nextAction());
        assertEquals(1, calls.get());
    }

    private SufficiencyAssessment evaluate(String output, Memory memory, DiscoveryReadiness readiness, boolean pending) {
        AtomicInteger calls = new AtomicInteger();
        var evaluator = new ProfileSufficiencyEvaluator(request -> {
            assertEquals(1, calls.incrementAndGet(), "确定性归一化不应再调用模型");
            return output;
        }, json);
        return evaluator.evaluateRemembered(profile(), "本轮输入", memory, readiness, false, pending);
    }

    private Memory memory(String question) {
        return new Memory(List.of(), List.of(), List.of(), question == null ? List.of()
                : List.of(new Topic("behaviors", question, "ASKED")), question, true);
    }

    private UserProfile profile() {
        return UserProfile.reconstitute(new UserProfileId("reliability"), UserProfileStatus.EXPLORING, 1,
                List.of("阅读"), List.of("整理课程资料"), List.of(), List.of("完成过 Java REST 接口"),
                List.of("为自己的课程资料做检索与笔记工具"), List.of(),
                List.of(new Evidence(EvidenceSourceType.USER_INPUT, "做课程资料工具，使用 Java，投入时间不确定",
                        "用户希望整理和检索课程资料", null, false)));
    }
}
