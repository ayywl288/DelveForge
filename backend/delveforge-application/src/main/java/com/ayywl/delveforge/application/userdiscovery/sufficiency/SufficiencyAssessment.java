package com.ayywl.delveforge.application.userdiscovery.sufficiency;

import com.ayywl.delveforge.domain.user.UserProfileStatus;

import java.util.List;

/**
 * 一次 Profile Sufficiency Assessment 的结果。
 *
 * <p>它不是领域对象；持久对话入口会将结果与请求、画像版本和消息一起提交，
 * 同版的最低充分性结论可复用，但不授予审阅或确认权限。
 *
 * <p>{@code profileStatus} 是评估结束后 Profile 的实际状态：信息不足时保持
 * {@code EXPLORING}；普通对话充分时仍为 {@code EXPLORING}，显式审阅请求
 * 充分且 Domain 允许转移时为 {@code REVIEWING}。
 *
 * @param sufficient    本次评估的结论
 * @param missingAreas  仍缺失的重要信息或维度；足够时为空列表
 * @param nextQuestion  探索时的可选下一问，与 sufficient 独立
 * @param profileStatus 评估结束后 User Profile 的状态
 * @param assistantReply 对话轮的回应正文；显式审阅评估不生成正文
 */
public record SufficiencyAssessment(
        boolean sufficient,
        List<String> missingAreas,
        String nextQuestion,
        UserProfileStatus profileStatus,
        String assistantReply,
        String questionArea,
        List<String> deferredAreas,
        String previousQuestionState,
        List<String> resumedAreas,
        DialogueAction nextAction,
        boolean reviewInvitation) {

    public SufficiencyAssessment(
            boolean sufficient,
            List<String> missingAreas,
            String nextQuestion,
            UserProfileStatus profileStatus,
            String assistantReply,
            String questionArea,
            List<String> deferredAreas,
            String previousQuestionState,
            List<String> resumedAreas) {
        this(sufficient, missingAreas, nextQuestion, profileStatus, assistantReply, questionArea, deferredAreas,
                previousQuestionState, resumedAreas, nextQuestion == null ? DialogueAction.PAUSE : DialogueAction.ASK, false);
    }

    public SufficiencyAssessment(boolean sufficient, List<String> missingAreas, String nextQuestion,
            UserProfileStatus profileStatus, String assistantReply, String questionArea, List<String> deferredAreas,
            String previousQuestionState) {
        this(sufficient, missingAreas, nextQuestion, profileStatus, assistantReply, questionArea, deferredAreas,
                previousQuestionState, List.of());
    }

    public SufficiencyAssessment(boolean sufficient, List<String> missingAreas, String nextQuestion,
            UserProfileStatus profileStatus, String assistantReply, String questionArea, List<String> deferredAreas) {
        this(sufficient, missingAreas, nextQuestion, profileStatus, assistantReply, questionArea, deferredAreas, "OPEN");
    }

    public SufficiencyAssessment(boolean sufficient, List<String> missingAreas, String nextQuestion,
            UserProfileStatus profileStatus, String assistantReply) {
        this(sufficient, missingAreas, nextQuestion, profileStatus, assistantReply, null, List.of());
    }

    public SufficiencyAssessment(boolean sufficient, List<String> missingAreas,
            String nextQuestion, UserProfileStatus profileStatus) {
        this(sufficient, missingAreas, nextQuestion, profileStatus, null);
    }

    public SufficiencyAssessment {
        missingAreas = List.copyOf(missingAreas);
        deferredAreas = List.copyOf(deferredAreas);
        resumedAreas = resumedAreas == null ? List.of() : List.copyOf(resumedAreas);
        if (profileStatus == null) {
            throw new IllegalArgumentException("SufficiencyAssessment 必须包含 profileStatus");
        }
    }
}
