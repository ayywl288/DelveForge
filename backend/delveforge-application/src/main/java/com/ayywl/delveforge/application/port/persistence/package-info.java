/**
 * Persistence Repository 抽象。
 *
 * <p>这些接口由 Application 拥有、由 Infrastructure 的 Persistence Adapter 实现
 * （RULE-ARCH-003、RULE-ARCH-004）。Domain 与 Application 不得出现 SQLite、
 * MyBatis-Plus、Flyway、Mapper 或持久化 Entity 等具体技术类型。
 *
 * <p>这里不提供通用 Repository 抽象（{@code GenericRepository<T>} /
 * {@code BaseRepository<T>}）：每个接口对应一个明确的应用语义边界，
 * 方法集合以当前 Use Case 的实际需求为准。
 */
package com.ayywl.delveforge.application.port.persistence;
