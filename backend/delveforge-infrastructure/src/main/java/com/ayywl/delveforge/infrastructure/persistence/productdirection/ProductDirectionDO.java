package com.ayywl.delveforge.infrastructure.persistence.productdirection;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code product_direction} 表的数据对象。
 *
 * <p>保存这条方向的身份、它在用户侧与资产侧的追溯点、推荐内容与当前生命周期状态。
 * 多值字段（repositoryProfileIds / candidateAssetIds / risks / evidence）保存在子表中，
 * 见同包下的其它数据对象。
 *
 * <p>与 {@code repository_profile} 不同：Product Direction 是拥有生命周期的 Entity
 * （DOMAIN_MODEL.md §6.2），因此本表有 {@code status} 列，且同一行允许被更新。
 * 与 {@code user_profile} 也不同：这里没有 revision 列，方向不需要版本划分。
 *
 * <p>本类型属于 Infrastructure Persistence 的实现细节，不是领域对象，
 * 不得出现在 Domain / Application 中。
 */
@TableName("product_direction")
public class ProductDirectionDO {

    /** 身份由 Domain 的 {@code ProductDirectionId} 提供，不由数据库生成。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 生成该方向所依据的 User Profile，跨 Aggregate 引用，只保留身份。 */
    private String userProfileId;

    /** 所依据的 User Profile 版本，不随后续 User Profile 更新而变化（INV-D02）。 */
    private Integer userProfileRevision;

    private String title;

    private String problem;

    private String targetProduct;

    private String userFit;

    private String differentiation;

    private String technicalValue;

    private String estimatedComplexity;

    /** 保存领域状态的名字：CANDIDATE / SELECTED / REJECTED / SUPERSEDED。 */
    private String status;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserProfileId() {
        return userProfileId;
    }

    public void setUserProfileId(String userProfileId) {
        this.userProfileId = userProfileId;
    }

    public Integer getUserProfileRevision() {
        return userProfileRevision;
    }

    public void setUserProfileRevision(Integer userProfileRevision) {
        this.userProfileRevision = userProfileRevision;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getProblem() {
        return problem;
    }

    public void setProblem(String problem) {
        this.problem = problem;
    }

    public String getTargetProduct() {
        return targetProduct;
    }

    public void setTargetProduct(String targetProduct) {
        this.targetProduct = targetProduct;
    }

    public String getUserFit() {
        return userFit;
    }

    public void setUserFit(String userFit) {
        this.userFit = userFit;
    }

    public String getDifferentiation() {
        return differentiation;
    }

    public void setDifferentiation(String differentiation) {
        this.differentiation = differentiation;
    }

    public String getTechnicalValue() {
        return technicalValue;
    }

    public void setTechnicalValue(String technicalValue) {
        this.technicalValue = technicalValue;
    }

    public String getEstimatedComplexity() {
        return estimatedComplexity;
    }

    public void setEstimatedComplexity(String estimatedComplexity) {
        this.estimatedComplexity = estimatedComplexity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
