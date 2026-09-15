package com.ayywl.delveforge.infrastructure.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
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
     * SQLite 驱动不会创建缺失的父目录，首次在全新环境启动时会直接连接失败。
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
