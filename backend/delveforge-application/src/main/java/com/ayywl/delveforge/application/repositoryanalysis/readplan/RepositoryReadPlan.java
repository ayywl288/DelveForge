package com.ayywl.delveforge.application.repositoryanalysis.readplan;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryFileReference;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一份确定的读取计划：在某个 revision 上，接下来应该实际读哪些已提交文件。
 *
 * <pre>
 * analyzedRevision
 *         ↓
 * foundationEntries        按类别轮转选出的基础材料
 * targetedSourceEntries    按 Scout 聚焦区域轮转选出的唯一源码文件
 * skippedCandidates        被跳过及其原因（诊断，不参与读取）
 * </pre>
 *
 * <h2>它只是计划，不触发任何读取</h2>
 *
 * <p>本类型不持有 Workspace 能力，也没有读取入口。它描述的是「哪些文件值得在预算内读进来」，
 * 真正的读取与内容合并属于后续阶段。因此它既不持久化，也不进入 Domain。
 *
 * <h2>里面的每一条都已经解析过</h2>
 *
 * <p>全部条目都是 {@link RepositoryMapEntry}，路径来自建立 Map 时的列目录结果，
 * 不来自模型。{@code RF-*} 编号到此为止：计划里不再需要它们，后续读取只认路径与 revision。
 *
 * <h2>两条通道互不占用</h2>
 *
 * <p>Foundation 与定向源码各自在自己的预算内选取，一方读得多不会让另一方读得少。
 * 两个列表保持各自的选取顺序（那就是它们被读取的顺序），且**不会包含同一个文件**：
 * 一个描述符只会被路由到其中一条通道。
 *
 * <h2>跨区域重复只读一次</h2>
 *
 * <p>Scout 允许同一个文件出现在多个聚焦区域里，那是「从几个角度看同一处」的表达。
 * 计划里它只出现一次，也只计一次文件数与字节数——物理读取没有「读两遍」的语义。
 */
public final class RepositoryReadPlan {

    private final String analyzedRevision;

    private final List<RepositoryMapEntry> foundationEntries;

    private final List<RepositoryMapEntry> targetedSourceEntries;

    private final List<SkippedReadCandidate> skippedCandidates;

    private RepositoryReadPlan(String analyzedRevision,
                               List<RepositoryMapEntry> foundationEntries,
                               List<RepositoryMapEntry> targetedSourceEntries,
                               List<SkippedReadCandidate> skippedCandidates) {
        this.analyzedRevision = analyzedRevision;
        this.foundationEntries = foundationEntries;
        this.targetedSourceEntries = targetedSourceEntries;
        this.skippedCandidates = skippedCandidates;
    }

    /**
     * 建立一份读取计划。
     *
     * <p>校验两条不变量：两条通道合起来，同一个引用不得出现两次，同一个**相对路径**也不得
     * 出现两次。它们在路由上是互斥的，因此这两条在正常路径下必然成立；在这里强制它们，
     * 是为了让「唯一文件只读一次」由类型本身保证，而不是依赖规划器写对。
     *
     * <p>路径这一条不能省：ADR-0004 把「已解析 revision + 提交树相对路径」定义为稳定技术
     * 身份，因此两个**不同的编号**指向同一个路径时，物理上仍然是同一个文件——
     * 那同样会让它被读两次。
     *
     * @param analyzedRevision     本次分析固定的 commit id，不得为空白
     * @param foundationEntries    基础材料，按读取顺序；不得为 {@code null}，元素不得为 {@code null}
     * @param targetedSourceEntries 定向源码，按读取顺序；不得为 {@code null}，元素不得为 {@code null}
     * @param skippedCandidates    被跳过的候选与原因；不得为 {@code null}，元素不得为 {@code null}
     * @throws IllegalArgumentException 任一条件不成立
     */
    public static RepositoryReadPlan of(String analyzedRevision,
                                        List<RepositoryMapEntry> foundationEntries,
                                        List<RepositoryMapEntry> targetedSourceEntries,
                                        List<SkippedReadCandidate> skippedCandidates) {
        if (analyzedRevision == null || analyzedRevision.isBlank()) {
            throw new IllegalArgumentException("Repository Read Plan 必须指定 analyzedRevision");
        }
        Set<RepositoryFileReference> seenReferences = new LinkedHashSet<>();
        Set<String> seenPaths = new LinkedHashSet<>();
        List<RepositoryMapEntry> foundation =
                copyUnique(foundationEntries, "foundationEntries", seenReferences, seenPaths);
        List<RepositoryMapEntry> targeted = copyUnique(
                targetedSourceEntries, "targetedSourceEntries", seenReferences, seenPaths);
        List<SkippedReadCandidate> skipped = copyDiagnostics(skippedCandidates);

        return new RepositoryReadPlan(analyzedRevision, foundation, targeted, skipped);
    }

    /**
     * 本次计划对应的已解析 revision。
     *
     * <p>计划里的每个条目都只在这个 revision 上有意义：同一个路径在别的 commit 上
     * 可能是完全不同的内容。
     */
    public String analyzedRevision() {
        return analyzedRevision;
    }

    /** 基础材料，按选取顺序（也就是读取顺序）。 */
    public List<RepositoryMapEntry> foundationEntries() {
        return foundationEntries;
    }

    /**
     * 定向源码，按选取顺序。
     *
     * <p>顺序反映了 Scout 的优先级与聚焦区域轮转的结果，已经去重。
     */
    public List<RepositoryMapEntry> targetedSourceEntries() {
        return targetedSourceEntries;
    }

    /**
     * 两条通道合并后的派生视图：基础材料在前，定向源码在后。
     *
     * <p>纯派生，不构成第二份状态。合并时**不需要再去重**——两条通道在路由上互斥，
     * 同一个描述符不可能同时出现在里面。
     *
     * <p>通道来源仍然可以分辨：每个条目所属的候选组由
     * {@code RepositoryCandidateLane.of(entry)} 决定。
     */
    public List<RepositoryMapEntry> entries() {
        List<RepositoryMapEntry> all =
                new ArrayList<>(foundationEntries.size() + targetedSourceEntries.size());
        all.addAll(foundationEntries);
        all.addAll(targetedSourceEntries);
        return List.copyOf(all);
    }

    /**
     * 被跳过、不进入可读集合的候选及其原因。
     *
     * <p>诊断信息，不参与读取，也不构成任何领域事实。两条通道的跳过都记录在这里；
     * 某个诊断属于哪条通道可以由条目所属的候选组推出。
     */
    public List<SkippedReadCandidate> skippedCandidates() {
        return skippedCandidates;
    }

    /** 本次计划将读取的唯一文件数。 */
    public int size() {
        return foundationEntries.size() + targetedSourceEntries.size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    private static List<RepositoryMapEntry> copyUnique(List<RepositoryMapEntry> entries,
                                                       String field,
                                                       Set<RepositoryFileReference> seenReferences,
                                                       Set<String> seenPaths) {
        if (entries == null) {
            throw new IllegalArgumentException("Repository Read Plan 的 " + field + " 不能为 null");
        }
        List<RepositoryMapEntry> copy = new ArrayList<>(entries.size());
        for (RepositoryMapEntry entry : entries) {
            if (entry == null) {
                throw new IllegalArgumentException(
                        "Repository Read Plan 的 " + field + " 不能包含 null");
            }
            if (!seenReferences.add(entry.reference())) {
                throw new IllegalArgumentException(
                        "同一个文件不得在读取计划里出现两次: " + entry.relativePath());
            }
            if (!seenPaths.add(entry.relativePath())) {
                throw new IllegalArgumentException(
                        "同一个路径不得在读取计划里出现两次（不同编号也不行）: "
                                + entry.relativePath());
            }
            copy.add(entry);
        }
        return List.copyOf(copy);
    }

    private static List<SkippedReadCandidate> copyDiagnostics(
            List<SkippedReadCandidate> skipped) {
        if (skipped == null) {
            throw new IllegalArgumentException(
                    "Repository Read Plan 的 skippedCandidates 不能为 null");
        }
        for (SkippedReadCandidate candidate : skipped) {
            if (candidate == null) {
                throw new IllegalArgumentException(
                        "Repository Read Plan 的 skippedCandidates 不能包含 null");
            }
        }
        return List.copyOf(skipped);
    }
}
