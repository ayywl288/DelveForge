package com.ayywl.delveforge.app.api;

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
 * @param revision 用户确认时所依据的 revision
 */
public record ConfirmUserProfileRequest(Integer revision) {
}
