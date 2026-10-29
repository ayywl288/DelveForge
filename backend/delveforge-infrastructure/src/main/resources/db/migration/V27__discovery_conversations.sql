-- 问答不是 Profile 事实；请求身份用于丢失响应、重复提交和进程重启后的结果核对。
CREATE TABLE discovery_request (
    profile_id TEXT NOT NULL REFERENCES user_profile(id),
    request_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('TURN', 'QUESTION', 'CANCEL')),
    project_id TEXT,
    fingerprint TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('PENDING', 'COMPLETED', 'FAILED', 'INTERRUPTED', 'CANCELLED')),
    owner_pid INTEGER NOT NULL,
    owner_start TEXT NOT NULL,
    owner_token TEXT NOT NULL,
    expected_revision INTEGER NOT NULL,
    result_revision INTEGER,
    result_json TEXT,
    created_at TEXT NOT NULL,
    completed_at TEXT,
    PRIMARY KEY (profile_id, request_id)
);
CREATE UNIQUE INDEX discovery_one_pending ON discovery_request(profile_id) WHERE state = 'PENDING';

CREATE TABLE discovery_message (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    profile_id TEXT NOT NULL,
    request_id TEXT NOT NULL,
    project_id TEXT,
    role TEXT NOT NULL CHECK (role IN ('user', 'agent')),
    text TEXT NOT NULL,
    question TEXT,
    area TEXT,
    question_state TEXT CHECK (question_state IN ('ASKED', 'ANSWERED', 'DEFERRED')),
    created_at TEXT NOT NULL,
    FOREIGN KEY (profile_id, request_id) REFERENCES discovery_request(profile_id, request_id),
    UNIQUE (profile_id, request_id, role)
);
CREATE INDEX discovery_message_history ON discovery_message(profile_id, sequence);

CREATE TABLE discovery_deferred_topic (
    profile_id TEXT NOT NULL REFERENCES user_profile(id),
    area TEXT NOT NULL,
    PRIMARY KEY (profile_id, area)
);

CREATE TABLE discovery_readiness (
    profile_id TEXT PRIMARY KEY REFERENCES user_profile(id),
    revision INTEGER NOT NULL,
    sufficient INTEGER NOT NULL CHECK (sufficient IN (0, 1)),
    missing_json TEXT NOT NULL
);
-- 旧库只能迁移真实存在的通过记录，不能从 Evidence 重建聊天或编造负面评估。
INSERT INTO discovery_readiness(profile_id, revision, sufficient, missing_json)
SELECT profile_id, revision, 1, '[]' FROM user_profile_sufficiency;
