package com.ayywl.delveforge.infrastructure.persistence.productdirection;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code product_direction_evidence_support} 表的数据对象：某条 Product Direction 在某个
 * 关键判断下的一条依据，以及它出自哪里。
 *
 * <p>{@code category} 取领域字段名（userNeed / userFit / reusableCapability），
 * {@code position} 保留该组内的顺序。同一份依据可以出现在多个 {@code category} 中，
 * 因此主键是 (direction_id, category, position)。
 *
 * <h2>来源</h2>
 *
 * <p>{@code originKind} 决定用哪一组列，不匹配本次取值的列保持 {@code null}：
 *
 * <pre>
 * userProfile        originUserProfileId + originUserProfileRevision
 * repositoryProfile  originRepositoryProfileId
 * </pre>
 *
 * <p>User Profile 侧带上 revision：只记 id 会让「依据出自哪一版用户画像」重新变得
 * 不可回答（INV-D01、INV-D08）。
 *
 * <p>本类型属于 Infrastructure Persistence 的实现细节，不是领域对象。
 */
@TableName("product_direction_evidence_support")
public class ProductDirectionEvidenceSupportDO {

    /** 依据来自哪一版 User Profile。 */
    public static final String ORIGIN_USER_PROFILE = "userProfile";

    /** 依据来自哪一份 Repository Profile。 */
    public static final String ORIGIN_REPOSITORY_PROFILE = "repositoryProfile";

    private String directionId;

    private String category;

    private Integer position;

    private String sourceType;

    private String sourceRef;

    private String claim;

    /** 允许为 {@code null}，表示未给出确定性判断。 */
    private Double confidence;

    /** 以 0 / 1 保存：SQLite 没有原生 boolean 类型。 */
    private Integer confirmed;

    private String originKind;

    private String originUserProfileId;

    private Integer originUserProfileRevision;

    private String originRepositoryProfileId;

    public String getDirectionId() {
        return directionId;
    }

    public void setDirectionId(String directionId) {
        this.directionId = directionId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
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
