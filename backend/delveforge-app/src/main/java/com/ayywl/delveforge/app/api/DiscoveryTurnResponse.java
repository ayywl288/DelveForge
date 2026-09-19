package com.ayywl.delveforge.app.api;

import java.util.List;

/**
 * 一轮 User Discovery 在 HTTP 接口上的表示。
 *
 * <p>{@code profile} 是这一轮结束时的 User Profile，它同时承载了
 * {@code status} 与 {@code revision}：{@code sufficient} 为 true 时 {@code status}
 * 一定是 {@code REVIEWING}（由 Domain 决定，不是接口层写死的）。
 *
 * <p>{@code sufficient} 为 false 时，{@code missingAreas} 与 {@code nextQuestion}
 * 描述还缺什么、下一句该问什么；调用方用 {@code nextQuestion} 向用户提问，拿到回答后
 * 再发起下一轮。循环跨 HTTP 请求完成，服务端不等待用户。
 *
 * <p>本类型是 Interface Adapter 的 DTO，不是领域对象，也不暴露 Persistence 数据对象。
 *
 * @param profile      本轮结束时的 User Profile
 * @param sufficient   本轮的充分性结论
 * @param missingAreas 仍缺失的重要信息或维度；足够时为空数组
 * @param nextQuestion 信息不足时最值得继续询问的问题；足够时为 {@code null}
 */
public record DiscoveryTurnResponse(
        UserProfileResponse profile,
        boolean sufficient,
        List<String> missingAreas,
        String nextQuestion) {
}
