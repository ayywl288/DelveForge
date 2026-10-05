package com.ayywl.delveforge.application.port.workspace;

/**
 * 外部准备的技术凭据，不代表领域 WorkingCopy 或代码修改授权。
 */
public record PreparedWorkspace(WorkspaceRef workspace, String revision, String preparationToken) {
    public PreparedWorkspace {
        if (workspace == null || revision == null || revision.isBlank()
                || preparationToken == null || preparationToken.isBlank()) {
            throw new IllegalArgumentException("Prepared workspace receipt is incomplete");
        }
    }
}
