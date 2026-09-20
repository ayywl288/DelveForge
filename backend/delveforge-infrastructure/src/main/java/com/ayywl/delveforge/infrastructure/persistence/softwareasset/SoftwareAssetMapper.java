package com.ayywl.delveforge.infrastructure.persistence.softwareasset;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * {@code software_asset} 表的 Mapper。
 *
 * <p>单表、单主键，MyBatis-Plus 的 {@code BaseMapper} 已覆盖本 Adapter 需要的
 * 全部操作，因此不额外声明 SQL。
 *
 * <p>只被 {@link SqliteSoftwareAssetRepository} 使用；Domain / Application 不得引用。
 */
public interface SoftwareAssetMapper extends BaseMapper<SoftwareAssetDO> {
}
