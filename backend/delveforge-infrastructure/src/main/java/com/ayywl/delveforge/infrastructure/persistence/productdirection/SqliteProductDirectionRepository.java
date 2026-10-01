package com.ayywl.delveforge.infrastructure.persistence.productdirection;

import com.ayywl.delveforge.application.port.persistence.ProductDirectionContentConflictException;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionIntegrityConflictException;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionSelectionConflictException;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionStatusConflictException;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionTransition;
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
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <p>写入分两条路径，它们改的东西不同：
 *
 * <pre>
 * save / saveAll       新建方向，或重复保存一条内容与状态都没变的方向
 * saveTransitions      生命周期转换；只改 status，并且是条件更新
 * </pre>
 *
 * <p>按标识保存：不存在时写入整条方向；已经存在时更新同一个标识。
 * 这与 {@code SqliteRepositoryProfileRepository} 的「只写一次」不同——
 * Repository Profile 是不可改写的分析快照，Product Direction 是拥有生命周期的 Entity。
 *
 * <p>已经保存过时，本类先比较已保存的 discovery basis、recommendation content、
 * candidate assets 与 Evidence：
 *
 * <pre>
 * 完全一致、且状态也一致   接受这次保存
 * 内容有任何不同           拒绝，抛 ProductDirectionContentConflictException
 * 状态不同                 拒绝，抛 ProductDirectionStatusConflictException
 * </pre>
 *
 * <p>状态变化不走 {@code save}：那条路径上没有任何信息能说明「本次转换依据的是哪个状态」，
 * 因此无从判断手上这份是不是过期副本，无条件覆盖就会把另一个请求刚刚提交的用户决定改掉。
 * 生命周期变化必须经 {@link #saveTransitions}，由它在同一条语句里核对起始状态。
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

    /** SQLite 的 {@code SQLITE_BUSY}：另一个写入者持有锁，等不到它释放。 */
    private static final int SQLITE_BUSY = 5;

    /** SQLite 的 {@code SQLITE_LOCKED}：连接自己持有的锁阻止了本次写入。 */
    private static final int SQLITE_LOCKED = 6;

    private final ProductDirectionMapper directionMapper;
    private final ProductDirectionRepositoryProfileMapper repositoryProfileMapper;
    private final ProductDirectionCandidateAssetMapper candidateAssetMapper;
    private final ProductDirectionRiskMapper riskMapper;
    private final ProductDirectionEvidenceSupportMapper evidenceSupportMapper;

    /**
     * 写入事务的边界。
     *
     * <p>写入不靠 {@code @Transactional}：那样提交发生在方法返回**之后**，本类就碰不到
     * 提交阶段的失败——而 SQLite 恰恰会在提交时因为拿不到写锁而失败。把边界收进方法内部，
     * 提交失败才能被翻译成 Port 能表达的语义。
     */
    private final TransactionTemplate writeTransaction;

    public SqliteProductDirectionRepository(
            ProductDirectionMapper directionMapper,
            ProductDirectionRepositoryProfileMapper repositoryProfileMapper,
            ProductDirectionCandidateAssetMapper candidateAssetMapper,
            ProductDirectionRiskMapper riskMapper,
            ProductDirectionEvidenceSupportMapper evidenceSupportMapper,
            PlatformTransactionManager transactionManager) {

        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.directionMapper = directionMapper;
        this.repositoryProfileMapper = repositoryProfileMapper;
        this.candidateAssetMapper = candidateAssetMapper;
        this.riskMapper = riskMapper;
        this.evidenceSupportMapper = evidenceSupportMapper;
    }

    @Override
    public void save(ProductDirection productDirection) {
        inWriteTransaction(() -> write(productDirection));
    }

    /**
     * 整批写入。
     *
     * <p>整批的原子性由写入事务保证：其中任意一条失败时，这一批已经写入的部分一并回滚。
     * 这正是 {@link ProductDirectionRepository#saveAll} 存在的原因，而它只能在这一层实现：
     * 调用方（Application）无法自己拼出这个保证。
     */
    @Override
    public void saveAll(List<ProductDirection> productDirections) {
        inWriteTransaction(() -> {
            for (ProductDirection productDirection : productDirections) {
                write(productDirection);
            }
        });
    }

    /**
     * 生命周期转换的整批写入。
     *
     * <p>每一条都是一次**条件更新**：只有存储中的状态仍是该转换依据的那个状态时才写入。
     * 判断与写入在同一条 {@code UPDATE … WHERE id = ? AND status = ?} 里完成，因此不存在
     * 「先查再写」的窗口——两个请求不可能都读到同一个起始状态然后都写成功。
     *
     * <p>受影响行数为 0 表示这次转换的依据已经不成立。这里不额外查一次去区分「状态变了」
     * 与「这一行不存在」：本 Port 没有删除路径，而调用方在这之前都已经按标识加载过该方向，
     * 因此「读到 0 行」实际只有一种成因。
     *
     * <p>本方法不触碰任何内容行：状态变化在存储层面根本碰不到它们（§10.5）。
     */
    @Override
    public void saveTransitions(List<ProductDirectionTransition> transitions) {
        inWriteTransaction(() -> {
            for (ProductDirectionTransition transition : transitions) {
                applyTransition(transition);
            }
        });
    }

    /**
     * 在一个写入事务里执行 {@code work}，并把**提交阶段**的失败也翻译过来。
     *
     * <p>语句阶段的失败已经在写入路径上翻译过（见 {@link #translateStoredConflict}），
     * 那些异常会原样穿过这里——所以这里只处理提交/回滚本身报出来的失败。
     *
     * <p>SQLite 的提交需要独占锁，因此即使每条语句都成功了，提交仍可能因为另一个连接
     * 持着锁而失败。那不是「上一次写入留下了脏连接」那种问题，而是一次真实的并发冲突：
     * 本次写入没能生效，调用方应当重新读取后再决定。
     *
     * <p>提交失败时连接会被回滚并清理干净（见 {@code SqliteDataSourceConfiguration}
     * 里 {@code rollbackOnCommitFailure} 的说明），因此这里可以放心地把失败交出去。
     */
    private void inWriteTransaction(Runnable work) {
        try {
            writeTransaction.executeWithoutResult(status -> work.run());
        } catch (DataAccessException exception) {
            throw translateCommitFailure(exception);
        } catch (TransactionException exception) {
            throw translateCommitFailure(exception);
        }
    }

    private static RuntimeException translateCommitFailure(Throwable failure) {
        if (isWriteLockContention(failure)) {
            return new ProductDirectionStatusConflictException(failure);
        }
        return (failure instanceof RuntimeException runtime) ? runtime
                : new TransactionSystemException("Product Direction 的写入事务失败", failure);
    }

    private void applyTransition(ProductDirectionTransition transition) {
        ProductDirection direction = transition.direction();

        try {
            int updated = directionMapper.update(null,
                    new LambdaUpdateWrapper<ProductDirectionDO>()
                            .eq(ProductDirectionDO::getId, direction.id().value())
                            .eq(ProductDirectionDO::getStatus, transition.expectedFrom().name())
                            .set(ProductDirectionDO::getStatus, direction.status().name()));

            if (updated == 0) {
                throw new ProductDirectionStatusConflictException(
                        direction.id(), transition.expectedFrom());
            }
        } catch (DataAccessException exception) {
            throw translateStoredConflict(direction, exception);
        }
    }

    /**
     * 单条的写入逻辑；事务边界由调用它的公开方法决定。
     *
     * <p>存储层异常在这里翻译成 Port 能表达的语义，见 {@link #translateStoredConflict}。
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

        // 状态变化不能经由这条路径：这里没有「本次转换依据的是哪个状态」这项信息，
        // 因此无从判断手上这份是不是过期副本。无条件覆盖会把另一个请求刚刚提交的
        // 用户决定改掉。生命周期变化请走 saveTransitions。
        if (!stored.getStatus().equals(productDirection.status().name())) {
            throw new ProductDirectionStatusConflictException(productDirection.id());
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

        if (isConstraintViolation(exception)
                && productDirection.status() == ProductDirectionStatus.SELECTED) {
            return new ProductDirectionSelectionConflictException(productDirection.id(), exception);
        }
        if (isWriteLockContention(exception)) {
            return new ProductDirectionStatusConflictException(productDirection.id(), exception);
        }
        return exception;
    }

    /**
     * 异常链里是否有一个「没拿到写锁」的 SQL 错误。
     *
     * <p>SQLite 在已有并发写入者时直接拒绝后来者，而不是排队等待：默认的 busy timeout
     * 用尽后抛 {@code SQLITE_BUSY}（5），持有共享锁又需要升级时抛 {@code SQLITE_LOCKED}（6）。
     * 这与「依据的状态已经变化」有同样的含义与同样的处置——本次写入无法确认自己所依据的
     * 状态仍然成立，调用方应当重新读取之后再决定——因此同样翻译成项目自己的冲突类型。
     *
     * <p>不翻译它会怎样：它会以未分类的数据访问异常一路走到接口层变成 500。调用方既拿不到
     * 可判定的失败语义，响应里也没有任何与并发有关的信息。并发选择的败方本来就是一次
     * 可预期的冲突，不是服务端故障。
     */
    private static boolean isWriteLockContention(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && (sqlException.getErrorCode() & SQLITE_PRIMARY_CODE_MASK)
                            == SQLITE_BUSY) {
                return true;
            }
            if (current instanceof SQLException sqlException
                    && (sqlException.getErrorCode() & SQLITE_PRIMARY_CODE_MASK)
                            == SQLITE_LOCKED) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
            throw new ProductDirectionIntegrityConflictException(
                    "存在多于一个当前 SELECTED 的 Product Direction（INV-D09）: "
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
