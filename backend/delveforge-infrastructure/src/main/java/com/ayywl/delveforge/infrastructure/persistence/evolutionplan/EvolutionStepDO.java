package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

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
    private String deliveryRevision;

    public String getId() { return id; }
    public void setId(String value) { id = value; }

    public String getPlanId() { return planId; }
    public void setPlanId(String value) { planId = value; }

    public Integer getPosition() { return position; }
    public void setPosition(Integer value) { position = value; }

    public String getGoal() { return goal; }
    public void setGoal(String value) { goal = value; }

    public String getScope() { return scope; }
    public void setScope(String value) { scope = value; }

    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }

    public String getBaselineRevision() { return baselineRevision; }
    public void setBaselineRevision(String value) { baselineRevision = value; }

    public String getDeliveryRevision() { return deliveryRevision; }
    public void setDeliveryRevision(String value) { deliveryRevision = value; }
}
