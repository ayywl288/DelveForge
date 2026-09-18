package com.ayywl.delveforge.application.userdiscovery;

import com.ayywl.delveforge.domain.user.UserProfile;

/**
 * 构造 User Profile 的隔离候选副本。
 *
 * <p>Use Case 不应直接在 Repository 返回的对象上应用改动：那个对象同时也是持久化状态的
 * 载体，中途失败会让它留在半更新状态——即使没有写入存储，调用方随后读到的也是被改了一半
 * 的对象。改动先落在副本上，全部成功后再交给 Repository 保存。
 *
 * <p>副本通过 {@code UserProfile.reconstitute} 按当前状态重建，因此拥有完整的领域语义：
 * 状态约束、revision 规则与内容校验都与原对象一致。
 */
final class UserProfileCandidates {

    private UserProfileCandidates() {
    }

    static UserProfile copyOf(UserProfile profile) {
        return UserProfile.reconstitute(
                profile.id(),
                profile.status(),
                profile.revision(),
                profile.interests(),
                profile.behaviors(),
                profile.painPoints(),
                profile.technicalCapabilities(),
                profile.projectGoals(),
                profile.constraints(),
                profile.evidence());
    }
}
