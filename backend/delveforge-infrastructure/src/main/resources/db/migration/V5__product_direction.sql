-- Product Direction 持久化：系统结合 User Profile 与 Repository Profile 发现的候选产品方向。
--
-- 设计要点：
--
-- 1. 有 status 列，且同一 id 允许被更新。
--
--    Product Direction 是拥有生命周期的 Entity（§4.1、§6.2），不是分析快照。
--    同一个标识会因为合法领域行为从 CANDIDATE 变为 SELECTED / REJECTED / SUPERSEDED，
--    因此这里与 repository_profile 的「一行只写一次」不同，与 user_profile 的
--    「按 id 覆盖、另存历史快照」也不同：本表只保存方向的当前状态。
--
--    允许更新的是 status；discovery basis、recommendation content、candidate assets
--    与 Evidence 不得因状态变化而被改写（§10.5、RULE-DOM-007）。这条由 Adapter 在
--    「同一标识再次保存」时比较内容后拒绝，而不是由本 schema 表达——SQLite 没有
--    可以让某几列不可更新的约束。
--
--    本表不包含 revision：Product Direction 不是版本化对象，领域模型没有为它定义
--    revision，§10.5 要求的「不丢失最初的分析依据」由内容行本身不被改写保证。
--
-- 2. 跨 Aggregate 引用只保存身份，不保存对象。
--
--    user_profile_id + user_profile_revision 是这条方向在用户侧的追溯点（INV-D01、
--    INV-D02），repository_profile_id 是它在资产侧的追溯点（INV-D05），
--    product_direction_candidate_asset.asset_id 指向可用的 Software Asset（INV-D10）。
--    这四者都是 Identity 引用：不复制完整 UserProfile / RepositoryProfile /
--    SoftwareAsset 的内容，也不声明外键。user_profile_revision 记录的是生成该方向时
--    所依据的版本，它不随后续 User Profile 更新而变化（INV-D02）。
--
-- 3. 四个多值字段各用一张子表，按 position 保留列表顺序。
--
--    repositoryProfileIds / candidateAssetIds / risks / evidence 的顺序具有领域含义
--    （Evidence 的呈现顺序、候选资产的先后），因此保存 position，读取时按 position 还原。
--    不合并成一张带 discriminator 的表：repository_profile_section_item 之所以共用一张表，
--    是因为那六个内容区是同类的字符串列表；这里三张表里的值是不同 Aggregate 的身份引用
--    与自由文本风险，语义不同，拆开更直接。
--
-- 4. 不声明外键约束：与 user_profile / repository_profile 的处理一致，
--    SQLite 默认不启用 foreign_keys pragma，声明外键只会造成「已经受到约束」的错觉。
--    子表行的写入由 Adapter 在同一事务内完成。
--
-- 5. evidence 的 source_type / confidence / confirmed 与 user_profile_evidence、
--    repository_profile_evidence 同构：Evidence 是同一类领域值，
--    存储形式不因归属的 Aggregate 而不同。
--
--    confidence 用 REAL 保存时负零会退化成正零，这是 SQLite 的存储行为。
--    Adapter 因此在写入与比较时统一取正零，使往返转换不制造出调用方从未提交过的差异。
--    统一正负零是该 Adapter 的实现选择，不是领域模型定义的等价语义：
--    §3.6 没有规定正负零是否等价，领域层也未归一化这个取值。
--
-- 6. status 保存领域类型的名字（CANDIDATE / SELECTED / REJECTED / SUPERSEDED），
--    与 user_profile.status 的处理一致。

CREATE TABLE product_direction (
    id                    TEXT    NOT NULL PRIMARY KEY,
    user_profile_id       TEXT    NOT NULL,
    user_profile_revision INTEGER NOT NULL,
    title                 TEXT    NOT NULL,
    problem               TEXT    NOT NULL,
    target_product        TEXT    NOT NULL,
    user_fit              TEXT    NOT NULL,
    differentiation       TEXT    NOT NULL,
    technical_value       TEXT    NOT NULL,
    estimated_complexity  TEXT    NOT NULL,
    status                TEXT    NOT NULL
);

-- 生成该方向所依据的 Repository Profile（INV-D05）。至少一行。
CREATE TABLE product_direction_repository_profile (
    direction_id         TEXT    NOT NULL,
    position             INTEGER NOT NULL,
    repository_profile_id TEXT   NOT NULL,
    PRIMARY KEY (direction_id, position)
);

-- 该方向标识的 Candidate Software Asset（INV-D10）。至少一行。
-- 这里只证明「方向标识了哪些资产」；这些资产是否真的来自上面那些 Repository Profile
-- 对应的 Software Asset，是跨 Aggregate 校验（INV-D10 后半句），不由本 schema 表达。
CREATE TABLE product_direction_candidate_asset (
    direction_id TEXT    NOT NULL,
    position     INTEGER NOT NULL,
    asset_id     TEXT    NOT NULL,
    PRIMARY KEY (direction_id, position)
);

-- 当前已知主要风险。可以为空：一个真实存在的方向可能确实没有已识别的主要风险。
CREATE TABLE product_direction_risk (
    direction_id TEXT    NOT NULL,
    position     INTEGER NOT NULL,
    value        TEXT    NOT NULL,
    PRIMARY KEY (direction_id, position)
);

-- 支撑该方向中重要判断的依据（INV-D06）。至少一行。
-- confirmed 以 0 / 1 保存：SQLite 没有原生 boolean 类型。
-- confidence 允许为 NULL，表示未给出确定性判断。
CREATE TABLE product_direction_evidence (
    direction_id TEXT    NOT NULL,
    position     INTEGER NOT NULL,
    source_type  TEXT    NOT NULL,
    source_ref   TEXT    NOT NULL,
    claim        TEXT    NOT NULL,
    confidence   REAL,
    confirmed    INTEGER NOT NULL,
    PRIMARY KEY (direction_id, position)
);
