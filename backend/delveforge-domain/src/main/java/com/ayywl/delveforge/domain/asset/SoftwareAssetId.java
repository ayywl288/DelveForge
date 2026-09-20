package com.ayywl.delveforge.domain.asset;

/**
 * Software Asset 的唯一标识。
 *
 * <p>Software Asset 是 Entity（DOMAIN_MODEL.md §4.1）：其身份由本类型表达。
 * 资产的位置、读取权限或使用授权发生变化时，它仍然是同一个软件资产。
 *
 * <p>标识的取值与生成方式由 Application / Persistence 决定，领域模型不规定其格式。
 *
 * @param value 非空且非空白的标识
 */
public record SoftwareAssetId(String value) {

    public SoftwareAssetId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SoftwareAssetId 的 value 不能为空");
        }
    }
}
