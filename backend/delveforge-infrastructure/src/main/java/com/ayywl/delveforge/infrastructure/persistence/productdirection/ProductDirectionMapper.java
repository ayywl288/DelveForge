package com.ayywl.delveforge.infrastructure.persistence.productdirection;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * {@code product_direction} 表的 Mapper。
 *
 * <p>只被 {@link SqliteProductDirectionRepository} 使用；Domain / Application 不得引用。
 */
public interface ProductDirectionMapper extends BaseMapper<ProductDirectionDO> {
}
