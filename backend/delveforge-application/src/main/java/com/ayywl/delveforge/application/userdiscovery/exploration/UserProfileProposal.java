package com.ayywl.delveforge.application.userdiscovery.exploration;

import java.util.List;
import com.ayywl.delveforge.application.userdiscovery.profile.UpdateUserProfileUseCase;

/**
 * AI 提出的 User Profile 更新建议。
 *
 * <p>它是 AI 边界上的中间数据，不是领域对象（DOMAIN_MODEL.md §14.11）：
 * 在被 Aggregate 接受之前，它不构成任何合法领域状态，也不携带领域身份与生命周期。
 *
 * <p>六个内容区沿用 {@code UpdateUserProfileUseCase} 的语义：
 *
 * <pre>
 * null     本次不建议修改该区
 * 非 null  该区建议替换成的完整内容
 * </pre>
 *
 * <p>{@code evidenceClaims} 只承载「本轮可以支撑什么判断」这层信息。Evidence 的来源、
 * 可信程度与确认状态刻意不由模型给出——那部分可追溯信息由 Application 补齐，
 * 使模型无法虚构无法追溯的依据（见 {@code ExploreUserProfileUseCase}）。
 *
 * @param interests             兴趣与关注领域的建议内容
 * @param behaviors             行为与使用场景的建议内容
 * @param painPoints            痛点与不满意之处的建议内容
 * @param technicalCapabilities 技术能力的建议内容
 * @param projectGoals          项目目标的建议内容
 * @param constraints           重要约束的建议内容
 * @param evidenceClaims        本轮用户输入所支撑的判断，逐条一句话
 */
public record UserProfileProposal(
        List<String> interests,
        List<String> behaviors,
        List<String> painPoints,
        List<String> technicalCapabilities,
        List<String> projectGoals,
        List<String> constraints,
        List<String> evidenceClaims) {
}
