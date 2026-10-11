-- 只隐藏工作台记录；不更新或删除任何业务 Aggregate 或 Git 证据。
ALTER TABLE project_flow_context ADD COLUMN deleted_at TEXT;
CREATE INDEX ix_context_visibility ON project_flow_context(deleted_at, updated_at, id);

-- 未认领的旧 Plan 投影也有稳定删除标记，刷新时不能重新出现。
CREATE TABLE project_legacy_visibility (
    plan_id TEXT NOT NULL PRIMARY KEY,
    version INTEGER NOT NULL CHECK(version >= 0),
    deleted_at TEXT
);
