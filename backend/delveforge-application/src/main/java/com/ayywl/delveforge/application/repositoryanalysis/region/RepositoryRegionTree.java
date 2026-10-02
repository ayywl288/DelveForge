package com.ayywl.delveforge.application.repositoryanalysis.region;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryCandidateLane;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryLanguage;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryRoleHint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 在 {@code RepositoryMap} 之上按目录前缀聚合出的确定性 Region 视图。
 *
 * <pre>
 * RepositoryMap（完整已提交 tree 的描述符）
 *         ↓  只取 SCOUT_SOURCE，按路径前缀聚合
 * RepositoryRegionTree
 *         ↓  取某一层的兄弟 Region
 * RepositoryRegionCatalog（一次 Region Scout 调用的输入）
 * </pre>
 *
 * <h2>确定、纯函数式、无内容</h2>
 *
 * <p>同一张 Map 得到同一棵树：Region 的顺序按 {@code pathPrefix} 升序，与 Map 自身的
 * 相对路径升序同源。构造过程只读描述符，不读取任何文件内容（ADR-0004 / ADR-0005）。
 *
 * <h2>只有 SCOUT_SOURCE 参与</h2>
 *
 * <p>基础材料（FOUNDATION）与非候选（NONE）都不参与 Region 导航：分层要缩小的正是
 * 「交给 File Scout 的源码候选」，Foundation 通道不受它影响。因此一个目录即使含其它材料，
 * 只要没有源码候选，就不会形成 Region。
 *
 * <h2>仓库根目录下的源码候选不属于任何 Region</h2>
 *
 * <p>Region 由**目录前缀**定义。直接位于仓库根目录的源码候选（路径里没有 {@code /}）
 * 因此不在任何 Region 之下。这一点必须显式暴露而不是静默丢弃，
 * 见 {@link #rootDirectSourceFileCount()}——是否以及如何把它们带进材料，
 * 属于后续编排步骤的取舍，不在本视图内决定。
 *
 * <h2>它不做取舍</h2>
 *
 * <p>本类只回答「树长什么样」：某个前缀有多大、有哪些子目录。它不判断哪个 Region 重要，
 * 也不选择 Region——那是 Region Scout 与（后续的）编排步骤的事。
 */
public final class RepositoryRegionTree {

    private final String analyzedRevision;
    private final Map<String, RepositoryRegion> regionsByPrefix;
    private final Map<String, List<String>> childPrefixesByParent;
    private final int rootDirectSourceFileCount;

    private RepositoryRegionTree(String analyzedRevision,
                                 Map<String, RepositoryRegion> regionsByPrefix,
                                 Map<String, List<String>> childPrefixesByParent,
                                 int rootDirectSourceFileCount) {
        this.analyzedRevision = analyzedRevision;
        this.regionsByPrefix = regionsByPrefix;
        this.childPrefixesByParent = childPrefixesByParent;
        this.rootDirectSourceFileCount = rootDirectSourceFileCount;
    }

    /**
     * 从一张 Repository Map 建立 Region 视图。
     *
     * <p>不修改传入的 Map；没有任何源码候选时得到一棵只含 revision 的空树
     * （调用方要不要在没有源码候选时失败，属于调用方的判断）。
     *
     * @param map 本次分析建立的 Map，不得为 {@code null}
     * @return 该 Map 之上的确定性 Region 视图
     * @throws IllegalArgumentException map 为 {@code null}
     */
    public static RepositoryRegionTree of(RepositoryMap map) {
        if (map == null) {
            throw new IllegalArgumentException("RepositoryRegionTree 必须指定 map");
        }

        Map<String, Integer> direct = new HashMap<>();
        Map<String, Integer> descendant = new HashMap<>();
        Map<String, Set<RepositoryLanguage>> languages = new HashMap<>();
        Map<String, Set<RepositoryRoleHint>> roleHints = new HashMap<>();
        int rootDirect = 0;

        for (RepositoryMapEntry entry : map.entriesIn(RepositoryCandidateLane.SCOUT_SOURCE)) {
            String path = entry.relativePath();
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash < 0) {
                rootDirect++;
                continue;
            }
            direct.merge(path.substring(0, lastSlash), 1, Integer::sum);

            String[] segments = path.split("/");
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < segments.length - 1; i++) {
                if (i > 0) {
                    prefix.append('/');
                }
                prefix.append(segments[i]);
                String value = prefix.toString();
                descendant.merge(value, 1, Integer::sum);
                languages.computeIfAbsent(value, key -> new LinkedHashSet<>())
                        .add(entry.language());
                if (!entry.roleHints().isEmpty()) {
                    roleHints.computeIfAbsent(value, key -> new LinkedHashSet<>())
                            .addAll(entry.roleHints());
                }
            }
        }

        Map<String, Integer> childCounts = new HashMap<>();
        for (String prefix : descendant.keySet()) {
            int slash = prefix.lastIndexOf('/');
            if (slash > 0) {
                childCounts.merge(prefix.substring(0, slash), 1, Integer::sum);
            }
        }

        List<String> prefixes = new ArrayList<>(descendant.keySet());
        Collections.sort(prefixes);

        Map<String, RepositoryRegion> regions = new LinkedHashMap<>();
        Map<String, List<String>> children = new LinkedHashMap<>();
        for (String prefix : prefixes) {
            regions.put(prefix, new RepositoryRegion(
                    prefix,
                    direct.getOrDefault(prefix, 0),
                    descendant.get(prefix),
                    childCounts.getOrDefault(prefix, 0),
                    List.copyOf(languages.getOrDefault(prefix, Set.of())),
                    List.copyOf(roleHints.getOrDefault(prefix, Set.of()))));
            children.computeIfAbsent(parentOf(prefix), key -> new ArrayList<>()).add(prefix);
        }

        return new RepositoryRegionTree(
                map.analyzedRevision(), Map.copyOf(regions), Map.copyOf(children), rootDirect);
    }

    /** 本视图对应的已解析 commit id。 */
    public String analyzedRevision() {
        return analyzedRevision;
    }

    /**
     * 仓库根目录的直接子 Region，按路径升序。
     *
     * <p>这是分层导航第一层能看到的 Region 集合（ADR-0005 的 Region Catalog 来源）。
     * 一个根目录下没有源码候选时，这里为空。
     */
    public List<RepositoryRegion> rootRegions() {
        return childRegions("");
    }

    /**
     * 某个目录前缀的直接子 Region，按路径升序。
     *
     * @param pathPrefix 目录前缀；{@code ""} 表示仓库根目录
     * @return 该前缀下含源码候选的直接子目录；没有则为空列表
     */
    public List<RepositoryRegion> childRegions(String pathPrefix) {
        if (pathPrefix == null) {
            throw new IllegalArgumentException(
                    "RepositoryRegionTree 必须指定 pathPrefix（仓库根目录用空字符串）");
        }
        List<String> prefixes = childPrefixesByParent.get(pathPrefix);
        if (prefixes == null) {
            return List.of();
        }
        List<RepositoryRegion> result = new ArrayList<>(prefixes.size());
        for (String prefix : prefixes) {
            result.add(regionsByPrefix.get(prefix));
        }
        return List.copyOf(result);
    }

    /** 按目录前缀取出该 Region；不存在时为空。 */
    public Optional<RepositoryRegion> region(String pathPrefix) {
        if (pathPrefix == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(regionsByPrefix.get(pathPrefix));
    }

    /** 全部 Region 的数量（所有层级的目录前缀之和）。 */
    public int size() {
        return regionsByPrefix.size();
    }

    /**
     * 直接位于仓库根目录的源码候选数。
     *
     * <p>它们不属于任何 Region（Region 由目录前缀定义）。暴露这个数量是为了让
     * 「根目录下的源码候选」不被静默丢弃：后续编排需要决定如何把它们带进材料。
     */
    public int rootDirectSourceFileCount() {
        return rootDirectSourceFileCount;
    }

    private static String parentOf(String pathPrefix) {
        int slash = pathPrefix.lastIndexOf('/');
        return slash < 0 ? "" : pathPrefix.substring(0, slash);
    }
}
