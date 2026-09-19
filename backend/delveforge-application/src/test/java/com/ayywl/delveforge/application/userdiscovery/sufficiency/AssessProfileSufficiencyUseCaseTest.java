package com.ayywl.delveforge.application.userdiscovery.sufficiency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.ai.AiResponseFormat;
import com.ayywl.delveforge.application.port.ai.AiRole;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStatus;
import com.ayywl.delveforge.domain.user.UserProfileStateException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.ayywl.delveforge.application.userdiscovery.profile.UserProfileNotFoundException;
import com.ayywl.delveforge.application.userdiscovery.shared.InMemoryUserProfileRepository;

/**
 * 验证 Profile Sufficiency Assessment 的编排：AI 只判断「够不够」，
 * 是否进入 REVIEWING 由 Domain 决定。
 *
 * <p>AI 能力在 Port 边界用替身替代，因此本类不依赖 Spring、不访问网络，
 * 也不产生真实 LLM 调用。
 */
class AssessProfileSufficiencyUseCaseTest {

    private static final UserProfileId PROFILE_ID = new UserProfileId("user-profile-1");

    private static final int INITIAL_REVISION = 1;

    private static final String INSUFFICIENT_RESPONSE = """
            {
              "sufficient": false,
              "missingAreas": ["缺少真实行为", "缺少技术能力"],
              "nextQuestion": "你最近有没有反复做过、觉得麻烦的事情？"
            }
            """;

    private static final String SUFFICIENT_RESPONSE =
            "{\"sufficient\":true,\"missingAreas\":[],\"nextQuestion\":\"\"}";

    private final InMemoryUserProfileRepository repository = new InMemoryUserProfileRepository();

    private final StubAiGateway aiGateway = new StubAiGateway();

    private final AssessProfileSufficiencyUseCase useCase =
            new AssessProfileSufficiencyUseCase(repository, aiGateway, new ObjectMapper());

    @Test
    void keepsExploringAndReturnsQuestionWhenInsufficient() {
        UserProfile profile = seedProfile();
        int revisionBefore = profile.revision();
        int savesBefore = repository.saveCount();
        aiGateway.respond(INSUFFICIENT_RESPONSE);

        SufficiencyAssessment assessment = useCase.assess(PROFILE_ID);

        assertFalse(assessment.sufficient());
        assertEquals(List.of("缺少真实行为", "缺少技术能力"), assessment.missingAreas());
        assertEquals("你最近有没有反复做过、觉得麻烦的事情？", assessment.nextQuestion());
        assertEquals(UserProfileStatus.EXPLORING, assessment.profileStatus());

        assertEquals(UserProfileStatus.EXPLORING, profile.status(), "信息不足时保持 EXPLORING");
        assertEquals(revisionBefore, profile.revision(), "信息不足不得推进 revision");
        assertEquals(savesBefore, repository.saveCount(), "信息不足不写入");
    }

    @Test
    void movesToReviewingWhenSufficient() {
        UserProfile profile = seedProfile();
        int revisionBefore = profile.revision();
        aiGateway.respond(SUFFICIENT_RESPONSE);

        SufficiencyAssessment assessment = useCase.assess(PROFILE_ID);

        assertTrue(assessment.sufficient());
        assertEquals(List.of(), assessment.missingAreas());
        assertNull(assessment.nextQuestion());
        assertEquals(UserProfileStatus.REVIEWING, assessment.profileStatus());

        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(UserProfileStatus.REVIEWING, reloaded.status(), "状态转移应被保存");
        assertEquals(revisionBefore, reloaded.revision(), "状态变化不推进 revision");
    }

    @Test
    void sendsCurrentProfileToAi() {
        UserProfile profile = seedProfile();
        profile.updateProjectGoals(List.of("做出一个自己会用的工具"));
        aiGateway.respond(INSUFFICIENT_RESPONSE);

        useCase.assess(PROFILE_ID);

        AiRequest request = aiGateway.lastRequest();
        assertEquals(AiResponseFormat.JSON, request.responseFormat());
        assertEquals(2, request.messages().size());
        assertEquals(AiRole.SYSTEM, request.messages().get(0).role());
        assertEquals(AiRole.USER, request.messages().get(1).role());
        assertTrue(request.messages().get(1).content().contains("做出一个自己会用的工具"),
                "请求上下文应包含当前 Profile 内容");
    }

    /**
     * 模型输出里即使带上状态字段也不会生效：状态只由 Domain 决定。
     */
    @Test
    void doesNotLetAiInfluenceStatus() {
        seedProfile();
        aiGateway.respond("""
                {
                  "sufficient": false,
                  "missingAreas": ["缺少目标"],
                  "nextQuestion": "你想做出什么样的东西？",
                  "status": "CONFIRMED",
                  "revision": 99
                }
                """);

        SufficiencyAssessment assessment = useCase.assess(PROFILE_ID);

        assertEquals(UserProfileStatus.EXPLORING, assessment.profileStatus());
        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(UserProfileStatus.EXPLORING, reloaded.status());
        assertEquals(INITIAL_REVISION, reloaded.revision());
    }

    @Test
    void failsWithoutChangingProfileWhenAiCallFails() {
        UserProfile profile = seedProfile();
        int savesBefore = repository.saveCount();
        aiGateway.failWith(new AiGatewayException("模型不可用"));

        assertThrows(AiGatewayException.class, () -> useCase.assess(PROFILE_ID));

        assertNothingChanged(profile, savesBefore);
    }

    @Test
    void failsWithoutChangingProfileWhenProposalCannotBeParsed() {
        UserProfile profile = seedProfile();
        int savesBefore = repository.saveCount();
        aiGateway.respond("这不是 json");

        assertThrows(AiGatewayException.class, () -> useCase.assess(PROFILE_ID));

        assertNothingChanged(profile, savesBefore);
    }

    @Test
    void failsWithoutChangingProfileWhenProposalIsContradictory() {
        UserProfile profile = seedProfile();
        int savesBefore = repository.saveCount();
        aiGateway.respond("{\"sufficient\":true,\"missingAreas\":[\"缺少行为\"]}");

        assertThrows(AiGatewayException.class, () -> useCase.assess(PROFILE_ID));

        assertNothingChanged(profile, savesBefore);
    }

    /**
     * AI 说「足够」，但 Domain 拒绝这条状态转移——CONFIRMED 不是 EXPLORING → REVIEWING
     * 的合法起点。Profile 与其状态都保持原样。
     */
    @Test
    void failsWithoutChangingProfileWhenDomainRejectsTheTransition() {
        UserProfile confirmed = UserProfile.reconstitute(
                PROFILE_ID, UserProfileStatus.CONFIRMED, 3,
                List.of("兴趣"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        repository.save(confirmed);
        int savesBefore = repository.saveCount();
        aiGateway.respond(SUFFICIENT_RESPONSE);

        assertThrows(UserProfileStateException.class, () -> useCase.assess(PROFILE_ID));

        assertEquals(UserProfileStatus.CONFIRMED, confirmed.status(), "原对象不得被改动");
        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(UserProfileStatus.CONFIRMED, reloaded.status());
        assertEquals(3, reloaded.revision());
        assertEquals(savesBefore, repository.saveCount(), "Domain 拒绝后不得写入");
    }

    @Test
    void failsWhenProfileDoesNotExist() {
        aiGateway.respond(SUFFICIENT_RESPONSE);

        assertThrows(UserProfileNotFoundException.class,
                () -> useCase.assess(new UserProfileId("unknown-profile")));

        assertEquals(0, aiGateway.callCount(), "目标不存在时不应调用 AI");
    }

    @Test
    void rejectsMissingDependencies() {
        ObjectMapper objectMapper = new ObjectMapper();

        assertThrows(IllegalArgumentException.class,
                () -> new AssessProfileSufficiencyUseCase(null, aiGateway, objectMapper));
        assertThrows(IllegalArgumentException.class,
                () -> new AssessProfileSufficiencyUseCase(repository, null, objectMapper));
        assertThrows(IllegalArgumentException.class,
                () -> new AssessProfileSufficiencyUseCase(repository, aiGateway, null));
    }

    private UserProfile seedProfile() {
        UserProfile profile = UserProfile.create(PROFILE_ID);
        repository.save(profile);
        return profile;
    }

    /**
     * 断言 Profile 完全没有被改动：原对象、重新读取的内容、状态与 revision 都保持原状，
     * 也没有写入。
     */
    private void assertNothingChanged(UserProfile original, int savesBefore) {
        assertEquals(UserProfileStatus.EXPLORING, original.status(), "原对象不得被修改");
        assertEquals(INITIAL_REVISION, original.revision(), "原对象的 revision 不得变化");

        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(UserProfileStatus.EXPLORING, reloaded.status(), "重新读取的状态不得变化");
        assertEquals(INITIAL_REVISION, reloaded.revision(), "重新读取的 revision 不得变化");
        assertEquals(savesBefore, repository.saveCount(), "不得写入");
    }

    /** AI Gateway 替身：记录收到的请求，并返回预设内容或抛出预设失败。 */
    private static final class StubAiGateway implements AiGateway {

        private final List<AiRequest> requests = new ArrayList<>();

        private String response;

        private RuntimeException failure;

        void respond(String rawResponse) {
            this.response = rawResponse;
            this.failure = null;
        }

        void failWith(RuntimeException exception) {
            this.failure = exception;
            this.response = null;
        }

        AiRequest lastRequest() {
            return requests.get(requests.size() - 1);
        }

        int callCount() {
            return requests.size();
        }

        @Override
        public String generate(AiRequest request) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
