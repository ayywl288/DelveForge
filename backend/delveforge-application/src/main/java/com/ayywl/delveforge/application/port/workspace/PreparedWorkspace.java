package com.ayywl.delveforge.application.port.workspace;

/** Technical provisioning receipt. It is neither a Domain Working Copy nor mutation authority. */
public record PreparedWorkspace(WorkspaceRef workspace, String revision, String preparationToken) {
    public PreparedWorkspace {
        if (workspace == null || revision == null || revision.isBlank()
                || preparationToken == null || preparationToken.isBlank())
            throw new IllegalArgumentException("Prepared workspace receipt is incomplete");
    }
}
