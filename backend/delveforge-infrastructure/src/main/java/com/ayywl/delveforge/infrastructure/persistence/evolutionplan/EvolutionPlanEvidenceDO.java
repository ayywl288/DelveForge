package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.baomidou.mybatisplus.annotation.*;

@TableName("evolution_plan_evidence")
public class EvolutionPlanEvidenceDO {
    private String planId;
    private Integer position;
    private String sourceType;
    private String sourceRef;
    private String claim;
    private Double confidence;
    private Integer confirmed;
    private String originKind;
    private String originUserProfileId;
    private Integer originUserProfileRevision;
    private String originRepositoryProfileId;

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
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

    public String getOriginKind() {
        return originKind;
    }

    public void setOriginKind(String originKind) {
        this.originKind = originKind;
    }

    public String getOriginUserProfileId() {
        return originUserProfileId;
    }

    public void setOriginUserProfileId(String originUserProfileId) {
        this.originUserProfileId = originUserProfileId;
    }

    public Integer getOriginUserProfileRevision() {
        return originUserProfileRevision;
    }

    public void setOriginUserProfileRevision(Integer originUserProfileRevision) {
        this.originUserProfileRevision = originUserProfileRevision;
    }

    public String getOriginRepositoryProfileId() {
        return originRepositoryProfileId;
    }

    public void setOriginRepositoryProfileId(String originRepositoryProfileId) {
        this.originRepositoryProfileId = originRepositoryProfileId;
    }
}
