package com.ayywl.delveforge.infrastructure.persistence.workingcopy;

import com.baomidou.mybatisplus.annotation.*;

@TableName("working_copy")
public class WorkingCopyDO {
    @TableId(type = IdType.INPUT) private String id;
    private String sourceAssetId;
    private String sourceRevision;
    private String location;
    private String currentRevision;
    private String lastVerifiedRevision;
    private String status;
    public String getId() {
        return id;
    }

    public void setId(String value) {
        id = value;
    }

    public String getSourceAssetId() {
        return sourceAssetId;
    }

    public void setSourceAssetId(String value) {
        sourceAssetId = value;
    }

    public String getSourceRevision() {
        return sourceRevision;
    }

    public void setSourceRevision(String value) {
        sourceRevision = value;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String value) {
        location = value;
    }

    public String getCurrentRevision() {
        return currentRevision;
    }

    public void setCurrentRevision(String value) {
        currentRevision = value;
    }

    public String getLastVerifiedRevision() {
        return lastVerifiedRevision;
    }

    public void setLastVerifiedRevision(String value) {
        lastVerifiedRevision = value;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        status = value;
    }
}
