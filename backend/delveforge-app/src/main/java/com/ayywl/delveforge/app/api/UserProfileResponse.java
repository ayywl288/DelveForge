package com.ayywl.delveforge.app.api;

import com.ayywl.delveforge.domain.user.UserProfileStatus;
import java.util.List;

/**
 * User Profile 在 HTTP 接口上的表示。
 *
 * <p>{@code status} 使用 Domain 的取值；{@code revision} 由 Domain 决定，
 * 接口只负责呈现，不参与计算。历史 revision 不通过本接口暴露：
 * 返回的始终是当前（最新）revision。
 *
 * <p>本类型是 Interface Adapter 的 DTO，不是领域对象，也不暴露 Persistence 数据对象。
 *
 * @param id                    Profile 标识
 * @param status                当前生命周期状态
 * @param revision              当前版本
 * @param interests             兴趣与关注领域
 * @param behaviors             真实存在的行为与使用场景
 * @param painPoints            希望解决的问题或不满意之处
 * @param technicalCapabilities 当前具备的开发与技术能力
 * @param projectGoals          希望通过项目实现的目标
 * @param constraints           影响项目方向选择的重要约束
 * @param evidence              该 revision 对应的判断依据
 */
public record UserProfileResponse(
        String id,
        UserProfileStatus status,
        int revision,
        List<String> interests,
        List<String> behaviors,
        List<String> painPoints,
        List<String> technicalCapabilities,
        List<String> projectGoals,
        List<String> constraints,
        List<EvidencePayload> evidence) {
}
