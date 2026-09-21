-- Repository Profile 持久化：某个 Software Asset 在确定 revision 下的一次分析快照。
--
-- 设计要点：
--
-- 1. 一次分析对应一行，且只写入一次。
--
--    新 revision 重新分析产生的是新的 Repository Profile（新的 id），不是对已有行的更新。
--    历史 Product Direction 或 Evolution Plan 可能仍引用旧快照，覆盖它会破坏追溯
--    （DOMAIN_MODEL.md §10.4、INV-D04）。因此 Adapter 在写入前检查 id 是否已存在，
--    已存在则拒绝，而不是覆盖。
--
-- 2. 没有 revision 与 status 列。
--
--    Repository Profile 不定义状态机（§6），它自己就是某个 revision 的一次快照；
--    换句话说 analyzed_revision 描述的是「分析的是哪个软件状态」，
--    不是「这份 Profile 自己的第几版」。
--
-- 3. 六个分析内容区与 Evidence 各用一张子表，按 position 保留列表顺序。
--
--    内容列表的顺序具有领域含义（例如 techStack 的先后、evidence 的呈现顺序），
--    因此保存 position，读取时按 position 还原。
--
-- 4. 不声明外键约束：与 user_profile 的处理一致，SQLite 默认不启用 foreign_keys
--    pragma，声明外键只会造成「已经受到约束」的错觉。子表行的写入由 Adapter 在同一
--    事务内完成。
--
-- 5. evidence 的 source_type / confidence / confirmed 与 user_profile_evidence 同构：
--    Evidence 是同一类领域值，存储形式不因归属的 Aggregate 而不同。

CREATE TABLE repository_profile (
    id                TEXT NOT NULL PRIMARY KEY,
    asset_id          TEXT NOT NULL,
    analyzed_revision TEXT NOT NULL,
    purpose           TEXT NOT NULL
);

-- 六个内容区共用一张子表，用 section 区分；section 取领域字段名
-- （techStack / modules / capabilities / reusableAssets / limitations / risks）。
CREATE TABLE repository_profile_section_item (
    profile_id TEXT    NOT NULL,
    section    TEXT    NOT NULL,
    position   INTEGER NOT NULL,
    value      TEXT    NOT NULL,
    PRIMARY KEY (profile_id, section, position)
);

-- confirmed 以 0 / 1 保存：SQLite 没有原生 boolean 类型。
-- confidence 允许为 NULL，表示未给出确定性判断。
CREATE TABLE repository_profile_evidence (
    profile_id  TEXT    NOT NULL,
    position    INTEGER NOT NULL,
    source_type TEXT    NOT NULL,
    source_ref  TEXT    NOT NULL,
    claim       TEXT    NOT NULL,
    confidence  REAL,
    confirmed   INTEGER NOT NULL,
    PRIMARY KEY (profile_id, position)
);
