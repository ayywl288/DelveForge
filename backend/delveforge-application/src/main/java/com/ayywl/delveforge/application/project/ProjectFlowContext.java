package com.ayywl.delveforge.application.project;

import java.time.Instant;
import java.util.List;

/** Application 的导航身份记录；不承载领域状态、执行权限或可信 revision。 */
public record ProjectFlowContext(String id, String name, String userProfileId, List<String> assetIds,
        List<String> repositoryProfileIds, List<String> directionIds, String planId,
        Instant createdAt, Instant updatedAt, long version, String flowId, String previousContextId, Instant deletedAt) {

    public ProjectFlowContext(String id, String name, String userProfileId, List<String> assetIds,
            List<String> repositoryProfileIds, List<String> directionIds, String planId,
            Instant createdAt, Instant updatedAt, long version, String flowId, String previousContextId) {
        this(id, name, userProfileId, assetIds, repositoryProfileIds, directionIds, planId,
                createdAt, updatedAt, version, flowId, previousContextId, null);
    }

    public ProjectFlowContext(String id, String name, String userProfileId, List<String> assetIds,
            List<String> repositoryProfileIds, List<String> directionIds, String planId,
            Instant createdAt, Instant updatedAt, long version) {
        this(id, name, userProfileId, assetIds, repositoryProfileIds, directionIds, planId,
                createdAt, updatedAt, version, id, null, null);
    }

    public ProjectFlowContext {
        requireId(id);
        requireId(flowId);
        requireOptionalId(previousContextId);
        if (name == null || name.isBlank() || name.length() > 120 || version < 0) {
            throw new IllegalArgumentException("Project name and version are required");
        }
        requireOptionalId(userProfileId);
        requireOptionalId(planId);
        assetIds = ids(assetIds);
        repositoryProfileIds = ids(repositoryProfileIds);
        directionIds = ids(directionIds);
        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Project timestamps are required");
        }
    }

    private static List<String> ids(List<String> values) {
        if (values == null || values.size() > 100 || values.stream().distinct().count() != values.size()) {
            throw new IllegalArgumentException("Explicit unique resource identities are required");
        }
        values.forEach(ProjectFlowContext::requireId);
        return List.copyOf(values);
    }

    private static void requireOptionalId(String id) {
        if (id != null) {
            requireId(id);
        }
    }

    public static void requireId(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,160}")) {
            throw new IllegalArgumentException("Invalid resource identity");
        }
    }
}
