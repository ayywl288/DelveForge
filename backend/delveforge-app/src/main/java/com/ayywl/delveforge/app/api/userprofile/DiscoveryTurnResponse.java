package com.ayywl.delveforge.app.api.userprofile;

import java.util.List;

/**
 * 一轮 User Discovery 在 HTTP 接口上的表示。
 *
 * <p>{@code profile} 是这一轮结束时的 User Profile，它同时承载了
 * {@code status} 与 {@code revision}：本轮仍为 {@code EXPLORING}；{@code sufficient}
 * 为 true 只代表最低审阅条件满足，不代表用户已经选择结束探索。
 *
 * <p>{@code missingAreas} 描述最低条件还缺什么；无论充分与否，
 * 调用方用 {@code nextQuestion} 向用户提问，拿到回答后
 * 再发起下一轮。循环跨 HTTP 请求完成，服务端不等待用户。
 *
 * <p>本类型是 Interface Adapter 的 DTO，不是领域对象，也不暴露 Persistence 数据对象。
 *
 * @param profile      本轮结束时的 User Profile
 * @param sufficient   本轮的充分性结论
 * @param missingAreas 仍缺失的重要信息或维度；足够时为空数组
 * @param nextQuestion 当前探索的下一问，充分后仍可继续深入
 */
public record DiscoveryTurnResponse(
        UserProfileResponse profile,
        boolean sufficient,
        List<String> missingAreas,
        String nextQuestion) {
}
