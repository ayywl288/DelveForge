package com.ayywl.delveforge.application.repositoryanalysis.region;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * <h2>引用自带作用域，因此跨调用的引用可判定</h2>
 *
 * <p>每个引用形如 {@code RR-<scope>-<position>}，其中 {@code scope} 由**目录内容**派生
 * （{@code analyzedRevision} 与该层 Region 前缀序列的摘要）。于是：
 *
 * <pre>
 * 拿另一份目录的引用来本目录解析 → 字符串不同 → 找不到 → 拒绝
 * 内容完全相同的目录               → 作用域相同 → 引用可互换（两份目录没有可观察差别）
 * </pre>
 *
 * <p>本类型因此是引用校验的唯一依据，而校验是**字符串相等**——不需要额外核对调用 token，
 * 也无法把外来编号误认成本次调用。理由与取舍见 {@link RepositoryRegionReference}。
 *
 * <p>它不持久化，也不进入 Domain：换一层、换一个 revision，作用域随之改变。
 *
 * <h2>它不选择，也不排序</h2>
 *
 * <p>顺序就是传入的顺序（起点是 Region 视图的确定性顺序）。哪个 Region 该被探索由
 * Region Scout 决定，本类型只负责「把可选项与它们的编号固定下来」。
 */
public final class RepositoryRegionCatalog {

    /** 作用域长度：8 位十六进制（4 字节摘要）。 */
    private static final int SCOPE_HEX_LENGTH = 8;

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

        String scope = scopeOf(analyzedRevision, copy);
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

    /**
     * 由目录内容派生本次调用的作用域。
     *
     * <p>取 {@code analyzedRevision} 与该层 Region 前缀序列（含顺序）的 SHA-256 前 4 字节。
     * 内容是确定的，作用域因此也是确定的：同一份目录重建两次得到同一个作用域，
     * 内容不同则作用域不同。
     */
    private static String scopeOf(String analyzedRevision, List<RepositoryRegion> regions) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(analyzedRevision.getBytes(StandardCharsets.UTF_8));
            for (RepositoryRegion region : regions) {
                digest.update((byte) 0);
                digest.update(region.pathPrefix().getBytes(StandardCharsets.UTF_8));
            }
            byte[] hash = digest.digest();
            StringBuilder scope = new StringBuilder(SCOPE_HEX_LENGTH);
            for (int i = 0; i < SCOPE_HEX_LENGTH / 2; i++) {
                scope.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                scope.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return scope.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
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
