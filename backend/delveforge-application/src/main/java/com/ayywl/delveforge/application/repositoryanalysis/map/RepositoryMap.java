package com.ayywl.delveforge.application.repositoryanalysis.map;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 某个已解析 revision 上，完整已提交树中的全部文件描述符。
 *
 * <pre>
 * analyzedRevision
 *         ↓
 * RF-1 … RF-n  →  RepositoryMapEntry（路径 / 大小 / 语言 / 材料类别 / 角色提示）
 * </pre>
 *
 * <h2>它是「完整」的，不是采样结果</h2>
 *
 * <p>Map 不因为后续阶段的上下文预算而截断：预算属于「读什么」，不属于「看得到什么」。
 * M1 的层级筛选之所以造成系统性盲区，正是因为它把不可见的问题伪装成了取舍
 * （{@code docs/retrospectives/m1-repository-analysis.md} §5.8）。
 *
 * <p>因此即使一个文件最终不会被读取，它仍然在 Map 里有一个描述符。
 *
 * <h2>它不含任何文件内容</h2>
 *
 * <p>Map 的全部输入是提交树的元数据。它没有读取入口，也不持有 Workspace。
 *
 * <h2>引用与顺序</h2>
 *
 * <p>{@code RF-*} 引用按描述符在 {@link #entries()} 中的位置编号：第 i 个条目的引用一定是
 * {@code RF-i}。这条对应关系由 {@link #of} 强制，因此「引用能不能解析」是一个确定的、
 * 可以判定的问题，而不是「尽力匹配」。
 *
 * <p>顺序由 {@link RepositoryMapBuilder} 决定（相对路径升序），本类型只保留它。
 * 顺序是描述符的一部分：同一个 revision 上重建 Map 必须得到同一份顺序，
 * 否则 {@code RF-3} 会在两次分析之间指向不同的文件。
 *
 * <p>本类型不持久化，也不进入 Domain：它是 Application 在一次 Repository Analysis 内
 * 建立并消费的中间结构（ADR-0004）。
 */
public final class RepositoryMap {

    private final String analyzedRevision;

    private final List<RepositoryMapEntry> entries;

    private final Map<RepositoryFileReference, RepositoryMapEntry> byReference;

    private RepositoryMap(String analyzedRevision, List<RepositoryMapEntry> entries) {
        this.analyzedRevision = analyzedRevision;
        this.entries = entries;

        Map<RepositoryFileReference, RepositoryMapEntry> index = new LinkedHashMap<>();
        for (RepositoryMapEntry entry : entries) {
            index.put(entry.reference(), entry);
        }
        this.byReference = Map.copyOf(index);
    }

    /**
     * 用一族已确定的描述符建立 Map。
     *
     * <p>校验两条使引用可判定的不变量：
     *
     * <pre>
     * 引用必须恰好是 RF-1 … RF-n，顺序与位置一致
     * 相对路径不得重复（提交树里一个路径只对应一个 blob）
     * </pre>
     *
     * <p>两者都是「Map 自身是否可信」的条件，因此在这里拒绝，而不是留给消费方各自处理。
     *
     * @param analyzedRevision 本次分析固定的 commit id，不得为空白
     * @param entries          描述符，顺序即引用编号顺序；不得为 {@code null}，元素不得为
     *                         {@code null}，可以为空（空仓库）
     * @throws IllegalArgumentException 任一条件不成立
     */
    public static RepositoryMap of(String analyzedRevision, List<RepositoryMapEntry> entries) {
        if (analyzedRevision == null || analyzedRevision.isBlank()) {
            throw new IllegalArgumentException("Repository Map 必须指定 analyzedRevision");
        }
        if (entries == null) {
            throw new IllegalArgumentException("Repository Map 的 entries 不能为 null");
        }

        List<RepositoryMapEntry> copy = new ArrayList<>(entries.size());
        Map<String, RepositoryFileReference> seenPaths = new LinkedHashMap<>();

        for (int index = 0; index < entries.size(); index++) {
            RepositoryMapEntry entry = entries.get(index);
            if (entry == null) {
                throw new IllegalArgumentException("Repository Map 的 entries 不能包含 null");
            }
            RepositoryFileReference expected = RepositoryFileReference.of(index + 1);
            if (!expected.equals(entry.reference())) {
                throw new IllegalArgumentException(
                        "Repository Map 的引用必须按位置编号：第 " + (index + 1)
                                + " 个条目的引用应为 " + expected.value()
                                + "，实际为 " + entry.reference().value());
            }
            RepositoryFileReference previous = seenPaths.put(entry.relativePath(), expected);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Repository Map 的路径不得重复: " + entry.relativePath()
                                + "（" + previous.value() + " 与 " + expected.value() + "）");
            }
            copy.add(entry);
        }
        return new RepositoryMap(analyzedRevision, List.copyOf(copy));
    }

    /**
     * 本次 Map 对应的已解析 revision。
     *
     * <p>它是 Map 的稳定技术身份的一半；另一半是每个描述符的相对路径。
     * 两者一起才能指认「这一次分析看的到底是哪一份内容」。
     */
    public String analyzedRevision() {
        return analyzedRevision;
    }

    /** 全部描述符，按相对路径升序；引用与位置一一对应。 */
    public List<RepositoryMapEntry> entries() {
        return entries;
    }

    /** 描述符数量。可以为 0（该 revision 上没有任何已提交文件）。 */
    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * 按引用取出描述符。
     *
     * <p>这是「校验模型给出的引用」这件事的全部实现：命中则返回，越界则为空。
     * 调用方据此决定拒绝还是继续——本类型不抛异常，因为「引用不存在」是可预期的输入问题，
     * 不是 Map 自身的故障。
     *
     * <p>返回的描述符里的 {@code relativePath} 是**唯一**允许进入读取调用的路径。
     *
     * @param reference 待解析的引用；{@code null} 视为不存在
     * @return 对应的描述符；引用不属于本次 Map 时为空
     */
    public Optional<RepositoryMapEntry> find(RepositoryFileReference reference) {
        if (reference == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byReference.get(reference));
    }

    /**
     * 某个候选组下的全部描述符，保持 Map 自身顺序。
     *
     * <p>纯派生视图，不构成第二份状态：它每次都按 {@link RepositoryCandidateLane#of} 算出。
     */
    public List<RepositoryMapEntry> entriesIn(RepositoryCandidateLane lane) {
        if (lane == null) {
            throw new IllegalArgumentException("筛选候选组必须指定 lane");
        }
        List<RepositoryMapEntry> selected = new ArrayList<>();
        for (RepositoryMapEntry entry : entries) {
            if (RepositoryCandidateLane.of(entry) == lane) {
                selected.add(entry);
            }
        }
        return List.copyOf(selected);
    }
}
