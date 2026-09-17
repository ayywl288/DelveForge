/**
 * User Profile 的 Persistence Adapter。
 *
 * <pre>
 * SqliteUserProfileRepository   UserProfileRepository 的 SQLite / MyBatis-Plus 实现
 * UserProfileDO                 持久化数据对象
 * UserProfile*Mapper            MyBatis-Plus Mapper
 * UserProfileRevisionMapper     已保存版本锚点，复合主键，直接声明 SQL
 * </pre>
 *
 * <p>本包按领域对象划分：一个 Aggregate 的持久化实现集中在同一个包内，
 * 便于 Mapper 扫描范围与职责边界保持清晰。
 *
 * <p>SQLite、MyBatis-Plus 与数据对象只出现在本包内。Domain 与 Application
 * 不得引用本包的任何类型（RULE-ARCH-002、RULE-ARCH-003）。
 *
 * <p>Schema 由 {@code src/main/resources/db/migration/V2__user_profile.sql} 建立；
 * 已发布的迁移不可修改，只能追加新版本。
 */
package com.ayywl.delveforge.infrastructure.persistence.userprofile;
