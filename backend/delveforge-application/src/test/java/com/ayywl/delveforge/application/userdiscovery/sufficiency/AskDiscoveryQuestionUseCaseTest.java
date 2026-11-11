package com.ayywl.delveforge.application.userdiscovery.sufficiency;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.userdiscovery.shared.InMemoryUserProfileRepository;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStateException;
import com.ayywl.delveforge.domain.user.UserProfileStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AskDiscoveryQuestionUseCaseTest {

    private static final UserProfileId ID = new UserProfileId("profile");
    private static final String RESPONSE = """
            {"sufficient":true,"missingAreas":[],"nextQuestion":"你希望作品首先展示哪一项后端能力？",
             "assistantReply":"我们可以继续细化展示成果。","questionArea":"exploration","deferredAreas":[]}
            """;
    private final InMemoryUserProfileRepository repository = new InMemoryUserProfileRepository();
    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void sufficientProfileReceivesOneRealQuestionWithoutSavingAnything() {
        UserProfile original = seed(UserProfileStatus.EXPLORING);
        int saves = repository.saveCount();
        SufficiencyAssessment result = useCase(request -> {
            assertTrue(request.messages().get(1).content().contains("\"continueExploration\":true"));
            assertTrue(request.messages().get(1).content().contains("每周能投入多少时间？"));
            return RESPONSE;
        }).ask(ID, 4, List.of("每周能投入多少时间？"));

        assertTrue(result.sufficient());
        assertEquals("你希望作品首先展示哪一项后端能力？", result.nextQuestion());
        assertEquals(1, calls.get());
        assertEquals(saves, repository.saveCount());
        assertEquals(original, repository.findById(ID).orElseThrow());
        assertEquals(4, original.revision());
        assertEquals(UserProfileStatus.EXPLORING, original.status());
    }

    @Test
    void basicMissingGuardReplansQuestionAgainstFinalAssessment() {
        UserProfile empty = UserProfile.create(ID);
        repository.save(empty);
        SufficiencyAssessment result = useCase(request -> RESPONSE).ask(ID, 1, null);
        assertFalse(result.sufficient());
        assertEquals(3, result.missingAreas().size());
        assertEquals("你希望这个项目帮助你完成什么成果，或展示什么能力？", result.nextQuestion());
        assertEquals(1, empty.revision());
        assertEquals(List.of(), empty.evidence());
        assertEquals(1, repository.saveCount());
    }

    @Test
    void insufficientProfileStillGetsAQuestion() {
        seed(UserProfileStatus.EXPLORING);
        SufficiencyAssessment result = useCase(request -> """
                {"sufficient":false,"missingAreas":["使用场景尚不明确"],"nextQuestion":"你准备给谁使用？",
                 "assistantReply":"还需要了解使用场景。","questionArea":"behaviors","deferredAreas":[]}
                """).ask(ID, 4, null);
        assertFalse(result.sufficient());
        assertEquals("你准备给谁使用？", result.nextQuestion());
        assertEquals(1, calls.get());
    }

    @Test
    void negativeReassessmentCannotLeaveAReusablePositiveResultForTheSameRevision() {
        UserProfile original = seed(UserProfileStatus.EXPLORING);
        repository.recordSufficientAssessment(original);
        useCase(request -> """
                {"sufficient":false,"missingAreas":["场景仍不明确"],"nextQuestion":"准备先给谁使用？",
                 "assistantReply":"可以先澄清使用场景。","questionArea":"behaviors","deferredAreas":[]}
                """).ask(ID, 4, null);
        assertFalse(repository.hasSufficientAssessment(ID, 4));
        assertEquals(4, original.revision());
        assertEquals(1, repository.saveCount());
    }

    @Test
    void confirmedReviewingAndStaleVersionsAreRejectedBeforeAi() {
        for (UserProfileStatus status : List.of(UserProfileStatus.CONFIRMED, UserProfileStatus.REVIEWING)) {
            UserProfile original = seed(status);
            int saves = repository.saveCount();
            assertThrows(UserProfileStateException.class, () -> useCase(request -> RESPONSE).ask(ID, 4, null));
            assertEquals(status, original.status());
            assertEquals(saves, repository.saveCount());
        }
        seed(UserProfileStatus.EXPLORING);
        assertThrows(UserProfileStateException.class, () -> useCase(request -> RESPONSE).ask(ID, 3, null));
        assertEquals(0, calls.get());
    }

    @Test
    void failureMissingOrRepeatedQuestionDoesNotMutateOrRetry() {
        UserProfile original = seed(UserProfileStatus.EXPLORING);
        int saves = repository.saveCount();
        assertThrows(AiGatewayException.class, () -> useCase(request -> {
            throw new AiGatewayException("失败");
        }).ask(ID, 4, null));
        assertThrows(AiGatewayException.class, () -> useCase(request -> "{\"sufficient\":true}").ask(ID, 4, null));
        assertThrows(AiGatewayException.class, () -> useCase(request -> RESPONSE).ask(
                ID, 4, List.of("你希望作品首先展示哪一项后端能力？")));
        assertEquals(3, calls.get(), "每次用户请求最多调用一次，没有自动重试");
        assertEquals(saves, repository.saveCount());
        assertEquals(4, original.revision());
        assertEquals(1, original.evidence().size());
    }

    @Test
    void oversizedContextIsRejectedBeforeAi() {
        seed(UserProfileStatus.EXPLORING);
        for (List<String> context : List.of(java.util.Collections.nCopies(9, "问题？"),
                List.of("x".repeat(513)), List.of(" "))) {
            assertThrows(IllegalArgumentException.class, () -> useCase(request -> RESPONSE).ask(ID, 4, context));
        }
        assertEquals(0, calls.get());
    }

    @Test
    void changeDuringAiCallDiscardsOldQuestion() {
        UserProfile original = seed(UserProfileStatus.EXPLORING);
        assertThrows(UserProfileStateException.class, () -> useCase(request -> {
            original.updateConstraints(List.of("改为每周两小时"));
            return RESPONSE;
        }).ask(ID, 4, null));
        assertEquals(1, calls.get());
        assertEquals(5, original.revision(), "仅并发的实际更新推进版本，提问用例没有写入");
        assertEquals(1, repository.saveCount());
    }

    @Test
    void entryIntoReviewDuringAiCallDiscardsQuestionEvenWhenRevisionIsUnchanged() {
        UserProfile original = seed(UserProfileStatus.EXPLORING);
        assertThrows(UserProfileStateException.class, () -> useCase(request -> {
            original.beginReview();
            return RESPONSE;
        }).ask(ID, 4, null));
        assertEquals(4, original.revision());
        assertEquals(UserProfileStatus.REVIEWING, original.status());
    }

    @Test
    void explicitRequestFillsAnActionableGapWhenModelOnlyReplies() {
        UserProfile original = UserProfile.create(ID);
        repository.save(original);
        SufficiencyAssessment result = useCase(request -> questionless(List.of())).ask(ID, 1, null);
        assertFalse(result.sufficient());
        assertEquals("你希望这个项目帮助你完成什么成果，或展示什么能力？", result.nextQuestion());
        assertEquals(1, calls.get());
        assertEquals(1, original.revision());
        assertEquals(List.of(), original.evidence());
        assertEquals(1, repository.saveCount());
    }

    @Test
    void fallbackRespectsSkippedAreasAndRecentlyAskedGapQuestions() {
        repository.save(UserProfile.create(ID));
        SufficiencyAssessment result = useCase(request -> questionless(List.of("projectGoals")))
                .ask(ID, 1, List.of("你目前用哪些技术完成过什么样的开发？"));
        assertEquals("请用一个具体场景或想达成的成果，说明你为什么想做这个项目。", result.nextQuestion());
        assertEquals(1, calls.get());
    }

    @Test
    void allSkippedOrExhaustedGapsFailExplicitlyWithoutPersistenceOrRetries() {
        repository.save(UserProfile.create(ID));
        List<String> allAreas = List.of("projectGoals", "technicalCapabilities", "constraints", "evidence");
        assertThrows(AiGatewayException.class,
                () -> useCase(request -> questionless(allAreas)).ask(ID, 1, List.of()));
        List<String> allQuestions = List.of("你希望这个项目帮助你完成什么成果，或展示什么能力？",
                "你目前用哪些技术完成过什么样的开发？",
                "你能投入的时间或项目范围有什么限制？没有重要限制也可以说明。",
                "请用一个具体场景或想达成的成果，说明你为什么想做这个项目。");
        assertThrows(AiGatewayException.class,
                () -> useCase(request -> questionless(List.of())).ask(ID, 1, allQuestions));
        assertEquals(2, calls.get());
        assertEquals(1, repository.saveCount());
        assertEquals(1, repository.findById(ID).orElseThrow().revision());
    }

    @Test
    void sufficientProfileWithoutAProposedQuestionFailsBeforeChangingReadiness() {
        UserProfile original = seed(UserProfileStatus.EXPLORING);
        repository.recordSufficientAssessment(original);
        assertThrows(AiGatewayException.class, () -> useCase(request -> """
                {"sufficient":true,"missingAreas":[],"assistantReply":"你可以继续探索。",
                 "nextQuestion":null,"questionArea":null,"deferredAreas":[]}
                """).ask(ID, 4, null));
        assertTrue(repository.hasSufficientAssessment(ID, 4));
        assertEquals(1, calls.get());
        assertEquals(1, repository.saveCount());
        assertEquals(4, original.revision());
    }

    @Test
    void repeatedModelQuestionUsesAnotherUnskippedGapRatherThanAParaphrase() {
        repository.save(UserProfile.create(ID));
        SufficiencyAssessment result = useCase(request -> """
                {"sufficient":false,"missingAreas":["成果尚未明确"],"assistantReply":"先换个角度。",
                 "nextQuestion":"希望先展示什么成果？","questionArea":"projectGoals","deferredAreas":[]}
                """).ask(ID, 1, List.of("希望先展示什么成果？"));
        assertEquals("你目前用哪些技术完成过什么样的开发？", result.nextQuestion());
        assertEquals(1, calls.get());
    }

    @Test
    void skippedMinimumTopicsMayUseAModelProposedRelevantExplorationAlternative() {
        repository.save(UserProfile.create(ID));
        SufficiencyAssessment result = useCase(request -> """
                {"sufficient":false,"missingAreas":["最低信息待补充"],"assistantReply":"可以换一个愿意聊的角度。",
                 "nextQuestion":"最近使用的软件中有没有让你想改善的体验？","questionArea":"exploration",
                 "deferredAreas":["projectGoals","technicalCapabilities","constraints","evidence"]}
                """).ask(ID, 1, List.of());
        assertFalse(result.sufficient());
        assertEquals("最近使用的软件中有没有让你想改善的体验？", result.nextQuestion());
        assertEquals(1, calls.get());
    }

    private String questionless(List<String> deferred) {
        try {
            return """
                    {"sufficient":false,"missingAreas":["需要明确项目目标"],
                     "assistantReply":"我们可以开始探索。","nextQuestion":null,"questionArea":null,
                     "deferredAreas":%s}
                    """.formatted(new ObjectMapper().writeValueAsString(deferred));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }

    private AskDiscoveryQuestionUseCase useCase(AiGateway gateway) {
        return new AskDiscoveryQuestionUseCase(repository, new ProfileSufficiencyEvaluator(request -> {
            calls.incrementAndGet();
            return gateway.generate(request);
        }, new ObjectMapper()));
    }

    private UserProfile seed(UserProfileStatus status) {
        UserProfile profile = UserProfile.reconstitute(ID, status, 4,
                List.of("Java后端"), List.of("每周做小项目"), List.of(), List.of("会写REST接口"),
                List.of("六周完成可展示的后端作品"), List.of("每周五小时"),
                List.of(new Evidence(EvidenceSourceType.USER_INPUT, "六周做作品，每周五小时，会写接口",
                        "目标与投入范围", null, false)));
        repository.save(profile);
        return profile;
    }
}
