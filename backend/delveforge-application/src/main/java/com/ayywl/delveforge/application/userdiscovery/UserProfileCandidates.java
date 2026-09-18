package com.ayywl.delveforge.application.userdiscovery;

import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;

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

    /**
     * 按标识取出已有 Profile，并返回它的隔离候选副本。
     *
     * <p>供「只需要拿到候选副本、不需要读取原对象内容」的 Use Case 使用，
     * 使「改动必须落在副本上」这条约束只存在于一处，不会被某条路径漏掉。
     *
     * @throws UserProfileNotFoundException Profile 不存在
     */
    static UserProfile loadCopy(UserProfileRepository repository, UserProfileId userProfileId) {
        return copyOf(repository.findById(userProfileId)
                .orElseThrow(() -> new UserProfileNotFoundException(userProfileId)));
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
