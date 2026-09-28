package com.ayywl.delveforge.app.api.evidence;

/**
 * Evidence 出处类别的接口表示。
 *
 * <p>它与领域侧 {@code EvidenceOrigin} 已允许的两个实现一一对应，取值名称沿用
 * {@code UserProfileEvidenceOrigin} / {@code RepositoryProfileEvidenceOrigin} 的语义，
 * 不引入新的领域名词（RULE-DOM-001）。
 *
 * <p>它是 Interface Adapter 的 DTO 取值，不是领域概念，也不承担任何领域判断：
 * 领域侧的类型区分仍然由 {@code EvidenceOrigin} 这个 sealed interface 保证，
 * 这里只是让响应里的出处对调用方显式可辨。
 */
public enum EvidenceOriginKind {

    /** 该依据来自某一版 User Profile。 */
    USER_PROFILE,

    /** 该依据来自某一份 Repository Profile。 */
    REPOSITORY_PROFILE
}
