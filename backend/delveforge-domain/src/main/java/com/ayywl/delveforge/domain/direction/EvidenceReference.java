package com.ayywl.delveforge.domain.direction;

/**
 * 指向本次 AI 调用所提供的某条 Evidence 的引用。
 *
 * <p>Product Direction 的推荐理由需要能够回答「这个判断由哪些已有依据支撑」
 * （DOMAIN_MODEL.md §3.6、INV-D06）。AI 有资格指出「哪些已有依据支撑了这条判断」，
 * 但没有资格重新给出依据本身——它不该、也无法知道这些依据当初是怎么被记录的。
 *
 * <p>因此一次 Direction Discovery 会先把 Confirmed User Profile 与 Repository Profile
 * 中已经存在的 Evidence 连同各自的引用交给模型，模型只能引用其中的条目。本类型就是
 * 那个引用。
 *
 * <h2>它不是 Evidence 的身份</h2>
 *
 * <p>本类型的取值<b>只在产生它的那一次 AI 调用中有效</b>：它是「本次提示里第几个依据」
 * 的编号，不是 Evidence 的持久标识。Evidence 当前仍然不拥有独立身份，也不因此获得
 * 一个身份（§3.6：「Evidence 当前不要求拥有独立身份」）。
 *
 * <p>同一个 {@code U-E1} 在另一次调用中可能指向完全不同的依据；把它写进持久化状态
 * 是不成立的。因此它只出现在 Proposal 上——Proposal 本身也不被持久化，
 * 会被下一 Task 的 {@code ProductDirectionDiscoveryService} 解析成真正的
 * {@code Evidence} 集合。
 *
 * @param value 引用本身，不得为 {@code null} 或空白
 */
public record EvidenceReference(String value) {

    public EvidenceReference {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("EvidenceReference 的 value 不能为空");
        }
    }
}
