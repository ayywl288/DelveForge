package com.ayywl.delveforge.infrastructure.persistence;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Persistence 配置，前缀 {@code delveforge.persistence}。
 *
 * <p>环境相关值必须通过配置提供，不得硬编码进业务代码（AGENTS.md §8.9）。
 * 默认值位于 {@code delveforge-app} 的 {@code application.yml}，不在代码中兜底，
 * 以便配置缺失时快速失败而不是静默使用意料之外的位置。
 *
 * <p>该配置属于 Persistence 技术栈，只在本模块内使用，不泄漏到 Domain / Application。
 *
 * @param databaseFile SQLite 数据库文件位置
 */
@ConfigurationProperties("delveforge.persistence")
public record PersistenceProperties(Path databaseFile) {

    public PersistenceProperties {
        if (databaseFile == null) {
            throw new IllegalArgumentException(
                    "必须配置 delveforge.persistence.database-file");
        }
    }
}
