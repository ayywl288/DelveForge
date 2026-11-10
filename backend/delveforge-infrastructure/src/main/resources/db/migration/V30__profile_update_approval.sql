-- 旧库无法可靠区分历史人工编辑。包括空分区在内全部保护，保守换取一次显式批准。
CREATE TABLE user_profile_section_protection (
    profile_id TEXT NOT NULL REFERENCES user_profile(id),
    section TEXT NOT NULL CHECK(section IN
        ('interests','behaviors','painPoints','technicalCapabilities','projectGoals','constraints')),
    PRIMARY KEY(profile_id, section)
);
INSERT INTO user_profile_section_protection(profile_id, section)
SELECT p.id, s.section FROM user_profile p CROSS JOIN (
    SELECT 'interests' AS section UNION ALL SELECT 'behaviors' UNION ALL SELECT 'painPoints'
    UNION ALL SELECT 'technicalCapabilities' UNION ALL SELECT 'projectGoals' UNION ALL SELECT 'constraints'
) s;

CREATE TABLE user_profile_update_proposal (
    id TEXT PRIMARY KEY,
    profile_id TEXT NOT NULL REFERENCES user_profile(id),
    source_request_id TEXT NOT NULL,
    project_id TEXT,
    base_revision INTEGER NOT NULL,
    base_status TEXT NOT NULL CHECK(base_status IN ('EXPLORING','REVIEWING')),
    snapshot_json TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('PENDING','ACCEPTED','DISMISSED','SUPERSEDED')),
    resulting_revision INTEGER,
    CHECK((state='ACCEPTED' AND resulting_revision IS NOT NULL) OR
          (state<>'ACCEPTED' AND resulting_revision IS NULL)),
    UNIQUE(profile_id, source_request_id)
);
CREATE UNIQUE INDEX user_profile_one_pending_update ON user_profile_update_proposal(profile_id)
WHERE state='PENDING';

-- 任何内容/状态变化都不能留下过期的待批提案；批准操作先 CAS 状态，再保存内容。
CREATE TRIGGER user_profile_supersede_update AFTER UPDATE OF revision, status ON user_profile
WHEN NEW.revision<>OLD.revision OR NEW.status<>OLD.status
BEGIN
    UPDATE user_profile_update_proposal SET state='SUPERSEDED'
    WHERE profile_id=NEW.id AND state='PENDING';
END;
