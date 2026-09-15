package com.ayywl.delveforge.infrastructure.persistence.probe;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 测试用探针实体，对应 {@code test_probe} 表。
 *
 * <p>仅存在于测试范围，用于验证 MyBatis-Plus 与 SQLite 的映射链路。
 * 它不是领域对象，也不得被提升为生产实体。
 */
@TableName("test_probe")
public class TestProbe {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    public TestProbe() {
    }

    public TestProbe(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
