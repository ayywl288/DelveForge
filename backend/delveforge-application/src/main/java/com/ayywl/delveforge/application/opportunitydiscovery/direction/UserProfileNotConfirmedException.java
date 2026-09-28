package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStatus;

/**
 * 用于 Product Direction Discovery 的 User Profile 尚未确认。
 *
 * <p>§8.4 的前置条件与 INV-D08：用于生成 Product Direction 的 User Profile 必须处于
 * {@code CONFIRMED}。未确认的画像仍在变化，基于它得出的方向没有稳定的追溯点——
 * 记录下来的 revision 会指向一版用户从未认可过的画像。
 *
 * <p>本异常表示「调用方要求做一次发现，但这份输入还不具备条件」，而不是「调用方写错了
 * 请求」：请求本身可以理解，只是当前不能执行。Interface 层据此映射为 409，
 * 与 404（不存在）区分开。
 *
 * <p>{@code ProductDirectionDiscoveryService} 也会拒绝未确认的 Profile——那里是这条
 * 规则的权威所在。这里提前拦一次，是为了不在明知不可能成功时还去调用 AI。
 */
public class UserProfileNotConfirmedException extends RuntimeException {

    public UserProfileNotConfirmedException(UserProfileId userProfileId,
                                            UserProfileStatus status) {
        super("Product Direction Discovery 要求 User Profile 处于 CONFIRMED，当前为 "
                + status + ": " + userProfileId.value());
    }
}
