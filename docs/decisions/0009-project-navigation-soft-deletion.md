# ADR-0009：项目导航软删除与恢复

Status: Accepted

Date: 2026-10-11

## 问题与选择

工作台需要隐藏和恢复不再使用的项目，但历史 Domain 对象、共享资源和 Git 验证证据不能
级联删除。采用精确工作台 key 的软删除：project:<Context ID> 或 plan:<未认领 Plan ID>。
每张规划轮次卡片有独立导航身份；删除不批量删除同 flowId 的其它轮次。原身份与引用保留。
不按路径、Profile 或时间推断删除范围。

V17 添加 Context.deleted_at；删除/恢复增加现有 version。未认领 Plan 的只读投影以
project_legacy_visibility(plan_id,version,deleted_at) 保存导航标记，不制造可信归属。
默认查询同时过滤两类标记；已被任何 Context 绑定的 Plan 不再次作为旧投影出现。
新表不修改旧业务记录。已删除上下文及旧投影不能被普通更新或认领隐式恢复。

## 并发与执行边界

请求提交明确 expectedVersion。事务先取得 SQLite 写锁，再核查相关 Plan / Step /
WorkingCopy，最后只改导航标记。过期版本、重复删除、重复恢复返回 409，调用方须重新查询；
没有自动重试。不存在返回成功同时丢失业务数据的级联操作。

CodingProposal 准备中的 Step 可能仍是 READY。现有保存结果不足以分辨空闲授权和正在准备，
因此删除保守拒绝 READY、EXECUTING、VERIFYING、恢复相关状态、未知状态、缺失或损坏结果，
以及 currentRevision != lastVerifiedRevision 的副本。已知 Context 检查同 flowId 的 Plan，
防止从另一个轮次隐藏正在执行的项目；未知历史投影只能沿其确切 Plan 检查。
此轮不增加 M4 执行租约或撤销授权能力，因此闲置 READY 也不能删除，这是已知限制。

前端二次确认说明业务数据和代码保留；未决 execute 标记保留并阻止相应删除。删除当前
导航时清理对象 ID 和聊天展示，不能清除请求未决标记或取消后台执行。跨项目和列表切换
丢弃过期响应。恢复只恢复可见性，不重新选择方向、激活 Plan、执行 AI/Git/Maven。

## 替代方案与回滚

物理级联删除、磁盘清理和永久擦除需另行设计，不在本任务。仅前端过滤不能在刷新或
后端重启后保持删除，也不能阻挡过期关联写入，因此不采用。
升级需停止后端后备份一致数据库，由 Flyway 正常执行；本任务不修改用户数据库。
旧版本不理解删除标记和多项目方向归属，回滚应恢复升级前一致备份；直接回退程序会让
已删除导航重新出现，且 V16 的多 SELECTED 不兼容旧规则。
