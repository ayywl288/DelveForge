package com.ayywl.delveforge.app.api.project;

import com.ayywl.delveforge.application.project.ProjectScopeBasis;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public record ProjectScopeRequest(String contextId,
        @JsonDeserialize(using = ProjectContextVersionDeserializer.class) Long expectedVersion) {
    public ProjectScopeRequest {
        if (expectedVersion == null) {
            throw new IllegalArgumentException("Project version is required");
        }
        new ProjectScopeBasis(contextId, expectedVersion);
    }

    public ProjectScopeBasis basis() {
        return new ProjectScopeBasis(contextId, expectedVersion);
    }
}
