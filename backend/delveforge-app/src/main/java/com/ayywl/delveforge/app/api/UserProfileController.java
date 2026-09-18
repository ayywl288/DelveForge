package com.ayywl.delveforge.app.api;

import com.ayywl.delveforge.application.userdiscovery.AssessProfileSufficiencyUseCase;
import com.ayywl.delveforge.application.userdiscovery.CreateUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.ExploreUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.GetUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.SufficiencyAssessment;
import com.ayywl.delveforge.application.userdiscovery.UpdateUserProfileRequest;
import com.ayywl.delveforge.application.userdiscovery.UpdateUserProfileUseCase;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * User Profile 业务端点。
 *
 * <pre>
 * POST   /api/user-profiles                 创建空的 EXPLORING Profile
 * GET    /api/user-profiles/{id}            读取当前（最新）revision
 * PATCH  /api/user-profiles/{id}            结构化 partial update
 * POST   /api/user-profiles/{id}/explore    提交一轮用户输入，由 AI 提出建议后更新
 * </pre>
 *
 * <p>Controller 保持轻量（RULE-ARCH-005）：解析请求、映射为 Application Use Case 的输入、
 * 把结果映射为响应。它不判断内容是否合法、不计算 revision、不参与状态转换、
 * 也不接触任何 AI 调用——这些由 Application 与 Domain 决定，失败由
 * {@code com.ayywl.delveforge.app.error} 统一翻译。
 *
 * <p>{@code revision} 是否推进完全由 Domain 决定：即使 PATCH 提交的内容与当前完全一致，
 * 本层也不会人为制造一次版本变化。
 */
@RestController
@RequestMapping("/api/user-profiles")
public class UserProfileController {

    private final CreateUserProfileUseCase createUserProfileUseCase;
    private final GetUserProfileUseCase getUserProfileUseCase;
    private final UpdateUserProfileUseCase updateUserProfileUseCase;
    private final ExploreUserProfileUseCase exploreUserProfileUseCase;
    private final AssessProfileSufficiencyUseCase assessProfileSufficiencyUseCase;

    public UserProfileController(CreateUserProfileUseCase createUserProfileUseCase,
                                 GetUserProfileUseCase getUserProfileUseCase,
                                 UpdateUserProfileUseCase updateUserProfileUseCase,
                                 ExploreUserProfileUseCase exploreUserProfileUseCase,
                                 AssessProfileSufficiencyUseCase assessProfileSufficiencyUseCase) {
        this.createUserProfileUseCase = createUserProfileUseCase;
        this.getUserProfileUseCase = getUserProfileUseCase;
        this.updateUserProfileUseCase = updateUserProfileUseCase;
        this.exploreUserProfileUseCase = exploreUserProfileUseCase;
        this.assessProfileSufficiencyUseCase = assessProfileSufficiencyUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserProfileResponse create() {
        return toResponse(createUserProfileUseCase.create());
    }

    @GetMapping("/{id}")
    public UserProfileResponse get(@PathVariable String id) {
        return toResponse(getUserProfileUseCase.get(new UserProfileId(id)));
    }

    @PatchMapping("/{id}")
    public UserProfileResponse patch(@PathVariable String id,
                                     @RequestBody UserProfilePatchRequest request) {
        return toResponse(updateUserProfileUseCase.update(toApplicationRequest(id, request)));
    }

    /**
     * 提交一轮用户输入，由 AI 提出建议后更新 Profile。
     *
     * <p>AI 调用发生在 Use Case 内部；本层只负责把原始输入交出去，
     * 既不接触 Provider，也不解析模型输出。
     */
    @PostMapping("/{id}/explore")
    public UserProfileResponse explore(@PathVariable String id,
                                       @RequestBody ExploreUserProfileRequest request) {
        return toResponse(exploreUserProfileUseCase.explore(new UserProfileId(id), request.input()));
    }

    /**
     * 评估当前 Profile 的信息是否足够进入 Review 阶段。
     *
     * <p>是否需要状态转移由 Application 与 Domain 决定；本层只返回结果，
     * 不判断「足够」的含义，也不接触状态。
     */
    @PostMapping("/{id}/sufficiency-assessment")
    public SufficiencyAssessmentResponse assessSufficiency(@PathVariable String id) {
        return toResponse(assessProfileSufficiencyUseCase.assess(new UserProfileId(id)));
    }

    private static SufficiencyAssessmentResponse toResponse(SufficiencyAssessment assessment) {
        return new SufficiencyAssessmentResponse(
                assessment.sufficient(),
                assessment.missingAreas(),
                assessment.nextQuestion(),
                assessment.profileStatus());
    }

    private static UpdateUserProfileRequest toApplicationRequest(String id,
                                                                 UserProfilePatchRequest request) {
        return new UpdateUserProfileRequest(
                new UserProfileId(id),
                request.interests(),
                request.behaviors(),
                request.painPoints(),
                request.technicalCapabilities(),
                request.projectGoals(),
                request.constraints(),
                toEvidenceList(request.additionalEvidence()));
    }

    /** {@code null} 表示本次不新增 Evidence，与内容区的「未提供」语义一致。 */
    private static List<Evidence> toEvidenceList(List<EvidencePayload> payloads) {
        if (payloads == null) {
            return null;
        }
        return payloads.stream().map(UserProfileController::toEvidence).toList();
    }

    /**
     * 把接口层的 Evidence 表示转换为领域对象。
     *
     * <p>数组中的 {@code null} 元素是请求形状问题，不是内容值问题：它没有对应的
     * 领域对象可以构造，因此在转换处直接拒绝，而不是把 {@code null} 传给下游。
     *
     * <p>Evidence 内容本身的合法性（{@code sourceRef} / {@code claim} 为空等）
     * 仍由 Domain 判定，这里不重复领域规则。
     */
    private static Evidence toEvidence(EvidencePayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("additionalEvidence 不能包含 null 元素");
        }
        return new Evidence(
                payload.sourceType(),
                payload.sourceRef(),
                payload.claim(),
                payload.confidence(),
                payload.confirmed());
    }

    private static UserProfileResponse toResponse(UserProfile profile) {
        return new UserProfileResponse(
                profile.id().value(),
                profile.status(),
                profile.revision(),
                profile.interests(),
                profile.behaviors(),
                profile.painPoints(),
                profile.technicalCapabilities(),
                profile.projectGoals(),
                profile.constraints(),
                profile.evidence().stream().map(UserProfileController::toPayload).toList());
    }

    private static EvidencePayload toPayload(Evidence evidence) {
        return new EvidencePayload(
                evidence.sourceType(),
                evidence.sourceRef(),
                evidence.claim(),
                evidence.confidence(),
                evidence.confirmed());
    }
}
