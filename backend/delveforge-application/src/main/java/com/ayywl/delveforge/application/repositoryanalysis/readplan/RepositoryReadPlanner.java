package com.ayywl.delveforge.application.repositoryanalysis.readplan;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryCandidateLane;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryFileReference;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMaterialKind;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryInspectionArea;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryInspectionPlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 把 Repository Map 与 Scout 的查看计划转成一份有预算、确定的读取计划。
 *
 * <pre>
 * Foundation 候选
 *         ↓  按材料类别分组 → 类别轮转
 * Foundation 预算
 *                                     → RepositoryReadPlan
 * Scout 查看区域（有序）
 *         ↓  聚焦区域轮转 + 跨区域去重
 * 定向源码预算
 * </pre>
 *
 * <h2>两条通道各走各的</h2>
 *
 * <p>Foundation 与定向源码使用各自独立的预算实例，一方读了多少不会让另一方少读。
 * 它们选取的材料性质不同，因此也不共用一条轮转队列：
 *
 * <pre>
 * Foundation   要的是覆盖工程形态的各侧面 → 按材料类别轮转，任何一类都不能独占
 * 定向源码     要的是 Scout 指出的那几处实现 → 按聚焦区域轮转，保持它给的优先级
 * </pre>
 *
 * <h2>只看 metadata，不读内容</h2>
 *
 * <p>本类**不调用** {@code WorkspaceReadPort#readFile}，也不持有 Workspace 能力。
 * 全部取舍都依据列目录时得到的 blob 大小，因此取舍发生在读取之前——既定的预算约束的是
 * 真实工作量，而不只是最终送进模型的内容量。
 *
 * <p>代价是：blob 大小与实际读到的内容长度不一定完全一致（M1 曾在读之后复核一次）。
 * 本阶段不读，因此无从复核；读取阶段的失败与偏差由后续流程处理。
 *
 * <h2>轮转的行为</h2>
 *
 * <pre>
 * 每一轮从每条通道各取一个候选
 * 候选放不下就跳过它、继续在本通道里找下一个能放下的
 * 已经选过的（跨区域重复）直接跳过，不再计一次文件与字节
 * 连一个都选不出来时结束；达到 maxFiles 也结束
 * </pre>
 *
 * <p>「放不下就继续找」与 M1 的选材有一处不同：M1 在遇到放不下的文件时**直接停止整轮收集**，
 * 这里改为跳过并继续考察更小的候选。理由是同一通道里后面的候选没有理由因为前面一个太大
 * 而一起失去机会。
 *
 * <p>本类不截断、不分块、不读半个文件：放不下就是不读。
 */
public final class RepositoryReadPlanner {

    private final RepositoryMaterialBudget foundationBudget;

    private final RepositoryMaterialBudget targetedSourceBudget;

    /**
     * @param foundationBudget     基础材料的预算，不得为 {@code null}
     * @param targetedSourceBudget 定向源码的预算，不得为 {@code null}；
     *                             与前者是两份独立的预算，不会互相占用
     */
    public RepositoryReadPlanner(RepositoryMaterialBudget foundationBudget,
                                 RepositoryMaterialBudget targetedSourceBudget) {
        if (foundationBudget == null) {
            throw new IllegalArgumentException("RepositoryReadPlanner 必须指定 foundationBudget");
        }
        if (targetedSourceBudget == null) {
            throw new IllegalArgumentException(
                    "RepositoryReadPlanner 必须指定 targetedSourceBudget");
        }
        this.foundationBudget = foundationBudget;
        this.targetedSourceBudget = targetedSourceBudget;
    }

    /**
     * 规划本次要读取的文件。
     *
     * @param map             本次分析建立的 Repository Map，不得为 {@code null}
     * @param inspectionPlan  同一次分析的 Scout 查看计划，不得为 {@code null}，
     *                        且必须与 {@code map} 来自同一个 revision
     * @return 确定的读取计划
     * @throws IllegalArgumentException 任一参数为 {@code null}，或查看计划不是由本次这张
     *                                  Map 产生的（revision 不同，或条目与本次 Map 对不上）
     */
    public RepositoryReadPlan plan(RepositoryMap map, RepositoryInspectionPlan inspectionPlan) {
        if (map == null) {
            throw new IllegalArgumentException("RepositoryReadPlanner 必须指定 map");
        }
        if (inspectionPlan == null) {
            throw new IllegalArgumentException(
                    "RepositoryReadPlanner 必须指定 inspectionPlan");
        }
        requireSameRevision(map, inspectionPlan);

        LaneResult foundation = roundRobin(foundationLanes(map), foundationBudget);
        LaneResult targeted =
                roundRobin(targetedSourceLanes(map, inspectionPlan), targetedSourceBudget);

        List<SkippedReadCandidate> skipped =
                new ArrayList<>(foundation.skipped().size() + targeted.skipped().size());
        skipped.addAll(foundation.skipped());
        skipped.addAll(targeted.skipped());

        return RepositoryReadPlan.of(
                map.analyzedRevision(), foundation.selected(), targeted.selected(), skipped);
    }

    /**
     * 两份输入必须描述同一个软件状态。
     *
     * <p>查看计划里的描述符来自建立它时的那张 Map；拿它去和**另一张** Map 一起规划，得到的
     * 计划会混用两个 revision 的路径与大小——而计划只会记录一个 {@code analyzedRevision}，
     * 于是那份记录就是假的。因此这里直接拒绝，而不是挑一个 revision 写进去。
     *
     * <p>这是调用方把不可能的组合传了进来，不是模型输出问题，因此用参数异常。
     */
    private static void requireSameRevision(RepositoryMap map,
                                            RepositoryInspectionPlan inspectionPlan) {
        if (!map.analyzedRevision().equals(inspectionPlan.analyzedRevision())) {
            throw new IllegalArgumentException(
                    "Repository Map 与查看计划来自不同的 revision，不能混合成一份读取计划: "
                            + map.analyzedRevision() + " 与 " + inspectionPlan.analyzedRevision());
        }
    }

    // ---------------------------------------------------------------------
    // 两条通道的候选队列
    // ---------------------------------------------------------------------

    /**
     * 基础材料的候选队列：按材料类别分组，组内按相对路径升序。
     *
     * <p>类别顺序取枚举声明顺序，因此对同一份 Map 是确定的。类别之间轮转的意义是
     * 「任何一类都不能凭数量占满预算」——一个文档很多、源码很少的仓库，不该只分析出文档。
     *
     * <p>组内显式排序，不依赖 Map 的输出顺序：同一份输入必须得到同一份计划，
     * 这件事应当由本类的代码保证。
     */
    private static List<List<RepositoryMapEntry>> foundationLanes(RepositoryMap map) {
        Map<RepositoryMaterialKind, List<RepositoryMapEntry>> byKind =
                new EnumMap<>(RepositoryMaterialKind.class);

        for (RepositoryMapEntry entry : map.entriesIn(RepositoryCandidateLane.FOUNDATION)) {
            byKind.computeIfAbsent(entry.materialKind(), kind -> new ArrayList<>()).add(entry);
        }

        List<List<RepositoryMapEntry>> lanes = new ArrayList<>(byKind.size());
        for (RepositoryMaterialKind kind : RepositoryMaterialKind.values()) {
            List<RepositoryMapEntry> group = byKind.get(kind);
            if (group == null) {
                continue;
            }
            group.sort(Comparator.comparing(RepositoryMapEntry::relativePath));
            lanes.add(List.copyOf(group));
        }
        return lanes;
    }

    /**
     * 定向源码的候选队列：Scout 的聚焦区域，保持它给出的区域顺序与区域内顺序。
     *
     * <p>这里不做任何重排——顺序就是优先级，模型用它表达了「先看哪里」。
     */
    private static List<List<RepositoryMapEntry>> targetedSourceLanes(
            RepositoryMap map, RepositoryInspectionPlan inspectionPlan) {
        List<List<RepositoryMapEntry>> lanes = new ArrayList<>(inspectionPlan.areaCount());
        for (RepositoryInspectionArea area : inspectionPlan.areas()) {
            List<RepositoryMapEntry> entries = new ArrayList<>(area.entries().size());
            for (RepositoryMapEntry entry : area.entries()) {
                entries.add(requireEntryOfThisMap(entry, map));
            }
            lanes.add(List.copyOf(entries));
        }
        return lanes;
    }

    /**
     * 计划里的条目必须确实出自本次这张 Map。
     *
     * <p>只比较 revision 是不够的：revision 是一个字符串，它可以相同，而两张 Map 的内容
     * 不同。把另一张 Map（哪怕它声明同一个 revision）产生的计划拿过来规划，得到的计划会包含
     * **本次 Map 里根本不存在**的路径——而计划只会记录一个 {@code analyzedRevision}，
     * 于是它声称的那份内容就是假的。ADR-0004 把「已解析 revision + 提交树相对路径」定义为
     * 稳定技术身份，两者必须一起成立。
     *
     * <p>逐条核对三件事：
     *
     * <pre>
     * 编号在本次 Map 里存在       否则计划来自别的 Map，或引用了不存在的编号
     * 它确实是源码候选            否则它不该出现在查看计划里
     * 描述符与本次 Map 完全一致   否则「同一个编号」在两张 Map 里指向不同的文件
     * </pre>
     *
     * <p>返回的是**本次 Map 的描述符**：后续读取只用它的路径，不用计划里那个对象的路径。
     *
     * <p>这是两份输入不属于同一次分析，属于调用方把不可能的组合传了进来，因此用参数异常——
     * 与 revision 不一致的处理一致。模型输出本身的问题在 Scout 的引用校验里已经处理过。
     */
    private static RepositoryMapEntry requireEntryOfThisMap(RepositoryMapEntry entry,
                                                            RepositoryMap map) {
        Optional<RepositoryMapEntry> resolved = map.find(entry.reference());
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException(
                    "查看计划引用了本次 Repository Map 里不存在的编号: "
                            + entry.reference().value() + "（" + entry.relativePath()
                            + "）；计划必须由建立本次调用的那张 Map 产生");
        }

        RepositoryMapEntry fromMap = resolved.get();
        if (RepositoryCandidateLane.of(fromMap) != RepositoryCandidateLane.SCOUT_SOURCE) {
            throw new IllegalArgumentException(
                    "查看计划引用了本次 Map 中不是源码候选的文件: "
                            + entry.reference().value() + "（" + fromMap.relativePath() + "）");
        }
        if (!fromMap.equals(entry)) {
            throw new IllegalArgumentException(
                    "查看计划里的描述符与本次 Repository Map 不一致: "
                            + entry.reference().value() + " 在本次 Map 中是 "
                            + fromMap.relativePath() + "，计划里却是 " + entry.relativePath()
                            + "；两份输入不是来自同一次分析");
        }
        return fromMap;
    }

    // ---------------------------------------------------------------------
    // 轮转
    // ---------------------------------------------------------------------

    /**
     * 在预算内按轮转从各条队列里选取候选。
     *
     * <p>每一轮从每条队列各取一个：先看过的不代表后面的更好，也不代表后面的更差——
     * 轮转保证的是每条队列都有机会贡献，而不是让排在最前的队列占满预算。
     *
     * <p>一条队列在自己的这一轮里会一直往下找，直到找到一个「没处理过、放得下」的候选：
     * 跨区域重复、超过单文件上限、放不进剩余总量，都只是跳过当前这个，不影响后面的候选。
     * 这正是「重复不应该消耗掉一条队列本轮的贡献机会」的实现。
     *
     * <p>「已处理」既包括被选中的，也包括已经被记录为跳过的。同一个文件被几个队列同时提到时，
     * 它的结局只有一个：要么读一次，要么因为同一个原因被跳过一次。若只对选中的去重，
     * 一个被三个区域引用的大文件会留下三条一模一样的诊断，把「跳过了几个文件」这件事
     * 说成三倍。跳过是文件的属性，不是引用的属性。
     *
     * <p>把跳过的也标记为已处理是安全的：字节只增不减，放不进的候选之后更放不进；
     * 尺寸也不会在规划过程中变化。因此后续再次遇到它，结论不会不同。
     */
    private static LaneResult roundRobin(List<List<RepositoryMapEntry>> lanes,
                                         RepositoryMaterialBudget budget) {

        int[] cursors = new int[lanes.size()];
        List<RepositoryMapEntry> selected = new ArrayList<>();
        Set<RepositoryFileReference> handledReferences = new LinkedHashSet<>();
        List<SkippedReadCandidate> skipped = new ArrayList<>();
        long selectedBytes = 0;

        boolean progressed = true;
        while (progressed && selected.size() < budget.maxFiles()) {
            progressed = false;

            for (int lane = 0; lane < lanes.size(); lane++) {
                if (selected.size() >= budget.maxFiles()) {
                    break;
                }
                List<RepositoryMapEntry> candidates = lanes.get(lane);

                while (cursors[lane] < candidates.size()) {
                    RepositoryMapEntry candidate = candidates.get(cursors[lane]++);

                    if (handledReferences.contains(candidate.reference())) {
                        // 这个文件已经通过另一条队列选过、或已经记过一条跳过了。
                        // 物理上只会处理它一次，因此既不重复计数，也不留下重复诊断，
                        // 更不占用本队列这一轮的贡献机会。
                        continue;
                    }
                    if (candidate.sizeInBytes() > budget.maxFileBytes()) {
                        skipped.add(new SkippedReadCandidate(
                                candidate, RepositoryReadSkipReason.SELECTED_BUT_TOO_LARGE));
                        handledReferences.add(candidate.reference());
                        continue;
                    }
                    if (selectedBytes + candidate.sizeInBytes() > budget.maxTotalBytes()) {
                        skipped.add(new SkippedReadCandidate(
                                candidate,
                                RepositoryReadSkipReason.EXCEEDS_REMAINING_TOTAL_BYTES));
                        handledReferences.add(candidate.reference());
                        continue;
                    }

                    selected.add(candidate);
                    handledReferences.add(candidate.reference());
                    selectedBytes += candidate.sizeInBytes();
                    progressed = true;
                    break;
                }
            }
        }
        return new LaneResult(List.copyOf(selected), List.copyOf(skipped));
    }

    /** 一条通道的选取结果：选中的候选，以及被跳过的候选。 */
    private record LaneResult(List<RepositoryMapEntry> selected,
                              List<SkippedReadCandidate> skipped) {
    }
}
