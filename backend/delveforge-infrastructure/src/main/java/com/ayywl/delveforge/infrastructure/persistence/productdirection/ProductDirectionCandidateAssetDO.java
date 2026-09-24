package com.ayywl.delveforge.infrastructure.persistence.productdirection;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code product_direction_candidate_asset} 表的数据对象：某条 Product Direction 标识的
 * 一个 Candidate Software Asset。
 *
 * <p>{@code position} 保留列表顺序。跨 Aggregate 引用，只保存身份（INV-D10）。
 *
 * <p>本类型属于 Infrastructure Persistence 的实现细节，不是领域对象。
 */
@TableName("product_direction_candidate_asset")
public class ProductDirectionCandidateAssetDO {

    private String directionId;

    private Integer position;

    private String assetId;

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

    public String getAssetId() {
        return assetId;
    }

    public void setAssetId(String assetId) {
        this.assetId = assetId;
    }
}
