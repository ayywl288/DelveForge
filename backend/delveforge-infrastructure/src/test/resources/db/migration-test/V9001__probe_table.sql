-- 仅用于 Infrastructure 集成测试的探针表。
--
-- 该迁移位于 src/test/resources，只会在测试 classpath 下被加载，
-- 不会进入生产 Schema。目的是在不引入虚假生产业务表的前提下，
-- 验证 MyBatis-Plus 能够对真实 SQLite 执行 SQL。
--
-- 版本号刻意取一个远高于生产迁移序列的值，避免将来与生产迁移冲突。
CREATE TABLE test_probe (
    id   INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL
);
