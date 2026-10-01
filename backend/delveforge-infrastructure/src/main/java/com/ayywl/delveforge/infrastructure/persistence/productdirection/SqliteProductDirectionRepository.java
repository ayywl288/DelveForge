package com.ayywl.delveforge.infrastructure.persistence.productdirection;

import com.ayywl.delveforge.application.port.persistence.ProductDirectionContentConflictException;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionSelectionConflictException;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.direction.DirectionEvidenceSupport;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.EvidenceOrigin;
import com.ayywl.delveforge.domain.evidence.RepositoryProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.evidence.UserProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
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
 * product_direction_evidence_support    关键判断下的依据 + 它出自哪里，保留 category 与 position
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
 * 被误判成改写已有记录。见 {@link #sameSupport} 与 {@link #canonicalConfidence}。
 *
 * <p>不提供删除：REJECTED 与 SUPERSEDED 的方向同样保留（§10.5）。
 *
 * <p>不在此处校验领域规则：方向是否合法由 {@code ProductDirection} Aggregate 决定
 * （RULE-DOM-002），本类只负责把它的当前状态写下去、按存储重建回来。
 */
@Repository
public class SqliteProductDirectionRepository implements ProductDirectionRepository {

    /** SQLite 扩展结果码的高位部分，取低八位即得主结果码。 */
    private static final int SQLITE_PRIMARY_CODE_MASK = 0xFF;

    /** SQLite 的 {@code SQLITE_CONSTRAINT}：违反约束（含唯一约束）。 */
    private static final int SQLITE_CONSTRAINT = 19;

    private final ProductDirectionMapper directionMapper;
    private final ProductDirectionRepositoryProfileMapper repositoryProfileMapper;
    private final ProductDirectionCandidateAssetMapper candidateAssetMapper;
    private final ProductDirectionRiskMapper riskMapper;
    private final ProductDirectionEvidenceSupportMapper evidenceSupportMapper;

    public SqliteProductDirectionRepository(
            ProductDirectionMapper directionMapper,
            ProductDirectionRepositoryProfileMapper repositoryProfileMapper,
            ProductDirectionCandidateAssetMapper candidateAssetMapper,
            ProductDirectionRiskMapper riskMapper,
            ProductDirectionEvidenceSupportMapper evidenceSupportMapper) {

        this.directionMapper = directionMapper;
        this.repositoryProfileMapper = repositoryProfileMapper;
        this.candidateAssetMapper = candidateAssetMapper;
        this.riskMapper = riskMapper;
        this.evidenceSupportMapper = evidenceSupportMapper;
    }

    @Override
    @Transactional
    public void save(ProductDirection productDirection) {
        write(productDirection);
    }

    /**
     * 整批写入。
     *
     * <p>{@code @Transactional} 在这里的意义与单条保存不同：它保证整批的原子性——
     * 其中任意一条失败时，这一批已经写入的部分一并回滚。这正是
     * {@link ProductDirectionRepository#saveAll} 存在的原因，而它只能在这一层实现：
     * 调用方（Application）无法自己拼出这个保证。
     */
    @Override
    @Transactional
    public void saveAll(List<ProductDirection> productDirections) {
        for (ProductDirection productDirection : productDirections) {
            write(productDirection);
        }
    }

    /**
     * 单条的写入逻辑；事务边界由调用它的公开方法决定。
     *
     * <p>唯一性冲突在这里翻译成 Port 能表达的语义，见 {@link #translateStoredConflict}。
     */
    private void write(ProductDirection productDirection) {
        try {
            writeDirection(productDirection);
        } catch (DataAccessException exception) {
            throw translateStoredConflict(productDirection, exception);
        }
    }

    private void writeDirection(ProductDirection productDirection) {
        String directionId = productDirection.id().value();
        ProductDirectionDO stored = directionMapper.selectById(directionId);

        if (stored == null) {
            directionMapper.insert(toRow(productDirection));
            insertRepositoryProfiles(directionId, productDirection);
            insertCandidateAssets(directionId, productDirection);
            insertRisks(directionId, productDirection);
            insertEvidenceSupport(directionId, productDirection);
            return;
        }

        if (!sameRecommendation(toDomain(stored), productDirection)) {
            throw new ProductDirectionContentConflictException(productDirection.id());
        }

        directionMapper.updateById(toRow(productDirection));
    }

    /**
     * 把存储层报告的约束冲突翻译成这个 Port 能表达的语义。
     *
     * <p>V7 的部分唯一索引只允许一行 {@code status = 'SELECTED'}（INV-D09）。写入一条
     * {@code SELECTED} 方向时撞上唯一约束，说明库里已经存在另一个当前方向——
     * 正常切换路径不会走到这里（它在同一个批次里先把原方向写成 {@code SUPERSEDED}），
     * 因此这通常意味着并发：两个选择请求都读到「当前没有 SELECTED 方向」，
     * 各自判定无需取代任何东西，后提交的那个失败。
     *
     * <p>只有写成 {@code SELECTED} 时才做这个翻译。更新不改 {@code id}，主键不会因此冲突；
     * 其余状态也不进入那条部分索引。因此其它失败原样抛出，不被误标成选择冲突，
     * 也不会让调用方以为「换一个方向重试」就能解决一个真正的数据问题。
     *
     * <p>翻译发生在 Adapter 边界而不是更外层：SQLite 的结果码与索引名属于本层细节，
     * 不应穿到 Application 与 Interface（AGENTS.md §8.7）。
     */
    private static RuntimeException translateStoredConflict(
            ProductDirection productDirection, DataAccessException exception) {

        if (productDirection.status() != ProductDirectionStatus.SELECTED) {
            return exception;
        }
        if (!isConstraintViolation(exception)) {
            return exception;
        }
        return new ProductDirectionSelectionConflictException(productDirection.id(), exception);
    }

    /**
     * 异常链里是否有一个「违反约束」的 SQL 错误。
     *
     * <p>不能只看 Spring 的异常类型：Spring 没有 SQLite 的错误码映射表，因此 SQLite 的
     * 约束错误会被兜底翻译成 {@link org.springframework.jdbc.UncategorizedSQLException}，
     * 而不是 {@link org.springframework.dao.DataIntegrityViolationException}。
     * 只接住后者会让真实的选择冲突以「未分类的数据访问异常」穿到上层。
     *
     * <p>因此这里看驱动给出的结果码。SQLite 的扩展码是
     * {@code 主码 | (n << 8)}，取低八位即可覆盖 {@code SQLITE_CONSTRAINT} 与它的全部
     * 扩展码（例如 {@code SQLITE_CONSTRAINT_UNIQUE}），不必逐个枚举。
     *
     * <p>按数值而不是按错误文本判断：错误文本是本地化的、随版本变化的实现细节，
     * 让它参与分支判断既脆弱又会把数据库内部信息带进判定逻辑。
     */
    private static boolean isConstraintViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && (sqlException.getErrorCode() & SQLITE_PRIMARY_CODE_MASK)
                            == SQLITE_CONSTRAINT) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
     * 查询全局当前 {@code SELECTED} 的方向（INV-D09）。
     *
     * <p>不按 User Profile、revision、Repository Profile 或候选资产收窄：领域模型没有为
     * 「同一条演化流程」定义任何持久化身份，按这些维度收窄等于替它发明一个。
     *
     * <p>正常写入路径下最多只会有一行——V7 的部分唯一索引只允许一行
     * {@code status = 'SELECTED'}。读到多于一行说明存储与领域模型不一致（更早的数据、
     * 被绕过的写入、迁移中的中间态），此时抛错而不是挑一行返回：静默挑一条会让调用方
     * 以为系统里只有一个当前方向，而它刚刚取代的那个可能才是有依据的那个。
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<ProductDirection> findCurrentSelected() {
        List<ProductDirectionDO> rows = directionMapper.selectList(
                new LambdaQueryWrapper<ProductDirectionDO>()
                        .eq(ProductDirectionDO::getStatus,
                                ProductDirectionStatus.SELECTED.name()));

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "存储中存在多于一个当前 SELECTED 的 Product Direction（INV-D09）: "
                            + rows.size() + " 条");
        }
        return Optional.of(toDomain(rows.get(0)));
    }

    /**
     * 两份方向的 discovery basis、recommendation content、candidate assets 与
     * Evidence 是否完全一致。
     *
     * <p>{@code status} 刻意不在比较范围内：它正是被允许变化的那一项。
     *
     * <p>依据走 {@link #sameSupport} 而不是 record equality，见那里的说明。
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
                && sameSupport(stored.evidenceSupport(), incoming.evidenceSupport());
    }

    /**
     * 两组「关键判断 → 依据」是否完全一致，按存储语义逐组逐条比较。
     *
     * <p>比较的是整个结构，不只是依据本身：把一条依据从 {@code userNeed} 移到
     * {@code userFit}，依据没变，但这条方向对「凭什么这么说」的回答变了，
     * 因此同样算内容冲突。依据各自的来源也在比较范围内。
     *
     * <p>不能直接用 record equality：{@code Evidence} 按 {@code Double.equals} 比较
     * {@code confidence}，而 {@code Double.equals} 区分正零与负零；SQLite 的 REAL
     * 不保留负零——{@code -0.0} 写入后读回是 {@code 0.0}——于是同一份依据在存储往返之后
     * 会「自己不等于自己」，把一次内容完全没变的重复保存、或一次纯粹的状态更新，
     * 误判成用不同内容覆盖已有记录并予以拒绝。
     *
     * <p>因此比较发生在存储语义上：两侧的 confidence 都先经过
     * {@link #canonicalConfidence}，其余字段（含 {@code null}）与来源仍严格比较。
     */
    private static boolean sameSupport(DirectionEvidenceSupport stored,
                                       DirectionEvidenceSupport incoming) {
        return sameBases(stored.userNeed(), incoming.userNeed())
                && sameBases(stored.userFit(), incoming.userFit())
                && sameBases(stored.reusableCapability(), incoming.reusableCapability());
    }

    private static boolean sameBases(List<EvidenceBasis> stored, List<EvidenceBasis> incoming) {
        if (stored.size() != incoming.size()) {
            return false;
        }
        for (int i = 0; i < stored.size(); i++) {
            EvidenceBasis left = stored.get(i);
            EvidenceBasis right = incoming.get(i);
            if (!left.origin().equals(right.origin()) || !sameEvidence(left.evidence(), right.evidence())) {
                return false;
            }
        }
        return true;
    }

    /** 两条 Evidence 是否表示同一份依据，按存储语义比较。 */
    private static boolean sameEvidence(Evidence left, Evidence right) {
        return left.sourceType() == right.sourceType()
                && left.sourceRef().equals(right.sourceRef())
                && left.claim().equals(right.claim())
                && left.confirmed() == right.confirmed()
                && Objects.equals(canonicalConfidence(left.confidence()),
                        canonicalConfidence(right.confidence()));
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
                loadEvidenceSupport(directionId),
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

    private void insertEvidenceSupport(String directionId, ProductDirection productDirection) {
        DirectionEvidenceSupport support = productDirection.evidenceSupport();
        insertBases(directionId, Category.USER_NEED, support.userNeed());
        insertBases(directionId, Category.USER_FIT, support.userFit());
        insertBases(directionId, Category.REUSABLE_CAPABILITY, support.reusableCapability());
    }

    private void insertBases(String directionId, Category category, List<EvidenceBasis> bases) {
        for (int position = 0; position < bases.size(); position++) {
            EvidenceBasis basis = bases.get(position);
            Evidence evidence = basis.evidence();

            ProductDirectionEvidenceSupportDO row = new ProductDirectionEvidenceSupportDO();
            row.setDirectionId(directionId);
            row.setCategory(category.storedValue());
            row.setPosition(position);
            row.setSourceType(evidence.sourceType().name());
            row.setSourceRef(evidence.sourceRef());
            row.setClaim(evidence.claim());
            row.setConfidence(canonicalConfidence(evidence.confidence()));
            row.setConfirmed(evidence.confirmed() ? 1 : 0);

            if (basis.origin() instanceof UserProfileEvidenceOrigin origin) {
                row.setOriginKind(ProductDirectionEvidenceSupportDO.ORIGIN_USER_PROFILE);
                row.setOriginUserProfileId(origin.userProfileId().value());
                row.setOriginUserProfileRevision(origin.userProfileRevision());
            } else if (basis.origin() instanceof RepositoryProfileEvidenceOrigin origin) {
                row.setOriginKind(ProductDirectionEvidenceSupportDO.ORIGIN_REPOSITORY_PROFILE);
                row.setOriginRepositoryProfileId(origin.repositoryProfileId().value());
            }

            evidenceSupportMapper.insert(row);
        }
    }

    /**
     * 重建某条方向的关键判断与依据。
     *
     * <p>按 category 分组、组内按 position 还原顺序。同一份依据可以出现在多个 category 中：
     * 主键包含 category，因此这不是重复行，而是「这条依据同时支撑两个判断」。
     */
    private DirectionEvidenceSupport loadEvidenceSupport(String directionId) {
        List<ProductDirectionEvidenceSupportDO> rows = evidenceSupportMapper.selectList(
                new LambdaQueryWrapper<ProductDirectionEvidenceSupportDO>()
                        .eq(ProductDirectionEvidenceSupportDO::getDirectionId, directionId)
                        .orderByAsc(ProductDirectionEvidenceSupportDO::getCategory)
                        .orderByAsc(ProductDirectionEvidenceSupportDO::getPosition));

        Map<String, List<EvidenceBasis>> byCategory = new LinkedHashMap<>();
        for (ProductDirectionEvidenceSupportDO row : rows) {
            requireKnownCategory(row.getCategory());
            byCategory.computeIfAbsent(row.getCategory(), category -> new ArrayList<>())
                    .add(toBasis(row));
        }

        return new DirectionEvidenceSupport(
                basesOf(byCategory, Category.USER_NEED),
                basesOf(byCategory, Category.USER_FIT),
                basesOf(byCategory, Category.REUSABLE_CAPABILITY));
    }

    private static EvidenceBasis toBasis(ProductDirectionEvidenceSupportDO row) {
        Evidence evidence = new Evidence(
                EvidenceSourceType.valueOf(row.getSourceType()),
                row.getSourceRef(),
                row.getClaim(),
                row.getConfidence(),
                row.getConfirmed() != 0);

        return new EvidenceBasis(evidence, toOrigin(row));
    }

    /**
     * 还原这条依据的来源。
     *
     * <p>未知的 {@code origin_kind} 会在这里失败，而不是被当成「没有来源」：
     * 一条说不出自己出自哪里的依据无法满足 INV-D06，静默降级只会让它看起来合格。
     *
     * <p>同时核对 kind 与专属列一致：{@code userProfile} 只应填 User Profile 的两列，
     * {@code repositoryProfile} 只应填 Repository Profile 的那一列。一条记录同时带着
     * 两种来源的字段是矛盾的，只读其中一种会让另一组值被静默忽略。
     * 表级 CHECK 已经挡住了这种写入，这里再查一次是为了不依赖存储是否真的约束过它——
     * 读到的数据可能来自更早的迁移、手工修改或被绕过的写入。
     */
    private static EvidenceOrigin toOrigin(ProductDirectionEvidenceSupportDO row) {
        if (ProductDirectionEvidenceSupportDO.ORIGIN_USER_PROFILE.equals(row.getOriginKind())) {
            requireAbsent(row.getOriginRepositoryProfileId(), row,
                    "origin_repository_profile_id", "userProfile");
            if (row.getOriginUserProfileId() == null || row.getOriginUserProfileRevision() == null) {
                throw inconsistentOrigin(row, "缺少 userProfile 来源所需的列");
            }
            return new UserProfileEvidenceOrigin(
                    new UserProfileId(row.getOriginUserProfileId()),
                    row.getOriginUserProfileRevision());
        }
        if (ProductDirectionEvidenceSupportDO.ORIGIN_REPOSITORY_PROFILE
                .equals(row.getOriginKind())) {
            requireAbsent(row.getOriginUserProfileId(), row,
                    "origin_user_profile_id", "repositoryProfile");
            requireAbsent(row.getOriginUserProfileRevision(), row,
                    "origin_user_profile_revision", "repositoryProfile");
            if (row.getOriginRepositoryProfileId() == null) {
                throw inconsistentOrigin(row, "缺少 repositoryProfile 来源所需的列");
            }
            return new RepositoryProfileEvidenceOrigin(
                    new RepositoryProfileId(row.getOriginRepositoryProfileId()));
        }
        throw new IllegalStateException(
                "无法识别的 Evidence 来源类型: " + row.getOriginKind());
    }

    private static void requireAbsent(Object value,
                                      ProductDirectionEvidenceSupportDO row,
                                      String column,
                                      String kind) {
        if (value != null) {
            throw inconsistentOrigin(row,
                    "来源类型为 " + kind + " 时 " + column + " 必须为空");
        }
    }

    private static IllegalStateException inconsistentOrigin(
            ProductDirectionEvidenceSupportDO row, String detail) {
        return new IllegalStateException(
                "Evidence 来源记录不一致（category=" + row.getCategory()
                        + ", position=" + row.getPosition() + "）: " + detail);
    }

    /**
     * 拒绝无法识别的判断分组。
     *
     * <p>只读取三个已知分组、把其余行默默丢掉，会让一条依据凭空消失：方向读出来是好的，
     * 只是少了一条它本来持有的依据。而 §10.5 要求这层对应关系必须被保留——
     * 存储里出现第四个分组说明数据与领域模型不一致，此时报错比放行安全。
     */
    private static void requireKnownCategory(String category) {
        for (Category known : Category.values()) {
            if (known.storedValue().equals(category)) {
                return;
            }
        }
        throw new IllegalStateException("无法识别的 Evidence 判断分组: " + category);
    }

    private static List<EvidenceBasis> basesOf(Map<String, List<EvidenceBasis>> byCategory,
                                               Category category) {
        return byCategory.getOrDefault(category.storedValue(), List.of());
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

    /** 三个关键判断组在存储中的名字，取领域字段名。 */
    private enum Category {

        USER_NEED("userNeed"),
        USER_FIT("userFit"),
        REUSABLE_CAPABILITY("reusableCapability");

        private final String storedValue;

        Category(String storedValue) {
            this.storedValue = storedValue;
        }

        String storedValue() {
            return storedValue;
        }
    }
}
