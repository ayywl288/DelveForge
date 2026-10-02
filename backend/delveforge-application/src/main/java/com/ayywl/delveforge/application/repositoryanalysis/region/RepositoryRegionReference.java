package com.ayywl.delveforge.application.repositoryanalysis.region;

import java.util.regex.Pattern;

/**
 * 一次 Region Catalog 内的区域引用，形如 {@code RR-3f1a9c02b4d5e6f708192a3b4c5d6e7f-1}。
 *
 * <h2>引用里带着调用作用域</h2>
 *
 * <p>形式是 {@code RR-<scope>-<position>}：{@code scope} 是**本次调用**的标识
 * （每次构造目录时新生成，与内容无关），{@code position} 是该区域在本次目录中的位置（从 1 开始）。
 *
 * <p>作用域保留**完整 32 位十六进制**（UUID 去掉连字符）。截短是不行的：32 位只有约 43 亿种取值，
 * 而按生日悖论，两万多次调用就会出现一次碰撞——两个不同调用拿到同一个作用域，于是旧引用被
 * 新目录解析成了别的区域。碰撞在这里不是理论风险，是本版本会被真实触发的问题。
 *
 * <p>这样做的原因是「引用必须能在跨调用的场合被识别出来」。如果编号只是 {@code RR-1}，
 * 那么 A 调用的 {@code RR-1} 与 B 调用的 {@code RR-1} 是两个无法区分的字符串——把 A 的引用
 * 交给 B 解析会**静默成功**，并返回 B 的第 1 个区域。那等于「当前目录成员校验」，
 * 而不是 ADR-0005 要求的「跨调用引用拒绝」。
 *
 * <p>把作用域写进引用本身之后，不同调用产生的引用在字符串层面就不同：拿另一次调用的引用来解析
 * 只会得到「本次没有提供的编号」，由 {@link RepositoryRegionProposalResolver} 拒绝。
 * 不需要模型额外交回一个 token 字段——引用本来就是不透明的，带上作用域是免费的。
 *
 * <h2>作用域是调用身份，不是内容的函数</h2>
 *
 * <p>作用域由 {@link RepositoryRegionCatalog} 在每次构造时新生成。**刻意不做成内容摘要**：
 * 摘要表达不了调用身份——相同输入的不同调用会算出同一个摘要，上一次调用留下的响应仍会被
 * 这一次接受；而且有限长度的摘要还会碰撞，让两份不同的目录偶然产生同一个引用。
 *
 * <h2>与 RF-* 的关系</h2>
 *
 * <p>它和 {@code RepositoryFileReference} 同源：都是调用内的闭集指针，模型只能**指认**，
 * 不能给出路径。差别在于 {@code RF-*} 由调用方在同一张 Map 上校验，不存在「另一份编号相同
 * 但内容不同」的目录；Region 会一次调用建一份、逐层重建，因此必须把作用域写进值里。
 *
 * <p>它仍不是 Domain 身份，不持久化，换一次导航即失效。
 *
 * @param value 形如 {@code RR-3f1a9c02b4d5e6f708192a3b4c5d6e7f-1}；非空且非空白
 */
public record RepositoryRegionReference(String value) {

    /** 作用域长度：完整 UUID 的 32 位十六进制。 */
    public static final int SCOPE_HEX_LENGTH = 32;

    /** 引用的完整格式：{@code RR-} + 32 位十六进制作用域 + {@code -} + 从 1 开始的位置。 */
    public static final Pattern VALUE_PATTERN =
            Pattern.compile("RR-[0-9a-f]{" + SCOPE_HEX_LENGTH + "}-[1-9][0-9]*");

    /** 作用域本身的格式。 */
    private static final Pattern SCOPE_PATTERN =
            Pattern.compile("[0-9a-f]{" + SCOPE_HEX_LENGTH + "}");

    public RepositoryRegionReference {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("RepositoryRegionReference 的 value 不能为空");
        }
    }

    /**
     * 按作用域与位置生成引用。
     *
     * <p>作用域由 {@link RepositoryRegionCatalog} 在每次调用时生成；本方法只负责拼装与校验形状。
     *
     * @param scope    本次调用作用域，32 位小写十六进制（完整 UUID）
     * @param position 该区域在本次目录中的位置，不得小于 1
     * @throws IllegalArgumentException scope 或 position 不满足上述约束
     */
    public static RepositoryRegionReference of(String scope, int position) {
        if (scope == null || !SCOPE_PATTERN.matcher(scope).matches()) {
            throw new IllegalArgumentException(
                    "RepositoryRegionReference 的作用域必须是 " + SCOPE_HEX_LENGTH
                            + " 位小写十六进制: " + scope);
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
