package com.ayywl.delveforge.application.userdiscovery.shared;

import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.userdiscovery.exploration.ProfileUpdateProposal;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;

import java.util.HashMap;
import java.util.List;
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

    private final Map<UserProfileId, java.util.Set<String>> protections = new HashMap<>();
    private final Map<String, ProfileUpdateProposal> proposals = new HashMap<>();

    @Override
    public List<String> protectedSections(UserProfileId id) {
        return List.copyOf(protections.getOrDefault(id, java.util.Set.of()));
    }

    @Override
    public synchronized void saveHumanUpdate(UserProfile profile, UserProfile expected, List<String> sections) {
        saveIfUnchanged(profile, expected);
        protections.computeIfAbsent(profile.id(), ignored -> new java.util.HashSet<>()).addAll(sections);
        pendingProfileUpdate(profile.id()).ifPresent(proposal -> proposals.put(proposal.id(), proposal.resolved("SUPERSEDED", null)));
    }

    @Override
    public synchronized void saveExtraction(UserProfile profile, UserProfile expected, ProfileUpdateProposal proposal) {
        requireUnchanged(expected);
        if (pendingProfileUpdate(expected.id()).isPresent()) {
            throw new com.ayywl.delveforge.domain.user.UserProfileStateException("请先处理待批提案");
        }
        if (proposal == null) {
            save(profile);
        } else {
            proposals.put(proposal.id(), proposal);
        }
    }

    @Override
    public synchronized void saveExtractionWithSufficiency(UserProfile profile, UserProfile expected,
            ProfileUpdateProposal proposal, boolean sufficient) {
        saveExtraction(profile, expected, proposal);
        recordSufficiencyAssessment(profile, sufficient);
    }

    @Override
    public Optional<ProfileUpdateProposal> pendingProfileUpdate(UserProfileId id) {
        return proposals.values().stream().filter(proposal -> proposal.profileId().equals(id.value())
                && proposal.state().equals("PENDING")).findFirst();
    }

    @Override
    public Optional<ProfileUpdateProposal> profileUpdate(UserProfileId id, String proposalId) {
        return Optional.ofNullable(proposals.get(proposalId)).filter(proposal -> proposal.profileId().equals(id.value()));
    }

    @Override
    public synchronized ProfileUpdateProposal resolveProfileUpdate(UserProfile profile, UserProfile expected,
            String proposalId, String state) {
        requireUnchanged(expected);
        ProfileUpdateProposal proposal = profileUpdate(expected.id(), proposalId).orElseThrow();
        if (!proposal.state().equals("PENDING")) {
            return proposal;
        }
        ProfileUpdateProposal resolved = proposal.resolved(state, state.equals("ACCEPTED") ? profile.revision() : null);
        proposals.put(proposalId, resolved);
        if (state.equals("ACCEPTED")) {
            saveHumanUpdate(profile, expected, List.copyOf(proposal.proposedSections().keySet()));
        }
        return resolved;
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
