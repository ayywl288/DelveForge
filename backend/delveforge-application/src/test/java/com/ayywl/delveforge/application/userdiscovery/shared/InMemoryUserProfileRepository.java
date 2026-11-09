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

    private final Map<UserProfileId, Map<Integer, Sections>> revisions = new HashMap<>();

    @Override
    public Optional<Sections> findSections(UserProfileId id, int revision) {
        return Optional.ofNullable(revisions.getOrDefault(id, Map.of()).get(revision));
    }

    private int saveCount;

    @Override
    public void save(UserProfile profile) {
        if (!java.util.Objects.equals(sufficientRevisions.get(profile.id()), profile.revision())) {
            sufficientRevisions.remove(profile.id());
        }
        revisions.computeIfAbsent(profile.id(), ignored -> new HashMap<>()).put(profile.revision(),
                new Sections(profile.interests(), profile.behaviors(), profile.painPoints(),
                        profile.technicalCapabilities(), profile.projectGoals(), profile.constraints()));
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
        recordSufficiencyAssessment(profile, sufficient);
    }

    @Override
    public synchronized void saveReviewIfSufficient(UserProfile profile, UserProfile expected) {
        requireUnchanged(expected);
        if (!profile.id().equals(expected.id()) || profile.revision() != expected.revision()
                || !hasSufficientAssessment(expected.id(), expected.revision())) {
            throw new com.ayywl.delveforge.domain.user.UserProfileStateException("审阅所复用的充分性记录已失效");
        }
        save(profile);
    }

    @Override
    public synchronized void recordSufficiencyAssessment(UserProfile expected, boolean sufficient) {
        requireUnchanged(expected);
        if (sufficient) {
            sufficientRevisions.put(expected.id(), expected.revision());
        } else {
            sufficientRevisions.remove(expected.id());
        }
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
