package com.ayywl.delveforge.application.userdiscovery;

import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.user.UserProfileId;
import java.util.List;

/**
 * 一次 User Profile 更新的结构化输入。
 *
 * <p>输入已经是结构化数据：从原始用户消息到本类型的转换（AI 分析、交互流程）
 * 不属于本层职责。
 *
 * <h2>六个内容区的语义</h2>
 *
 * <pre>
 * null       本次不涉及该区，保持 Profile 中的原值
 * 非 null    该区更新后的完整状态：整区替换旧值
 * </pre>
 *
 * <p>刻意不提供 append / merge 语义：Profile 表达的是系统“当前对用户的结构化理解”，
 * 而不是一条只增不减的记录。后续需要纠正或删除旧判断时，调用方给出该区的新内容即可。
 * 旧的 Profile 与新的用户输入如何得到这个新内容，属于 User Discovery / AI 层的职责。
 *
 * <p>{@code additionalEvidence} 与六个内容区不同：Evidence 是逐条记录的判断依据，
 * 因此本字段表示“本次新增记录的 Evidence”，为空表示本次不新增
 * （Domain 侧由 {@code UserProfile.recordEvidence} 承担，重复的条目不会重复记录）。
 *
 * @param profileId             目标 Profile 标识，不得为 {@code null}
 * @param interests             兴趣与关注领域；{@code null} 表示本次不更新
 * @param behaviors             真实存在的行为与使用场景；{@code null} 表示本次不更新
 * @param painPoints            希望解决的问题或不满意之处；{@code null} 表示本次不更新
 * @param technicalCapabilities 当前具备的开发与技术能力；{@code null} 表示本次不更新
 * @param projectGoals          希望通过项目实现的目标；{@code null} 表示本次不更新
 * @param constraints           影响项目方向选择的重要约束；{@code null} 表示本次不更新
 * @param additionalEvidence    本次新增记录的 Evidence；{@code null} 或空表示不新增
 */
public record UpdateUserProfileRequest(
        UserProfileId profileId,
        List<String> interests,
        List<String> behaviors,
        List<String> painPoints,
        List<String> technicalCapabilities,
        List<String> projectGoals,
        List<String> constraints,
        List<Evidence> additionalEvidence) {

    public UpdateUserProfileRequest {
        if (profileId == null) {
            throw new IllegalArgumentException("UpdateUserProfileRequest 必须指定 profileId");
        }
        // 其余字段允许为 null，其含义见类文档，不在此处做领域校验：
        // 内容是否合法由 UserProfile Aggregate 判定（RULE-DOM-002）。
    }
}
