CREATE TABLE candidate_delivery (
    candidate_id TEXT PRIMARY KEY NOT NULL,
    run_id TEXT NOT NULL UNIQUE,
    plan_id TEXT NOT NULL,
    step_id TEXT NOT NULL,
    working_copy_id TEXT NOT NULL,
    baseline TEXT NOT NULL,
    expected_tree TEXT NOT NULL,
    location TEXT NOT NULL,
    original_location TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('INTENT','APPLIED','COMMIT_READY','PUBLISHED','COMPLETED',
        'DELIVERY_BLOCKED','RECOVERY_REQUIRED')),
    revision TEXT,
    reason TEXT,
    created_at TEXT NOT NULL,
    occupied INTEGER NOT NULL DEFAULT 1 CHECK(occupied IN (0,1)),
    CHECK(state <> 'COMPLETED' OR (revision IS NOT NULL AND occupied=0))
);
CREATE UNIQUE INDEX delivery_one_occupied_copy ON candidate_delivery(working_copy_id) WHERE occupied=1;
ALTER TABLE evolution_step ADD COLUMN delivery_revision TEXT;

CREATE TRIGGER delivery_blocks_agent BEFORE INSERT ON agent_run
WHEN EXISTS (SELECT 1 FROM candidate_delivery WHERE working_copy_id=NEW.working_copy_id AND occupied=1)
BEGIN SELECT RAISE(ABORT, 'WorkingCopy occupied by Delivery'); END;

CREATE TRIGGER delivery_excludes_agent BEFORE INSERT ON candidate_delivery
WHEN EXISTS (SELECT 1 FROM agent_run WHERE working_copy_id=NEW.working_copy_id AND occupied=1)
BEGIN SELECT RAISE(ABORT, 'WorkingCopy occupied by Agent'); END;

CREATE TRIGGER delivery_guards_working_copy BEFORE UPDATE ON working_copy
WHEN EXISTS (SELECT 1 FROM candidate_delivery WHERE working_copy_id=OLD.id AND occupied=1)
BEGIN SELECT RAISE(ABORT, 'WorkingCopy occupied by Delivery'); END;

CREATE TRIGGER delivery_blocks_legacy BEFORE UPDATE OF status ON evolution_step
WHEN NEW.status='EXECUTING' AND EXISTS (SELECT 1 FROM candidate_delivery d
    JOIN evolution_plan p ON p.working_copy_id=d.working_copy_id WHERE p.id=NEW.plan_id AND d.occupied=1)
BEGIN SELECT RAISE(ABORT, 'WorkingCopy occupied by Delivery'); END;
