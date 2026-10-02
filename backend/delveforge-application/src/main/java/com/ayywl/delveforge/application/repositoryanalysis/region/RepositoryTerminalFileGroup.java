package com.ayywl.delveforge.application.repositoryanalysis.region;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import java.util.ArrayList;
import java.util.List;

/**
 * 分层导航的一个终态文件组：可以直接交给**一次分支本地 File Scout 调用**的一组源码文件。
 *
 * <pre>
 * 一个终态组来自两种情况之一：
 *   1. 某个 Region 的全部后代文件已经能装进 File Catalog —— 整支停下来
 *   2. 某个节点被分解时，直接位于该节点下的源码文件 —— 它们没有更细的结构可以下钻
 * </pre>
 *
 * <h2>它是原描述符的子集</h2>
 *
 * <p>{@code sourceFiles} 里的每一项都是**建立本次导航那张 {@code RepositoryMap} 上的原描述符**，
 * 编号保持 Map 中的取值。因此「这组文件是 Map 的一个子集」是可核对的，
 * 而不是靠重新编号后看起来像。
 *
 * <p>引用编号是**调用内**的短名（ADR-0004），换一次 File Scout 调用就会重新分配；
 * 文件身份始终由 {@code relativePath} 承载。为分支本地调用重新编号是消费方的事——
 * 本类型只保证「是哪几个文件」不会丢、不会串。
 *
 * @param pathPrefix       该组所属的目录前缀；直接位于仓库根目录的文件用空字符串
 * @param sourceFiles      该组的源码文件，是该 Map 描述符的子集；不得为 {@code null} 或空
 * @param fileCatalogBytes 这组文件作为一次分支本地 File Scout 目录时的载荷字节数
 *                         （按分支本地调用重新编号后序列化得到）
 */
public record RepositoryTerminalFileGroup(
        String pathPrefix,
        List<RepositoryMapEntry> sourceFiles,
        int fileCatalogBytes) {

    public RepositoryTerminalFileGroup {
        if (pathPrefix == null) {
            throw new IllegalArgumentException(
                    "RepositoryTerminalFileGroup 必须指定 pathPrefix（根目录用空字符串）");
        }
        if (pathPrefix.startsWith("/") || pathPrefix.endsWith("/")
                || pathPrefix.contains("\\")) {
            throw new IllegalArgumentException(
                    "RepositoryTerminalFileGroup 的 pathPrefix 必须是仓库内相对目录: " + pathPrefix);
        }
        if (sourceFiles == null || sourceFiles.isEmpty()) {
            throw new IllegalArgumentException(
                    "RepositoryTerminalFileGroup 的 sourceFiles 不能为空");
        }
        if (fileCatalogBytes < 0) {
            throw new IllegalArgumentException(
                    "RepositoryTerminalFileGroup 的 fileCatalogBytes 不能为负数: "
                            + fileCatalogBytes);
        }
        List<RepositoryMapEntry> copy = new ArrayList<>(sourceFiles.size());
        for (RepositoryMapEntry entry : sourceFiles) {
            if (entry == null) {
                throw new IllegalArgumentException(
                        "RepositoryTerminalFileGroup 的 sourceFiles 不能包含 null");
            }
            copy.add(entry);
        }
        sourceFiles = List.copyOf(copy);
    }

    /** 这组文件的数量。 */
    public int size() {
        return sourceFiles.size();
    }
}
