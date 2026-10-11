# Task F-Fix：项目级方向隔离与重新规划

日期：2026-10-11。范围仅为方向归属、Plan 失效、项目导航和重新规划；不调用付费 Provider，
不执行真实 Step，不修改用户 SQLite 或 GS REST 文件。

## 真实根因与规则演进

旧 `SelectProductDirectionUseCase` 使用全局 `findCurrentSelected()`，V7 的 SELECTED 唯一
索引也是全局约束。Task F 增加导航项目后未改变这条旧单流程规则，因此 B 的选择会使 A
的方向及其 PROPOSED / ACTIVE Plan 失效。不是浏览器显示问题。

现在 Direction 和 Plan 保存不可变的 `flow_id`；客户端仅传上下文 ID 和 expectedVersion，
后端核对实际归属、画像、分析和资产。SQLite 在同一个事务内检查版本、选择新方向、
废弃本作用域旧方向及其 Plan；部分唯一索引保证每个已知 flowId 最多一个 SELECTED。
不同项目可以共享资源，但不共享选择作用域。INV-D09、INV-P08 和 ADR-0008 同步更新。

第一阶段提交：`d39adb5`。第二阶段提供新规划轮次和界面，提交可从本文件所在提交追溯。

## 为什么建立新轮次

原上下文的 planId 不可替换。切换方向后，旧 Plan 继续显示 SUPERSEDED、原 Step 结果和
原副本。用户点击「基于当前方向重新规划」先通过
`POST /api/projects/{id}/planning-rounds` 创建同 flowId、带 previousContextId 的后续记录。
建立轮次不调用 AI。用户随后明确选择合法的 Asset / RepositoryProfile，再调用现有
Planning API；Plan 写入与上下文绑定原子提交，失败不会留下半份关联。

历史轮次从 `GET /api/projects/{id}/rounds` 读取。恢复和切换不会自动规划或执行；
慢响应不能覆盖另一个项目。新 Plan 不继承旧 WorkingCopy，也不把旧 Verified B 当作
新的 analyzedRevision。

## V16 迁移及旧数据

新增三张现有表的 flow_id、Context.previous_context_id、索引与归属不可修改触发器。
仅按明确的 Context → Direction / Plan → Direction 引用确定旧归属：

- 唯一上下文明确引用且画像一致的方向归入该上下文作用域。
- 无上下文的旧 Plan 按保存的 Plan → Direction 链分配 legacy 作用域。
- 多上下文歧义及未引用候选保持未知归属，只读而不可选择、认领或规划。

迁移不改生命周期、授权、Git revision 或结果。已经 SUPERSEDED 的旧 GS REST Plan
不能继续确认或执行剩余 Step。其 Step 1 SUCCEEDED、VerificationResult 和 `c822cc3`
证据保留；无终态恢复操作。可在已知作用域中显式选择尚未失效的候选并建立新轮次；
没有合法候选时需用户明确请求新方向发现，可复用已有确认画像和分析，不自动付费。
不能凭路径、共享画像或时间把未知候选重新拼到旧项目。

升级由下次启动 Flyway 执行，本任务没有启动用户数据库。迁移后不能直接降级旧代码：
它不理解多 SELECTED；回滚需使用升级前一致备份，不能删除新字段当作安全回滚。

## 验证与手动验收

确定性测试覆盖共享画像 / 分析的项目隔离、同作用域替代、保存成功证据、不同与同作用域
并发选择、伪造归属、上下文 CAS、新轮次绑定、AI 期间版本变化回滚、旧迁移和前端
延迟响应。Provider 均为测试替身。Maven Wrapper `verify` PASS（9 分 14 秒）；
前端 7 文件、75 项测试 PASS，typecheck / build PASS。证据分别为
`logs/task-f-fix-verify-host.log`、`logs/task-f-fix-frontend-final.log`、
`logs/task-f-fix-typecheck-final.log`、`logs/task-f-fix-build-final.log`（本机忽略日志，非发布附件）。
首轮受限环境完整验证因 Git `couldn't create signal pipe, Win32 error 5` 失败；
经工具审批本机重跑通过，未改变测试断言。

1. 打开 A 与 B，分别选择方向；回到 A，确认其 SELECTED / ACTIVE 与 Step 数量不变。
2. 在 A 显式选择另一个候选，旧计划显示失效原因；查看其历史验证结果。
3. 建立新轮次，选择 Base 分析快照。仅点击「生成计划」才发起付费请求，测试时可停在按钮前。
4. 从轮次列表打开旧 Plan，再打开新轮次；确认原 planId、副本及 Verified revision 不变。
5. 刷新页面或切换项目，不应出现自动 Planning / execute 请求。旧根目录仍需配置
   existingRoots；目录不可用须显示真实问题。

本地确定性回归不等于新的跨仓库 Provider PASS；Windows 宿主 Maven 仍非 OS 沙箱。
