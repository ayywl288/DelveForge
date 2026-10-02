package com.ayywl.delveforge.application.repositoryanalysis.region;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 一次 Region Scout 调用可见的 Region 集合，以及它们的 invocation-local 引用。
 *
 * <pre>
 * RepositoryRegionTree
 *         ↓  取某一层（起点是根层）的兄弟 Region
 * RepositoryRegionCatalog（analyzedRevision + 有序 Region + RR-* 编号）
 * </pre>
 *
 * <h2>引用自带作用域，因此跨调用的引用可判定</h2>
 *
 * <p>每个引用形如 {@code RR-<scope>-<position>}，其中 {@code scope} 是**本次调用**的标识：
 * 每构造一份 Catalog 就取一个新的，与目录内容无关。于是：
 *
 * <pre>
 * 同一次调用内的引用 → 互相可解析
 * 任何另一次调用     → 引用字符串不同 → 找不到 → 拒绝
 * </pre>
 *
 * <p>作用域**刻意不由内容派生**。内容摘要做不到调用身份：相同输入的不同调用会得到相同引用，
 * 上一次调用留下的响应仍会被这一次接受；而且有限长度的摘要还会碰撞，让两份不同的目录
 * 偶然产生同一个引用。调用身份必须是每次调用新生成的，不是算出来的。
 *
 * <p>生成方式可注入（{@link #of(String, List, Supplier)}），因此测试可以给出确定的标识；
 * 默认实现与项目其它标识一致，取随机值。
 *
 * <p>它不持久化，也不进入 Domain：换一层、换一个 revision、换一次调用，作用域都会变。
 *
 * <h2>它不选择，也不排序</h2>
 *
 * <p>顺序就是传入的顺序（起点是 Region 视图的确定性顺序）。哪个 Region 该被探索由
 * Region Scout 决定，本类型只负责「把可选项与它们的编号固定下来」。
 */
public final class RepositoryRegionCatalog {

    /** 作用域长度：8 位十六进制。 */
    private static final int SCOPE_HEX_LENGTH = 8;

    /** 作用域取值格式。 */
    private static final Pattern SCOPE_PATTERN = Pattern.compile("[0-9a-f]{8}");

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
     * <p>作用域取每次调用新生成的标识（默认随机，见 {@link #of(String, List, Supplier)}）。
     * 同一目录前缀不得出现两次：重复会让「第 n 个是哪一个」变得不可判定，
     * 而引用一旦不可判定，校验就失去意义。
     *
     * @param analyzedRevision 本次导航固定的 commit id，不得为空白
     * @param regions          有序 Region，不得为 {@code null} 或空，元素不得为 {@code null}，
     *                         且目录前缀不得重复
     * @throws IllegalArgumentException 参数不满足上述约束
     */
    public static RepositoryRegionCatalog of(String analyzedRevision,
                                             List<RepositoryRegion> regions) {
        return of(analyzedRevision, regions, RepositoryRegionCatalog::newInvocationScope);
    }

    /**
     * 用一族有序 Region 建立本次调用的目录，作用域由给定来源产生。
     *
     * <p>作用域必须**每次调用都不同**——它是调用身份的载体，不是内容的函数。注入的来源
     * 让测试可以给出确定的标识；生产路径使用默认的随机标识。
     *
     * @param analyzedRevision 本次导航固定的 commit id，不得为空白
     * @param regions          有序 Region，不得为 {@code null} 或空，元素不得为 {@code null}，
     *                         且目录前缀不得重复
     * @param scopeSupplier    本次调用作用域的来源，不得为 {@code null}，产出的取值必须符合
     *                         {@code [0-9a-f]{8}}
     * @throws IllegalArgumentException 参数不满足上述约束
     */
    public static RepositoryRegionCatalog of(String analyzedRevision,
                                             List<RepositoryRegion> regions,
                                             Supplier<String> scopeSupplier) {
        if (analyzedRevision == null || analyzedRevision.isBlank()) {
            throw new IllegalArgumentException(
                    "RepositoryRegionCatalog 必须指定 analyzedRevision");
        }
        if (regions == null || regions.isEmpty()) {
            throw new IllegalArgumentException(
                    "RepositoryRegionCatalog 的 regions 不能为空");
        }
        if (scopeSupplier == null) {
            throw new IllegalArgumentException(
                    "RepositoryRegionCatalog 必须指定 scopeSupplier");
        }

        List<RepositoryRegion> copy = new ArrayList<>(regions.size());
        for (RepositoryRegion region : regions) {
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
        }

        String scope = requireScope(scopeSupplier.get());
        List<RegionEntry> entries = new ArrayList<>(copy.size());
        Map<String, RepositoryRegion> byReference = new LinkedHashMap<>();
        for (int index = 0; index < copy.size(); index++) {
            RepositoryRegionReference reference = RepositoryRegionReference.of(scope, index + 1);
            entries.add(new RegionEntry(reference, copy.get(index)));
            byReference.put(reference.value(), copy.get(index));
        }
        return new RepositoryRegionCatalog(
                analyzedRevision, List.copyOf(copy), List.copyOf(entries),
                Map.copyOf(byReference));
    }

    /** 每次调用新生成一个作用域标识：与目录内容无关，因此相同输入的不同调用也不同。 */
    private static String newInvocationScope() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, SCOPE_HEX_LENGTH);
    }

    private static String requireScope(String scope) {
        if (scope == null || !SCOPE_PATTERN.matcher(scope).matches()) {
            throw new IllegalArgumentException(
                    "本次调用的作用域必须是 8 位小写十六进制: " + scope);
        }
        return scope;
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
