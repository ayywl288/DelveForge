package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.baomidou.mybatisplus.annotation.*;

/** Infrastructure-only mapping of evolution_plan. */
@TableName("evolution_plan")
public class EvolutionPlanDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String productDirectionId;
    private String baseAssetId;
    private String baseRepositoryProfileId;
    private String workingCopyId;
    private String currentSummary;
    private String targetProblem;
    private String targetProduct;
    private String targetDifferentiation;
    private String status;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProductDirectionId() { return productDirectionId; }
    public void setProductDirectionId(String productDirectionId) { this.productDirectionId = productDirectionId; }
    public String getBaseAssetId() { return baseAssetId; }
    public void setBaseAssetId(String baseAssetId) { this.baseAssetId = baseAssetId; }
    public String getBaseRepositoryProfileId() { return baseRepositoryProfileId; }
    public void setBaseRepositoryProfileId(String baseRepositoryProfileId) { this.baseRepositoryProfileId = baseRepositoryProfileId; }
    public String getWorkingCopyId() { return workingCopyId; }
    public void setWorkingCopyId(String workingCopyId) { this.workingCopyId = workingCopyId; }
    public String getCurrentSummary() { return currentSummary; }
    public void setCurrentSummary(String currentSummary) { this.currentSummary = currentSummary; }
    public String getTargetProblem() { return targetProblem; }
    public void setTargetProblem(String targetProblem) { this.targetProblem = targetProblem; }
    public String getTargetProduct() { return targetProduct; }
    public void setTargetProduct(String targetProduct) { this.targetProduct = targetProduct; }
    public String getTargetDifferentiation() { return targetDifferentiation; }
    public void setTargetDifferentiation(String targetDifferentiation) { this.targetDifferentiation = targetDifferentiation; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
