package com.ayywl.delveforge.infrastructure.persistence.repositoryprofile;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code repository_profile_evidence} 表的数据对象：一次分析中记录的一条 Evidence。
 *
 * <p>与 {@code user_profile_evidence} 同构：Evidence 是同一类领域值，
 * 存储形式不因归属的 Aggregate 而不同。
 *
 * <p>本类型属于 Infrastructure Persistence 的实现细节，不是领域对象。
 */
@TableName("repository_profile_evidence")
public class RepositoryProfileEvidenceDO {

    private String profileId;

    private Integer position;

    private String sourceType;

    private String sourceRef;

    private String claim;

    /** 允许为 {@code null}，表示未给出确定性判断。 */
    private Double confidence;

    /** 以 0 / 1 保存：SQLite 没有原生 boolean 类型。 */
    private Integer confirmed;

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
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
