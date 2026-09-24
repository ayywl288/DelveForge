package com.ayywl.delveforge.domain.direction;

/**
 * Product Direction 的唯一标识。
 *
 * <p>Product Direction 是 Entity（DOMAIN_MODEL.md §4.1）：其身份由本类型表达。
 * 候选方向会经历 Candidate、Selected、Rejected、Superseded 等状态变化，
 * 但仍然是同一个方向。
 *
 * <p>标识的取值与生成方式由 Application / Persistence 决定，领域模型不规定其格式。
 *
 * @param value 非空且非空白的标识
 */
public record ProductDirectionId(String value) {

    public ProductDirectionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ProductDirectionId 的 value 不能为空");
        }
    }
}
