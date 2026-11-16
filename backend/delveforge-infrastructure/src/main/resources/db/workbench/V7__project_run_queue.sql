DROP INDEX run_foreground;

ALTER TABLE run ADD COLUMN queue_order INTEGER;
ALTER TABLE run ADD COLUMN environment_kind TEXT NOT NULL DEFAULT 'PROJECT';
UPDATE run SET environment_kind='LEGACY';

CREATE TABLE workbench_capacity (
    id INTEGER PRIMARY KEY CHECK(id=1),
    slots INTEGER NOT NULL CHECK(slots BETWEEN 1 AND 64)
);
INSERT INTO workbench_capacity(id,slots) VALUES(1,2);

CREATE UNIQUE INDEX run_waiting_conversation ON run(conversation_id) WHERE state='QUEUED';
CREATE INDEX run_fifo ON run(queue_order) WHERE state='QUEUED';

CREATE TABLE project_maintenance (
    project_id TEXT PRIMARY KEY REFERENCES project(id),
    operation TEXT NOT NULL,
    started_at TEXT NOT NULL
);

CREATE TRIGGER run_capacity_insert BEFORE INSERT ON run
WHEN NEW.state IN ('RUNNING','STOPPING','RECONCILING','BLOCKED')
AND (SELECT count(*) FROM run WHERE state IN ('RUNNING','STOPPING','RECONCILING','BLOCKED'))
    >= (SELECT slots FROM workbench_capacity WHERE id=1)
BEGIN SELECT RAISE(ABORT,'workbench capacity exceeded'); END;

CREATE TRIGGER run_capacity_update BEFORE UPDATE OF state ON run
WHEN NEW.state IN ('RUNNING','STOPPING','RECONCILING','BLOCKED')
AND OLD.state NOT IN ('RUNNING','STOPPING','RECONCILING','BLOCKED')
AND (SELECT count(*) FROM run WHERE state IN ('RUNNING','STOPPING','RECONCILING','BLOCKED'))
    >= (SELECT slots FROM workbench_capacity WHERE id=1)
BEGIN SELECT RAISE(ABORT,'workbench capacity exceeded'); END;
