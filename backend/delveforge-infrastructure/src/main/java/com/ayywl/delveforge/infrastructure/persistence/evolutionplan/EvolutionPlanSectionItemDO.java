package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.baomidou.mybatisplus.annotation.*;

/** Infrastructure-only mapping of evolution_plan_section_item. */
@TableName("evolution_plan_section_item")
public class EvolutionPlanSectionItemDO {
    private String planId;
    private String section;
    private Integer position;
    private String value;

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }
    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }
    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
