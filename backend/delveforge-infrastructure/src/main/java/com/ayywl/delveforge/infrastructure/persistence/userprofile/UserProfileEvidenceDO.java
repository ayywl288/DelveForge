package com.ayywl.delveforge.infrastructure.persistence.userprofile;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code user_profile_evidence} 表的数据对象：某个 revision 下的一条 Evidence。
 *
 * <p>本类型属于 Infrastructure Persistence 的实现细节，不是领域对象。
 */
@TableName("user_profile_evidence")
public class UserProfileEvidenceDO {

    private String profileId;

    private Integer revision;

    private Integer position;

    private String sourceType;

    private String sourceRef;

    private String claim;

    private Double confidence;

    /** 0 表示 false，1 表示 true：SQLite 没有原生 boolean 类型。 */
    private Integer confirmed;

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer revision) {
        this.revision = revision;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public String getClaim() {
        return claim;
    }

    public void setClaim(String claim) {
        this.claim = claim;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public Integer getConfirmed() {
        return confirmed;
    }

    public void setConfirmed(Integer confirmed) {
        this.confirmed = confirmed;
    }
}
