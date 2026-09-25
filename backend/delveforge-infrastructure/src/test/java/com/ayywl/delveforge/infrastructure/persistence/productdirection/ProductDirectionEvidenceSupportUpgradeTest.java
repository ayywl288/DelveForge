package com.ayywl.delveforge.infrastructure.persistence.productdirection;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

/**
 * 验证 V6 在真实 SQLite 上的升级行为：有旧 Product Direction 数据时必须失败。
 *
 * <p>只丢弃依据、保留方向行会留下一个比迁移失败更糟的状态：ProductDirection 要求至少
 * 有一条依据（INV-D06），缺了依据的方向读出来就会抛异常——迁移报告成功，数据却不可恢复。
 * 因此 V6 在动手之前先检查方向表是否为空，有数据就整体失败，把「怎么处理开发数据」
 * 留给调用方显式决定。
 *
 * <p>本类不用 Spring，直接驱动真实的 Flyway 与 SQLite：要验证的正是迁移本身的行为。
 */
class ProductDirectionEvidenceSupportUpgradeTest {

    /**
     * 每次调用都得到一份全新的数据库文件。
     *
     * <p>不能把它做成类级常量：两个用法共享同一个文件时，先跑的那个会把库迁到 V6，
     * 后跑的再迁就已经无版本可迁，「有数据时拒绝升级」那条会静默变成空操作。
     */
    private static SQLiteDataSource freshDataSource() throws Exception {
        Path databaseFile =
                Path.of("target", "test-databases", UUID.randomUUID().toString(), "upgrade.db");
        Files.createDirectories(databaseFile.getParent());

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + databaseFile);
        return dataSource;
    }

    @Test
    void refusesToUpgradeWhenAProductDirectionAlreadyExists() throws Exception {
        SQLiteDataSource dataSource = freshDataSource();
        migrateTo(dataSource, "5");

        insertDirection(dataSource);

        FlywayException failure = assertThrows(FlywayException.class,
                () -> migrateTo(dataSource, null),
                "方向表非空时 V6 必须失败，而不是留下没有依据的方向");

        assertTrue(mentionsGuard(failure),
                () -> "失败信息必须说明发生了什么，实际为: " + dump(failure));
        assertTrue(tableExists(dataSource, "product_direction_evidence"),
                "失败的迁移必须整体回滚：旧依据表仍在");
        assertTrue(!tableExists(dataSource, "product_direction_evidence_support"),
                "失败的迁移不得留下半建的新表");
    }

    /** 方向表为空时（当前唯一的真实情形）升级正常完成。 */
    @Test
    void upgradesWhenNoProductDirectionExists() throws Exception {
        SQLiteDataSource dataSource = freshDataSource();
        migrateTo(dataSource, "5");

        migrateTo(dataSource, null);

        assertTrue(tableExists(dataSource, "product_direction_evidence_support"),
                "新依据表应已建立");
        assertTrue(!tableExists(dataSource, "product_direction_evidence"),
                "旧依据表应已删除");
    }


    /**
     * 失败的提示里能否看出「是那次前置检查拦下的」。
     *
     * <p>数据库抛出的约束名在原因链里，而 Flyway 顶层的 message 只有一句
     * 「Script V6... failed」，因此逐层找。
     */
    private static String dump(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            text.append("\n  [").append(current.getClass().getName()).append("] ")
                    .append(current.getMessage());
        }
        return text.toString();
    }

    /**
     * 失败的提示必须让人看懂发生了什么。
     *
     * <p>数据库抛出的消息在原因链里，而 Flyway 顶层的 message 只有一句
     * 「Script V6... failed」，因此逐层找。这里要求它提到 {@code product_direction}——
     * 一个只回显约束表达式（{@code CHECK constraint failed: direction_count = 0}）
     * 的报错对这个目的没有帮助。
     */
    private static boolean mentionsGuard(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null
                    && current.getMessage().contains("product_direction")) {
                return true;
            }
        }
        return false;
    }


    /** {@code targetVersion} 为 {@code null} 表示迁移到最新。 */
    private static void migrateTo(SQLiteDataSource dataSource, String targetVersion) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(targetVersion == null ? null : MigrationVersion.fromVersion(targetVersion))
                .load()
                .migrate();
    }

    /** 写入一行 V5 形态的方向主表记录——它没有依据，正是升级后无法恢复的那种数据。 */
    private static void insertDirection(SQLiteDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO product_direction
                        (id, user_profile_id, user_profile_revision, title, problem, target_product,
                         user_fit, differentiation, technical_value, estimated_complexity, status)
                    VALUES ('direction-1', 'user-profile-1', 3, '标题', '问题', '目标产品',
                            '匹配点', '差异化', '技术价值', '复杂度', 'CANDIDATE')
                    """);
        }
    }

    private static boolean tableExists(SQLiteDataSource dataSource, String tableName)
            throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet tables = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '"
                             + tableName + "'")) {
            return tables.next();
        }
    }
}
