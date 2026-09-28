package com.ayywl.delveforge.app.api.userprofile;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * {@code POST /api/user-profiles/{id}/confirm} 的请求体。
 *
 * <p>必须携带用户做出确认时所看的 {@code revision}：确认的意义是「用户同意了这一版
 * 内容」，而不是「确认服务端当前碰巧是什么版本」。如果用户查看之后内容又变化过，
 * 携带的 revision 会与当前 revision 不一致，请求会被 Domain 拒绝并返回 409。
 *
 * <p>{@code revision} 用包装类型而不是 {@code int}，是为了把「字段缺失」与
 * 「revision 为 0」区分开：前者是请求形状错误（400），后者是版本不匹配（409）。
 *
 * <p>还有一类取值是包装类型拦不住的：{@code "revision": 3.9}。Jackson 默认把浮点数有损地
 * 读成整数，等到 Controller 判断「有没有给」时，字段已经是 {@code 3}——一个看起来完全
 * 合法、且可能与当前版本相等的版本号，于是一次「用户确认了第 3.9 版」的请求会照着第 3 版
 * 成功确认。信息在进入本类型之前就已经丢掉。因此该字段带
 * {@link UserProfileRevisionDeserializer}：只接受 JSON 整数，小数、指数写法、字符串等一律
 * 在反序列化阶段失败并映射为 400，拒绝发生在任何业务代码被调用之前。
 *
 * @param revision 用户确认时所依据的 revision；必须是 JSON 整数
 */
public record ConfirmUserProfileRequest(
        @JsonDeserialize(using = UserProfileRevisionDeserializer.class) Integer revision) {
}
