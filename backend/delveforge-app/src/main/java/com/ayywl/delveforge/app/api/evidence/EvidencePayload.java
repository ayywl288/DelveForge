package com.ayywl.delveforge.app.api.evidence;

import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;

/**
 * Evidence 在 HTTP 接口上的表示，请求与响应共用同一形状。
 *
 * <p>{@code sourceType} 直接使用 Domain 的取值，不引入接口层同义词（RULE-DOM-001）。
 * 未知取值会在反序列化阶段失败，并由统一错误映射翻译为 {@code INVALID_REQUEST}。
 *
 * <p>本类型是 Interface Adapter 的 DTO，不是领域对象，也不暴露 Persistence 数据对象。
 *
 * @param sourceType Evidence 来源类型
 * @param sourceRef  能够追溯原始依据的引用
 * @param claim      该 Evidence 所支撑的事实或判断
 * @param confidence 对推断型 Evidence 的可信程度；{@code null} 表示未给出确定性判断
 * @param confirmed  是否已经经过用户或其他可靠方式确认
 */
public record EvidencePayload(
        EvidenceSourceType sourceType,
        String sourceRef,
        String claim,
        Double confidence,
        boolean confirmed) {

    /**
     * 把领域 Evidence 映射为它的接口表示。
     *
     * <p>映射放在负载自己的类上：它被 User Profile 与 Repository Profile 两个资源共用，
     * 放在任一资源里都会让另一个资源反过来依赖它的 Controller（或复制一份）。
     */
    public static EvidencePayload from(Evidence evidence) {
        return new EvidencePayload(
                evidence.sourceType(),
                evidence.sourceRef(),
                evidence.claim(),
                evidence.confidence(),
                evidence.confirmed());
    }
}
