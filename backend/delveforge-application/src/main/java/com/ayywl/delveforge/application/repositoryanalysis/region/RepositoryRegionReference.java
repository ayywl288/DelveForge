package com.ayywl.delveforge.application.repositoryanalysis.region;

/**
 * 一次 Region Catalog 内的区域引用（{@code RR-1}、{@code RR-2}…）。
 *
 * <p>与 {@code RepositoryFileReference}（{@code RF-*}）同源、同一条边界：模型需要指认
 * 「就是这几个区域」，但复述路径既冗长又可能与真实路径不一致。因此给它一本闭集里的短名：
 * 引用要么命中建立本次调用时那份 Region Catalog 中的某个 Region，要么就是失败。
 *
 * <pre>
 * 它不是 Domain 身份          没有独立生命周期，不进入领域对象
 * 它不得被持久化              换一次导航、换一棵树，同一个 RR-1 指向的就是别的目录
 * 它只在一次调用内可解析      越界即为可判定的失败，而不是「尽力匹配」
 * </pre>
 *
 * <p>因此「来自另一次调用的引用」在语义上无法指认别的东西：引用按位置编号，
 * RR-3 只可能是**本次** Catalog 的第 3 个区域。跨调用的引用只会以「本次没有提供的编号」
 * 这一形式出现，由 {@link RepositoryRegionProposalResolver} 拒绝。
 *
 * <p>稳定的技术身份仍是「已解析的 revision + 提交树中的相对目录路径」这一对；
 * 引用只是它在一次调用内的短名。
 *
 * @param value 形如 {@code RR-1}；非空且非空白
 */
public record RepositoryRegionReference(String value) {

    public RepositoryRegionReference {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("RepositoryRegionReference 的 value 不能为空");
        }
    }

    /**
     * 按 Region 在本次 Catalog 中的位置生成引用。
     *
     * @param position 从 1 开始的序号，不得小于 1
     */
    public static RepositoryRegionReference of(int position) {
        if (position < 1) {
            throw new IllegalArgumentException(
                    "RepositoryRegionReference 的 position 从 1 开始: " + position);
        }
        return new RepositoryRegionReference("RR-" + position);
    }
}
