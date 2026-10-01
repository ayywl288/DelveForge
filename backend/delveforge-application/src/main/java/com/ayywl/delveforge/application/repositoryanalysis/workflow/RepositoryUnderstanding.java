package com.ayywl.delveforge.application.repositoryanalysis.workflow;

import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryCandidateLane;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapBuilder;
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryReadExecutor;
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryReadPlan;
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryReadPlanner;
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryReadResult;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryInspectionPlan;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryScoutExtraction;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryScoutInputs;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在一个已解析的 revision 上理解 Repository，并读回可用的分析材料。
 *
 * <pre>
 * RepositoryMap                    完整已提交树的描述符
 *         ↓  取源码候选
 * Scout                            只看描述符，指出「去哪里看」
 *         ↓  引用校验
 * RepositoryInspectionPlan
 *         ↓  两条通道各自轮转
 * RepositoryReadPlan
 *         ↓  真实读取（同一 revision）+ 实际内容尺寸复核
 * List&lt;RepositorySourceFile&gt;
 * </pre>
 *
 * <h2>它回答「读到了什么」，不回答「这说明什么」</h2>
 *
 * <p>本类只负责把材料拿到手。材料说明什么由 {@code RepositoryAnalysisExtraction} 与
 * Repository Profile 决定（ADR-0004：Scout 决定读什么，Domain 决定这些内容说明了什么）。
 * 因此它不接触 {@code RepositoryProfile}、不写入任何东西。
 *
 * <h2>失败都在读取之前或读取之中</h2>
 *
 * <p>前置条件不成立时不去调用模型：没有源码候选、目录载荷超出本版本的承受范围——
 * 两者都在调用 Scout 之前失败，既不该先付一次模型调用的代价，也不该拿到一份基于错误输入的
 * 结果。整条链路不会产生任何持久化副作用。
 *
 * <h2>本版本的两处刻意边界</h2>
 *
 * <pre>
 * 没有源码候选时直接失败        不做「只分析基础材料」的降级——那是另一种产品行为，未经验证
 * 不做目录截断或采样            目录超出上限即失败；分层 Scout 属于后续阶段
 * </pre>
 *
 * <p>两条都是**当前版本的边界**，不是领域规则。真实 smoke 之后再按证据决定。
 *
 * <h2>日志只记聚合信息</h2>
 *
 * <p>本类记录一行聚合日志：各通道计划选了多少、实际读到多少、按原因跳过了多少。
 * 它**不记录任何路径**，也不记录异常文本——路径是用户数据，而异常文本可能嵌入凭据
 * （AGENTS.md §8.8）。需要逐条定位时，诊断对象在内存里，由测试与调用方按需查看。
 */
public class RepositoryUnderstanding {

    /** Scout 目录超出本版本承受范围时，日志与异常里使用的稳定原因标识。 */
    static final String SCOUT_CATALOG_TOO_LARGE = "SCOUT_CATALOG_TOO_LARGE";

    private static final Logger log = LoggerFactory.getLogger(RepositoryUnderstanding.class);

    private final RepositoryMapBuilder mapBuilder;
    private final RepositoryScoutExtraction scoutExtraction;
    private final RepositoryReadPlanner readPlanner;
    private final RepositoryReadExecutor readExecutor;
    private final int maxScoutCatalogBytes;

    /**
     * @param mapBuilder             建立 Repository Map，不得为 {@code null}
     * @param scoutExtraction        执行 Scout，不得为 {@code null}
     * @param readPlanner            规划读哪些文件，不得为 {@code null}
     * @param readExecutor           执行读取，不得为 {@code null}
     * @param maxScoutCatalogBytes   Scout 目录载荷的字节上限，必须大于 0
     */
    public RepositoryUnderstanding(RepositoryMapBuilder mapBuilder,
                                   RepositoryScoutExtraction scoutExtraction,
                                   RepositoryReadPlanner readPlanner,
                                   RepositoryReadExecutor readExecutor,
                                   int maxScoutCatalogBytes) {
        if (mapBuilder == null) {
            throw new IllegalArgumentException("RepositoryUnderstanding 必须指定 mapBuilder");
        }
        if (scoutExtraction == null) {
            throw new IllegalArgumentException(
                    "RepositoryUnderstanding 必须指定 scoutExtraction");
        }
        if (readPlanner == null) {
            throw new IllegalArgumentException("RepositoryUnderstanding 必须指定 readPlanner");
        }
        if (readExecutor == null) {
            throw new IllegalArgumentException("RepositoryUnderstanding 必须指定 readExecutor");
        }
        if (maxScoutCatalogBytes <= 0) {
            throw new IllegalArgumentException(
                    "Scout 目录字节上限必须大于 0: " + maxScoutCatalogBytes);
        }
        this.mapBuilder = mapBuilder;
        this.scoutExtraction = scoutExtraction;
        this.readPlanner = readPlanner;
        this.readExecutor = readExecutor;
        this.maxScoutCatalogBytes = maxScoutCatalogBytes;
    }

    /**
     * 在该 revision 上读回可用的分析材料。
     *
     * @param workspaceRef      目标 Repository，不得为 {@code null}
     * @param analyzedRevision  本次分析固定的 commit id，不得为 {@code null}；
     *                          必须是已经解析出来的完整 commit id，本类不重新解析 HEAD
     * @return 真正读到、并通过执行期尺寸复核的材料
     * @throws IllegalArgumentException 任一参数为 {@code null}
     * @throws RepositoryNotAnalyzableException 没有源码候选、目录超出上限、
     *                                          读取计划为空，或读完之后没有可用材料
     * @throws com.ayywl.delveforge.application.port.ai.AiGatewayException
     *                                          Scout 调用失败，或返回内容不满足约定
     * @throws com.ayywl.delveforge.application.port.workspace.WorkspaceException
     *                                          列目录或读取文件失败
     */
    public List<RepositorySourceFile> understand(WorkspaceRef workspaceRef,
                                                 String analyzedRevision) {
        if (workspaceRef == null) {
            throw new IllegalArgumentException("RepositoryUnderstanding 必须指定 workspaceRef");
        }
        if (analyzedRevision == null) {
            throw new IllegalArgumentException(
                    "RepositoryUnderstanding 必须指定 analyzedRevision");
        }

        RepositoryMap map = mapBuilder.build(workspaceRef, analyzedRevision);
        requireSourceCandidates(map, analyzedRevision);

        RepositoryScoutInputs scoutInputs = RepositoryScoutInputs.of(map);
        requireCatalogWithinLimit(scoutInputs, analyzedRevision);

        RepositoryInspectionPlan inspectionPlan = scoutExtraction.scout(scoutInputs);
        RepositoryReadPlan readPlan = readPlanner.plan(map, inspectionPlan);
        if (readPlan.isEmpty()) {
            throw new RepositoryNotAnalyzableException(
                    "本次读取计划没有选中任何文件，无法形成分析材料: " + analyzedRevision);
        }

        RepositoryReadResult read = readExecutor.execute(readPlan, workspaceRef);
        logAggregates(readPlan, read);

        if (read.isEmpty()) {
            throw new RepositoryNotAnalyzableException(
                    "读取计划里的文件在执行期尺寸复核后全部不可用，无法形成分析材料: "
                            + analyzedRevision);
        }
        return read.material();
    }

    /**
     * 没有源码候选就不进行一次注定失败的调用。
     *
     * <p>本版本不做「只分析基础材料」的降级：那是一种不同的产品行为，没有经过验证，
     * 悄悄启用它会让「分析结果为什么只有工程元数据」变成一个需要猜的问题。
     */
    private static void requireSourceCandidates(RepositoryMap map, String analyzedRevision) {
        if (map.entriesIn(RepositoryCandidateLane.SCOUT_SOURCE).isEmpty()) {
            throw new RepositoryNotAnalyzableException(
                    "该 Repository 在本次分析方式下没有任何源码候选，无内容可供深入理解: "
                            + analyzedRevision);
        }
    }

    /**
     * 目录载荷超出上限时失败关闭。
     *
     * <p>不截断、不采样、不降级到旧的选材策略：截断会让 Scout 在不知情的情况下少看一部分
     * 候选，而降级会把「这次走的是哪条路径」变成调用方看不见的事。超出上限说明这个仓库的形状
     * 还不是本版本能处理的，如实失败比悄悄换一种做法更可信。
     */
    private void requireCatalogWithinLimit(RepositoryScoutInputs scoutInputs,
                                           String analyzedRevision) {
        int payloadBytes = scoutExtraction.catalogPayloadBytes(scoutInputs);
        if (payloadBytes > maxScoutCatalogBytes) {
            throw new RepositoryNotAnalyzableException(
                    SCOUT_CATALOG_TOO_LARGE + ": Scout 目录载荷 " + payloadBytes
                            + " 字节，超过上限 " + maxScoutCatalogBytes
                            + " 字节；本版本不截断、不采样，请在更大的上限下重新分析: "
                            + analyzedRevision);
        }
    }

    /**
     * 记录一行聚合结果。
     *
     * <p>只记数量，不记路径，也不记任何异常文本——路径属于用户数据，
     * 而异常文本可能嵌入凭据（AGENTS.md §8.8）。
     */
    private static void logAggregates(RepositoryReadPlan readPlan, RepositoryReadResult read) {
        log.info("operation=repository-analysis result={} foundationSelectedCount={} "
                        + "targetedSourceSelectedCount={} materialCount={} tooLargeCount={} "
                        + "totalBudgetExceededCount={}",
                read.isEmpty() ? "NO_USABLE_MATERIAL" : "READY",
                readPlan.foundationEntries().size(),
                readPlan.targetedSourceEntries().size(),
                read.size(),
                read.tooLargeCount(),
                read.totalBudgetExceededCount());
    }
}
