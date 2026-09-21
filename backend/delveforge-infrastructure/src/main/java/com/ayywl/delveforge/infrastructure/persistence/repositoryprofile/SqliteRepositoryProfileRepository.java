package com.ayywl.delveforge.infrastructure.persistence.repositoryprofile;

import com.ayywl.delveforge.application.port.persistence.RepositoryProfileAlreadyExistsException;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link RepositoryProfileRepository} 的 SQLite / MyBatis-Plus 实现。
 *
 * <p>SQLite、MyBatis-Plus 与持久化数据对象只出现在本包内；Domain 与 Application
 * 只看到 {@link RepositoryProfileRepository} 与领域对象（RULE-ARCH-002、RULE-ARCH-003）。
 *
 * <h2>存储结构</h2>
 *
 * <pre>
 * repository_profile                身份 + 被分析的 asset + analyzedRevision + purpose
 * repository_profile_section_item   按 (profile_id, section) 的分析内容，保留 position
 * repository_profile_evidence       按 (profile_id) 的 Evidence，保留 position
 * </pre>
 *
 * <p>三张表都没有 revision 维度：一次分析对应一行，Profile 自己就是某个
 * {@code analyzedRevision} 的快照，不需要再有版本划分。
 *
 * <h2>写入规则</h2>
 *
 * <p>只写入一次：该标识已经存在时拒绝，不覆盖、也不静默忽略——已有快照可能仍被历史
 * Product Direction 或 Evolution Plan 引用（§10.4、INV-D04）。检查与写入在同一个
 * 事务内，避免并发写入绕过该判断。
 *
 * <p>写入是整体的：身份行、内容行与 Evidence 行要么都写入，要么都不写入，
 * 不暴露只写入一部分的中间状态。
 *
 * <p>本类与本项目 {@code SqliteSoftwareAssetRepository} 的按标识覆盖不同：
 * 那里保存的是可能变化的资产元数据，这里保存的是不可改写的分析快照。
 * </p>
 */
@Repository
public class SqliteRepositoryProfileRepository implements RepositoryProfileRepository {

    private final RepositoryProfileMapper profileMapper;
    private final RepositoryProfileSectionItemMapper sectionItemMapper;
    private final RepositoryProfileEvidenceMapper evidenceMapper;

    public SqliteRepositoryProfileRepository(RepositoryProfileMapper profileMapper,
                                             RepositoryProfileSectionItemMapper sectionItemMapper,
                                             RepositoryProfileEvidenceMapper evidenceMapper) {
        this.profileMapper = profileMapper;
        this.sectionItemMapper = sectionItemMapper;
        this.evidenceMapper = evidenceMapper;
    }

    @Override
    @Transactional
    public void save(RepositoryProfile profile) {
        String profileId = profile.id().value();

        if (profileMapper.selectById(profileId) != null) {
            throw new RepositoryProfileAlreadyExistsException(profile.id());
        }

        profileMapper.insert(toRow(profile));
        insertSectionItems(profileId, profile);
        insertEvidence(profileId, profile);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RepositoryProfile> findById(RepositoryProfileId id) {
        RepositoryProfileDO row = profileMapper.selectById(id.value());
        if (row == null) {
            return Optional.empty();
        }

        Map<String, List<String>> sections = loadSections(row.getId());

        return Optional.of(RepositoryProfile.reconstitute(
                new RepositoryProfileId(row.getId()),
                new SoftwareAssetId(row.getAssetId()),
                row.getAnalyzedRevision(),
                row.getPurpose(),
                section(sections, Section.TECH_STACK),
                section(sections, Section.MODULES),
                section(sections, Section.CAPABILITIES),
                section(sections, Section.REUSABLE_ASSETS),
                section(sections, Section.LIMITATIONS),
                section(sections, Section.RISKS),
                loadEvidence(row.getId())));
    }

    private static RepositoryProfileDO toRow(RepositoryProfile profile) {
        RepositoryProfileDO row = new RepositoryProfileDO();
        row.setId(profile.id().value());
        row.setAssetId(profile.assetId().value());
        row.setAnalyzedRevision(profile.analyzedRevision());
        row.setPurpose(profile.purpose());
        return row;
    }

    private void insertSectionItems(String profileId, RepositoryProfile profile) {
        insertSectionItems(profileId, Section.TECH_STACK, profile.techStack());
        insertSectionItems(profileId, Section.MODULES, profile.modules());
        insertSectionItems(profileId, Section.CAPABILITIES, profile.capabilities());
        insertSectionItems(profileId, Section.REUSABLE_ASSETS, profile.reusableAssets());
        insertSectionItems(profileId, Section.LIMITATIONS, profile.limitations());
        insertSectionItems(profileId, Section.RISKS, profile.risks());
    }

    private void insertSectionItems(String profileId, Section section, List<String> values) {
        for (int position = 0; position < values.size(); position++) {
            RepositoryProfileSectionItemDO row = new RepositoryProfileSectionItemDO();
            row.setProfileId(profileId);
            row.setSection(section.storedValue());
            row.setPosition(position);
            row.setValue(values.get(position));
            sectionItemMapper.insert(row);
        }
    }

    private void insertEvidence(String profileId, RepositoryProfile profile) {
        List<Evidence> evidence = profile.evidence();
        for (int position = 0; position < evidence.size(); position++) {
            Evidence item = evidence.get(position);
            RepositoryProfileEvidenceDO row = new RepositoryProfileEvidenceDO();
            row.setProfileId(profileId);
            row.setPosition(position);
            row.setSourceType(item.sourceType().name());
            row.setSourceRef(item.sourceRef());
            row.setClaim(item.claim());
            row.setConfidence(item.confidence());
            row.setConfirmed(item.confirmed() ? 1 : 0);
            evidenceMapper.insert(row);
        }
    }

    private Map<String, List<String>> loadSections(String profileId) {
        List<RepositoryProfileSectionItemDO> rows = sectionItemMapper.selectList(
                new LambdaQueryWrapper<RepositoryProfileSectionItemDO>()
                        .eq(RepositoryProfileSectionItemDO::getProfileId, profileId)
                        .orderByAsc(RepositoryProfileSectionItemDO::getSection)
                        .orderByAsc(RepositoryProfileSectionItemDO::getPosition));

        Map<String, List<String>> sections = new LinkedHashMap<>();
        for (RepositoryProfileSectionItemDO row : rows) {
            sections.computeIfAbsent(row.getSection(), section -> new ArrayList<>())
                    .add(row.getValue());
        }
        return sections;
    }

    private List<Evidence> loadEvidence(String profileId) {
        List<RepositoryProfileEvidenceDO> rows = evidenceMapper.selectList(
                new LambdaQueryWrapper<RepositoryProfileEvidenceDO>()
                        .eq(RepositoryProfileEvidenceDO::getProfileId, profileId)
                        .orderByAsc(RepositoryProfileEvidenceDO::getPosition));

        List<Evidence> evidence = new ArrayList<>(rows.size());
        for (RepositoryProfileEvidenceDO row : rows) {
            evidence.add(new Evidence(
                    EvidenceSourceType.valueOf(row.getSourceType()),
                    row.getSourceRef(),
                    row.getClaim(),
                    row.getConfidence(),
                    row.getConfirmed() != 0));
        }
        return List.copyOf(evidence);
    }

    /** 取某个内容区的已存内容；该区没有任何条目时为空列表。 */
    private static List<String> section(Map<String, List<String>> sections, Section section) {
        return sections.getOrDefault(section.storedValue(), List.of());
    }

    /** 六个分析内容区在存储中的名字，取领域字段名。 */
    private enum Section {

        TECH_STACK("techStack"),
        MODULES("modules"),
        CAPABILITIES("capabilities"),
        REUSABLE_ASSETS("reusableAssets"),
        LIMITATIONS("limitations"),
        RISKS("risks");

        private final String storedValue;

        Section(String storedValue) {
            this.storedValue = storedValue;
        }

        String storedValue() {
            return storedValue;
        }
    }
}
