package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.baomidou.mybatisplus.annotation.*;

/** Infrastructure-only mapping of evolution_step_section_item. */
@TableName("evolution_step_section_item")
public class EvolutionStepSectionItemDO {
    private String stepId;
    private String section;
    private Integer position;
    private String value;

    public String getStepId() { return stepId; }
    public void setStepId(String stepId) { this.stepId = stepId; }
    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }
    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
