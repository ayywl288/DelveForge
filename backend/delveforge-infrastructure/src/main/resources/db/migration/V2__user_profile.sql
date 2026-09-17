-- User Profile 持久化。
--
-- 设计要点：
--
-- 1. user_profile 保存身份与当前状态（status、当前 revision）。
--
--    status 不参与 revision 口径：DOMAIN_MODEL.md §6.1 的 Revision 触发规则只覆盖
--    六个内容区与 Evidence，状态变化不推进 revision。因此 status 属于「当前状态」，
--    不是某个版本的内容，历史快照里不含它。历史快照只能恢复内容，不能恢复当时的 status。
--
-- 2. 六个内容区与 Evidence 按 (profile_id, revision) 分表保存该版本的内容快照。
--
--    只保存实际提交给 Repository 的 revision。Application 可能在一次 Use Case 内
--    连续修改多个内容区，最后只提交一次 save，因此版本号之间可能存在间隔：
--    例如只有 revision 1 与 4 被保存过，2、3 从未存在。
--    不能把「N <= 当前 revision」当作「revision N 有快照」。
--
-- 3. user_profile_revision 记录哪些 revision 实际被保存过。
--
--    不能靠「子表有没有行」来判断版本是否存在：一个已保存的版本可能六个内容区全空、
--    也没有 Evidence，此时它没有任何子行，但必须与从未保存过的版本区分开。
--
-- 4. 内容列表的顺序具有领域含义（按位置整体替换、按位置比较），因此保存 position。
--
-- 5. 不声明外键约束：SQLite 默认不启用 foreign_keys pragma，声明外键只会造成
--    「已经受到约束」的错觉。快照行的删除与写入由 Adapter 在同一事务内完成。

CREATE TABLE user_profile (
    id       TEXT    NOT NULL PRIMARY KEY,
    status   TEXT    NOT NULL,
    revision INTEGER NOT NULL
);

-- 已保存版本的锚点：只表示「这个 revision 被写入过」，不承载内容。
CREATE TABLE user_profile_revision (
    profile_id TEXT    NOT NULL,
    revision   INTEGER NOT NULL,
    PRIMARY KEY (profile_id, revision)
);

-- 六个内容区共用一张子表，用 section 区分；section 取领域字段名
-- （interests / behaviors / painPoints / technicalCapabilities / projectGoals / constraints）。
CREATE TABLE user_profile_section_item (
    profile_id TEXT    NOT NULL,
    revision   INTEGER NOT NULL,
    section    TEXT    NOT NULL,
    position   INTEGER NOT NULL,
    value      TEXT    NOT NULL,
    PRIMARY KEY (profile_id, revision, section, position)
);

-- confirmed 以 0 / 1 保存：SQLite 没有原生 boolean 类型。
-- confidence 允许为 NULL，表示未给出确定性判断。
CREATE TABLE user_profile_evidence (
    profile_id  TEXT    NOT NULL,
    revision    INTEGER NOT NULL,
    position    INTEGER NOT NULL,
    source_type TEXT    NOT NULL,
    source_ref  TEXT    NOT NULL,
    claim       TEXT    NOT NULL,
    confidence  REAL,
    confirmed   INTEGER NOT NULL,
    PRIMARY KEY (profile_id, revision, position)
);
