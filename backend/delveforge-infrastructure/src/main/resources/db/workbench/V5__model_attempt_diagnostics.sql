CREATE TABLE model_attempt (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id TEXT NOT NULL REFERENCES run(id),
    purpose TEXT NOT NULL,
    attempt INTEGER NOT NULL,
    category TEXT NOT NULL,
    http_status INTEGER,
    duration_ms INTEGER NOT NULL
);
CREATE INDEX model_attempt_run ON model_attempt(run_id,seq);
