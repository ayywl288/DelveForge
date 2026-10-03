package com.ayywl.delveforge.application.repositoryanalysis.region;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryCandidateLane;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryFileReference;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import com.ayywl.delveforge.application.repositoryanalysis.scout.FileCatalogPayload;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryScoutExtraction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分层导航：当一次分析的 flat File Catalog 超出预算时，沿真实目录结构递归下降，
 * 直到每一支的文件集合都能被一次 File Scout 调用承载（ADR-0005）。
 *
 * <pre>
 * 超出预算的源码集合
 *         ↓  Region Scout（只看目录描述符）
 * 有序的选中 Region（顺序即分支优先级）
 *         ↓  逐个独立处理
 *   该 Region 的后代文件装得下  → 终态分支
 *   装不下                      → 对它含源码的子目录再问一次 Region Scout，递归
 *         ↓
 * 有序的终态文件组
 * </pre>
 *
 * <h2>不再丢失「直接就放在这一层」的源码</h2>
 *
 * <p>一个节点的文件集是：
 *
 * <pre>
 * 该节点直属的文件  ∪  各子 Region 下的文件
 * </pre>
 *
 * <p>后者是 Region Scout 的取舍范围，前者不是——任何一次 Region Scout 都看不到它们，
 * 因为它们不对应任何一个子目录。因此节点被分解时，直属文件**单独成为一组终态文件**：
 * 装得下就保留，装不下就说明它们没有更细的结构可分，失败关闭。仓库根目录同理。
 *
 * <p>这条规则同时保证了**被选中**的分支在分解中不丢文件：每个非直属文件恰好属于一个子 Region，
 * 直属文件又被单独收走，因此沿着一条被选中的分支走下去，不会有哪个源码候选悄悄消失。
 *
 * <p>会让文件**不进入结果**的只有一件事：Region Scout 没有选中它所属的分支。那是取舍，
 * 不是丢失——也正是分层要达成的缩减。终态组里文件的总数因此是「被保留的」，而不是「全部的」。
 *
 * <h2>停止条件是字节，不是深度</h2>
 *
 * <p>递归在「该节点的文件作为一次分支本地 File Scout 目录时的**实际序列化字节数**
 * 已在预算内」时停止。不用深度、不用文件数：目录结构的深浅与文件大小无关，
 * 同样是 3 层，有的装得下、有的装不下。
 *
 * <h2>失败是整次的</h2>
 *
 * <p>预算耗尽（单分支轮数 / Region 调用数 / 整次分析的 Scout 调用总数）、目录超限、
 * 结构不可再分、模型输出不合法——任一种都让整次导航以异常结束，**不返回已经走出来的
 * 那部分**。部分结果会让调用方以为「这个仓库就是这些分支」。
 * 没有采样、没有截断、没有「退回把整份 oversized 目录直接发出去」的降级。
 *
 * <p>三种预算都在**每次 Region 调用之前**判定：超限的那一次不触达模型。整次分析的总数
 * 守卫尤其不能推迟到分支阶段——那时已经付掉的 Region 调用收不回来。
 */
public final class RepositoryRegionNavigator {

    private final RepositoryRegionScoutExtraction regionScout;
    private final FileCatalogPayload fileCatalog;
    private final int maxFileCatalogBytes;
    private final RegionRecursionBudget budget;
    private final ScoutCallBudget scoutCallBudget;

    /**
     * @param regionScout          Region Scout，不得为 {@code null}（本类只调用它的 scout）
     * @param fileCatalog          度量候选分支的 File Catalog 载荷，不得为 {@code null}；
     *                             与 File Scout 实际发出的是同一个渲染入口
     * @param maxFileCatalogBytes 一次分支本地 File Scout 目录的字节预算，必须大于 0
     * @param budget               递归守卫（单分支轮数 / Region 调用数），不得为 {@code null}
     * @param scoutCallBudget      整次分析的 Scout 调用总数守卫（Region + File），
     *                             不得为 {@code null}
     */
    public RepositoryRegionNavigator(RepositoryRegionScoutExtraction regionScout,
                                     FileCatalogPayload fileCatalog,
                                     int maxFileCatalogBytes,
                                     RegionRecursionBudget budget,
                                     ScoutCallBudget scoutCallBudget) {
        if (regionScout == null) {
            throw new IllegalArgumentException(
                    "RepositoryRegionNavigator 必须指定 regionScout");
        }
        if (fileCatalog == null) {
            throw new IllegalArgumentException(
                    "RepositoryRegionNavigator 必须指定 fileCatalog");
        }
        if (maxFileCatalogBytes <= 0) {
            throw new IllegalArgumentException(
                    "分支本地 File Catalog 的字节预算必须大于 0: " + maxFileCatalogBytes);
        }
        if (budget == null) {
            throw new IllegalArgumentException(
                    "RepositoryRegionNavigator 必须指定 budget");
        }
        if (scoutCallBudget == null) {
            throw new IllegalArgumentException(
                    "RepositoryRegionNavigator 必须指定 scoutCallBudget");
        }
        this.regionScout = regionScout;
        this.fileCatalog = fileCatalog;
        this.maxFileCatalogBytes = maxFileCatalogBytes;
        this.budget = budget;
        this.scoutCallBudget = scoutCallBudget;
    }

    /**
     * 对一张 Repository Map 上的全部源码候选做分层导航。
     *
     * <p>整份源码候选已经在预算内时直接得到一个根层终态组，**不调用 Region Scout**——
     * 小仓库旁路分层，与 ADR-0005 一致。
     *
     * @param map 本次分析的 Map，不得为 {@code null}
     * @return 有序的终态文件组
     * @throws IllegalArgumentException                       map 为 {@code null}
     * @throws RepositoryRegionCatalogTooLargeException        某一层 Region 目录超过其预算
     * @throws RegionNavigationBudgetExceededException         递归守卫用尽（轮数 / Region 调用数）
     * @throws ScoutCallBudgetExceededException                再调一次会超过整次分析的 Scout 调用总数
     * @throws RegionHierarchyNotReducibleException            结构上无法再缩小
     * @throws com.ayywl.delveforge.application.port.ai.AiGatewayException
     *                                                       Region Scout 调用失败或输出不合法
     */
    public RepositoryRegionNavigation navigate(RepositoryMap map) {
        if (map == null) {
            throw new IllegalArgumentException("RepositoryRegionNavigator 必须指定 map");
        }
        return new Navigation(map).run();
    }

    /**
     * 一次分支本地 File Scout 目录的字节预算。
     *
     * <p>暴露它是为了让装配层能断言一件事：这个值与「flat 目录是否放得下」用的是**同一个**
     * 门槛。若两者不等，分层路径可能交出一个「超过了 flat 上限、却在分支上限之内」的目录，
     * 那条保证就断了。
     */
    public int maxFileCatalogBytes() {
        return maxFileCatalogBytes;
    }

    /**
     * 一次导航的运行状态：目录视图、按前缀索引的文件、累计的调用数与结果。
     *
     * <p>做成每次调用新建的实例，是为了让 {@code navigate} 本身无状态——
     * 计数器与结果列表不能跨调用共享。
     */
    private final class Navigation {

        private final String revision;
        private final RepositoryRegionTree tree;
        private final List<RepositoryMapEntry> allSourceFiles;
        private final Map<String, List<RepositoryMapEntry>> directFilesByPrefix;
        private final Map<String, List<RepositoryMapEntry>> subtreeByPrefix;
        private final List<RepositoryTerminalFileGroup> groups = new ArrayList<>();
        private int scoutCalls;

        Navigation(RepositoryMap map) {
            this.revision = map.analyzedRevision();
            this.tree = RepositoryRegionTree.of(map);
            this.allSourceFiles = map.entriesIn(RepositoryCandidateLane.SCOUT_SOURCE);

            Map<String, List<RepositoryMapEntry>> direct = new LinkedHashMap<>();
            Map<String, List<RepositoryMapEntry>> subtree = new LinkedHashMap<>();
            for (RepositoryMapEntry entry : allSourceFiles) {
                String path = entry.relativePath();
                int lastSlash = path.lastIndexOf('/');
                direct.computeIfAbsent(lastSlash < 0 ? "" : path.substring(0, lastSlash),
                        key -> new ArrayList<>()).add(entry);

                String[] segments = path.split("/");
                StringBuilder prefix = new StringBuilder();
                for (int i = 0; i < segments.length - 1; i++) {
                    if (i > 0) {
                        prefix.append('/');
                    }
                    prefix.append(segments[i]);
                    subtree.computeIfAbsent(prefix.toString(), key -> new ArrayList<>())
                            .add(entry);
                }
            }
            this.directFilesByPrefix = immutableLists(direct);
            this.subtreeByPrefix = immutableLists(subtree);
        }

        RepositoryRegionNavigation run() {
            descend("", allSourceFiles, 0);
            return RepositoryRegionNavigation.of(revision, List.copyOf(groups), scoutCalls);
        }

        /**
         * 处理一个节点。
         *
         * @param prefix     该节点对应的目录前缀；{@code ""} 表示仓库根目录
         * @param entries    该节点下的全部源码候选（直属 + 各子 Region 后代），保持 Map 顺序
         * @param roundsUsed 本分支**在到达该节点之前**已经用掉的 Region Scout 轮数
         */
        private void descend(String prefix, List<RepositoryMapEntry> entries, int roundsUsed) {
            int catalogBytes = catalogBytes(entries);
            if (catalogBytes <= maxFileCatalogBytes) {
                groups.add(new RepositoryTerminalFileGroup(prefix, entries, catalogBytes));
                return;
            }

            List<RepositoryRegion> childRegions = tree.childRegions(prefix);
            if (childRegions.isEmpty()) {
                throw new RegionHierarchyNotReducibleException(
                        RegionHierarchyNotReducibleException.HIERARCHY_NOT_REDUCIBLE
                                + ": 「" + describe(prefix) + "」下的源码超出 File Catalog 预算，"
                                + "且没有任何含源码的子目录可以继续下钻: " + revision);
            }

            List<RepositoryMapEntry> direct = directFilesByPrefix.getOrDefault(prefix, List.of());
            int directBytes = direct.isEmpty() ? 0 : catalogBytes(direct);
            if (directBytes > maxFileCatalogBytes) {
                throw new RegionHierarchyNotReducibleException(
                        RegionHierarchyNotReducibleException.HIERARCHY_NOT_REDUCIBLE
                                + ": 「" + describe(prefix) + "」直接包含的 " + direct.size()
                                + " 个源码文件本身就超过分支本地 File Catalog 预算（"
                                + directBytes + " > " + maxFileCatalogBytes
                                + "），而它们没有更细的结构可以分: " + revision);
            }

            // 轮数守的是**逻辑深度**，不是尝试次数：同一个节点重试一次不构成「更深一层」。
            if (roundsUsed + 1 > budget.maxRoundsPerBranch()) {
                throw new RegionNavigationBudgetExceededException(
                        RegionNavigationBudgetExceededException
                                .MAX_REGION_SCOUT_ROUNDS_EXCEEDED
                                + ": 沿该分支已进行 " + roundsUsed + " 轮 Region Scout，"
                                + "在「" + describe(prefix) + "」上再调一次将超过单分支上限 "
                                + budget.maxRoundsPerBranch() + ": " + revision);
            }

            // 两次调用计数守卫都在许可里，因此它们同时守住**重试**那一次：
            // 重试是一次真实调用，额度不够就不发出去。
            RepositoryRegionSelection selection = regionScout.scout(
                    RepositoryRegionCatalog.of(revision, childRegions),
                    () -> claimRegionScoutAttempt(prefix));

            // 直属文件先成组：它们就在这一层，不属于任何一个子分支。
            if (!direct.isEmpty()) {
                groups.add(new RepositoryTerminalFileGroup(prefix, direct, directBytes));
            }
            for (RepositoryRegion region : selection.regions()) {
                List<RepositoryMapEntry> subtree = subtreeByPrefix.get(region.pathPrefix());
                if (subtree == null) {
                    throw new IllegalStateException(
                            "Region 应由 SCOUT_SOURCE 聚合而来，必然存在对应文件: "
                                    + region.pathPrefix());
                }
                descend(region.pathPrefix(), subtree, roundsUsed + 1);
            }
        }

        /**
         * 申请一次 Region Scout Provider 调用，并把它计入本分支的尝试数。
         *
         * <pre>
         * 单次分析 Region Scout 上限     RegionRecursionBudget.maxRegionScoutCalls
         * 整次分析 Scout 调用总数        ScoutCallBudget.maxTotalCalls（Region + File 合并计数）
         * </pre>
         *
         * <p>两条都要在**调用之前**判定：Region 调用与 File 调用合并计数，因此分层下降先花掉的
         * 那几次同样占用终态分支的额度。等到分支阶段才发现超限，并不能撤销这里已经付出的调用。
         *
         * <p>它同时是**重试**之前那道门：重试多花一次真实调用，因此也要先在这里过一遍。
         *
         * <p>计数的是**尝试次数**：一次契约违反后的重试会让计数 +1，日志与导航结果因此反映
         * 实际付掉的模型调用数，而不是逻辑上的节点数。
         */
        private void claimRegionScoutAttempt(String prefix) {
            if (scoutCalls + 1 > budget.maxRegionScoutCalls()) {
                throw new RegionNavigationBudgetExceededException(
                        RegionNavigationBudgetExceededException
                                .MAX_REGION_SCOUT_CALLS_EXCEEDED
                                + ": 本次分析已调用 Region Scout " + scoutCalls
                                + " 次，再调一次将超过总上限 "
                                + budget.maxRegionScoutCalls() + ": " + revision);
            }
            if (scoutCalls + 1 > scoutCallBudget.maxTotalCalls()) {
                throw new ScoutCallBudgetExceededException(
                        ScoutCallBudgetExceededException.MAX_TOTAL_SCOUT_CALLS_EXCEEDED
                                + ": 本次分析已调用 Scout " + scoutCalls
                                + " 次，在「" + describe(prefix) + "」上再调一次 Region Scout "
                                + "将超过整次分析的总数上限 "
                                + scoutCallBudget.maxTotalCalls() + ": " + revision);
            }
            scoutCalls++;
        }

        /**
         * 这组文件作为一次**分支本地** File Scout 目录时的载荷字节数。
         *
         * <p>编号按组内位置重新分配（{@code RF-1}…{@code RF-n}）：一次分支本地调用看到的
         * 是它自己那一份目录，引用本来就是调用内的短名（ADR-0004）。度量与 File Scout 发送
         * 走同一个渲染入口，因此这里量到的就是那一份会真的发出去。
         */
        private int catalogBytes(List<RepositoryMapEntry> entries) {
            List<RepositoryMapEntry> renumbered = new ArrayList<>(entries.size());
            for (int index = 0; index < entries.size(); index++) {
                RepositoryMapEntry entry = entries.get(index);
                renumbered.add(new RepositoryMapEntry(
                        RepositoryFileReference.of(index + 1),
                        entry.relativePath(),
                        entry.sizeInBytes(),
                        entry.language(),
                        entry.materialKind(),
                        entry.roleHints()));
            }
            return fileCatalog.payloadBytes(revision, renumbered);
        }

        private String describe(String prefix) {
            return prefix.isEmpty() ? "仓库根目录" : prefix;
        }
    }

    private static Map<String, List<RepositoryMapEntry>> immutableLists(
            Map<String, List<RepositoryMapEntry>> source) {
        Map<String, List<RepositoryMapEntry>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<RepositoryMapEntry>> entry : source.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }
}
