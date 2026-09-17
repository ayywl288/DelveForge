package com.ayywl.delveforge.app.api;

import java.util.List;

/**
 * {@code PATCH /api/user-profiles/{id}} 的请求体。
 *
 * <h2>六个内容区的语义</h2>
 *
 * <pre>
 * 字段缺失或为 null   本次不更新该区，保持 Profile 中的原值
 * 字段存在（含 []）   该值是该区更新后的完整内容，整区替换
 * </pre>
 *
 * <p>刻意不提供追加 / 合并语义：Profile 表达的是系统当前对用户的结构化理解，
 * 而不是一条只增不减的记录。清空一个区用 {@code []}。
 *
 * <p>{@code additionalEvidence} 与内容区不同：Evidence 是逐条记录的判断依据，
 * 因此它表示本次新增哪些条目，而不是替换整个集合。
 *
 * <p>本类型只做协议层的形状定义，不校验内容是否合法——内容约束由 Domain 判定，
 * 相应的失败会由统一错误映射翻译为 {@code INVALID_REQUEST}（RULE-ARCH-005、RULE-DOM-002）。
 */
public record UserProfilePatchRequest(
        List<String> interests,
        List<String> behaviors,
        List<String> painPoints,
        List<String> technicalCapabilities,
        List<String> projectGoals,
        List<String> constraints,
        List<EvidencePayload> additionalEvidence) {
}
