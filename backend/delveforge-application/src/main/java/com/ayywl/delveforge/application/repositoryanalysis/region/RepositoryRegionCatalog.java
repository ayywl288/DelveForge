package com.ayywl.delveforge.application.repositoryanalysis.region;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一次 Region Scout 调用可见的 Region 集合，以及它们的 invocation-local 引用。
 *
 * <pre>
 * RepositoryRegionTree
 *         ↓  取某一层（起点是根层）的兄弟 Region
 * RepositoryRegionCatalog（analyzedRevision + 有序 Region + RR-* 编号）
 * </pre>
 *
 * <h2>编号只在本 Catalog 内有效</h2>
 *
 * <p>引用按列表位置分配：第一个 Region 是 {@code RR-1}，依次类推。因此本类型同时是
 * 引用校验的唯一依据——模型返回的 {@code RR-*} 必须拿**建立本次调用的这一份** Catalog 解析。
 *
 * <p>它不持久化，也不进入 Domain：换一层、换一个 revision，同一个 {@code RR-1} 指向的
 * 就是别的目录。
 *
 * <h2>它不选择，也不排序</h2>
 *
 * <p>顺序就是传入的顺序（起点是 Region 视图的确定性顺序）。哪个 Region 该被探索由
 * Region Scout 决定，本类型只负责「把可选项与它们的编号固定下来」。
 */
public final class RepositoryRegionCatalog {

    private final String analyzedRevision;
    private final List<RepositoryRegion> regions;
    private final List<RegionEntry> entries;
    private final Map<String, RepositoryRegion> byReference;

    private RepositoryRegionCatalog(String analyzedRevision,
                                    List<RepositoryRegion> regions,
                                    List<RegionEntry> entries,
                                    Map<String, RepositoryRegion> byReference) {
        this.analyzedRevision = analyzedRevision;
        this.regions = regions;
        this.entries = entries;
        this.byReference = byReference;
    }

    /**
     * 用一族有序 Region 建立本次调用的目录，并按位置分配 {@code RR-*}。
     *
     * <p>同一目录前缀不得出现两次：重复会让「{@code RR-3} 指的是哪一个」变得不可判定，
     * 而引用一旦不可判定，校验就失去意义。
     *
     * @param analyzedRevision 本次导航固定的 commit id，不得为空白
     * @param regions          有序 Region，不得为 {@code null} 或空，元素不得为 {@code null}，
     *                         且目录前缀不得重复
     * @throws IllegalArgumentException 参数不满足上述约束
     */
    public static RepositoryRegionCatalog of(String analyzedRevision,
                                             List<RepositoryRegion> regions) {
        if (analyzedRevision == null || analyzedRevision.isBlank()) {
            throw new IllegalArgumentException(
                    "RepositoryRegionCatalog 必须指定 analyzedRevision");
        }
        if (regions == null || regions.isEmpty()) {
            throw new IllegalArgumentException(
                    "RepositoryRegionCatalog 的 regions 不能为空");
        }

        List<RepositoryRegion> copy = new ArrayList<>(regions.size());
        List<RegionEntry> entries = new ArrayList<>(regions.size());
        Map<String, RepositoryRegion> byReference = new LinkedHashMap<>();
        for (int index = 0; index < regions.size(); index++) {
            RepositoryRegion region = regions.get(index);
            if (region == null) {
                throw new IllegalArgumentException(
                        "RepositoryRegionCatalog 的 regions 不能包含 null");
            }
            if (copy.stream().anyMatch(existing ->
                    existing.pathPrefix().equals(region.pathPrefix()))) {
                throw new IllegalArgumentException(
                        "RepositoryRegionCatalog 的 regions 不能重复目录前缀: "
                                + region.pathPrefix());
            }
            copy.add(region);
            RepositoryRegionReference reference = RepositoryRegionReference.of(index + 1);
            entries.add(new RegionEntry(reference, region));
            byReference.put(reference.value(), region);
        }
        return new RepositoryRegionCatalog(
                analyzedRevision, List.copyOf(copy), List.copyOf(entries),
                Map.copyOf(byReference));
    }

    /** 本次导航固定的 commit id。 */
    public String analyzedRevision() {
        return analyzedRevision;
    }

    /** 目录中的 Region，顺序即编号顺序。 */
    public List<RepositoryRegion> regions() {
        return regions;
    }

    /** 目录项：Region 与它在本次调用中的引用，顺序即编号顺序。 */
    public List<RegionEntry> entries() {
        return entries;
    }

    public int size() {
        return regions.size();
    }

    /**
     * 按引用取出本次调用提供的 Region。
     *
     * <p>这是引用校验的唯一入口：返回值存在，说明该编号属于**建立本次调用时**的目录；
     * 为空，说明模型引用了一个本次没有提供的编号（编造，或来自另一次调用）。
     *
     * @param reference 待解析的引用；{@code null} 视为不存在
     */
    public Optional<RepositoryRegion> find(RepositoryRegionReference reference) {
        if (reference == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byReference.get(reference.value()));
    }

    /** 一条目录项：Region 与它在本次调用中的引用。 */
    public record RegionEntry(RepositoryRegionReference reference, RepositoryRegion region) {

        public RegionEntry {
            if (reference == null) {
                throw new IllegalArgumentException("RegionEntry 必须指定 reference");
            }
            if (region == null) {
                throw new IllegalArgumentException("RegionEntry 必须指定 region");
            }
        }
    }
}
