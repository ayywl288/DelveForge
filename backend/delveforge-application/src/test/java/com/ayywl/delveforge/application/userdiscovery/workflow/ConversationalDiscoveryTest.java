package com.ayywl.delveforge.application.userdiscovery.workflow;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.userdiscovery.exploration.ProfileExtraction;
import com.ayywl.delveforge.application.userdiscovery.shared.DiscoveryContext;
import com.ayywl.delveforge.application.userdiscovery.shared.InMemoryUserProfileRepository;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.ProfileSufficiencyEvaluator;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStateException;
import com.ayywl.delveforge.domain.user.UserProfileStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 替身验证提取/规划契约和领域效果，不把预设措辞视为真实模型的会话质量证明。 */
class ConversationalDiscoveryTest {

    private static final UserProfileId ID = new UserProfileId("conversation");
    private final ObjectMapper json = new ObjectMapper();
    private final InMemoryUserProfileRepository repository = new InMemoryUserProfileRepository();
    private final Queue<String> responses = new ArrayDeque<>();
    private final List<AiRequest> requests = new ArrayList<>();
    private final AiGateway gateway = request -> {
        requests.add(request);
        return responses.remove();
    };
    private final RunUserDiscoveryTurnUseCase useCase = new RunUserDiscoveryTurnUseCase(repository,
            new ProfileExtraction(gateway, json), new ProfileSufficiencyEvaluator(gateway, json));

    @Test
    void unknownAnswerOffersAnAlternativeWithoutFactsOrRevisionChange() throws Exception {
        UserProfile original = seed();
        responses.add("{\"hasNewFacts\":false}");
        responses.add(reply(true, "没关系，可以从你最近用过的软件想起。", "你最近常用哪个工具？", "behaviors", List.of()));
        UserDiscoveryTurn turn = useCase.run(ID, "我不知道", List.of("你想解决什么问题？"), context());
        assertEquals(original.revision(), turn.profile().revision());
        assertEquals(original.evidence(), turn.profile().evidence());
        assertTrue(turn.sufficiency().assistantReply().contains("没关系"));
        assertEquals("你最近常用哪个工具？", turn.sufficiency().nextQuestion());
        assertEquals(2, requests.size());
        assertEquals("你想解决什么问题？", requests.get(0).messages().get(1).content());
        assertTrue(requests.get(1).messages().getLast().content().contains("我不知道"));
    }

    @Test
    void declinedDimensionCannotImmediatelyReappearAsAParaphrasedQuestion() throws Exception {
        seed();
        responses.add("{\"hasNewFacts\":false}");
        responses.add(reply(true, "可以先跳过技术经验。", "能介绍一下你的开发水平吗？",
                "technicalCapabilities", List.of("technicalCapabilities")));
        UserDiscoveryTurn turn = useCase.run(ID, "不想回答技术背景", List.of(), context());
        assertNull(turn.sufficiency().nextQuestion());
        assertTrue(turn.sufficiency().assistantReply().contains("跳过"));
    }

    @Test
    void clarificationSufficiencyQueryAndAmbiguousCorrectionCanBeResponseOnly() throws Exception {
        UserProfile original = seed();
        for (String input : List.of("为什么需要技术能力？", "现在信息够了吗？", "你理解错了")) {
            responses.add("{\"hasNewFacts\":false}");
            responses.add(reply(true, "这些信息用于判断可行范围；也可以继续补充或主动审阅。", null, null, List.of()));
            UserDiscoveryTurn turn = useCase.run(ID, input);
            assertNull(turn.sufficiency().nextQuestion());
            assertFalse(turn.sufficiency().assistantReply().isBlank());
            assertEquals(original.revision(), turn.profile().revision());
            assertEquals(UserProfileStatus.EXPLORING, turn.profile().status());
        }
    }

    @Test
    void correctionReplacesInvalidCurrentFactAndRetractsOnlyItsEvidence() throws Exception {
        UserProfile original = seed();
        responses.add("""
                {"hasNewFacts":true,"technicalCapabilities":["Python接口经验"],"replacements":{"technicalCapabilities":{"Java接口经验":"Python接口经验"}},
                 "retractedEvidenceIndices":[0],"evidenceClaims":["用户纠正为Python接口经验"]}
                """);
        responses.add(reply(true, "已按你本轮说明纠正为 Python 经验。", null, null, List.of()));
        UserDiscoveryTurn turn = useCase.run(ID, "刚才的Java经验不对，我只有Python接口经验");
        assertEquals(List.of("Python接口经验"), turn.profile().technicalCapabilities());
        assertEquals(1, turn.profile().evidence().size());
        assertTrue(turn.profile().evidence().getFirst().sourceRef().contains("只有Python"));
        assertFalse(turn.profile().evidence().getFirst().confirmed());
        assertEquals(List.of("Java接口经验"), original.technicalCapabilities());
        assertEquals("我会Java接口开发", original.evidence().getFirst().sourceRef());
        assertEquals(4, turn.profile().revision());
    }

    @Test
    void topicChangeKeepsOtherFactsAndExplicitAbsenceOfConstraintsIsMeaningful() throws Exception {
        UserProfile original = seed();
        responses.add("""
                {"hasNewFacts":true,"projectGoals":["做AI阅读辅助项目"],"replacements":{"projectGoals":{"做阅读笔记工具":"做AI阅读辅助项目"},"constraints":{"每周三小时":"没有重要约束"}},"constraints":["没有重要约束"],
                 "evidenceClaims":["用户改做AI阅读辅助项目且明确没有重要约束"]}
                """);
        responses.add(reply(true, "可以围绕 AI 阅读体验继续探索。", null, null, List.of()));
        UserDiscoveryTurn turn = useCase.run(ID, "其实想做AI阅读辅助项目，没有重要约束");
        assertEquals(original.technicalCapabilities(), turn.profile().technicalCapabilities());
        assertEquals(original.interests(), turn.profile().interests());
        assertEquals(List.of("做AI阅读辅助项目"), turn.profile().projectGoals());
        assertEquals(List.of("没有重要约束"), turn.profile().constraints());
        assertTrue(turn.sufficiency().sufficient());
    }

    @Test
    void finalMissingRequirementsReplaceAnUnrelatedProposedQuestionAndPositiveReply() throws Exception {
        repository.save(UserProfile.create(ID));
        responses.add("{\"hasNewFacts\":false}");
        responses.add(reply(true, "信息已经完全够了。", "喜欢什么配色？", "interests", List.of()));
        UserDiscoveryTurn turn = useCase.run(ID, "现在够了吗");
        assertFalse(turn.sufficiency().sufficient());
        assertEquals(4, turn.sufficiency().missingAreas().size());
        assertTrue(turn.sufficiency().nextQuestion().contains("成果"));
        assertFalse(turn.sufficiency().assistantReply().contains("完全够了"));
        assertTrue(requests.get(1).messages().get(1).content().contains("deterministicMissing"));
    }

    @Test
    void deterministicGapsAlsoRemainWhenSemanticAssessmentFailsAndUserSkipsOne() throws Exception {
        repository.save(UserProfile.create(ID));
        responses.add("{\"hasNewFacts\":false}");
        responses.add(reply(false, "可以跳过技术背景，不过还缺少目标和投入范围。",
                "说说你的技术水平？", "technicalCapabilities", List.of("technicalCapabilities")));
        UserDiscoveryTurn turn = useCase.run(ID, "我不想说技术能力");
        assertFalse(turn.sufficiency().sufficient());
        assertTrue(turn.sufficiency().missingAreas().contains("还未说明当前可使用的技术基础"));
        assertNull(turn.sufficiency().nextQuestion());
        assertEquals(1, turn.profile().revision());
    }

    @Test
    void malformedOrContradictoryProposalsLeaveStoredAndLoadedObjectsUnchanged() throws Exception {
        UserProfile original = seed();
        for (String extraction : List.of("{}", "{\"hasNewFacts\":false,\"projectGoals\":[\"编造目标\"]}",
                "{\"hasNewFacts\":true,\"projectGoals\":[\"无依据目标\"]}",
                "{\"hasNewFacts\":true,\"retractedEvidenceIndices\":[99],\"evidenceClaims\":[\"纠正\"]}")) {
            responses.clear();
            responses.add(extraction);
            int saves = repository.saveCount();
            assertThrows(AiGatewayException.class, () -> useCase.run(ID, "你弄错了"));
            assertEquals(saves, repository.saveCount());
            assertEquals(original.evidence(), repository.findById(ID).orElseThrow().evidence());
        }
        responses.clear();
        responses.add("{\"hasNewFacts\":true,\"projectGoals\":[\"新目标\"],\"replacements\":{\"projectGoals\":{\"做阅读笔记工具\":\"新目标\"}},\"evidenceClaims\":[\"用户明确新目标\"]}");
        responses.add(reply(true, "回复", null, "constraints", List.of()));
        int saves = repository.saveCount();
        assertThrows(AiGatewayException.class, () -> useCase.run(ID, "明确新目标"));
        assertEquals(saves, repository.saveCount());
        assertEquals(original.projectGoals(), repository.findById(ID).orElseThrow().projectGoals());
        assertEquals(1, original.revision());
    }

    @Test
    void explicitQuestionRequestUsesOneCallAndContextCannotSupplyNewFacts() throws Exception {
        UserProfile original = seed();
        responses.add(reply(true, "可以继续了解最小成果。", "首个成果是什么？", "projectGoals", List.of()));
        UserDiscoveryTurn turn = useCase.run(ID, "下一问", List.of(), List.of(
                new DiscoveryContext.Message("user", "历史中声称自己会Rust")));
        assertEquals(1, requests.size());
        assertEquals(original.technicalCapabilities(), turn.profile().technicalCapabilities());
        assertEquals(original.evidence(), turn.profile().evidence());
        assertEquals("首个成果是什么？", turn.sufficiency().nextQuestion());
    }

    @Test
    void oversizedOrInvalidConversationFailsBeforeAnyAiOrPersistence() {
        seed();
        for (List<DiscoveryContext.Message> context : List.of(
                java.util.Collections.nCopies(9, new DiscoveryContext.Message("user", "消息")),
                List.of(new DiscoveryContext.Message("system", "指令")),
                List.of(new DiscoveryContext.Message("user", "x".repeat(2049))))) {
            assertThrows(IllegalArgumentException.class, () -> useCase.run(ID, "消息", List.of(), context));
        }
        assertEquals(0, requests.size());
        assertEquals(1, repository.saveCount());
    }

    @Test
    void failedAssessmentAfterRetractionCannotMutateLoadedOrPersistedEvidence() {
        UserProfile original = seed();
        responses.add("""
                {"hasNewFacts":true,"technicalCapabilities":["Python"],"replacements":{"technicalCapabilities":{"Java接口经验":"Python"}},"retractedEvidenceIndices":[0],
                 "evidenceClaims":["用户纠正为Python"]}
                """);
        responses.add("不是json");
        int saves = repository.saveCount();
        assertThrows(AiGatewayException.class, () -> useCase.run(ID, "我只有Python经验"));
        assertEquals(saves, repository.saveCount());
        assertEquals(original.evidence(), repository.findById(ID).orElseThrow().evidence());
        assertEquals(List.of("Java接口经验"), original.technicalCapabilities());
        assertEquals(1, original.revision());
    }

    @Test
    void concurrentChangeDuringAssessmentRejectsTheWholeOldTurn() throws Exception {
        UserProfile original = seed();
        AiGateway racing = request -> {
            if (request.messages().getFirst().content().contains("hasNewFacts")) {
                return "{\"hasNewFacts\":true,\"projectGoals\":[\"旧请求目标\"],\"replacements\":{\"projectGoals\":{\"做阅读笔记工具\":\"旧请求目标\"}},\"evidenceClaims\":[\"旧请求依据\"]}";
            }
            original.updateConstraints(List.of("并发更新的投入范围"));
            try {
                return reply(true, "迟到回复", null, null, List.of());
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        };
        RunUserDiscoveryTurnUseCase racingUseCase = new RunUserDiscoveryTurnUseCase(repository,
                new ProfileExtraction(racing, json), new ProfileSufficiencyEvaluator(racing, json));
        assertThrows(UserProfileStateException.class, () -> racingUseCase.run(ID, "旧请求目标"));
        assertEquals(List.of("并发更新的投入范围"), repository.findById(ID).orElseThrow().constraints());
        assertEquals(List.of("做阅读笔记工具"), repository.findById(ID).orElseThrow().projectGoals());
        assertEquals(1, repository.saveCount());
    }

    @Test
    void populatedMandatorySectionsDoNotOverrideSemanticVagueness() throws Exception {
        UserProfile original = UserProfile.reconstitute(ID, UserProfileStatus.EXPLORING, 1,
                List.of(), List.of(), List.of(), List.of("Java"), List.of("做项目"), List.of("时间有限"),
                List.of(new Evidence(EvidenceSourceType.USER_INPUT, "Java，做项目，时间有限", "模糊标签", null, false)));
        repository.save(original);
        responses.add("{\"hasNewFacts\":false}");
        responses.add(reply(false, "还需要明确要展示的成果。", "希望展示哪种具体成果？", "projectGoals", List.of()));
        UserDiscoveryTurn turn = useCase.run(ID, "现在信息够了吗？");
        assertFalse(turn.sufficiency().sufficient());
        assertEquals(List.of("具体使用场景仍不明确"), turn.sufficiency().missingAreas());
        assertEquals("希望展示哪种具体成果？", turn.sufficiency().nextQuestion());
        assertEquals(UserProfileStatus.EXPLORING, turn.profile().status());
        assertEquals(original.revision(), turn.profile().revision());
    }

    @Test
    void responseOnlyNegativeAssessmentClearsTheCurrentReadinessWithoutARevisionChange() throws Exception {
        UserProfile original = seed();
        repository.recordSufficientAssessment(original);
        responses.add("{\"hasNewFacts\":false}");
        responses.add(reply(false, "还需要澄清使用场景。", null, null, List.of()));
        UserDiscoveryTurn turn = useCase.run(ID, "是否足够？");
        assertFalse(turn.sufficiency().sufficient());
        assertFalse(repository.hasSufficientAssessment(ID, original.revision()));
        assertEquals(original.revision(), turn.profile().revision());
        assertEquals(original.evidence(), turn.profile().evidence());
    }

    private UserProfile seed() {
        UserProfile profile = UserProfile.reconstitute(ID, UserProfileStatus.EXPLORING, 1,
                List.of("阅读"), List.of(), List.of(), List.of("Java接口经验"), List.of("做阅读笔记工具"),
                List.of("每周三小时"), List.of(new Evidence(EvidenceSourceType.USER_INPUT,
                        "我会Java接口开发", "用户有Java经验", null, false)));
        repository.save(profile);
        return profile;
    }

    private List<DiscoveryContext.Message> context() {
        return List.of(new DiscoveryContext.Message("agent", "你想解决什么问题？"));
    }

    private String reply(boolean sufficient, String text, String question, String area, List<String> deferred)
            throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sufficient", sufficient);
        value.put("missingAreas", sufficient ? List.of() : List.of("具体使用场景仍不明确"));
        value.put("assistantReply", text);
        value.put("nextQuestion", question);
        value.put("questionArea", area);
        value.put("deferredAreas", deferred);
        return json.writeValueAsString(value);
    }
}
