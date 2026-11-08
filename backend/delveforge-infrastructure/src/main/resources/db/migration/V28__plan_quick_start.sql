-- 说明性产物独立于 Plan / Delivery 终态，不触发代码或信任状态更新。
CREATE TABLE plan_quick_start (
    plan_id TEXT PRIMARY KEY NOT NULL REFERENCES evolution_plan(id),
    candidate_id TEXT NOT NULL REFERENCES candidate_delivery(candidate_id),
    revision TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('GENERATING','AVAILABLE','UNAVAILABLE')),
    markdown TEXT NOT NULL,
    reason TEXT,
    updated_at TEXT NOT NULL
);
