package com.ayywl.delveforge.application.userdiscovery.shared;

import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@link UserProfileRepository} 的测试替身。
 *
 * <p>Application 测试在 Port 边界使用替身，从而不依赖 Spring、SQLite、MyBatis-Plus
 * 或任何 Infrastructure 实现（AGENTS.md §10.2）。
 *
 * <p>保存的是 {@code UserProfile} 实例本身，不做深拷贝：当前 Domain 没有提供
 * “按已存储状态重建 Profile”的入口，因此替身无法模拟真实 Adapter 的读写分离。
 * 由此带来的限制见测试中对更新失败场景的说明。
 */
public final class InMemoryUserProfileRepository implements UserProfileRepository {

    private final Map<UserProfileId, UserProfile> profiles = new HashMap<>();

    private final Map<UserProfileId, Integer> sufficientRevisions = new HashMap<>();

    private int saveCount;

    @Override
    public void save(UserProfile profile) {
        if (!java.util.Objects.equals(sufficientRevisions.get(profile.id()), profile.revision())) {
            sufficientRevisions.remove(profile.id());
        }
        profiles.put(profile.id(), profile);
        saveCount++;
    }

    @Override
    public Optional<UserProfile> findById(UserProfileId id) {
        return Optional.ofNullable(profiles.get(id));
    }

    @Override
    public synchronized void saveIfUnchanged(UserProfile profile, UserProfile expected) {
        requireUnchanged(expected);
        save(profile);
    }

    @Override
    public synchronized void saveWithSufficiency(UserProfile profile, UserProfile expected, boolean sufficient) {
        saveIfUnchanged(profile, expected);
        if (sufficient) { recordSufficientAssessment(profile); }
    }

    @Override
    public synchronized void recordSufficientAssessment(UserProfile expected) {
        requireUnchanged(expected);
        sufficientRevisions.put(expected.id(), expected.revision());
    }

    @Override
    public boolean hasSufficientAssessment(UserProfileId id, int revision) {
        return java.util.Objects.equals(sufficientRevisions.get(id), revision);
    }

    private void requireUnchanged(UserProfile expected) {
        UserProfile current = profiles.get(expected.id());
        if (current == null || current.revision() != expected.revision()
                || current.status() != expected.status()) {
            throw new com.ayywl.delveforge.domain.user.UserProfileStateException("画像已变化");
        }
    }

    /** 累计调用 {@link #save} 的次数。 */
    public int saveCount() {
        return saveCount;
    }
}
