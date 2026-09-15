/**
 * SQLite / MyBatis-Plus Persistence Adapter 与 Flyway Migration。
 *
 * <p>SQLite、MyBatis-Plus、Flyway 属于 Persistence 技术栈，只允许出现在本模块内。
 * Domain / Application 不得引用这些类型，也不得通过间接方式获得数据库访问能力
 * （RULE-ARCH-002、RULE-ARCH-003）。
 *
 * <h2>本包的结构约定</h2>
 *
 * <pre>
 * 数据库位置     配置属性 delveforge.persistence.database-file
 *                （默认值与覆盖方式见 delveforge-app 的 application.yml）
 * DataSource     SqliteDataSourceConfiguration
 * Mapper 位置    com.ayywl.delveforge.infrastructure.persistence 及其子包
 * Mapper 装配    由 delveforge-app 的 Composition Root 负责扫描
 * Migration     src/main/resources/db/migration，命名 V{版本}__{描述}.sql
 * </pre>
 *
 * <p>迁移文件一旦发布便不可修改，只能追加新版本。
 *
 * <h2>当前状态</h2>
 *
 * <p>本包目前只建立 Persistence 基础：数据源装配、迁移管线与 MyBatis-Plus 集成。
 * 具体领域对象的 Repository Port、Persistence Adapter 与业务表在 M1 及之后
 * 随对应领域对象一起引入，当前不预先设计业务 Schema，也不提供
 * {@code BaseRepository} 一类的通用持久化抽象。
 */
package com.ayywl.delveforge.infrastructure.persistence;
