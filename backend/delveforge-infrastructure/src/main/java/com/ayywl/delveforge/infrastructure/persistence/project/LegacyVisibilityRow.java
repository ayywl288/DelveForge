package com.ayywl.delveforge.infrastructure.persistence.project;

/** 未认领历史 Plan 的导航标记，不包含领域正文。 */
public record LegacyVisibilityRow(String planId, long version, String deletedAt) {
}
