CREATE TABLE agent_run (
    id TEXT PRIMARY KEY NOT NULL,
    plan_id TEXT NOT NULL,
    step_id TEXT NOT NULL,
    working_copy_id TEXT NOT NULL,
    baseline TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('CREATED','PREPARING','RUNNING','FINALIZING',
        'SUCCEEDED','FAILED','CANCELLED','INTERRUPTED')),
    started_at TEXT NOT NULL,
    ended_at TEXT,
    reason TEXT,
    candidate_id TEXT,
    cancel_requested INTEGER NOT NULL DEFAULT 0,
    occupied INTEGER NOT NULL DEFAULT 1,
    retained INTEGER NOT NULL DEFAULT 0,
    model_calls INTEGER NOT NULL DEFAULT 0,
    tool_calls INTEGER NOT NULL DEFAULT 0,
    tokens INTEGER,
    CHECK ((state = 'SUCCEEDED') = (candidate_id IS NOT NULL))
);
CREATE UNIQUE INDEX agent_one_occupied_copy ON agent_run(working_copy_id) WHERE occupied = 1;
CREATE UNIQUE INDEX agent_one_candidate_step ON agent_run(step_id, baseline) WHERE state = 'SUCCEEDED';

CREATE TABLE agent_candidate (
    id TEXT PRIMARY KEY NOT NULL,
    run_id TEXT NOT NULL UNIQUE,
    baseline TEXT NOT NULL,
    expected_tree TEXT NOT NULL,
    diff BLOB NOT NULL,
    sha256 TEXT NOT NULL,
    changed_paths TEXT NOT NULL,
    test_evidence TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TRIGGER agent_candidate_immutable_update BEFORE UPDATE ON agent_candidate
BEGIN SELECT RAISE(ABORT, 'Frozen Candidate is immutable'); END;
CREATE TRIGGER agent_candidate_immutable_delete BEFORE DELETE ON agent_candidate
BEGIN SELECT RAISE(ABORT, 'Frozen Candidate is immutable'); END;

CREATE TABLE agent_event (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    summary TEXT NOT NULL,
    at TEXT NOT NULL
);
CREATE INDEX agent_event_run_sequence ON agent_event(run_id, sequence);

-- 与仍存在的旧入口共享数据库并发边界；不能只依赖 Java 锁或前端按钮。
CREATE TRIGGER agent_blocks_legacy_execution BEFORE UPDATE OF status ON evolution_step
WHEN NEW.status = 'EXECUTING' AND EXISTS (
    SELECT 1 FROM agent_run r JOIN evolution_plan p ON p.working_copy_id = r.working_copy_id
    WHERE p.id = NEW.plan_id AND r.occupied = 1)
BEGIN SELECT RAISE(ABORT, 'WorkingCopy occupied by Agent Run'); END;

CREATE TRIGGER agent_guards_working_copy BEFORE UPDATE ON working_copy
WHEN EXISTS (SELECT 1 FROM agent_run WHERE working_copy_id = OLD.id AND occupied = 1)
BEGIN SELECT RAISE(ABORT, 'WorkingCopy occupied by Agent Run'); END;
