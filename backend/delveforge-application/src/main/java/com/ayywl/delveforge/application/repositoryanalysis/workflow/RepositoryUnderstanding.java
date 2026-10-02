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
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryTargetedSourceCandidates;
import com.ayywl.delveforge.application.repositoryanalysis.region.RegionHierarchyNotReducibleException;
import com.ayywl.delveforge.application.repositoryanalysis.region.RegionNavigationBudgetExceededException;
import com.ayywl.delveforge.application.repositoryanalysis.region.RepositoryBranchScoutRunner;
import com.ayywl.delveforge.application.repositoryanalysis.region.RepositoryFileCandidates;
import com.ayywl.delveforge.application.repositoryanalysis.region.RepositoryRegionCatalogTooLargeException;
import com.ayywl.delveforge.application.repositoryanalysis.region.RepositoryRegionNavigation;
import com.ayywl.delveforge.application.repositoryanalysis.region.RepositoryRegionNavigator;
import com.ayywl.delveforge.application.repositoryanalysis.region.ScoutCallBudgetExceededException;
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
 *         ↓  取源码候选，量一次 flat File Catalog 的字节数
 *   在预算内 ─────────────────┐        超出预算 ──────────────────────────────┐
 *         ↓                  │              ↓                              │
 *   现有 File Scout          │   RepositoryRegionNavigator（目录层下降）     │
 *         ↓                  │              ↓                              │
 *   RepositoryInspectionPlan │   RepositoryBranchScoutRunner（逐组 Scout）   │
 *         └──────────────────┴──────────────┴──────────────────────────────┘
 *         ↓  两条路都折成同一份输入
 * RepositoryReadPlan              同一条定向源码通道、同一份预算
 *         ↓  真实读取（同一 revision）+ 实际内容尺寸复核
 * List&lt;RepositorySourceFile&gt;
 * </pre>
 *
 * <h2>两条 Scout 路径，一份规划语义</h2>
 *
 * <p>小仓库走原有的 flat 路径：整份源码候选一次交给 File Scout。目录形状大到一次放不下时
 * （ADR-0005），先在**目录层**缩范围，再对每个终态分支各跑一次 File Scout，把结果保序轮转
 * 合并成一条有序候选流。两条路此后完全汇合：同一份 {@link RepositoryReadPlanner}、
 * 同一组材料预算、同一套跳过原因、同一次读取。
 *
 * <p>判据只有一条：**这次分析实际会发出的那份 flat File Catalog 载荷的字节数**。
 * 它与 File Scout 走同一个渲染入口，因此量到的就是即将发出的那一份，不是估计值。
 *
 * <h2>它回答「读到了什么」，不回答「这说明什么」</h2>
 *
 * <p>本类只负责把材料拿到手。材料说明什么由 {@code RepositoryAnalysisExtraction} 与
 * Repository Profile 决定（ADR-0004：Scout 决定读什么，Domain 决定这些内容说明了什么）。
 * 因此它不接触 {@code RepositoryProfile}、不写入任何东西。
 *
 * <h2>失败都在读取之前或读取之中</h2>
 *
 * <p>没有源码候选、分层 Scout 的守卫挡住这次分析、任一 Scout 输出不合法、读取计划为空——
 * 都在获取材料的阶段失败，既不该先付一次注定失败的模型调用，也不该拿到一份基于错误输入的
 * 结果。整条链路不会产生任何持久化副作用。
 *
 * <h2>失败不回退</h2>
 *
 * <p>分层 Scout 失败时**不会**退回「把超限的 flat 目录直接发给 File Scout」，也不会截断分支、
 * 采样或降级到只读基础材料。失败关闭：一次分析要么按某条确定的路径走完，要么什么都不产出。
 *
 * <h2>对外只有两种失败语义</h2>
 *
 * <pre>
 * RepositoryNotAnalyzableException   这个仓库当前分析不了（形状超出能力，或读不出材料）
 * AiGatewayException / WorkspaceException   外部能力调用本身失败
 * </pre>
 *
 * <p>分层的那几条守卫属于前者：它们是「仓库形状不适合当前分析方式」，不是服务端故障。
 * 因此本类把它们统一成 {@link RepositoryNotAnalyzableException}，而不是让它们以各自的类型
 * 掉进接口层的「未知错误」。
 *
 * <h2>Revision 一致性</h2>
 *
 * <p>本类不解析 HEAD：{@code analyzedRevision} 由调用方给定，之后建 Map、两条 Scout 路径、
 * 规划与每一次读取都固定在同一取值上。因此读到的内容与它记录的那份快照严格对应。
 *
 * <h2>日志只记聚合信息</h2>
 *
 * <p>本类记录一行聚合日志：走的是哪条 Scout 路径、各通道计划选了多少、实际读到多少、
 * 按原因跳过了多少。它**不记录任何路径**，也不记录异常文本——路径是用户数据，而异常文本
 * 可能嵌入凭据（AGENTS.md §8.8）。需要逐条定位时，诊断对象在内存里，由测试与调用方按需查看。
 */
public class RepositoryUnderstanding {

    /** 两条 Scout 路径的稳定标识，只出现在聚合日志里。 */
    static final String SCOUT_PATH_FLAT = "FLAT";

    /** 分层 Scout 路径的稳定标识，只出现在聚合日志里。 */
    static final String SCOUT_PATH_HIERARCHICAL = "HIERARCHICAL";

    /**
     * 分层 Scout 的守卫挡住了这次分析时，日志与异常里使用的稳定原因标识。
     *
     * <p>具体是哪一条守卫在 cause 里：Region 目录超限 / 单分支轮数 / Region 调用数 /
     * Scout 调用总数 / 结构不可再分。对外它们含义相同，因此只有一个标识。
     */
    static final String SCOUT_HIERARCHY_GUARD_EXCEEDED = "SCOUT_HIERARCHY_GUARD_EXCEEDED";

    private static final Logger log = LoggerFactory.getLogger(RepositoryUnderstanding.class);

    private final RepositoryMapBuilder mapBuilder;
    private final RepositoryScoutExtraction scoutExtraction;
    private final RepositoryRegionNavigator regionNavigator;
    private final RepositoryBranchScoutRunner branchScoutRunner;
    private final RepositoryReadPlanner readPlanner;
    private final RepositoryReadExecutor readExecutor;
    private final int maxScoutCatalogBytes;

    /**
     * @param mapBuilder            建立 Repository Map，不得为 {@code null}
     * @param scoutExtraction       执行 flat 路径的 File Scout，不得为 {@code null}
     * @param regionNavigator       超出预算时的分层导航，不得为 {@code null}；
     *                              它的一次分支本地 File Catalog 预算必须与
     *                              {@code maxScoutCatalogBytes} 相同，否则「flat 放得下」
     *                              与「分支放得下」会变成两个不同的门槛
     * @param branchScoutRunner     分层路径下的逐组 File Scout 执行与合并，不得为 {@code null}
     * @param readPlanner           规划读哪些文件，不得为 {@code null}
     * @param readExecutor          执行读取，不得为 {@code null}
     * @param maxScoutCatalogBytes  一次 flat File Catalog 载荷的字节上限，必须大于 0；
     *                              它同时是「是否需要分层」的判据
     */
    public RepositoryUnderstanding(RepositoryMapBuilder mapBuilder,
                                   RepositoryScoutExtraction scoutExtraction,
                                   RepositoryRegionNavigator regionNavigator,
                                   RepositoryBranchScoutRunner branchScoutRunner,
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
        if (regionNavigator == null) {
            throw new IllegalArgumentException(
                    "RepositoryUnderstanding 必须指定 regionNavigator");
        }
        if (branchScoutRunner == null) {
            throw new IllegalArgumentException(
                    "RepositoryUnderstanding 必须指定 branchScoutRunner");
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
        this.regionNavigator = regionNavigator;
        this.branchScoutRunner = branchScoutRunner;
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
     * @throws RepositoryNotAnalyzableException 没有源码候选、读取计划为空、读完之后没有可用材料，
     *                                          或分层 Scout 的守卫挡住了这次分析
     *                                          （原始守卫在 cause 里）
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
        boolean oversized = scoutExtraction.catalogPayloadBytes(scoutInputs)
                > maxScoutCatalogBytes;

        RepositoryReadPlan readPlan = oversized
                ? hierarchicalReadPlan(map)
                : flatReadPlan(map, scoutInputs);
        if (readPlan.isEmpty()) {
            throw new RepositoryNotAnalyzableException(
                    "本次读取计划没有选中任何文件，无法形成分析材料: " + analyzedRevision);
        }

        RepositoryReadResult read = readExecutor.execute(readPlan, workspaceRef);
        logAggregates(readPlan, read, oversized);

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
     * flat 路径：整份源码候选一次交给 File Scout。
     *
     * <p>小仓库就停在这里，**不会**因为分层机制的存在而多付一次 Region Scout 调用
     * （ADR-0005：flat 目录已在预算内时完全旁路分层）。
     */
    private RepositoryReadPlan flatReadPlan(RepositoryMap map, RepositoryScoutInputs inputs) {
        RepositoryInspectionPlan inspectionPlan = scoutExtraction.scout(inputs);
        return readPlanner.plan(map, inspectionPlan);
    }

    /**
     * 分层路径：先在目录层缩小范围，再逐终态组跑分支本地 File Scout，合并成有序候选流。
     *
     * <p>产出仍然交给**同一份** {@code RepositoryReadPlanner}：合并的顺序就是定向源码的
     * 考虑顺序，材料预算的取舍与 flat 路径完全一致。
     *
     * <p>这里没有备用路径：导航或分支 Scout 失败就整次失败，不会退回把超限的 flat 目录发出去。
     *
     * <h2>守卫失败 = 「当前无法分析」，不是「服务端故障」</h2>
     *
     * <p>分层的那几个守卫（Region 目录超限 / 轮数 / Region 调用数 / Scout 调用总数 /
     * 结构不可再分）说的都是同一件事：**这个仓库的源码目录形状超出了当前分析方式的处理
     * 能力**。它与 flat 目录超限是同一种对外含义——调用方需要改变仓库形状或分析上限，
     * 而不是等重试、也改不了请求。因此在这里统一成 {@link RepositoryNotAnalyzableException}，
     * 让它与「位置不可读」「没有可分析材料」落到同一个协议结果，而不是掉进「未知服务端故障」。
     *
     * <p>原始守卫放进 cause：对定位的人，是哪一条守卫、卡在哪个目录仍然看得出来。
     *
     * <p>只转这几种守卫。{@code AiGatewayException} 与 {@code WorkspaceException} 不在这里
     * 转换——它们表示外部能力调用本身失败，与「仓库形状不适合分析」不是一回事。
     */
    private RepositoryReadPlan hierarchicalReadPlan(RepositoryMap map) {
        RepositoryFileCandidates candidates;
        try {
            RepositoryRegionNavigation navigation = regionNavigator.navigate(map);
            candidates = branchScoutRunner.run(navigation);
        } catch (RepositoryRegionCatalogTooLargeException
                 | RegionNavigationBudgetExceededException
                 | RegionHierarchyNotReducibleException
                 | ScoutCallBudgetExceededException guardFailure) {
            throw new RepositoryNotAnalyzableException(
                    SCOUT_HIERARCHY_GUARD_EXCEEDED + ": flat 目录超出上限，改用分层 Scout 之后"
                            + "仍无法把源码候选压进本版本的预算（具体守卫见 cause）: "
                            + map.analyzedRevision(),
                    guardFailure);
        }
        return readPlanner.plan(map, RepositoryTargetedSourceCandidates.of(
                candidates.analyzedRevision(), candidates.orderedFiles()));
    }

    /**
     * 记录一行聚合结果。
     *
     * <p>只记数量与路径标识，不记任何文件路径，也不记任何异常文本——路径属于用户数据，
     * 而异常文本可能嵌入凭据（AGENTS.md §8.8）。
     */
    private static void logAggregates(RepositoryReadPlan readPlan,
                                      RepositoryReadResult read,
                                      boolean hierarchical) {
        log.info("operation=repository-analysis result={} scoutPath={} foundationSelectedCount={} "
                        + "targetedSourceSelectedCount={} materialCount={} tooLargeCount={} "
                        + "totalBudgetExceededCount={}",
                read.isEmpty() ? "NO_USABLE_MATERIAL" : "READY",
                hierarchical ? SCOUT_PATH_HIERARCHICAL : SCOUT_PATH_FLAT,
                readPlan.foundationEntries().size(),
                readPlan.targetedSourceEntries().size(),
                read.size(),
                read.tooLargeCount(),
                read.totalBudgetExceededCount());
    }
}
