package com.ayywl.delveforge.infrastructure.persistence.productdirection;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code product_direction_repository_profile} 表的数据对象：生成某条 Product Direction
 * 所依据的一个 Repository Profile。
 *
 * <p>{@code position} 保留列表顺序。跨 Aggregate 引用，只保存身份（INV-D05）。
 *
 * <p>本类型属于 Infrastructure Persistence 的实现细节，不是领域对象。
 */
@TableName("product_direction_repository_profile")
public class ProductDirectionRepositoryProfileDO {

    private String directionId;

    private Integer position;

    private String repositoryProfileId;

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

    public String getRepositoryProfileId() {
        return repositoryProfileId;
    }

    public void setRepositoryProfileId(String repositoryProfileId) {
        this.repositoryProfileId = repositoryProfileId;
    }
}
