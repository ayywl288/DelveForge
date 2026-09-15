package com.ayywl.delveforge.app;

import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 保证 Spring Boot 上下文可以完整装配并启动，包括 Persistence 技术栈的自动装配。
 *
 * <p>使用测试专用的临时数据库路径，不接触开发者本地数据库。
 */
@SpringBootTest
class DelveForgeApplicationTests {

    /**
     * 使用独立的临时目录，避免在仓库工作目录下创建真实数据库。
     * 位于 target/ 下，属于构建产物，不会被提交。
     */
    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "delveforge.db");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE_FILE::toString);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Test
    void contextLoads() {
        assertNotNull(dataSource, "SQLite DataSource 应完成装配");
        assertNotNull(sqlSessionFactory, "MyBatis-Plus SqlSessionFactory 应完成装配");
    }
}
