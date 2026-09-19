package com.ayywl.delveforge.application.userdiscovery.exploration;

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
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.ayywl.delveforge.application.userdiscovery.profile.UserProfileNotFoundException;
import com.ayywl.delveforge.application.userdiscovery.shared.InMemoryUserProfileRepository;

/**
 * 验证 Explore User Profile 的编排：AI 只提出建议，是否接受由 Aggregate 决定。
 *
 * <p>AI 能力在 Port 边界用替身替代，因此本类不依赖 Spring、不访问网络，
 * 也不产生真实 LLM 调用（AGENTS.md §10.2、§10.6）。
 */
class ExploreUserProfileUseCaseTest {

    private static final UserProfileId PROFILE_ID = new UserProfileId("user-profile-1");

    private static final int INITIAL_REVISION = 1;

    private static final String USER_INPUT = "我平时喜欢自己找图片做头像，但每次都要手动裁剪很麻烦";

    private final InMemoryUserProfileRepository repository = new InMemoryUserProfileRepository();

    private final StubAiGateway aiGateway = new StubAiGateway();

    private final ExploreUserProfileUseCase useCase = new ExploreUserProfileUseCase(
            repository, new ProfileExtraction(aiGateway, new ObjectMapper()));

    @Test
    void updatesProfileFromProposal() {
        seedProfile();
        int savesBeforeExplore = repository.saveCount();
        aiGateway.respond("""
                {
                  "interests": ["图片处理"],
                  "painPoints": ["头像裁剪麻烦"],
                  "evidenceClaims": ["用户需要频繁处理头像图片"]
                }
                """);

        UserProfile updated = useCase.explore(PROFILE_ID, USER_INPUT);

        assertEquals(List.of("图片处理"), updated.interests());
        assertEquals(List.of("头像裁剪麻烦"), updated.painPoints());
        assertEquals(List.of(), updated.behaviors());

        assertEquals(1, updated.evidence().size());
        Evidence evidence = updated.evidence().get(0);
        assertEquals(EvidenceSourceType.USER_INPUT, evidence.sourceType());
        assertEquals(USER_INPUT, evidence.sourceRef(), "Evidence 必须追溯到本轮用户输入");
        assertEquals("用户需要频繁处理头像图片", evidence.claim());
        assertNull(evidence.confidence(), "领域模型未规定 confidence 口径，不由本层臆造");
        assertFalse(evidence.confirmed(), "AI 提取属于推断，不等于用户已确认");

        // 两个内容区各推进一次 + 一条 Evidence 一次
        assertEquals(INITIAL_REVISION + 3, updated.revision());
        assertEquals(savesBeforeExplore + 1, repository.saveCount());
    }

    /**
     * AI 输入必须同时包含当前 Profile 与本轮用户输入。
     */
    @Test
    void sendsCurrentProfileAndUserInputToAi() {
        UserProfile profile = seedProfile();
        profile.updateProjectGoals(List.of("做出一个自己会用的工具"));
        aiGateway.respond("{}");

        useCase.explore(PROFILE_ID, USER_INPUT);

        AiRequest request = aiGateway.lastRequest();
        assertEquals(AiResponseFormat.JSON, request.responseFormat());
        assertEquals(2, request.messages().size());
        assertEquals(AiRole.SYSTEM, request.messages().get(0).role());
        assertEquals(AiRole.USER, request.messages().get(1).role());

        String context = request.messages().get(1).content();
        assertTrue(context.contains(USER_INPUT), "请求上下文应包含本轮用户输入");
        assertTrue(context.contains("做出一个自己会用的工具"), "请求上下文应包含当前 Profile 内容");

        assertEquals(2, profile.revision(), "空建议不改变任何内容，也不推进 revision");
    }

    @Test
    void keepsSectionsThatProposalOmits() {
        UserProfile profile = seedProfile();
        profile.updateBehaviors(List.of("已有行为"));
        aiGateway.respond("{\"interests\":[\"新兴趣\"]}");

        UserProfile updated = useCase.explore(PROFILE_ID, USER_INPUT);

        assertEquals(List.of("新兴趣"), updated.interests());
        assertEquals(List.of("已有行为"), updated.behaviors(), "未提供的内容区保持原值");
    }

    @Test
    void doesNotAdvanceRevisionWhenProposalChangesNothing() {
        UserProfile profile = seedProfile();
        aiGateway.respond("{\"interests\":[]}");
        int revisionBefore = profile.revision();

        UserProfile updated = useCase.explore(PROFILE_ID, USER_INPUT);

        assertEquals(revisionBefore, updated.revision(),
                "无实际变化时不得由本层人为推进 revision");
    }

    /**
     * AI 流程不能改变 Profile 状态：无论模型输出什么，explore 都只改内容。
     *
     * <p>确认（REVIEWING → CONFIRMED）只能由用户显式请求触发，
     * AI 侧不存在通往它的路径。
     */
    @Test
    void neverChangesProfileStatus() {
        UserProfile profile = seedProfile();
        aiGateway.respond("{\"interests\":[\"兴趣\"],\"status\":\"CONFIRMED\"}");

        UserProfile updated = useCase.explore(PROFILE_ID, USER_INPUT);

        assertEquals(UserProfileStatus.EXPLORING, updated.status());
        assertEquals(UserProfileStatus.EXPLORING, profile.status());
    }

    @Test
    void failsWithoutHalfUpdatingProfileWhenAiCallFails() {
        UserProfile profile = seedProfile();
        int revisionBefore = profile.revision();
        int savesBefore = repository.saveCount();
        aiGateway.failWith(new AiGatewayException("模型不可用"));

        assertThrows(AiGatewayException.class, () -> useCase.explore(PROFILE_ID, USER_INPUT));

        assertNothingChanged(profile, revisionBefore, savesBefore);
    }

    @Test
    void failsWithoutHalfUpdatingProfileWhenProposalCannotBeParsed() {
        UserProfile profile = seedProfile();
        int revisionBefore = profile.revision();
        int savesBefore = repository.saveCount();
        aiGateway.respond("这不是 json");

        assertThrows(AiGatewayException.class, () -> useCase.explore(PROFILE_ID, USER_INPUT));

        assertNothingChanged(profile, revisionBefore, savesBefore);
    }

    /**
     * 回归：模型在 json 之后附带解释文字时，内容不得被接受，也不得写入。
     */
    @Test
    void failsWithoutHalfUpdatingProfileWhenProposalHasTrailingContent() {
        UserProfile profile = seedProfile();
        int revisionBefore = profile.revision();
        int savesBefore = repository.saveCount();
        aiGateway.respond("{\"interests\":[\"changed\"]} THIS IS NOT JSON");

        assertThrows(AiGatewayException.class, () -> useCase.explore(PROFILE_ID, USER_INPUT));

        assertNothingChanged(profile, revisionBefore, savesBefore);
    }

    /**
     * 回归：建议前半部分合法、后半部分被 Aggregate 拒绝时，从 Repository 读到的对象
     * 不得留下半更新——interests 已经被应用到内存对象上正是这里要防住的情况。
     */
    @Test
    void failsWithoutHalfUpdatingProfileWhenProposalBreaksDomainRulesMidway() {
        UserProfile profile = seedProfile();
        int revisionBefore = profile.revision();
        int savesBefore = repository.saveCount();
        aiGateway.respond("{\"interests\":[\"这个区会先被应用\"],\"behaviors\":[\"  \"]}");

        assertThrows(IllegalArgumentException.class, () -> useCase.explore(PROFILE_ID, USER_INPUT));

        assertNothingChanged(profile, revisionBefore, savesBefore);
    }

    @Test
    void failsWithoutHalfUpdatingProfileWhenEvidenceClaimIsBlank() {
        UserProfile profile = seedProfile();
        int revisionBefore = profile.revision();
        int savesBefore = repository.saveCount();
        aiGateway.respond("{\"interests\":[\"兴趣\"],\"evidenceClaims\":[\"  \"]}");

        assertThrows(IllegalArgumentException.class, () -> useCase.explore(PROFILE_ID, USER_INPUT));

        assertNothingChanged(profile, revisionBefore, savesBefore);
    }

    @Test
    void failsWhenProfileDoesNotExist() {
        aiGateway.respond("{}");

        assertThrows(UserProfileNotFoundException.class,
                () -> useCase.explore(new UserProfileId("unknown-profile"), USER_INPUT));

        assertEquals(0, aiGateway.callCount(), "目标不存在时不应调用 AI");
    }

    @Test
    void rejectsBlankUserInput() {
        assertThrows(IllegalArgumentException.class, () -> useCase.explore(PROFILE_ID, "   "));

        assertEquals(0, aiGateway.callCount());
    }

    @Test
    void rejectsMissingDependencies() {
        ObjectMapper objectMapper = new ObjectMapper();

        assertThrows(IllegalArgumentException.class,
                () -> new ExploreUserProfileUseCase(null, new ProfileExtraction(aiGateway, objectMapper)));
        assertThrows(IllegalArgumentException.class,
                () -> new ExploreUserProfileUseCase(repository, null));
        assertThrows(IllegalArgumentException.class, () -> new ProfileExtraction(null, objectMapper));
        assertThrows(IllegalArgumentException.class, () -> new ProfileExtraction(aiGateway, null));
    }

    private UserProfile seedProfile() {
        UserProfile profile = UserProfile.create(PROFILE_ID);
        repository.save(profile);
        return profile;
    }

    /**
     * 断言 Profile 完全没有被改动。
     *
     * <p>三个角度都要检查，不能只看 {@code saveCount}：从 Repository 读到的那个对象本身
     * 不得留下半更新，重新读取的内容与 revision 也不得变化。
     */
    private void assertNothingChanged(UserProfile original, int revisionBefore, int savesBefore) {
        assertEquals(List.of(), original.interests(), "原对象不得被修改");
        assertEquals(revisionBefore, original.revision(), "原对象的 revision 不得变化");

        UserProfile reloaded = repository.findById(PROFILE_ID).orElseThrow();
        assertEquals(List.of(), reloaded.interests(), "重新读取的内容不得变化");
        assertEquals(revisionBefore, reloaded.revision(), "重新读取的 revision 不得变化");
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
