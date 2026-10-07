package com.ayywl.delveforge.domain.evolution;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;

/**
 * 独立 Aggregate 只保存元数据，不承担 Git 或文件系统操作。
 * Execution 与 Verification 保持当前 revision 和可信 revision 的区别。
 */
public final class WorkingCopy {
    private final WorkingCopyId id;
    private final SoftwareAssetId sourceAssetId;
    private final String sourceRevision;
    private final String location;
    private String currentRevision;
    private String lastVerifiedRevision;
    private WorkingCopyStatus status;

    private WorkingCopy(WorkingCopyId id, SoftwareAssetId asset, String revision, String location) {
        if (id == null || asset == null) {
            throw new IllegalArgumentException("Working Copy identity and source are required");
        }
        this.id = id;
        this.sourceAssetId = asset;
        this.sourceRevision = PlanningContent.text(revision, "sourceRevision");
        this.location = PlanningContent.text(location, "Working Copy location");
        this.status = WorkingCopyStatus.CREATING;
    }

    public static WorkingCopy create(WorkingCopyId id, SoftwareAssetId asset, String sourceRevision, String location) {
        return new WorkingCopy(id, asset, sourceRevision, location);
    }

    /**
     * 只接受实际准备出的 source revision，并且只能初始化一次。
     */
    public void markReady(String actualRevision) {
        if (status != WorkingCopyStatus.CREATING || !sourceRevision.equals(actualRevision)) {
            throw new EvolutionPlanStateException("Working Copy must be created at the requested source revision");
        }
        currentRevision = sourceRevision;
        lastVerifiedRevision = sourceRevision;
        status = WorkingCopyStatus.READY;
    }

    public static WorkingCopy reconstitute(WorkingCopyId id, SoftwareAssetId asset, String sourceRevision,
            String location, String currentRevision, String lastVerifiedRevision, WorkingCopyStatus status) {
        WorkingCopy copy = new WorkingCopy(id, asset, sourceRevision, location);
        if (status == null) {
            throw new IllegalArgumentException("Working Copy status is required");
        }
        if (status == WorkingCopyStatus.READY
                && (!sourceRevision.equals(currentRevision) || !sourceRevision.equals(lastVerifiedRevision))) {
            throw new IllegalArgumentException("Initial READY Working Copy must retain its source baseline");
        }
        if (status == WorkingCopyStatus.CREATING && (currentRevision != null || lastVerifiedRevision != null)) {
            throw new IllegalArgumentException("CREATING Working Copy has no initialized baseline");
        }
        copy.currentRevision = currentRevision;
        copy.lastVerifiedRevision = lastVerifiedRevision;
        copy.status = status;
        return copy;
    }

    public WorkingCopyId id() {
        return id;
    }

    public WorkingCopy copy() {
        return reconstitute(id, sourceAssetId, sourceRevision, location, currentRevision, lastVerifiedRevision, status);
    }

    void startEvolution() {
        if ((status != WorkingCopyStatus.READY && status != WorkingCopyStatus.EVOLVING)
                || currentRevision == null || !currentRevision.equals(lastVerifiedRevision)) {
            throw new EvolutionPlanStateException("Evolution requires a trusted Working Copy");
        }
        status = WorkingCopyStatus.EVOLVING;
    }

    void requireCheckpoint(String baseline, String current) {
        if (status != WorkingCopyStatus.EVOLVING || !baseline.equals(lastVerifiedRevision)
                || !current.equals(currentRevision)) {
            throw new EvolutionPlanStateException("Working Copy does not match the Step checkpoint");
        }
    }

    void recordCandidate(String baseline, String candidate) {
        requireCheckpoint(baseline, baseline);
        if (baseline.equals(candidate)) {
            throw new EvolutionPlanStateException("Candidate must differ from baseline");
        }
        currentRevision = candidate;
    }

    void trustCandidate(String baseline, String candidate) {
        requireCheckpoint(baseline, candidate);
        lastVerifiedRevision = candidate;
    }

    public SoftwareAssetId sourceAssetId() {
        return sourceAssetId;
    }

    public String sourceRevision() {
        return sourceRevision;
    }

    public String location() {
        return location;
    }

    public String currentRevision() {
        return currentRevision;
    }

    public String lastVerifiedRevision() {
        return lastVerifiedRevision;
    }

    public WorkingCopyStatus status() {
        return status;
    }
}
