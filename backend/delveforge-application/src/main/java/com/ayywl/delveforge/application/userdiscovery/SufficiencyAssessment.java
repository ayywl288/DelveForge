package com.ayywl.delveforge.application.userdiscovery;

import com.ayywl.delveforge.domain.user.UserProfileStatus;
import java.util.List;

/**
 * 一次 Profile Sufficiency Assessment 的结果。
 *
 * <p>它是一次评估的结论，不是领域对象，也没有独立身份与生命周期：当前不持久化评估历史，
 * 调用方拿到的就是本次的结果（ROADMAP.md M1 只要求「判断是否足够」，未要求历史）。
 *
 * <p>{@code profileStatus} 是评估结束后 Profile 的实际状态：信息不足时保持
 * {@code EXPLORING}，足够且 Domain 允许该转移时为 {@code REVIEWING}。
 *
 * @param sufficient    本次评估的结论
 * @param missingAreas  仍缺失的重要信息或维度；足够时为空列表
 * @param nextQuestion  信息不足时最值得继续询问的问题；足够时为 {@code null}
 * @param profileStatus 评估结束后 User Profile 的状态
 */
public record SufficiencyAssessment(
        boolean sufficient,
        List<String> missingAreas,
        String nextQuestion,
        UserProfileStatus profileStatus) {

    public SufficiencyAssessment {
        missingAreas = List.copyOf(missingAreas);
        if (profileStatus == null) {
            throw new IllegalArgumentException("SufficiencyAssessment 必须包含 profileStatus");
        }
    }
}
