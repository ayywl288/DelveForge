-- Product Direction 的 Evidence 结构升级：从扁平列表改为「关键判断 → 依据」的对应关系。
--
-- 背景：
--
-- V5 把 Product Direction 的依据存成一份扁平列表（product_direction_evidence）。
-- 那样只回答得了「这个方向有一批依据」，回答不了 INV-D06 要的
-- 「用户匹配关系这个判断，是靠哪几条依据成立的」——三组依据压在一起，丢掉的正是
-- 可追溯性。同时裸 Evidence 也不表达它出自哪一份分析：两个 Repository Profile 完全
-- 可能各有一条 README.md /「使用 Spring Boot」，扁平列表无法区分。
--
-- 因此本迁移引入 product_direction_evidence_support：
--
--     direction_id   属于哪条方向
--     category       userNeed / userFit / reusableCapability
--     position       该组内的顺序
--     ...            Evidence 自身的字段（与 V5 同构）
--     origin_*       这条依据出自哪一版 User Profile 或哪一份 Repository Profile
--
-- 设计要点：
--
-- 1. 迁移前要求没有任何已存在的 Product Direction。
--
--    旧表的行里没有 category，也没有 origin——两者都是这次才引入的领域事实。
--    把旧行塞进新结构就必须替它猜测「这属于哪一类判断」「这出自哪一份分析」，
--    那等于凭空制造领域事实。因此这些依据无法转换。
--
--    而只丢弃依据、留着方向行同样不行：ProductDirection 要求至少有一条依据
--    （INV-D06），缺了依据的方向读出来就会失败——那是一个「迁移成功但数据不可恢复」
--    的状态，比迁移失败更糟。
--
--    所以这里的选择是：迁移前先检查方向表是否为空，有数据就直接失败，让调用方显式
--    决定怎么处理（清理开发数据，或先自行导出）。不静默删除，也不伪造转换。
--    下面那张临时表就是这个检查：product_direction 有任何一行时，CHECK 会失败，
--    整个迁移回滚，Flyway 报出的约束名本身就是提示。
--
-- 2. category 取领域字段名，与 repository_profile_section_item.section 的处理一致。
--
-- 3. origin 用四列表达两种来源，而不是一个通用字符串：
--
--        userProfile        origin_user_profile_id + origin_user_profile_revision
--        repositoryProfile  origin_repository_profile_id
--
--    kind 与专属列必须一致：表级 CHECK 保证 userProfile 只填前三列、
--    repositoryProfile 只填最后一列，且各自的必填列都不为 NULL。否则一条记录可能同时
--    带着两种来源的字段，读回来时另一组矛盾信息被静默忽略。
--
--    User Profile 侧必须带上 revision，否则「依据出自哪一版用户画像」重新变得不可回答
--    （INV-D01、INV-D08）。这里不为 origin 建立外键，也不给它独立身份：
--    它是依据的一部分，不是独立 Entity。
--
-- 4. 同一份依据可以出现在多个 category 中，因此主键包含 category，不使用去重约束。
--
-- 5. 不声明外键约束，与 user_profile / repository_profile 的处理一致。

-- 迁移前置检查：已有 Product Direction 数据时整个迁移失败。
--
-- 用 TEMP 表上的触发器而不是 CHECK 约束：SQLite 报 CHECK 失败时只回显表达式文本
-- （「CHECK constraint failed: direction_count = 0」），开发者看不出发生了什么；
-- RAISE 可以带一句真正说明问题的消息。
CREATE TEMP TABLE v6_direction_count_guard (direction_count INTEGER);

CREATE TEMP TRIGGER v6_direction_count_guard_trigger
BEFORE INSERT ON v6_direction_count_guard
WHEN NEW.direction_count > 0
BEGIN
    SELECT RAISE(ABORT,
        'V6 需要 product_direction 表为空：已有的 Product Direction 行没有 category 也没有 '
        || 'origin，它们的依据无法转换，而缺了依据的方向读不出来。请先显式处理这些开发数据'
        || '（清理或导出），再重新迁移。');
END;

INSERT INTO v6_direction_count_guard (direction_count)
    SELECT COUNT(*) FROM product_direction;

DROP TRIGGER v6_direction_count_guard_trigger;
DROP TABLE v6_direction_count_guard;

CREATE TABLE product_direction_evidence_support (
    direction_id                  TEXT    NOT NULL,
    category                      TEXT    NOT NULL,
    position                      INTEGER NOT NULL,
    source_type                   TEXT    NOT NULL,
    source_ref                    TEXT    NOT NULL,
    claim                         TEXT    NOT NULL,
    confidence                    REAL,
    confirmed                     INTEGER NOT NULL,
    origin_kind                   TEXT    NOT NULL,
    origin_user_profile_id        TEXT,
    origin_user_profile_revision  INTEGER,
    origin_repository_profile_id  TEXT,
    PRIMARY KEY (direction_id, category, position),
    CHECK (
        (origin_kind = 'userProfile'
            AND origin_user_profile_id IS NOT NULL
            AND origin_user_profile_revision IS NOT NULL
            AND origin_repository_profile_id IS NULL)
        OR
        (origin_kind = 'repositoryProfile'
            AND origin_repository_profile_id IS NOT NULL
            AND origin_user_profile_id IS NULL
            AND origin_user_profile_revision IS NULL)
    )
);

DROP TABLE product_direction_evidence;
