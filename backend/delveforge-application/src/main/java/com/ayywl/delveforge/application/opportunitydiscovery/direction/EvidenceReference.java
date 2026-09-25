package com.ayywl.delveforge.application.opportunitydiscovery.direction;

/**
 * 指向本次 AI 调用所提供的某条 Evidence 的引用。
 *
 * <h2>这是 AI 通信协议的一部分，不是领域概念</h2>
 *
 * <p>Product Direction 的推荐理由要能回答「这条判断由哪些已有依据支撑」（INV-D06）。
 * AI 有资格指出「哪些已有依据支撑了这条判断」，但没有资格重新给出依据本身。因此一次
 * Direction Discovery 会先把已有 Evidence 连同各自的临时引用交给模型，模型只能引用
 * 其中的条目——本类型就是那个引用。
 *
 * <p>它的取值只在产生它的那一次 AI 调用中有效：它是「本次提示里第几个依据」的编号，
 * 不是 Evidence 的持久标识，也不是任何领域身份。同一个 {@code U-E1} 在另一次调用中
 * 可能指向完全不同的依据。
 *
 * <p>因此它<b>不得越过 Application → Domain 边界</b>：Domain 不应该、也不需要理解
 * {@code U-E1} 这样的编号。{@link DirectionProposalResolver} 在进入 Domain 之前把它
 * 解析成真实的 {@code EvidenceBasis}。
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
