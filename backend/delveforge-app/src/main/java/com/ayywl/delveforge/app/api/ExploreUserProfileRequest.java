package com.ayywl.delveforge.app.api;

/**
 * {@code POST /api/user-profiles/{id}/explore} 的请求体：一轮用户自然语言输入。
 *
 * <p>只承载协议层的形状。输入是否为空由 Application / Domain 判定，这里不重复规则
 * （RULE-ARCH-005、RULE-DOM-002）。
 *
 * @param input 本轮用户输入的原文
 */
public record ExploreUserProfileRequest(String input) {
}
