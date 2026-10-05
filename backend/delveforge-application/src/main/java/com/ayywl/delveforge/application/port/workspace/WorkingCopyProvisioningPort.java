package com.ayywl.delveforge.application.port.workspace;

/** Isolated environment preparation, separate from repository reads and Step code mutation.
 * Inputs are technical references only: no Plan/Profile/Direction or authorization objects.
 * The adapter owns the configured managed root; directoryName is one direct child name.
 */
public interface WorkingCopyProvisioningPort {
    /** Independently clone, checkout and verify the exact source revision.
     * Reject moved source HEADs and existing/unsafe targets.
     * On failure, best-effort clean up only the directory created by this call.
     */
    PreparedWorkspace provision(WorkspaceRef source, String revision, String directoryName);

    /** Best-effort compensation after a failed Domain/DB commit.
     * Only the owned prepared directory under the managed root may be removed.
     * Failures must be reported, never silently converted to success.
     */
    void discard(PreparedWorkspace candidate);
}
