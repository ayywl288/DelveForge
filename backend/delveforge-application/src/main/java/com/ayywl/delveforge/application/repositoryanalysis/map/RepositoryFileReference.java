package com.ayywl.delveforge.application.repositoryanalysis.map;

/**
 * 一次 Repository Map 内的文件引用（{@code RF-1}、{@code RF-2}…）。
 *
 * <h2>它只在一次调用内有效</h2>
 *
 * <p>后续的 Scout 需要指认「就是这几个文件」。让它复述路径有两个问题：路径很长，
 * 而且模型复述出来的字符串与真实路径不一定一致——一旦不一致，就无法判断它指的是谁。
 * 因此给它一本闭集里的短名：引用要么命中本次 Map 中的某个描述符，要么就是失败。
 *
 * <p>它与 {@code DirectionDiscoveryInputs} 里的 {@code U-E1} / {@code R1-E2} 是同一类东西，
 * 也遵守同一条边界：
 *
 * <pre>
 * 它不是 Domain 身份          没有独立生命周期，不进入领域对象
 * 它不得被持久化              换一次分析、换一棵树，同一个 RF-1 指向的就是别的文件
 * 它只在一次 Map 内可解析      越界即为可判定的失败，而不是「尽力匹配」
 * </pre>
 *
 * <p>稳定的技术身份始终是「已解析的 revision + 提交树中的相对路径」这一对；
 * 引用只是它在一次调用内的短名。
 *
 * @param value 形如 {@code RF-1}；非空且非空白
 */
public record RepositoryFileReference(String value) {

    public RepositoryFileReference {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("RepositoryFileReference 的 value 不能为空");
        }
    }

    /**
     * 按描述符在本次 Map 中的位置生成引用。
     *
     * <p>从 1 开始，与人工阅读时的直觉一致。
     *
     * @param position 从 1 开始的序号，不得小于 1
     */
    public static RepositoryFileReference of(int position) {
        if (position < 1) {
            throw new IllegalArgumentException(
                    "RepositoryFileReference 的 position 从 1 开始: " + position);
        }
        return new RepositoryFileReference("RF-" + position);
    }
}
