package com.ayywl.delveforge.application.userdiscovery.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.userdiscovery.exploration.ProfileExtraction;
import com.ayywl.delveforge.application.userdiscovery.profile.UserProfileNotFoundException;
import com.ayywl.delveforge.application.userdiscovery.shared.InMemoryUserProfileRepository;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.ProfileSufficiencyEvaluator;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStateException;
import com.ayywl.delveforge.domain.user.UserProfileStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证一轮 User Discovery 的编排：提取 → 更新 → 评估 → 必要时进入 Review，
 * 整轮只保存一次，任何一步失败都不留下半轮结果。
 *
 * <p>AI 能力在 Port 边界用替身替代，因此本类不依赖 Spring、不访问网络，
 * 也不产生真实 LLM 调用。
 */
class RunUserDiscoveryTurnUseCaseTest {

    private static final UserProfileId PROFILE_ID = new UserProfileId("user-profile-1");

    private static final int INITIAL_REVISION = 1;

    private static final String USER_INPUT = "我平时喜欢自己找图片做头像，但每次都要手动裁剪很麻烦";

    /** 一次调用返回一个 Profile Proposal 的响应。 */
    private static final String EXTRACTION_RESPONSE = """
            {
              "interests": ["图片处理"],
              "painPoints": ["头像裁剪麻烦"],
              "evidenceClaims": ["用户需要频繁处理头像图片"]
            }
            """;

    private static final String SUFFICIENT_RESPONSE =
            "{\"sufficient\":true,\"missingAreas\":[],\"nextQuestion\":\"\"}";

    private static final String INSUFFICIENT_RESPONSE = """
            {
              "sufficient": false,
              "missingAreas": ["缺少技术能力"],
              "nextQuestion": "你平时主要用什么语言写代码？"
            }
            """;

    private final InMemoryUserProfileRepository repository = new InMemoryUserProfileRepository();

    private final StubAiGateway aiGateway = new StubAiGateway();

    private final RunUserDiscoveryTurnUseCase useCase = new RunUserDiscoveryTurnUseCase(
            repository,
            new ProfileExtraction(aiGateway, new ObjectMapper()),
            new ProfileSufficiencyEvaluator(aiGateway, new ObjectMapper()));

    @Test
    void keepsExploringAndReturnsNextQuestionWhenInsufficient() {
        seedProfile();
        int savesBefore = repository.saveCount();
        aiGateway.respondWith(EXTRACTION_RESPONSE, INSUFFICIENT_RESPONSE);

        UserDiscoveryTurn turn = useCase.run(PROFILE_ID, USER_INPUT);

        assertFalse(turn.sufficiency().sufficient());
        assertEquals(List.of("缺少技术能力"), turn.sufficiency().missingAreas());
        assertEquals("你平时主要用什么语言写代码？", turn.sufficiency().nextQuestion());
        assertEquals(UserProfileStatus.EXPLORING, turn.profile().status());

        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(List.of("图片处理"), reloaded.interests(), "本轮提取的内容应当被保存");
        assertEquals(List.of("头像裁剪麻烦"), reloaded.painPoints());
        assertEquals(UserProfileStatus.EXPLORING, reloaded.status());
        assertEquals(INITIAL_REVISION + 3, reloaded.revision(), "两个内容区 + 一条 Evidence");
        assertEquals(savesBefore + 1, repository.saveCount(), "一整轮只保存一次");
    }

    @Test
    void movesToReviewingAndSavesOnceWhenSufficient() {
        seedProfile();
        int savesBefore = repository.saveCount();
        aiGateway.respondWith(EXTRACTION_RESPONSE, SUFFICIENT_RESPONSE);

        UserDiscoveryTurn turn = useCase.run(PROFILE_ID, USER_INPUT);

        assertTrue(turn.sufficiency().sufficient());
        assertEquals(List.of(), turn.sufficiency().missingAreas());
        assertNull(turn.sufficiency().nextQuestion());
        assertEquals(UserProfileStatus.REVIEWING, turn.profile().status());

        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(UserProfileStatus.REVIEWING, reloaded.status());
        assertEquals(List.of("图片处理"), reloaded.interests());
        assertEquals(INITIAL_REVISION + 3, reloaded.revision(), "状态变化不推进 revision");
        assertEquals(savesBefore + 1, repository.saveCount(), "一整轮只保存一次");
    }

    /**
     * 充分性评估必须针对本轮已经更新过的候选 Profile，而不是旧 Profile——
     * 否则结论与用户当前状态无关。
     */
    @Test
    void assessesTheUpdatedCandidateNotTheStoredProfile() {
        seedProfile();
        aiGateway.respondWith(EXTRACTION_RESPONSE, INSUFFICIENT_RESPONSE);

        useCase.run(PROFILE_ID, USER_INPUT);

        assertEquals(2, aiGateway.callCount(), "一轮恰好两次 AI 调用：提取一次、评估一次");
        String extractionContext = aiGateway.requestAt(0).messages().get(1).content();
        String sufficiencyContext = aiGateway.requestAt(1).messages().get(1).content();

        assertTrue(extractionContext.contains(USER_INPUT), "提取阶段应收到本轮输入");
        assertFalse(extractionContext.contains("图片处理"), "提取阶段看到的是更新前的 Profile");

        assertTrue(sufficiencyContext.contains("图片处理"),
                "评估阶段必须看到本轮刚刚提取出的内容");
        assertTrue(sufficiencyContext.contains("头像裁剪麻烦"));
    }

    @Test
    void failsWithoutSavingWhenExtractionCallFails() {
        UserProfile profile = seedProfile();
        int savesBefore = repository.saveCount();
        aiGateway.failOnCall(0, new AiGatewayException("模型不可用"));

        assertThrows(AiGatewayException.class, () -> useCase.run(PROFILE_ID, USER_INPUT));

        assertNothingChanged(profile, savesBefore);
    }

    @Test
    void failsWithoutSavingWhenExtractionProposalCannotBeParsed() {
        UserProfile profile = seedProfile();
        int savesBefore = repository.saveCount();
        aiGateway.respondWith("这不是 json", SUFFICIENT_RESPONSE);

        assertThrows(AiGatewayException.class, () -> useCase.run(PROFILE_ID, USER_INPUT));

        assertNothingChanged(profile, savesBefore);
    }

    @Test
    void failsWithoutSavingWhenContentUpdateIsRejected() {
        UserProfile profile = seedProfile();
        int savesBefore = repository.saveCount();
        aiGateway.respondWith("{\"interests\":[\"这个区会先被应用\"],\"behaviors\":[\"  \"]}",
                SUFFICIENT_RESPONSE);

        assertThrows(IllegalArgumentException.class, () -> useCase.run(PROFILE_ID, USER_INPUT));

        assertNothingChanged(profile, savesBefore);
    }

    @Test
    void failsWithoutSavingWhenSufficiencyCallFails() {
        seedProfile();
        int savesBefore = repository.saveCount();
        aiGateway.respondWith(EXTRACTION_RESPONSE);
        aiGateway.failOnCall(1, new AiGatewayException("模型不可用"));

        assertThrows(AiGatewayException.class, () -> useCase.run(PROFILE_ID, USER_INPUT));

        assertEquals(savesBefore, repository.saveCount(),
                "评估失败时，本轮已经提取的内容也不得写入");
        assertEquals(List.of(), repository.findById(PROFILE_ID).orElseThrow().interests());
    }

    @Test
    void failsWithoutSavingWhenSufficiencyProposalIsContradictory() {
        seedProfile();
        int savesBefore = repository.saveCount();
        aiGateway.respondWith(EXTRACTION_RESPONSE,
                "{\"sufficient\":true,\"missingAreas\":[\"缺少行为\"]}");

        assertThrows(AiGatewayException.class, () -> useCase.run(PROFILE_ID, USER_INPUT));

        assertEquals(savesBefore, repository.saveCount());
        assertEquals(List.of(), repository.findById(PROFILE_ID).orElseThrow().interests());
    }

    /**
     * 提取与评估都成功，但 Domain 拒绝 EXPLORING → REVIEWING（当前已是 REVIEWING）。
     */
    @Test
    void failsWithoutSavingWhenBeginReviewIsRejected() {
        UserProfile reviewing = UserProfile.reconstitute(
                PROFILE_ID, UserProfileStatus.REVIEWING, 3,
                List.of("兴趣"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        repository.save(reviewing);
        int savesBefore = repository.saveCount();
        aiGateway.respondWith(EXTRACTION_RESPONSE, SUFFICIENT_RESPONSE);

        assertThrows(UserProfileStateException.class, () -> useCase.run(PROFILE_ID, USER_INPUT));

        assertEquals(UserProfileStatus.REVIEWING, reviewing.status(), "原对象不得被改动");
        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(UserProfileStatus.REVIEWING, reloaded.status());
        assertEquals(3, reloaded.revision());
        assertEquals(List.of("兴趣"), reloaded.interests(), "本轮提取的内容不得写入");
        assertEquals(savesBefore, repository.saveCount());
    }

    /**
     * 多轮推进：第一轮不足并给出下一问，用户回答后第二轮进入 REVIEWING。
     */
    @Test
    void advancesAcrossTurnsUntilSufficient() {
        seedProfile();
        aiGateway.respondWith(EXTRACTION_RESPONSE, INSUFFICIENT_RESPONSE);

        UserDiscoveryTurn first = useCase.run(PROFILE_ID, USER_INPUT);

        assertFalse(first.sufficiency().sufficient());
        String nextQuestion = first.sufficiency().nextQuestion();
        assertEquals("你平时主要用什么语言写代码？", nextQuestion);

        // 用户回答上一轮的问题，发起下一轮
        aiGateway.respondWith("{\"technicalCapabilities\":[\"Java\"]}", SUFFICIENT_RESPONSE);
        UserDiscoveryTurn second = useCase.run(PROFILE_ID, nextQuestion + " —— 主要用 Java");

        assertTrue(second.sufficiency().sufficient());
        assertEquals(UserProfileStatus.REVIEWING, second.profile().status());
        assertEquals(List.of("图片处理"), second.profile().interests(), "上一轮的内容应当保留");
        assertEquals(List.of("Java"), second.profile().technicalCapabilities());
    }

    /**
     * 回归：一轮探索只能从 EXPLORING 开始。
     *
     * <p>否则同一轮输入会因为模型恰好判断「足够」或「不足」而走向不同结局——
     * 「足够」时状态转移被拒、「不足」时内容被照常写入，等于让 AI 决定领域状态是否被接受。
     */
    @Test
    void rejectsTurnOutsideExploringWithoutCallingAiOrSaving() {
        for (UserProfileStatus status : List.of(
                UserProfileStatus.REVIEWING, UserProfileStatus.CONFIRMED)) {
            InMemoryUserProfileRepository repositoryForStatus = new InMemoryUserProfileRepository();
            StubAiGateway gateway = new StubAiGateway();
            RunUserDiscoveryTurnUseCase useCaseForStatus = new RunUserDiscoveryTurnUseCase(
                    repositoryForStatus,
                    new ProfileExtraction(gateway, new ObjectMapper()),
                    new ProfileSufficiencyEvaluator(gateway, new ObjectMapper()));

            UserProfile profile = UserProfile.reconstitute(
                    PROFILE_ID, status, 3,
                    List.of("兴趣"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
            repositoryForStatus.save(profile);
            int savesBefore = repositoryForStatus.saveCount();
            gateway.respondWith(EXTRACTION_RESPONSE, SUFFICIENT_RESPONSE);

            assertThrows(UserProfileStateException.class,
                    () -> useCaseForStatus.run(PROFILE_ID, USER_INPUT), status + " 下不应被接受");

            assertEquals(0, gateway.callCount(), status + " 下不应调用 AI");
            UserProfile reloaded = repositoryForStatus.findById(PROFILE_ID).orElseThrow();
            assertEquals(status, reloaded.status());
            assertEquals(3, reloaded.revision(), status + " 下不得改动内容");
            assertEquals(List.of("兴趣"), reloaded.interests());
            assertEquals(savesBefore, repositoryForStatus.saveCount(), status + " 下不得写入");
        }
    }

    @Test
    void rejectsBlankUserInput() {
        assertThrows(IllegalArgumentException.class, () -> useCase.run(PROFILE_ID, "   "));
        assertEquals(0, aiGateway.callCount());
    }

    @Test
    void failsWhenProfileDoesNotExist() {
        assertThrows(UserProfileNotFoundException.class,
                () -> useCase.run(new UserProfileId("unknown-profile"), USER_INPUT));
        assertEquals(0, aiGateway.callCount(), "目标不存在时不应调用 AI");
    }

    private UserProfile seedProfile() {
        UserProfile profile = UserProfile.create(PROFILE_ID);
        repository.save(profile);
        return profile;
    }

    /**
     * 断言 Profile 完全没有被改动：原对象与重新读取的内容都保持原状，也没有写入。
     */
    private void assertNothingChanged(UserProfile original, int savesBefore) {
        assertEquals(UserProfileStatus.EXPLORING, original.status(), "原对象不得被修改");
        assertEquals(INITIAL_REVISION, original.revision());

        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(List.of(), reloaded.interests(), "重新读取的内容不得变化");
        assertEquals(INITIAL_REVISION, reloaded.revision());
        assertEquals(savesBefore, repository.saveCount(), "不得写入");
    }

    /** AI Gateway 替身：按调用顺序返回预设内容，或让指定的一次调用失败。 */
    private static final class StubAiGateway implements AiGateway {

        private final List<AiRequest> requests = new ArrayList<>();

        private final List<String> responses = new ArrayList<>();

        private int failureCallIndex = -1;

        private RuntimeException failure;

        void respondWith(String... rawResponses) {
            responses.clear();
            responses.addAll(List.of(rawResponses));
            failureCallIndex = -1;
            failure = null;
        }

        /** 第 {@code callIndex} 次调用（从 0 开始）失败，其余按 respondWith 的预设返回。 */
        void failOnCall(int callIndex, RuntimeException exception) {
            this.failureCallIndex = callIndex;
            this.failure = exception;
        }

        AiRequest requestAt(int index) {
            return requests.get(index);
        }

        int callCount() {
            return requests.size();
        }

        @Override
        public String generate(AiRequest request) {
            int index = requests.size();
            requests.add(request);
            if (index == failureCallIndex) {
                throw failure;
            }
            if (responses.isEmpty()) {
                throw new IllegalStateException("替身没有为第 " + index + " 次调用准备响应");
            }
            return responses.remove(0);
        }
    }
}
