/**
 * Software Asset 的 Persistence Adapter。
 *
 * <pre>
 * SqliteSoftwareAssetRepository   SoftwareAssetRepository 的 SQLite / MyBatis-Plus 实现
 * SoftwareAssetDO                 持久化数据对象
 * SoftwareAssetMapper             MyBatis-Plus Mapper
 * </pre>
 *
 * <p>本包按领域对象划分：一个 Aggregate 的持久化实现集中在同一个包内，
 * 便于 Mapper 扫描范围与职责边界保持清晰。
 *
 * <p>SQLite、MyBatis-Plus 与数据对象只出现在本包内。Domain 与 Application
 * 不得引用本包的任何类型（RULE-ARCH-002、RULE-ARCH-003）。
 *
 * <p>Schema 由 {@code src/main/resources/db/migration/V3__software_asset.sql} 建立；
 * 已发布的迁移不可修改，只能追加新版本。
 */
package com.ayywl.delveforge.infrastructure.persistence.softwareasset;
