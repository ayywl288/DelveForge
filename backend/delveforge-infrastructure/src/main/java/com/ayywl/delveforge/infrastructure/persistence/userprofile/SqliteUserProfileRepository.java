package com.ayywl.delveforge.infrastructure.persistence.userprofile;

import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link UserProfileRepository} 的 SQLite / MyBatis-Plus 实现。
 *
 * <p>SQLite、MyBatis-Plus 与持久化数据对象只出现在本包内；Domain 与 Application
 * 只看到 {@link UserProfileRepository} 与领域对象（RULE-ARCH-002、RULE-ARCH-003）。
 *
 * <h2>存储结构</h2>
 *
 * <pre>
 * user_profile                身份 + 当前 status + 当前 revision
 * user_profile_revision       已保存版本的锚点
 * user_profile_section_item   按 (profile_id, revision) 的内容区快照
 * user_profile_evidence       按 (profile_id, revision) 的 Evidence 快照
 * </pre>
 *
 * <p>只保留实际提交给 {@link #save} 的 revision：Application 可能在一次 Use Case 内
 * 连续修改多个内容区、最后只提交一次 save，因此版本号之间可能有间隔，
 * 未提交过的 revision 不产生任何记录。
 *
 * <p>快照是内容快照，只含六个内容区与 Evidence，不含 status——§6.1 的 Revision
 * 触发规则不覆盖 status，状态变化不推进 revision。
 *
 * <h2>写入规则</h2>
 *
 * <p>为避免破坏已保存的历史，以下写入被拒绝：
 *
 * <pre>
 * 过期保存        传入 revision 低于已保存的当前 revision
 * 同版本内容冲突  传入 revision 已有快照，但内容不同
 * </pre>
 *
 * <p>内容完全相同的重复保存是幂等的。当前 revision 的快照行先删后写，使重复保存
 * 不产生重复行。整个 {@code save} 在一个事务内完成。
 *
 * <p>不在此处判断 Profile 是否发生了领域意义上的变化：是否推进 revision 由
 * {@code UserProfile} Aggregate 决定，Adapter 只负责把它的当前状态写下去。
 */
@Repository
public class SqliteUserProfileRepository implements UserProfileRepository {

    private final UserProfileMapper userProfileMapper;
    private final UserProfileRevisionMapper revisionMapper;
    private final UserProfileSectionItemMapper sectionItemMapper;
    private final UserProfileEvidenceMapper evidenceMapper;

    public SqliteUserProfileRepository(UserProfileMapper userProfileMapper,
                                       UserProfileRevisionMapper revisionMapper,
                                       UserProfileSectionItemMapper sectionItemMapper,
                                       UserProfileEvidenceMapper evidenceMapper) {
        this.userProfileMapper = userProfileMapper;
        this.revisionMapper = revisionMapper;
        this.sectionItemMapper = sectionItemMapper;
        this.evidenceMapper = evidenceMapper;
    }

    @Override
    @Transactional
    public void save(UserProfile profile) {
        String profileId = profile.id().value();
        int revision = profile.revision();

        UserProfileDO stored = userProfileMapper.selectById(profileId);
        if (stored != null) {
            requireSavable(stored, profile);
        }

        UserProfileDO row = toRow(profile);
        if (stored == null) {
            userProfileMapper.insert(row);
        } else {
            userProfileMapper.updateById(row);
        }

        revisionMapper.delete(profileId, revision);
        revisionMapper.insert(profileId, revision);

        deleteSnapshot(profileId, revision);
        insertSectionItems(profileId, revision, profile);
        insertEvidence(profileId, revision, profile);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserProfile> findById(UserProfileId id) {
        UserProfileDO row = userProfileMapper.selectById(id.value());
        if (row == null) {
            return Optional.empty();
        }

        int revision = row.getRevision();
        Map<String, List<String>> sections = loadSections(row.getId(), revision);
        List<Evidence> evidence = loadEvidence(row.getId(), revision);

        return Optional.of(UserProfile.reconstitute(
                new UserProfileId(row.getId()),
                UserProfileStatus.valueOf(row.getStatus()),
                revision,
                section(sections, Section.INTERESTS),
                section(sections, Section.BEHAVIORS),
                section(sections, Section.PAIN_POINTS),
                section(sections, Section.TECHNICAL_CAPABILITIES),
                section(sections, Section.PROJECT_GOALS),
                section(sections, Section.CONSTRAINTS),
                evidence));
    }

    /**
     * 拒绝会破坏已保存历史的写入。
     *
     * <p>过期保存会让当前 revision 倒退，并可能覆盖更早版本的内容；
     * 同一 revision 的不同内容会直接改写那个版本的历史快照。
     */
    private void requireSavable(UserProfileDO stored, UserProfile profile) {
        int storedRevision = stored.getRevision();
        int revision = profile.revision();

        if (revision < storedRevision) {
            throw new IllegalStateException(
                    "拒绝保存过期状态：存储中的 revision 为 " + storedRevision
                            + "，本次提交的 revision 为 " + revision);
        }
        if (revision == storedRevision && !matchesStoredContent(stored.getId(), revision, profile)) {
            throw new IllegalStateException(
                    "拒绝用不同内容覆盖已保存的 revision " + revision);
        }
    }

    /**
     * 本次提交的内容是否与存储中同一 revision 的内容快照一致。
     *
     * <p>只比较六个内容区与 Evidence：按 §6.1 的 revision 口径，只有它们构成
     * 版本内容；status 变化不推进 revision，因此同一 revision 下允许 status 不同。
     */
    private boolean matchesStoredContent(String profileId, int revision, UserProfile profile) {
        Map<String, List<String>> sections = loadSections(profileId, revision);
        return section(sections, Section.INTERESTS).equals(profile.interests())
                && section(sections, Section.BEHAVIORS).equals(profile.behaviors())
                && section(sections, Section.PAIN_POINTS).equals(profile.painPoints())
                && section(sections, Section.TECHNICAL_CAPABILITIES)
                        .equals(profile.technicalCapabilities())
                && section(sections, Section.PROJECT_GOALS).equals(profile.projectGoals())
                && section(sections, Section.CONSTRAINTS).equals(profile.constraints())
                && loadEvidence(profileId, revision).equals(profile.evidence());
    }

    private static UserProfileDO toRow(UserProfile profile) {
        UserProfileDO row = new UserProfileDO();
        row.setId(profile.id().value());
        row.setStatus(profile.status().name());
        row.setRevision(profile.revision());
        return row;
    }

    /**
     * 删除该 revision 已有的快照行。
     *
     * <p>只作用于 {@code save} 传入的 revision，其他 revision 的快照保持不变。
     */
    private void deleteSnapshot(String profileId, int revision) {
        sectionItemMapper.delete(new LambdaQueryWrapper<UserProfileSectionItemDO>()
                .eq(UserProfileSectionItemDO::getProfileId, profileId)
                .eq(UserProfileSectionItemDO::getRevision, revision));
        evidenceMapper.delete(new LambdaQueryWrapper<UserProfileEvidenceDO>()
                .eq(UserProfileEvidenceDO::getProfileId, profileId)
                .eq(UserProfileEvidenceDO::getRevision, revision));
    }

    private void insertSectionItems(String profileId, int revision, UserProfile profile) {
        insertSectionItems(profileId, revision, Section.INTERESTS, profile.interests());
        insertSectionItems(profileId, revision, Section.BEHAVIORS, profile.behaviors());
        insertSectionItems(profileId, revision, Section.PAIN_POINTS, profile.painPoints());
        insertSectionItems(profileId, revision, Section.TECHNICAL_CAPABILITIES,
                profile.technicalCapabilities());
        insertSectionItems(profileId, revision, Section.PROJECT_GOALS, profile.projectGoals());
        insertSectionItems(profileId, revision, Section.CONSTRAINTS, profile.constraints());
    }

    private void insertSectionItems(String profileId, int revision, Section section,
                                    List<String> values) {
        for (int position = 0; position < values.size(); position++) {
            UserProfileSectionItemDO row = new UserProfileSectionItemDO();
            row.setProfileId(profileId);
            row.setRevision(revision);
            row.setSection(section.storedValue());
            row.setPosition(position);
            row.setValue(values.get(position));
            sectionItemMapper.insert(row);
        }
    }

    private void insertEvidence(String profileId, int revision, UserProfile profile) {
        List<Evidence> evidence = profile.evidence();
        for (int position = 0; position < evidence.size(); position++) {
            Evidence item = evidence.get(position);
            UserProfileEvidenceDO row = new UserProfileEvidenceDO();
            row.setProfileId(profileId);
            row.setRevision(revision);
            row.setPosition(position);
            row.setSourceType(item.sourceType().name());
            row.setSourceRef(item.sourceRef());
            row.setClaim(item.claim());
            row.setConfidence(item.confidence());
            row.setConfirmed(item.confirmed() ? 1 : 0);
            evidenceMapper.insert(row);
        }
    }

    private Map<String, List<String>> loadSections(String profileId, int revision) {
        List<UserProfileSectionItemDO> rows = sectionItemMapper.selectList(
                new LambdaQueryWrapper<UserProfileSectionItemDO>()
                        .eq(UserProfileSectionItemDO::getProfileId, profileId)
                        .eq(UserProfileSectionItemDO::getRevision, revision)
                        .orderByAsc(UserProfileSectionItemDO::getSection)
                        .orderByAsc(UserProfileSectionItemDO::getPosition));

        Map<String, List<String>> sections = new LinkedHashMap<>();
        for (UserProfileSectionItemDO row : rows) {
            sections.computeIfAbsent(row.getSection(), section -> new ArrayList<>())
                    .add(row.getValue());
        }
        return sections;
    }

    private List<Evidence> loadEvidence(String profileId, int revision) {
        List<UserProfileEvidenceDO> rows = evidenceMapper.selectList(
                new LambdaQueryWrapper<UserProfileEvidenceDO>()
                        .eq(UserProfileEvidenceDO::getProfileId, profileId)
                        .eq(UserProfileEvidenceDO::getRevision, revision)
                        .orderByAsc(UserProfileEvidenceDO::getPosition));

        List<Evidence> evidence = new ArrayList<>(rows.size());
        for (UserProfileEvidenceDO row : rows) {
            evidence.add(new Evidence(
                    EvidenceSourceType.valueOf(row.getSourceType()),
                    row.getSourceRef(),
                    row.getClaim(),
                    row.getConfidence(),
                    row.getConfirmed() != 0));
        }
        return List.copyOf(evidence);
    }

    /** 取某个内容区在该 revision 下的内容；该区没有任何条目时为空列表。 */
    private static List<String> section(Map<String, List<String>> sections, Section section) {
        return sections.getOrDefault(section.storedValue(), List.of());
    }

    /** 六个内容区在存储中的名字。 */
    private enum Section {

        INTERESTS("interests"),
        BEHAVIORS("behaviors"),
        PAIN_POINTS("painPoints"),
        TECHNICAL_CAPABILITIES("technicalCapabilities"),
        PROJECT_GOALS("projectGoals"),
        CONSTRAINTS("constraints");

        private final String storedValue;

        Section(String storedValue) {
            this.storedValue = storedValue;
        }

        String storedValue() {
            return storedValue;
        }
    }
}
