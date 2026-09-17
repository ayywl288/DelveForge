package com.ayywl.delveforge.app.api;

import com.ayywl.delveforge.application.userdiscovery.CreateUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.GetUserProfileUseCase;
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
 * POST   /api/user-profiles        创建空的 EXPLORING Profile
 * GET    /api/user-profiles/{id}   读取当前（最新）revision
 * PATCH  /api/user-profiles/{id}   结构化 partial update
 * </pre>
 *
 * <p>Controller 保持轻量（RULE-ARCH-005）：解析请求、映射为 Application Use Case 的输入、
 * 把结果映射为响应。它不判断内容是否合法、不计算 revision、不参与状态转换——
 * 这些由 Application 与 Domain 决定，失败由
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

    public UserProfileController(CreateUserProfileUseCase createUserProfileUseCase,
                                 GetUserProfileUseCase getUserProfileUseCase,
                                 UpdateUserProfileUseCase updateUserProfileUseCase) {
        this.createUserProfileUseCase = createUserProfileUseCase;
        this.getUserProfileUseCase = getUserProfileUseCase;
        this.updateUserProfileUseCase = updateUserProfileUseCase;
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
