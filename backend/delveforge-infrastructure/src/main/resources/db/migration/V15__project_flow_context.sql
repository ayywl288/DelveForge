-- Application 导航记录，不改写任何已有 Aggregate、历史引用或执行状态。
CREATE TABLE project_flow_context (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    user_profile_id TEXT,
    asset_ids TEXT NOT NULL,
    repository_profile_ids TEXT NOT NULL,
    direction_ids TEXT NOT NULL,
    plan_id TEXT UNIQUE,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL CHECK(version >= 0)
);
CREATE INDEX project_context_activity ON project_flow_context(updated_at, id);
