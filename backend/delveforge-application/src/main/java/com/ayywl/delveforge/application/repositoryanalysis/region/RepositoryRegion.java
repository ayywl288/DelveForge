package com.ayywl.delveforge.application.repositoryanalysis.region;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryLanguage;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryRoleHint;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 一个 Repository Region：{@code RepositoryMap} 上某个目录前缀的导航视图。
 *
 * <h2>它是导航元数据，不是领域概念</h2>
 *
 * <p>Region 不是 Entity，没有身份、没有生命周期、不持久化、不进入 Domain。它只是
 * 「把整棵树按目录切块后，某一块看起来有多大」这一确定性事实的载体（ADR-0005）。
 * 换一个 revision、换一棵树，同一个前缀指向的就是别的内容。
 *
 * <h2>描述符里没有文件内容，也没有任何 AI 判断</h2>
 *
 * <p>全部字段都来自既有的 {@code RepositoryMap} 元数据（路径、语言、材料类别、结构角色提示）。
 * 不含 purpose / capability / importance / confidence / score 这类字段——那些是需要读过内容
 * 才能成立的东西，而 Region 阶段看不到内容。
 *
 * <h2>涵盖范围只由 SCOUT_SOURCE 决定</h2>
 *
 * <p>{@code descendantSourceFileCount} 与 {@code directSourceFileCount} 统计的都是
 * **源码候选**（{@code RepositoryCandidateLane.SCOUT_SOURCE}）。基础材料不参与 Region 导航：
 * 分层的目的是把「交给 File Scout 的源码候选」缩小，Foundation 通道不受它影响。
 *
 * <p>因此一个目录即使含有非源码候选的文件，只要没有源码候选，就不会形成 Region。
 *
 * @param pathPrefix                该 Region 对应的目录前缀，形如 {@code src/main/java}；
 *                                  仓库内的相对路径，不含首尾斜杠，非空且非空白
 * @param directSourceFileCount     直接位于该目录下的源码候选数，不得小于 0
 * @param descendantSourceFileCount 该前缀下全部源码候选数，不得小于 {@code directSourceFileCount}
 * @param childRegionCount          含有源码候选的直接子目录数，不得小于 0
 * @param languages                 后代源码候选出现过的语言，去重且有序
 * @param roleHints                 后代源码候选出现过的结构角色提示，去重且有序
 */
public record RepositoryRegion(
        String pathPrefix,
        int directSourceFileCount,
        int descendantSourceFileCount,
        int childRegionCount,
        List<RepositoryLanguage> languages,
        List<RepositoryRoleHint> roleHints) {

    /** Windows 盘符形式：{@code C:\...} 或 {@code C:/...}。 */
    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:[\\\\/].*");

    public RepositoryRegion {
        requireSafePathPrefix(pathPrefix);
        if (directSourceFileCount < 0) {
            throw new IllegalArgumentException(
                    "RepositoryRegion 的 directSourceFileCount 不能为负数: "
                            + directSourceFileCount);
        }
        if (descendantSourceFileCount < directSourceFileCount) {
            throw new IllegalArgumentException(
                    "RepositoryRegion 的 descendantSourceFileCount 不能小于 directSourceFileCount: "
                            + descendantSourceFileCount + " < " + directSourceFileCount);
        }
        if (childRegionCount < 0) {
            throw new IllegalArgumentException(
                    "RepositoryRegion 的 childRegionCount 不能为负数: " + childRegionCount);
        }
        languages = normalizeEnum(languages, "languages");
        roleHints = normalizeEnum(roleHints, "roleHints");
    }

    /**
     * 校验一个 Region 前缀是一个安全的仓库内相对目录路径。
     *
     * <p>与 {@code RepositoryMapEntry} 的路径校验同一个口径：拒绝空白、绝对路径、盘符、
     * 反斜杠与首尾斜杠。Region 前缀最终会出现在发给模型的目录载荷里，因此它必须是一个
     * 与平台无关、可以直接拼进相对路径的字符串。
     */
    private static void requireSafePathPrefix(String pathPrefix) {
        if (pathPrefix == null || pathPrefix.isBlank()) {
            throw new IllegalArgumentException("RepositoryRegion 必须指定 pathPrefix");
        }
        if (pathPrefix.startsWith("/") || pathPrefix.endsWith("/")) {
            throw new IllegalArgumentException(
                    "RepositoryRegion 的 pathPrefix 不能以斜杠开头或结尾: " + pathPrefix);
        }
        if (pathPrefix.contains("\\")) {
            throw new IllegalArgumentException(
                    "RepositoryRegion 的 pathPrefix 必须使用正斜杠: " + pathPrefix);
        }
        if (DRIVE_PREFIX.matcher(pathPrefix).matches()) {
            throw new IllegalArgumentException(
                    "RepositoryRegion 的 pathPrefix 必须是仓库内相对路径: " + pathPrefix);
        }
        for (String segment : pathPrefix.split("/", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException(
                        "RepositoryRegion 的 pathPrefix 含空路径段: " + pathPrefix);
            }
        }
    }

    /** 去重、排序并固化为不可修改列表：描述符输出必须对同一输入保持确定。 */
    private static <E extends Enum<E>> List<E> normalizeEnum(List<E> values, String field) {
        if (values == null) {
            throw new IllegalArgumentException("RepositoryRegion 的 " + field + " 不能为 null");
        }
        Set<E> unique = new LinkedHashSet<>();
        for (E value : values) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "RepositoryRegion 的 " + field + " 不能包含 null");
            }
            unique.add(value);
        }
        List<E> sorted = new ArrayList<>(unique);
        sorted.sort(Enum::compareTo);
        return List.copyOf(sorted);
    }
}
