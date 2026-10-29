package com.ayywl.delveforge.application.userdiscovery.sufficiency;

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
 * sufficient = true    missingAreas 为空，nextQuestion 可表示继续深入的问题
 * sufficient = false   missingAreas 非空；对话可以仅回复，nextQuestion 为空
 * </pre>
 *
 * <p>{@code missingAreas} 里的每一项是一句话，说明还缺哪一类信息。
 *
 * @param sufficient    是否已足够支撑 Product Direction Discovery
 * @param missingAreas  仍缺失的重要信息或维度；足够时为空列表
 * @param nextQuestion  下一问与最低充分性独立；普通对话可为空，主动索取时按语境规划
 * @param assistantReply 对当前输入的回应；不携带生命周期权限
 * @param questionArea 下一问所面向的维度，仅用于与最终缺口协调
 * @param deferredAreas 有限语境内拒答的维度，仅用于抑制追问，不作为用户事实保存
 */
public record ProfileSufficiencyProposal(
        boolean sufficient,
        List<String> missingAreas,
        String nextQuestion,
        String assistantReply,
        String questionArea,
        List<String> deferredAreas,
        String previousQuestionState,
        List<String> resumedAreas) {

    public ProfileSufficiencyProposal(boolean sufficient, List<String> missingAreas, String nextQuestion,
            String assistantReply, String questionArea, List<String> deferredAreas, String previousQuestionState) {
        this(sufficient, missingAreas, nextQuestion, assistantReply, questionArea, deferredAreas,
                previousQuestionState, List.of());
    }

    public ProfileSufficiencyProposal(boolean sufficient, List<String> missingAreas, String nextQuestion,
            String assistantReply, String questionArea, List<String> deferredAreas) {
        this(sufficient, missingAreas, nextQuestion, assistantReply, questionArea, deferredAreas, "OPEN");
    }

    public ProfileSufficiencyProposal(boolean sufficient, List<String> missingAreas, String nextQuestion) {
        this(sufficient, missingAreas, nextQuestion, null, null, List.of());
    }

    public ProfileSufficiencyProposal {
        missingAreas = List.copyOf(missingAreas);
        deferredAreas = List.copyOf(deferredAreas);
        resumedAreas = List.copyOf(resumedAreas);
    }
}
