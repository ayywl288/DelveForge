package com.ayywl.delveforge.domain.user;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 状态对 Profile 内容修改的约束（DOMAIN_MODEL.md §6.1）。
 *
 * <p>当前 Task 不实现 §6.1 的状态转换，因此在 Aggregate 上无法构造出
 * REVIEWING / CONFIRMED 状态，本类只能直接验证状态值自身的语义。
 * 聚合上的状态校验分支待状态转换实现后才能端到端验证。
 */
class UserProfileStatusTest {

    @Test
    void exploringAllowsProfileUpdate() {
        assertTrue(UserProfileStatus.EXPLORING.allowsProfileUpdate());
    }

    @Test
    void reviewingAllowsProfileUpdate() {
        assertTrue(UserProfileStatus.REVIEWING.allowsProfileUpdate());
    }

    @Test
    void confirmedDoesNotAllowProfileUpdate() {
        assertFalse(UserProfileStatus.CONFIRMED.allowsProfileUpdate());
    }
}
