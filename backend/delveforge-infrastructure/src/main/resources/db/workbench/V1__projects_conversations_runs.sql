CREATE TABLE project (
    id TEXT PRIMARY KEY, name TEXT NOT NULL, source TEXT, workcopy TEXT, state TEXT NOT NULL,
    detail TEXT, created_at TEXT NOT NULL
);
CREATE TABLE conversation (
    id TEXT PRIMARY KEY, project_id TEXT NOT NULL REFERENCES project(id), title TEXT NOT NULL, created_at TEXT NOT NULL,
    summary TEXT NOT NULL DEFAULT '', through_seq INTEGER NOT NULL DEFAULT 0, context_version INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE run (
    id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL REFERENCES conversation(id),
    project_id TEXT NOT NULL REFERENCES project(id), state TEXT NOT NULL,
    started_at TEXT NOT NULL, ended_at TEXT, detail TEXT, observed_input INTEGER NOT NULL DEFAULT 0,
    requests INTEGER NOT NULL DEFAULT 0, tokens INTEGER NOT NULL DEFAULT 0, allowance INTEGER NOT NULL,
    stop INTEGER NOT NULL DEFAULT 0, reconcile_attempts INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX run_foreground ON run((1)) WHERE state IN ('RUNNING','STOPPING','RECONCILING','BLOCKED');
CREATE UNIQUE INDEX run_project_foreground ON run(project_id) WHERE state IN ('RUNNING','STOPPING','RECONCILING','BLOCKED');
CREATE TABLE input (
    seq INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id TEXT NOT NULL REFERENCES conversation(id),
    request_id TEXT NOT NULL, text TEXT NOT NULL, accepted_at TEXT NOT NULL, consumed INTEGER NOT NULL DEFAULT 0,
    UNIQUE(conversation_id, request_id)
);
CREATE TABLE record (
    seq INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id TEXT NOT NULL REFERENCES conversation(id),
    run_id TEXT NOT NULL REFERENCES run(id), role TEXT NOT NULL, text TEXT, calls TEXT NOT NULL DEFAULT '[]',
    provider_id TEXT, tool_name TEXT, reasoning TEXT, recorded_at TEXT NOT NULL
);
CREATE INDEX record_conversation ON record(conversation_id,seq);
CREATE TABLE invocation (
    id TEXT PRIMARY KEY, run_id TEXT NOT NULL REFERENCES run(id), assistant_seq INTEGER NOT NULL REFERENCES record(seq),
    position INTEGER NOT NULL, provider_id TEXT NOT NULL, name TEXT NOT NULL, arguments TEXT NOT NULL,
    state TEXT NOT NULL, generation TEXT, container TEXT, exec_id TEXT, token_hash TEXT, exit_code INTEGER,
    gap INTEGER NOT NULL DEFAULT 0, result TEXT, UNIQUE(assistant_seq,position), UNIQUE(assistant_seq,provider_id)
);
CREATE TABLE captured (
    seq INTEGER PRIMARY KEY AUTOINCREMENT, invocation_id TEXT NOT NULL REFERENCES invocation(id),
    channel TEXT NOT NULL, bytes BLOB NOT NULL
);
CREATE INDEX captured_invocation ON captured(invocation_id,seq);
