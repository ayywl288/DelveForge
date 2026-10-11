package com.ayywl.delveforge.infrastructure.persistence.project;

/** 仅在 Persistence 内使用；列表 JSON 不包含业务正文、画像或秘密。 */
public record ProjectContextRow(String id, String name, String userProfileId, String assetIds,
        String repositoryProfileIds, String directionIds, String planId, String createdAt, String updatedAt,
        long version, String flowId, String previousContextId, String deletedAt) {
}
