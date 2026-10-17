package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.baomidou.mybatisplus.annotation.*;

@TableName("evolution_step")
public class EvolutionStepDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String planId;
    private Integer position;
    private String goal;
    private String scope;
    private String status;
    private String baselineRevision;
    private String executionResult;
    private String verificationResult;
    private String repairAuthorizations;
    private String retryAuthorizations;

    public String getRetryAuthorizations() { return retryAuthorizations; }
    public void setRetryAuthorizations(String value) { retryAuthorizations = value; }
    private String rollbackAuthorization;
    private String rollbackResult;
    private String executionFailureResult;
    private String automaticRollbackResult;

    public String getExecutionFailureResult() { return executionFailureResult; }
    public void setExecutionFailureResult(String value) { executionFailureResult = value; }
    public String getAutomaticRollbackResult() { return automaticRollbackResult; }
    public void setAutomaticRollbackResult(String value) { automaticRollbackResult = value; }

    public String getRollbackAuthorization() { return rollbackAuthorization; }
    public void setRollbackAuthorization(String value) { rollbackAuthorization = value; }
    public String getRollbackResult() { return rollbackResult; }
    public void setRollbackResult(String value) { rollbackResult = value; }

    public String getRepairAuthorizations() {
        return repairAuthorizations;
    }

    public void setRepairAuthorizations(String value) {
        repairAuthorizations = value;
    }

    public String getExecutionResult() { return executionResult; }
    public void setExecutionResult(String value) { executionResult = value; }
    public String getVerificationResult() { return verificationResult; }
    public void setVerificationResult(String value) { verificationResult = value; }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

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

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getBaselineRevision() {
        return baselineRevision;
    }

    public void setBaselineRevision(String baselineRevision) {
        this.baselineRevision = baselineRevision;
    }
}
