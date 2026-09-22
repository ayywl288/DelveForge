package com.ayywl.delveforge.app.api.userprofile;

import com.ayywl.delveforge.domain.user.UserProfileStatus;
import java.util.List;

/**
 * Profile Sufficiency Assessment 在 HTTP 接口上的表示。
 *
 * <p>{@code profileStatus} 是评估结束后 User Profile 的实际状态，由 Domain 决定；
 * 接口只负责呈现。信息不足时它始终是 {@code EXPLORING}。
 *
 * <p>评估结果当前不持久化，因此每次调用都会重新请求一次评估。
 *
 * <p>本类型是 Interface Adapter 的 DTO，不是领域对象，也不暴露 Persistence 数据对象。
 *
 * @param sufficient    本次评估的结论
 * @param missingAreas  仍缺失的重要信息或维度；足够时为空数组
 * @param nextQuestion  信息不足时最值得继续询问的问题；足够时为 {@code null}
 * @param profileStatus 评估结束后 User Profile 的状态
 */
public record SufficiencyAssessmentResponse(
        boolean sufficient,
        List<String> missingAreas,
        String nextQuestion,
        UserProfileStatus profileStatus) {
}
