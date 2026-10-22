package com.ayywl.delveforge.domain.evolution;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;

/**
 * 独立 Aggregate 只保存元数据，不承担 Git 或文件系统操作。
 * 正式交付接受后同时记录当前 revision 与可信 revision。
 */
public final class WorkingCopy {
    private final WorkingCopyId id;
    private final SoftwareAssetId sourceAssetId;
    private final String sourceRevision;
    private final String location;

    // 正式副本的已接受版本；沙箱 Candidate 不写入这里。
    private String currentRevision;
    // 只有 Domain 接受 Delivery 后才推进，后续 Step 必须从这个版本出发。
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

    /** 仅正式交付接受同时推进当前与可信 revision；不保存中间沙箱状态。 */
    void acceptDelivery(String baseline, String revision) {
        if ((status != WorkingCopyStatus.READY && status != WorkingCopyStatus.EVOLVING)
                || baseline == null || !baseline.matches("[0-9a-f]{40}")
                || !baseline.equals(currentRevision) || !baseline.equals(lastVerifiedRevision)
                || revision == null || !revision.matches("[0-9a-f]{40}") || baseline.equals(revision)) {
            throw new EvolutionPlanStateException("Delivery requires the unchanged trusted WorkingCopy baseline");
        }
        currentRevision = revision;
        lastVerifiedRevision = revision;
        status = WorkingCopyStatus.EVOLVING;
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
