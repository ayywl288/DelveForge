package com.ayywl.delveforge.infrastructure.persistence;

import com.ayywl.delveforge.infrastructure.persistence.probe.TestProbe;
import com.ayywl.delveforge.infrastructure.persistence.probe.TestProbeMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Persistence 基础与真实 SQLite 技术栈的集成：
 * DataSource 装配、Flyway migration、MyBatis-Plus SQL 执行。
 *
 * <p>使用测试专用的临时数据库，不接触开发者本地数据库。
 *
 * <p>注：本类中的 {@code @Transactional} 使每个测试方法结束后回滚，
 * 从而保证方法之间状态隔离；临时数据库目录按类独立，保证类之间隔离。
 */
@SpringBootTest(
        classes = SqlitePersistenceIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional
class SqlitePersistenceIntegrationTest {

    /**
     * 每次测试运行使用独立目录，避免跨运行残留影响结果。
     * 位于 target/ 下，属于构建产物，不会被提交。
     */
    private static final Path DATABASE_DIRECTORY =
            Path.of("target", "test-databases", UUID.randomUUID().toString());

    private static final Path DATABASE_FILE = DATABASE_DIRECTORY.resolve("delveforge.db");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE_FILE::toString);
        // 生产迁移 + 仅测试可见的探针表迁移
        registry.add("spring.flyway.locations",
                () -> "classpath:db/migration,classpath:db/migration-test");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(SqliteDataSourceConfiguration.class)
    @MapperScan("com.ayywl.delveforge.infrastructure.persistence")
    static class TestApplication {
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TestProbeMapper probeMapper;

    @Test
    @Order(1)
    void createsMissingDatabaseDirectoryAndConnectsToSqlite() throws Exception {
        assertTrue(Files.exists(DATABASE_FILE),
                "DataSource 应创建缺失的父目录并建立 SQLite 数据库文件");

        try (Connection connection = dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getDatabaseProductName().toLowerCase().contains("sqlite"),
                    "应连接到真实 SQLite 数据库");
        }
    }

    @Test
    @Order(2)
    void flywayAppliedMigrationsToSqlite() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {

            assertTrue(rows.next(), "Flyway 应记录至少一次迁移");
            assertEquals("1", rows.getString("version"));
            assertTrue(rows.getBoolean("success"));
        }
    }

    @Test
    @Order(3)
    void myBatisPlusInsertsAndSelectsOnSqlite() {
        TestProbe probe = new TestProbe("insert-select");
        int inserted = probeMapper.insert(probe);

        assertEquals(1, inserted);
        assertNotNull(probe.getId(), "AUTO 主键应由 SQLite 回填");

        TestProbe loaded = probeMapper.selectById(probe.getId());
        assertNotNull(loaded);
        assertEquals("insert-select", loaded.getName());
    }

    @Test
    @Order(4)
    void myBatisPlusUpdatesAndDeletesOnSqlite() {
        TestProbe probe = new TestProbe("before-update");
        probeMapper.insert(probe);

        probe.setName("after-update");
        assertEquals(1, probeMapper.updateById(probe));
        assertEquals("after-update", probeMapper.selectById(probe.getId()).getName());

        assertEquals(1, probeMapper.deleteById(probe.getId()));
        assertEquals(0, probeMapper.selectCount(null));
    }

    /**
     * 与 {@link #myBatisPlusInsertsAndSelectsOnSqlite()} 配对：
     * 若事务未回滚，前面方法写入的数据会残留，本断言将失败。
     */
    @Test
    @Order(5)
    void databaseStateIsIsolatedBetweenTestMethods() {
        assertEquals(0, probeMapper.selectCount(null),
                "上一个测试方法写入的数据不应残留");
    }
}
