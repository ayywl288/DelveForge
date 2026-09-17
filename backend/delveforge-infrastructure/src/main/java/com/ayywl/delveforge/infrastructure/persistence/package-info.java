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
 * <p>本包保存 Persistence 基础：数据源装配、迁移管线与 MyBatis-Plus 集成。
 *
 * <p>具体领域对象的 Persistence Adapter 按领域对象各自成包——例如
 * {@code userprofile}——使 Mapper 扫描范围与职责边界保持清晰。
 * 这里不提供 {@code BaseRepository} / {@code GenericRepository} 一类通用持久化抽象：
 * 每个 Adapter 只实现对应 Port 声明的操作。
 */
package com.ayywl.delveforge.infrastructure.persistence;
