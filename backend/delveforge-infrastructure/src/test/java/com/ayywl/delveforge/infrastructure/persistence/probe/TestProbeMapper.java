package com.ayywl.delveforge.infrastructure.persistence.probe;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 测试用探针 Mapper。
 *
 * <p>继承 {@code BaseMapper} 以证明 MyBatis-Plus（而非仅原生 MyBatis）的
 * 通用 CRUD 能力在 SQLite 上真实可用。
 */
public interface TestProbeMapper extends BaseMapper<TestProbe> {
}
