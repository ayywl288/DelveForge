package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import com.ayywl.delveforge.domain.user.UserProfileId;

/**
 * 调用方要求依据的那一版 User Profile 已经不是当前版本。
 *
 * <p>调用方声明「我依据的是第 N 版」，而存储中的 Profile 已经不在那一版上。此时不能
 * 默默改用当前版本：用户确认与查看的是第 N 版，拿之后的版本去推荐，得到的就不再是他
 * 认可过的那份画像所支持的方向。
 *
 * <p>它同时意味着调用方手上的信息已经过期——通常是因为期间又发生了一轮用户探索。
 * 正确的处理是让调用方重新读取 Profile、重新确认，而不是由这里替它决定。
 *
 * <p>与 {@link UserProfileNotConfirmedException} 一样，这是可预期的冲突而不是请求写错，
 * Interface 层据此映射为 409。
 */
public class StaleUserProfileRevisionException extends RuntimeException {

    public StaleUserProfileRevisionException(UserProfileId userProfileId,
                                             int expectedRevision,
                                             int actualRevision) {
        super("本次发现所依据的 User Profile 版本已过期: 期望 " + expectedRevision
                + "，当前为 " + actualRevision + "（" + userProfileId.value()
                + "）；请重新读取 Profile 后再发起发现");
    }
}
