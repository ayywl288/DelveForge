-- Software Asset 持久化。
--
-- 只保存资产元数据（DOMAIN_MODEL.md §10.2：身份、来源、定位信息、权限与授权相关元数据）。
-- 资产的实际源代码由 Git / Filesystem 保存，不进入本表，也不由本迁移负责。
--
-- 设计要点：
--
-- 1. 不声明 location 唯一约束，也不做任何去重。
--
--    领域模型没有定义「同一个 location 只能对应一个 Software Asset」。
--    登记阶段也不判断 location 是否真实存在、是否是 Git Repository，
--    这类判断属于 Analyze Repository 的前置条件（§8.3），需要 Workspace 能力。
--
-- 2. 按 id 覆盖，不保存历史版本。
--
--    这是当前实现选择，不是领域模型的规定：§10.2 只要求持久化资产的元数据，
--    没有规定覆盖或历史保留策略；§6 也没有为 Software Asset 定义状态机，
--    但这不等于文档已经决定了「覆盖保存」。
--
--    如果将来需要保留授权变化的历史，应新增迁移，而不是修改本文件。
--
-- 3. 取值列保存领域类型的名字，与 user_profile.status 的处理一致。
--
--    read_permission 以 0 / 1 保存：SQLite 没有原生 boolean 类型。0 表示不允许读取，
--    1 表示允许。该列 NOT NULL：Domain 要求读取权限必须显式给出，不存在「未说明」。
--
--    license_info 允许为 NULL，表示当前不知道该资产的许可证信息（§3.2 记录的是
--    「已知的」许可证信息），与「没有许可证限制」不是同一件事。
--
--    usage_authorization 保存 ALLOWED / DENIED / UNCLEAR 三者之一。三者语义不同
--    （已明确允许 / 已明确不允许 / 尚未确认），不得在存储层合并取值。

CREATE TABLE software_asset (
    id                  TEXT    NOT NULL PRIMARY KEY,
    type                TEXT    NOT NULL,
    source              TEXT    NOT NULL,
    location            TEXT    NOT NULL,
    read_permission     INTEGER NOT NULL,
    license_info        TEXT,
    usage_authorization TEXT    NOT NULL
);
