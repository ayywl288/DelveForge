package com.ayywl.delveforge.infrastructure.persistence.productdirection;

import com.ayywl.delveforge.application.port.persistence.ProductDirectionContentConflictException;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ProductDirectionRepository} 的 SQLite / MyBatis-Plus 实现。
 *
 * <p>SQLite、MyBatis-Plus 与持久化数据对象只出现在本包内；Domain 与 Application
 * 只看到 {@link ProductDirectionRepository} 与领域对象（RULE-ARCH-002、RULE-ARCH-003）。
 *
 * <h2>存储结构</h2>
 *
 * <pre>
 * product_direction                 身份 + 用户侧/推荐内容 + 当前 status
 * product_direction_repository_profile  依据的 Repository Profile，保留 position
 * product_direction_candidate_asset     标识的 Software Asset，保留 position
 * product_direction_risk                已知风险，保留 position
 * product_direction_evidence            Evidence，保留 position
 * </pre>
 *
 * <h2>写入规则</h2>
 *
 * <p>按标识保存：不存在时写入整条方向；已经存在时更新同一个标识。
 * 这与 {@code SqliteRepositoryProfileRepository} 的「只写一次」不同——
 * Repository Profile 是不可改写的分析快照，Product Direction 是拥有生命周期的 Entity。
 *
 * <p>允许变化的只有 {@code status}。同一标识再次保存时，本类先比较已保存的
 * discovery basis、recommendation content、candidate assets 与 Evidence：
 *
 * <pre>
 * 完全一致  更新 status
 * 有任何不同 拒绝保存，抛 ProductDirectionContentConflictException
 * </pre>
 *
 * <p>拒绝而不是覆盖，是因为覆盖会把这条方向当初凭什么被推荐的依据静默改写掉
 * （§10.5、RULE-DOM-007）；拒绝而不是静默忽略，是因为静默忽略会让调用方以为
 * 自己的改动已经生效。检查与写入在同一个事务内。
 *
 * <p>内容行只在首次写入时插入，之后不再删除或重写：状态变化在存储层面根本不会
 * 碰到它们，因此「状态更新不丢失原始分析依据」不是靠比较通过后的默契，而是靠
 * 这条路径上没有任何会改动内容行的操作。
 *
 * <p>比较按存储语义进行，不直接使用领域对象的相等性：{@code Evidence} 的 record
 * equality 会区分 {@code confidence} 的正负零，而 SQLite 的 REAL 不保留负零。
 * 若照搬 record equality，一次内容完全没变的保存会因为存储往返而「自己不等于自己」，
 * 被误判成改写已有记录。见 {@link #sameEvidence} 与 {@link #canonicalConfidence}。
 *
 * <p>不提供删除：REJECTED 与 SUPERSEDED 的方向同样保留（§10.5）。
 *
 * <p>不在此处校验领域规则：方向是否合法由 {@code ProductDirection} Aggregate 决定
 * （RULE-DOM-002），本类只负责把它的当前状态写下去、按存储重建回来。
 */
@Repository
public class SqliteProductDirectionRepository implements ProductDirectionRepository {

    private final ProductDirectionMapper directionMapper;
    private final ProductDirectionRepositoryProfileMapper repositoryProfileMapper;
    private final ProductDirectionCandidateAssetMapper candidateAssetMapper;
    private final ProductDirectionRiskMapper riskMapper;
    private final ProductDirectionEvidenceMapper evidenceMapper;

    public SqliteProductDirectionRepository(
            ProductDirectionMapper directionMapper,
            ProductDirectionRepositoryProfileMapper repositoryProfileMapper,
            ProductDirectionCandidateAssetMapper candidateAssetMapper,
            ProductDirectionRiskMapper riskMapper,
            ProductDirectionEvidenceMapper evidenceMapper) {

        this.directionMapper = directionMapper;
        this.repositoryProfileMapper = repositoryProfileMapper;
        this.candidateAssetMapper = candidateAssetMapper;
        this.riskMapper = riskMapper;
        this.evidenceMapper = evidenceMapper;
    }

    @Override
    @Transactional
    public void save(ProductDirection productDirection) {
        String directionId = productDirection.id().value();
        ProductDirectionDO stored = directionMapper.selectById(directionId);

        if (stored == null) {
            directionMapper.insert(toRow(productDirection));
            insertRepositoryProfiles(directionId, productDirection);
            insertCandidateAssets(directionId, productDirection);
            insertRisks(directionId, productDirection);
            insertEvidence(directionId, productDirection);
            return;
        }

        if (!sameRecommendation(toDomain(stored), productDirection)) {
            throw new ProductDirectionContentConflictException(productDirection.id());
        }

        directionMapper.updateById(toRow(productDirection));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductDirection> findById(ProductDirectionId id) {
        ProductDirectionDO row = directionMapper.selectById(id.value());
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(toDomain(row));
    }

    /**
     * 两份方向的 discovery basis、recommendation content、candidate assets 与
     * Evidence 是否完全一致。
     *
     * <p>{@code status} 刻意不在比较范围内：它正是被允许变化的那一项。
     *
     * <p>Evidence 走 {@link #sameEvidence} 而不是 record equality，见那里的说明。
     */
    private static boolean sameRecommendation(ProductDirection stored, ProductDirection incoming) {
        return stored.userProfileId().equals(incoming.userProfileId())
                && stored.userProfileRevision() == incoming.userProfileRevision()
                && stored.repositoryProfileIds().equals(incoming.repositoryProfileIds())
                && stored.title().equals(incoming.title())
                && stored.problem().equals(incoming.problem())
                && stored.targetProduct().equals(incoming.targetProduct())
                && stored.userFit().equals(incoming.userFit())
                && stored.candidateAssetIds().equals(incoming.candidateAssetIds())
                && stored.differentiation().equals(incoming.differentiation())
                && stored.technicalValue().equals(incoming.technicalValue())
                && stored.estimatedComplexity().equals(incoming.estimatedComplexity())
                && stored.risks().equals(incoming.risks())
                && sameEvidence(stored.evidence(), incoming.evidence());
    }

    /**
     * 两组 Evidence 是否表示同一份依据，按存储语义逐条比较。
     *
     * <p>不能直接用 {@code Evidence} 的 record equality：它按 {@code Double.equals}
     * 比较 {@code confidence}，而 {@code Double.equals} 区分正零与负零。
     * SQLite 的 REAL 不保留负零——{@code -0.0} 写入后读回是 {@code 0.0}——
     * 于是同一份 Evidence 在存储往返之后会「自己不等于自己」，把一次内容完全没变的
     * 重复保存、或一次纯粹的状态更新，误判成用不同内容覆盖已有记录并予以拒绝。
     *
     * <p>因此比较发生在存储语义上：两侧的 confidence 都先经过
     * {@link #canonicalConfidence}，其余字段（含 {@code null}）仍严格比较。
     */
    private static boolean sameEvidence(List<Evidence> stored, List<Evidence> incoming) {
        if (stored.size() != incoming.size()) {
            return false;
        }
        for (int i = 0; i < stored.size(); i++) {
            Evidence left = stored.get(i);
            Evidence right = incoming.get(i);
            if (left.sourceType() != right.sourceType()
                    || !left.sourceRef().equals(right.sourceRef())
                    || !left.claim().equals(right.claim())
                    || left.confirmed() != right.confirmed()
                    || !Objects.equals(canonicalConfidence(left.confidence()),
                            canonicalConfidence(right.confidence()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把 {@code confidence} 规范成这层存储实际能保存并读回的取值。
     *
     * <p>当前唯一的差异是负零：SQLite 的 REAL 不保留它，写入 {@code -0.0} 之后读回的是
     * {@code 0.0}。于是同一份 Evidence 在存储往返之后会「自己不等于自己」，把一次内容
     * 完全没变的重复保存、或一次纯粹的状态更新，误判成用不同内容覆盖已有记录。
     *
     * <p>把正负零统一成正零，是本 Adapter 为适配该存储行为而做的实现选择，
     * 不是领域模型已经定义的规则：DOMAIN_MODEL.md §3.6 只把 {@code confidence} 描述为
     * 「对推断型 Evidence 的可信程度」，既没有规定正负零是否等价，也没有要求领域层
     * 归一化这个取值。本 Adapter 因此只在「写下去的值」与「读回来的值」之间求一致，
     * 使往返转换不制造调用方从未提交过的差异；领域对象仍然保留调用方给出的原始取值。
     *
     * <p>{@code null}（未给出确定性判断）原样保留，不与任何数值合并。
     * NaN 与无穷不可能到达这里：{@code Evidence} 已经在构造时拒绝非有限数值。
     */
    private static Double canonicalConfidence(Double confidence) {
        if (confidence == null) {
            return null;
        }
        return confidence == 0.0 ? 0.0 : confidence;
    }

    /**
     * 按已保存的状态重建方向。
     *
     * <p>恢复走 Domain 的 {@link ProductDirection#reconstitute}，不在这里绕过 Aggregate
     * 构造对象：存储中的 status 直接由调用方交给 Aggregate，Aggregate 仍会校验其余
     * 结构不变量（RULE-DOM-002）。
     */
    private ProductDirection toDomain(ProductDirectionDO row) {
        String directionId = row.getId();
        return ProductDirection.reconstitute(
                new ProductDirectionId(directionId),
                new UserProfileId(row.getUserProfileId()),
                row.getUserProfileRevision(),
                loadRepositoryProfileIds(directionId),
                row.getTitle(),
                row.getProblem(),
                row.getTargetProduct(),
                row.getUserFit(),
                loadCandidateAssetIds(directionId),
                row.getDifferentiation(),
                row.getTechnicalValue(),
                row.getEstimatedComplexity(),
                loadRisks(directionId),
                loadEvidence(directionId),
                ProductDirectionStatus.valueOf(row.getStatus()));
    }

    private static ProductDirectionDO toRow(ProductDirection productDirection) {
        ProductDirectionDO row = new ProductDirectionDO();
        row.setId(productDirection.id().value());
        row.setUserProfileId(productDirection.userProfileId().value());
        row.setUserProfileRevision(productDirection.userProfileRevision());
        row.setTitle(productDirection.title());
        row.setProblem(productDirection.problem());
        row.setTargetProduct(productDirection.targetProduct());
        row.setUserFit(productDirection.userFit());
        row.setDifferentiation(productDirection.differentiation());
        row.setTechnicalValue(productDirection.technicalValue());
        row.setEstimatedComplexity(productDirection.estimatedComplexity());
        row.setStatus(productDirection.status().name());
        return row;
    }

    private void insertRepositoryProfiles(String directionId, ProductDirection productDirection) {
        List<RepositoryProfileId> ids = productDirection.repositoryProfileIds();
        for (int position = 0; position < ids.size(); position++) {
            ProductDirectionRepositoryProfileDO row = new ProductDirectionRepositoryProfileDO();
            row.setDirectionId(directionId);
            row.setPosition(position);
            row.setRepositoryProfileId(ids.get(position).value());
            repositoryProfileMapper.insert(row);
        }
    }

    private void insertCandidateAssets(String directionId, ProductDirection productDirection) {
        List<SoftwareAssetId> ids = productDirection.candidateAssetIds();
        for (int position = 0; position < ids.size(); position++) {
            ProductDirectionCandidateAssetDO row = new ProductDirectionCandidateAssetDO();
            row.setDirectionId(directionId);
            row.setPosition(position);
            row.setAssetId(ids.get(position).value());
            candidateAssetMapper.insert(row);
        }
    }

    private void insertRisks(String directionId, ProductDirection productDirection) {
        List<String> risks = productDirection.risks();
        for (int position = 0; position < risks.size(); position++) {
            ProductDirectionRiskDO row = new ProductDirectionRiskDO();
            row.setDirectionId(directionId);
            row.setPosition(position);
            row.setValue(risks.get(position));
            riskMapper.insert(row);
        }
    }

    private void insertEvidence(String directionId, ProductDirection productDirection) {
        List<Evidence> evidence = productDirection.evidence();
        for (int position = 0; position < evidence.size(); position++) {
            Evidence item = evidence.get(position);
            ProductDirectionEvidenceDO row = new ProductDirectionEvidenceDO();
            row.setDirectionId(directionId);
            row.setPosition(position);
            row.setSourceType(item.sourceType().name());
            row.setSourceRef(item.sourceRef());
            row.setClaim(item.claim());
            row.setConfidence(canonicalConfidence(item.confidence()));
            row.setConfirmed(item.confirmed() ? 1 : 0);
            evidenceMapper.insert(row);
        }
    }

    private List<RepositoryProfileId> loadRepositoryProfileIds(String directionId) {
        List<ProductDirectionRepositoryProfileDO> rows = repositoryProfileMapper.selectList(
                new LambdaQueryWrapper<ProductDirectionRepositoryProfileDO>()
                        .eq(ProductDirectionRepositoryProfileDO::getDirectionId, directionId)
                        .orderByAsc(ProductDirectionRepositoryProfileDO::getPosition));

        List<RepositoryProfileId> ids = new ArrayList<>(rows.size());
        for (ProductDirectionRepositoryProfileDO row : rows) {
            ids.add(new RepositoryProfileId(row.getRepositoryProfileId()));
        }
        return List.copyOf(ids);
    }

    private List<SoftwareAssetId> loadCandidateAssetIds(String directionId) {
        List<ProductDirectionCandidateAssetDO> rows = candidateAssetMapper.selectList(
                new LambdaQueryWrapper<ProductDirectionCandidateAssetDO>()
                        .eq(ProductDirectionCandidateAssetDO::getDirectionId, directionId)
                        .orderByAsc(ProductDirectionCandidateAssetDO::getPosition));

        List<SoftwareAssetId> ids = new ArrayList<>(rows.size());
        for (ProductDirectionCandidateAssetDO row : rows) {
            ids.add(new SoftwareAssetId(row.getAssetId()));
        }
        return List.copyOf(ids);
    }

    private List<String> loadRisks(String directionId) {
        List<ProductDirectionRiskDO> rows = riskMapper.selectList(
                new LambdaQueryWrapper<ProductDirectionRiskDO>()
                        .eq(ProductDirectionRiskDO::getDirectionId, directionId)
                        .orderByAsc(ProductDirectionRiskDO::getPosition));

        List<String> risks = new ArrayList<>(rows.size());
        for (ProductDirectionRiskDO row : rows) {
            risks.add(row.getValue());
        }
        return List.copyOf(risks);
    }

    private List<Evidence> loadEvidence(String directionId) {
        List<ProductDirectionEvidenceDO> rows = evidenceMapper.selectList(
                new LambdaQueryWrapper<ProductDirectionEvidenceDO>()
                        .eq(ProductDirectionEvidenceDO::getDirectionId, directionId)
                        .orderByAsc(ProductDirectionEvidenceDO::getPosition));

        List<Evidence> evidence = new ArrayList<>(rows.size());
        for (ProductDirectionEvidenceDO row : rows) {
            evidence.add(new Evidence(
                    EvidenceSourceType.valueOf(row.getSourceType()),
                    row.getSourceRef(),
                    row.getClaim(),
                    row.getConfidence(),
                    row.getConfirmed() != 0));
        }
        return List.copyOf(evidence);
    }
}
