-- SQLite 需要重建表才能扩展 CHECK。保留原消息、顺序、身份和外键。
ALTER TABLE discovery_message RENAME TO discovery_message_v27;
DROP INDEX discovery_message_history;
CREATE TABLE discovery_message (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    profile_id TEXT NOT NULL,
    request_id TEXT NOT NULL,
    project_id TEXT,
    role TEXT NOT NULL CHECK (role IN ('user', 'agent')),
    text TEXT NOT NULL,
    question TEXT,
    area TEXT,
    question_state TEXT CHECK (question_state IN ('ASKED', 'ANSWERED', 'DEFERRED', 'REPLACED')),
    created_at TEXT NOT NULL,
    FOREIGN KEY (profile_id, request_id) REFERENCES discovery_request(profile_id, request_id),
    UNIQUE (profile_id, request_id, role)
);
CREATE INDEX discovery_message_history ON discovery_message(profile_id, sequence);

INSERT INTO discovery_message SELECT * FROM discovery_message_v27;
DROP TABLE discovery_message_v27;

-- 旧库若存在多条未解决的问题，原界面呈现最新的一条，其余标为已被替换。
UPDATE discovery_message SET question_state='REPLACED'
WHERE question_state='ASKED' AND sequence NOT IN (
    SELECT MAX(sequence) FROM discovery_message WHERE question_state='ASKED' GROUP BY profile_id
);
CREATE UNIQUE INDEX discovery_one_question ON discovery_message(profile_id) WHERE question_state='ASKED';

CREATE TABLE discovery_invitation (
    profile_id TEXT PRIMARY KEY,
    request_id TEXT NOT NULL,
    choice TEXT CHECK (choice IN ('CONTINUE', 'REVIEW')),
    FOREIGN KEY(profile_id, request_id) REFERENCES discovery_request(profile_id, request_id)
);
-- 进入审阅后旧选择失活，已确认画像显式重开时才开启新的邀请周期。
CREATE TRIGGER discovery_review_entered AFTER UPDATE OF status ON user_profile
WHEN NEW.status='REVIEWING' AND OLD.status='EXPLORING'
BEGIN
    UPDATE discovery_invitation SET choice='REVIEW' WHERE profile_id=NEW.id AND choice IS NULL;
END;
CREATE TRIGGER discovery_cycle_reopened AFTER UPDATE OF status ON user_profile
WHEN NEW.status='EXPLORING' AND OLD.status='CONFIRMED'
BEGIN
    DELETE FROM discovery_invitation WHERE profile_id=NEW.id;
END;
