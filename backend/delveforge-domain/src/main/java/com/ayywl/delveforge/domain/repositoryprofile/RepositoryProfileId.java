package com.ayywl.delveforge.domain.repositoryprofile;

/**
 * Repository Profile 的唯一标识。
 *
 * <p>Repository Profile 是 Entity（DOMAIN_MODEL.md §4.1）：一次分析结果需要被
 * Product Direction 与 Evolution Plan 明确引用和追溯，因此它靠自身身份被识别，
 * 而不是靠内容比较——内容相同但不是同一次分析，仍然是两个 Profile。
 *
 * <p>标识的取值与生成方式由 Application / Persistence 决定，领域模型不规定其格式。
 *
 * @param value 非空且非空白的标识
 */
public record RepositoryProfileId(String value) {

    public RepositoryProfileId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("RepositoryProfileId 的 value 不能为空");
        }
    }
}
