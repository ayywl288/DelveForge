package com.ayywl.delveforge.application.repositoryanalysis.map;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Repository Map 中的一个文件描述符。
 *
 * <pre>
 * reference      本次 Map 内的短名（RF-1）
 * relativePath   提交树中的相对路径——这是稳定的技术身份
 * sizeInBytes    该 revision 上 blob 的字节数
 * language       按扩展名识别的语言
 * materialKind   这是什么材料
 * roleHints      源码可能扮演的结构角色（零个或多个）
 * </pre>
 *
 * <h2>它不含文件内容</h2>
 *
 * <p>本类型刻意没有内容字段：Map 的用途是让后续阶段**只看描述符**就能决定读什么。
 * 一旦把内容带进来，「看一眼整棵树」就重新变成了有界采样，而要解决的问题会原样回来。
 *
 * <p>因此本类型不提供任何读取入口，它的构造也不接触 Workspace。
 *
 * <h2>它是什么、不是什么</h2>
 *
 * <pre>
 * 是   一次分析内对一个已提交文件的确定性描述
 * 是   RF-* 引用与真实路径之间的唯一映射载体
 * 不是 领域对象：不进入 Domain，不持久化，换一棵树即失效
 * 不是 对文件内容的断言：language / materialKind / roleHints 都只是路径启发式
 * </pre>
 *
 * @param reference   本次 Map 内的短名，不得为 {@code null}
 * @param relativePath 相对于 Workspace 根目录的路径，不得为 {@code null}、空白或越界
 * @param sizeInBytes  该 revision 上该 blob 的字节数，不得为负
 * @param language     按扩展名识别的语言，不得为 {@code null}
 * @param materialKind 该文件属于哪一类材料，不得为 {@code null}
 * @param roleHints    结构角色提示；不得为 {@code null}，元素不得为 {@code null}，
 *                     不得重复；{@link RepositoryRoleHint#UNKNOWN} 只能单独出现
 */
public record RepositoryMapEntry(
        RepositoryFileReference reference,
        String relativePath,
        long sizeInBytes,
        RepositoryLanguage language,
        RepositoryMaterialKind materialKind,
        List<RepositoryRoleHint> roleHints) {

    /** Windows 盘符形式：{@code C:\...} 或 {@code C:/...}。 */
    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:[\\\\/].*");

    public RepositoryMapEntry {
        if (reference == null) {
            throw new IllegalArgumentException("Repository Map 条目必须指定 reference");
        }
        requireSafeRelativePath(relativePath);
        if (sizeInBytes < 0) {
            throw new IllegalArgumentException(
                    "Repository Map 条目的 sizeInBytes 不能为负数: " + sizeInBytes);
        }
        if (language == null) {
            throw new IllegalArgumentException("Repository Map 条目必须指定 language");
        }
        if (materialKind == null) {
            throw new IllegalArgumentException("Repository Map 条目必须指定 materialKind");
        }
        roleHints = normalizeRoleHints(roleHints);
    }

    /** 该条目是否带有某个结构角色提示。 */
    public boolean hasRoleHint(RepositoryRoleHint hint) {
        return roleHints.contains(hint);
    }

    /**
     * 路径必须仍然是一个安全的相对路径。
     *
     * <p>这条校验与 Workspace 边界上的同类校验重复了一次，是刻意的：描述符里的路径最终会
     * 被拿去调用 {@code readFile}，因此在它离开 Map 之前再确认一次「不会越过根目录」
     * 比信任上游更稳妥。真正的路径来源始终是 {@code listEntries} 的结果。
     */
    private static void requireSafeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Repository Map 条目的 relativePath 不能为空");
        }
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")
                || DRIVE_PREFIX.matcher(relativePath).matches()) {
            throw new IllegalArgumentException(
                    "Repository Map 条目的 relativePath 必须是相对路径: " + relativePath);
        }
        for (String segment : relativePath.split("[/\\\\]")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException(
                        "Repository Map 条目的 relativePath 不得越过 Workspace 根目录: "
                                + relativePath);
            }
        }
    }

    /**
     * 规范化角色提示：保持传入顺序、去重、并维持「UNKNOWN 只能单独出现」。
     *
     * <p>UNKNOWN 的含义是「没有别的可依据信号」，因此它与任何具体提示同时出现都是自相矛盾
     * 的描述。与其在消费侧各自处理这种组合，不如让它在这里就构造不出来。
     */
    private static List<RepositoryRoleHint> normalizeRoleHints(List<RepositoryRoleHint> hints) {
        if (hints == null) {
            throw new IllegalArgumentException("Repository Map 条目的 roleHints 不能为 null");
        }
        List<RepositoryRoleHint> normalized = new java.util.ArrayList<>(hints.size());
        for (RepositoryRoleHint hint : hints) {
            if (hint == null) {
                throw new IllegalArgumentException(
                        "Repository Map 条目的 roleHints 不能包含 null");
            }
            if (!normalized.contains(hint)) {
                normalized.add(hint);
            }
        }
        if (normalized.size() > 1 && normalized.contains(RepositoryRoleHint.UNKNOWN)) {
            throw new IllegalArgumentException(
                    "UNKNOWN 表示没有其它可依据信号，不能与具体角色提示同时出现: " + normalized);
        }
        return List.copyOf(normalized);
    }
}
