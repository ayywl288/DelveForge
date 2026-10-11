ALTER TABLE software_asset ADD COLUMN authorization_revision INTEGER NOT NULL DEFAULT 0
    CHECK (authorization_revision >= 0);

-- 登记事务先取得 SQLite 写锁再比较位置；不对历史重复记录建破坏性的唯一索引。
CREATE TABLE software_asset_registration_guard (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    guard_value INTEGER NOT NULL
);
INSERT INTO software_asset_registration_guard (id, guard_value) VALUES (1, 0);
