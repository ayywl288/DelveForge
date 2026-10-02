package com.ayywl.delveforge.application.repositoryanalysis.region;

import java.util.regex.Pattern;

/**
 * 一次 Region Catalog 内的区域引用，形如 {@code RR-3f1a9c02-1}。
 *
 * <h2>引用里带着调用作用域</h2>
 *
 * <p>形式是 {@code RR-<scope>-<position>}：{@code scope} 是建立本次 Catalog 时由内容派生出的
 * 短标识，{@code position} 是该区域在本次目录中的位置（从 1 开始）。
 *
 * <p>这样做的原因是「引用必须能在跨调用的场合被识别出来」。如果编号只是 {@code RR-1}，
 * 那么 A 调用的 {@code RR-1} 与 B 调用的 {@code RR-1} 是两个无法区分的字符串——把 A 的引用
 * 交给 B 解析会**静默成功**，并返回 B 的第 1 个区域。那等于「当前目录成员校验」，
 * 而不是 ADR-0005 要求的「跨调用引用拒绝」。
 *
 * <p>把作用域写进引用本身之后，不同目录产生的引用在字符串层面就不同：拿另一个目录的引用来解析
 * 只会得到「本次没有提供的编号」，由 {@link RepositoryRegionProposalResolver} 拒绝。
 * 不需要模型额外交回一个 token 字段——引用本来就是不透明的，带上作用域是免费的。
 *
 * <h2>作用域由内容派生，不是随机数</h2>
 *
 * <p>作用域是 {@code analyzedRevision} 与该层 Region 前缀序列的摘要（见
 * {@link RepositoryRegionCatalog}）。因此：
 *
 * <pre>
 * 内容不同 → 作用域不同 → 引用不可互换（这正是需要拒绝的危险情形）
 * 内容相同 → 作用域相同 → 引用可互换（两份目录在所有可观察意义上就是同一份，没有可拒绝的理由）
 * </pre>
 *
 * <p>这一点是刻意的：拒绝一个指向**正确**区域的引用会是误判。
 *
 * <h2>与 RF-* 的关系</h2>
 *
 * <p>它和 {@code RepositoryFileReference} 同源：都是调用内的闭集指针，模型只能**指认**，
 * 不能给出路径。差别在于 {@code RF-*} 由调用方在同一张 Map 上校验，不存在「另一份编号相同
 * 但内容不同」的目录；Region 会一次调用建一份、逐层重建，因此必须把作用域写进值里。
 *
 * <p>它仍不是 Domain 身份，不持久化，换一次导航即失效。
 *
 * @param value 形如 {@code RR-3f1a9c02-1}；非空且非空白
 */
public record RepositoryRegionReference(String value) {

    /** 引用的完整格式：{@code RR-} + 8 位十六进制作用域 + {@code -} + 从 1 开始的位置。 */
    public static final Pattern VALUE_PATTERN = Pattern.compile("RR-[0-9a-f]{8}-[1-9][0-9]*");

    /** 作用域本身的格式。 */
    private static final Pattern SCOPE_PATTERN = Pattern.compile("[0-9a-f]{8}");

    public RepositoryRegionReference {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("RepositoryRegionReference 的 value 不能为空");
        }
    }

    /**
     * 按作用域与位置生成引用。
     *
     * <p>作用域由 {@link RepositoryRegionCatalog} 从目录内容派生；本方法只负责拼装与校验形状。
     *
     * @param scope    本次调用作用域，8 位小写十六进制
     * @param position 该区域在本次目录中的位置，不得小于 1
     * @throws IllegalArgumentException scope 或 position 不满足上述约束
     */
    public static RepositoryRegionReference of(String scope, int position) {
        if (scope == null || !SCOPE_PATTERN.matcher(scope).matches()) {
            throw new IllegalArgumentException(
                    "RepositoryRegionReference 的作用域必须是 8 位小写十六进制: " + scope);
        }
        if (position < 1) {
            throw new IllegalArgumentException(
                    "RepositoryRegionReference 的 position 从 1 开始: " + position);
        }
        return new RepositoryRegionReference("RR-" + scope + "-" + position);
    }

    /**
     * 该取值是否符合引用格式。
     *
     * <p>格式知识放在引用类型上，是因为它现在同时被**生产方**（目录拼装编号）与
     * **校验方**（解析模型输出）使用；分开放会漂移。
     */
    public static boolean isWellFormed(String value) {
        return value != null && VALUE_PATTERN.matcher(value).matches();
    }
}
