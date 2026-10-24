package com.ayywl.delveforge.application.port.workspace;

import com.ayywl.delveforge.domain.evolution.PreparationProvenance;

/**
 * 外部准备的技术凭据，不代表领域 WorkingCopy 或代码修改授权。
 */
public record PreparedWorkspace(WorkspaceRef workspace, String revision, String preparationToken,
        PreparationProvenance provenance) {
    public PreparedWorkspace(WorkspaceRef workspace, String revision, String preparationToken) {
        this(workspace, revision, preparationToken, PreparationProvenance.unchanged(revision));
    }

    public PreparedWorkspace {
        if (workspace == null || revision == null || revision.isBlank()
                || provenance == null || !revision.equals(provenance.preparedRevision())
                || preparationToken == null || preparationToken.isBlank()) {
            throw new IllegalArgumentException("Prepared workspace receipt is incomplete");
        }
    }
}
