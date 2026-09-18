package com.ayywl.delveforge.application.userdiscovery;

import java.util.List;

/**
 * AI 对「当前 User Profile 信息是否足够」提出的建议。
 *
 * <p>它是 AI 边界上的中间数据，不是领域对象，也不携带任何状态：模型只能表达「够不够、
 * 缺什么、下一问是什么」，无法表达或改变 {@code UserProfileStatus}
 * （DOMAIN_MODEL.md §6.1）。是否真的进入 REVIEWING 由 Domain 决定。
 *
 * <p>两个方向的结果都是完整且自洽的：
 *
 * <pre>
 * sufficient = true    missingAreas 为空，nextQuestion 为 null
 * sufficient = false   missingAreas 非空，nextQuestion 非空
 * </pre>
 *
 * <p>{@code missingAreas} 里的每一项是一句话，说明还缺哪一类信息。
 *
 * @param sufficient    是否已足够支撑 Product Direction Discovery
 * @param missingAreas  仍缺失的重要信息或维度；足够时为空列表
 * @param nextQuestion  信息不足时最值得继续询问的问题；足够时为 {@code null}
 */
public record ProfileSufficiencyProposal(
        boolean sufficient,
        List<String> missingAreas,
        String nextQuestion) {

    public ProfileSufficiencyProposal {
        missingAreas = List.copyOf(missingAreas);
    }
}
