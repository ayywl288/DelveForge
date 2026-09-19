package com.ayywl.delveforge.app.api;

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
import com.ayywl.delveforge.application.userdiscovery.exploration.ExploreUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.profile.CreateUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.profile.GetUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.profile.UpdateUserProfileRequest;
import com.ayywl.delveforge.application.userdiscovery.profile.UpdateUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.review.ConfirmUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.review.ContinueDiscoveryUseCase;
import com.ayywl.delveforge.application.userdiscovery.review.ReopenDiscoveryUseCase;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.AssessProfileSufficiencyUseCase;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.SufficiencyAssessment;
import com.ayywl.delveforge.application.userdiscovery.workflow.RunUserDiscoveryTurnUseCase;
import com.ayywl.delveforge.application.userdiscovery.workflow.UserDiscoveryTurn;

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
    private final ConfirmUserProfileUseCase confirmUserProfileUseCase;
    private final ContinueDiscoveryUseCase continueDiscoveryUseCase;
    private final ReopenDiscoveryUseCase reopenDiscoveryUseCase;
    private final RunUserDiscoveryTurnUseCase runUserDiscoveryTurnUseCase;

    public UserProfileController(CreateUserProfileUseCase createUserProfileUseCase,
                                 GetUserProfileUseCase getUserProfileUseCase,
                                 UpdateUserProfileUseCase updateUserProfileUseCase,
                                 ExploreUserProfileUseCase exploreUserProfileUseCase,
                                 AssessProfileSufficiencyUseCase assessProfileSufficiencyUseCase,
                                 ConfirmUserProfileUseCase confirmUserProfileUseCase,
                                 ContinueDiscoveryUseCase continueDiscoveryUseCase,
                                 ReopenDiscoveryUseCase reopenDiscoveryUseCase,
                                 RunUserDiscoveryTurnUseCase runUserDiscoveryTurnUseCase) {
        this.createUserProfileUseCase = createUserProfileUseCase;
        this.getUserProfileUseCase = getUserProfileUseCase;
        this.updateUserProfileUseCase = updateUserProfileUseCase;
        this.exploreUserProfileUseCase = exploreUserProfileUseCase;
        this.assessProfileSufficiencyUseCase = assessProfileSufficiencyUseCase;
        this.confirmUserProfileUseCase = confirmUserProfileUseCase;
        this.continueDiscoveryUseCase = continueDiscoveryUseCase;
        this.reopenDiscoveryUseCase = reopenDiscoveryUseCase;
        this.runUserDiscoveryTurnUseCase = runUserDiscoveryTurnUseCase;
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

    /**
     * 面向产品的一轮 User Discovery：一轮用户输入完成「提取 → 更新 → 评估 → 必要时进入
     * Review」。
     *
     * <p>整轮作用在同一个候选 Profile 上并只保存一次；任何一步失败都不会留下半轮结果。
     * 返回的 {@code sufficient} 为 false 时，调用方用 {@code nextQuestion} 向用户提问，
     * 拿到回答后再发起下一轮——循环跨 HTTP 请求完成，服务端不等待用户。
     */
    @PostMapping("/{id}/discovery-turn")
    public DiscoveryTurnResponse discoveryTurn(@PathVariable String id,
                                               @RequestBody ExploreUserProfileRequest request) {
        return toResponse(runUserDiscoveryTurnUseCase.run(new UserProfileId(id), request.input()));
    }

    /**
     * 用户明确确认当前 Profile（REVIEWING → CONFIRMED）。
     *
     * <p>这是人类决策边界：它只能由调用方的显式请求触发，AI 流程没有通往这里的路径。
     *
     * <p>请求必须携带用户确认时所依据的 revision；内容在用户查看之后又变化过时，
     * Domain 会拒绝这次确认并返回 409，而不是默默确认服务端的最新版本。
     * 确认不推进 revision，因此返回的 {@code status} 与 {@code revision} 组合就是
     * 后续 Product Direction Discovery 可以引用的稳定基线。
     */
    @PostMapping("/{id}/confirm")
    public UserProfileResponse confirm(@PathVariable String id,
                                       @RequestBody ConfirmUserProfileRequest request) {
        if (request.revision() == null) {
            throw new IllegalArgumentException("确认请求必须携带所依据的 revision");
        }
        return toResponse(confirmUserProfileUseCase.confirm(new UserProfileId(id), request.revision()));
    }

    /**
     * 用户在 Review 阶段选择继续探索（REVIEWING → EXPLORING）。
     */
    @PostMapping("/{id}/continue-discovery")
    public UserProfileResponse continueDiscovery(@PathVariable String id) {
        return toResponse(continueDiscoveryUseCase.continueDiscovery(new UserProfileId(id)));
    }

    /**
     * 用户重新开启探索（CONFIRMED → EXPLORING）。
     */
    @PostMapping("/{id}/reopen-discovery")
    public UserProfileResponse reopenDiscovery(@PathVariable String id) {
        return toResponse(reopenDiscoveryUseCase.reopenDiscovery(new UserProfileId(id)));
    }

    private static DiscoveryTurnResponse toResponse(UserDiscoveryTurn turn) {
        SufficiencyAssessment assessment = turn.sufficiency();
        return new DiscoveryTurnResponse(
                toResponse(turn.profile()),
                assessment.sufficient(),
                assessment.missingAreas(),
                assessment.nextQuestion());
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
