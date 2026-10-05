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
