package com.ayywl.delveforge.domain.evidence;

/**
 * 一条 Evidence 来自哪个确定的 Aggregate 或 Snapshot。
 *
 * <p>{@link Evidence} 自身只表达「来自什么、说了什么、有多可信」，不表达「它属于谁」。
 * 而 Product Direction 必须能够回答「这条推荐理由的依据出自哪一份分析、哪一版用户画像」
 * （§3.6、INV-D06），因此进入 Domain 的依据需要同时带上它的来源。
 *
 * <p>必要性来自一个具体的歧义：两个 Repository Profile 完全可能拥有内容相同的依据——
 *
 * <pre>
 * R1  README.md  "使用 Spring Boot"
 * R2  README.md  "使用 Spring Boot"
 * </pre>
 *
 * 裸 {@code Evidence} 无法区分这两条，也就无法回答「这个方向用的是哪一份分析的结论」。
 *
 * <p>本类型只记录来源，不构成新的身份体系：它是随 Evidence 一起传递的值，
 * 不持久化独立生命周期，也不让 {@link Evidence} 获得 identity。
 *
 * <p>当前只有两种来源，因为只有两类 Aggregate 会产出 Evidence。
 */
public sealed interface EvidenceOrigin
        permits UserProfileEvidenceOrigin, RepositoryProfileEvidenceOrigin {
}
