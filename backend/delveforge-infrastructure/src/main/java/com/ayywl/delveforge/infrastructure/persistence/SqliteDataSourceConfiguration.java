package com.ayywl.delveforge.infrastructure.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SQLite DataSource 装配。
 *
 * <p>SQLite 属于 Persistence 技术栈，只允许出现在 Infrastructure 边界内
 * （RULE-ARCH-002、RULE-ARCH-003）。Domain / Application 不得引用本类或任何
 * SQLite、MyBatis-Plus、Flyway 类型。
 *
 * <p>数据库位置来自 {@link PersistenceProperties}，不硬编码在代码中（AGENTS.md §8.9）。
 * 配置默认值与覆盖方式见 {@code delveforge-app/src/main/resources/application.yml}。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PersistenceProperties.class)
public class SqliteDataSourceConfiguration {

    @Bean
    public DataSource dataSource(PersistenceProperties properties) throws IOException {

        Path databasePath = properties.databaseFile().toAbsolutePath().normalize();
        createParentDirectory(databasePath);

        // 连接池参数暂不调优：SQLite 的并发与 journal mode 取舍需要真实负载证据，
        // 当前先使用默认值，待出现实际瓶颈再决定。
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + databasePath);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setPoolName("delveforge-sqlite");
        return new HikariDataSource(config);
    }

    /**
     * SQLite 的事务管理器。
     *
     * <p>这里显式覆盖自动配置，只为了一件事：<b>提交失败时必须回滚并清理连接</b>。
     *
     * <p>默认值是 {@code rollbackOnCommitFailure = false}：提交抛错时 Spring 只把异常抛出去，
     * 不再碰这个事务。而 SQLite 的提交失败（例如拿不到写锁）**不会**让事务自己结束——
     * 事务在连接上仍然是打开的，锁也仍然被持有。连接就这样带着一个未结束的事务回到池子里，
     * 之后任何借到它的操作都会继续失败，直到连接被真正回收为止。
     *
     * <p>打开这个开关之后，提交失败会走 Spring 的回滚路径：状态被明确结束，连接被清理干净，
     * 池子里的下一位使用者拿到的是可用的连接。这不是 SQLite 特有的问题，只是 SQLite
     * 更容易让提交本身失败，因此这里对全应用生效，而不只是 Product Direction。
     *
     * <p>为什么放在 Infrastructure 而不是合并根：这是数据访问技术栈的事务行为，与
     * DataSource 属于同一层（RULE-ARCH-002、RULE-ARCH-004）。合并根只负责把两者接起来。
     */
    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
        transactionManager.setRollbackOnCommitFailure(true);
        return transactionManager;
    }

    /**
     * SQLite 驱动不会创建缺失的父目录，首次在全新环境下启动时会直接连接失败。
     *
     * <p>这里只创建 DelveForge 自身数据库文件所在的目录，
     * 与 Software Asset / Working Copy 的文件系统访问无关（RULE-ARCH-009 不适用于该场景）。
     */
    private static void createParentDirectory(Path databasePath) throws IOException {
        Path directory = databasePath.getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
    }
}
