package com.ayywl.delveforge.infrastructure.persistence.productdirection;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code product_direction_risk} 表的数据对象：某条 Product Direction 记录的一项已知风险。
 *
 * <p>{@code position} 保留列表顺序。风险可以为空列表，此时该方向没有任何行。
 *
 * <p>本类型属于 Infrastructure Persistence 的实现细节，不是领域对象。
 */
@TableName("product_direction_risk")
public class ProductDirectionRiskDO {

    private String directionId;

    private Integer position;

    private String value;

    public String getDirectionId() {
        return directionId;
    }

    public void setDirectionId(String directionId) {
        this.directionId = directionId;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
