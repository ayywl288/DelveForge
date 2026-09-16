package com.ayywl.delveforge.domain.user;

/**
 * User Profile 的唯一标识。
 *
 * <p>User Profile 是 Entity（DOMAIN_MODEL.md §4.1）：其身份由本类型表达。
 * Profile 的内容、revision 与 status 如何变化，都不改变它仍然是同一份用户画像。
 *
 * <p>标识的取值与生成方式由 Application / Persistence 决定，领域模型不规定其格式。
 *
 * @param value 非空且非空白的标识
 */
public record UserProfileId(String value) {

    public UserProfileId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("UserProfileId 的 value 不能为空");
        }
    }
}
