# Task F-Fix 追加：项目软删除与恢复

日期：2026-10-11。先完成并验证 F-Fix：`d39adb5`（方向隔离）与 `6549161`（新规划轮次）。
本追加另行提交，不调用 Provider、不执行真实 Step、不修改用户数据库或演示仓库。

## 删除的对象是什么

删除工作台卡片所代表的导航记录，既有项目用明确 Context ID，未认领旧历史用明确 Plan ID。
每个规划轮次独立保留，不按仓库路径、画像或 flowId 批量隐藏。不是永久擦除：
UserProfile、SoftwareAsset、RepositoryProfile、Direction、Plan、WorkingCopy、Step、
ExecutionResult、VerificationResult 和磁盘 Git 文件均不删除。

前端二次确认后从正常列表移除，可在「已删除项目」恢复同一身份。恢复不选择方向、
激活计划、重建副本或重做 AI；SUPERSEDED 保持 SUPERSEDED，SUCCEEDED 保持 SUCCEEDED。
删除当前打开的卡片只清理导航及会话展示，未决 execute 标记始终保留。

## API 与并发

| API | 行为 |
| --- | --- |
| `GET /api/projects` | 只读正常项目 |
| `GET /api/projects?deleted=true` | 只读已删除记录，同样分页 |
| `GET /api/projects/{key}` | 可读真实历史及 `visibility`，删除状态列为 `PROJECT_DELETED` |
| `DELETE /api/projects/{key}` | 显式软删除 |
| `POST /api/projects/{key}/restore` | 显式恢复可见性 |

key 是 API 返回的 `project:<Context ID>` 或 `plan:<未认领 Plan ID>`，由前端编码；
删除/恢复请求体为 `{"expectedVersion": <列表返回的 version>}`。不接受路径或 Profile ID。
成功版本增加 1；重复删除、重复恢复和过期请求返回 `PROJECT_CONTEXT_CONFLICT`（409），
不存在返回 404。调用方须重新读取，不自动重试未知结果。此处采用冲突语义而非无条件幂等成功。

应用先读取可验证的 Plan / 结果，持久化事务取得 SQLite 写锁后再次检查步骤与副本。
同流程其它轮次存在未决执行也不能隐藏当前轮次。已删除上下文不能继续关联更新、
方向发现/选择、Plan 绑定或建立新轮次；合法恢复才解除导航限制。Shared Resource 查询不变。

## 执行安全和限制

READY 可能仍有 CodingProposal 准备请求，没有可靠的持久化执行租约能区分它与闲置授权。
因此 READY、EXECUTING、VERIFYING、恢复相关状态、未知状态、缺失/无法解析的结果、
缺失副本记录或 currentRevision != lastVerifiedRevision 均拒绝删除，返回
`PROJECT_DELETION_BLOCKED`（409）。**闲置 READY 也无法删除**；本轮不新增撤销授权、
取消任务或 M5 恢复。前端未决标记再提供一层阻挡，不能靠清除浏览器 ID 绕过后端检查。

前端列表切换丢弃过期响应；删除失败保留上下文。刷新读回已删除的当前项目时回到工作台，
不重新认领或调用 AI。从项目间切换、恢复或删除不能被解释为取消后台请求。
无法恢复未持久化聊天、原始 Provider 响应和 CodingProposal 原文；没有生成这些历史内容。

## V17 迁移

- `project_flow_context.deleted_at` 和可见性索引；沿用 Context.version CAS。
- 新 `project_legacy_visibility(plan_id,version,deleted_at)` 仅记录旧 Plan 投影可见性。
- 默认历史查询同时检查两类标记。任何已被 Context 绑定的 Plan（即使 Context 已删除）
  都不会重新作为旧投影出现。

迁移没有业务内容、状态、授权或 revision 更新。本任务仅在临时测试 SQLite 执行。
用户升级前停止所有数据库拥有者并保存一致备份；下次正常启动由 Flyway 自动应用。
不得只复制活动 SQLite 主文件。旧代码不理解 V16/V17，回滚需恢复升级前一致备份；
直接降级可能重现全局选择及已删除项目。详见 ADR-0008/0009。

## 回归证据

临时数据库中的合成 GS REST 历史断言 1/4 Step 成功、Candidate `c822cc3`、VerificationResult
保存字节不变；这不是一次新的真实演化。独立数据库事务断言失败无残留标记、并发删除只
有一个赢家、新 Adapter / 连接读回删除状态、同流程另一轮次 READY 时拒绝删除。
API 覆盖恢复终态不变、未知结果拒绝、已删除上下文拒绝及 Planning 期间删除后的原子回滚。
前端覆盖二次确认、明确恢复、版本失败、未决标记、刷新和列表慢响应。

最终 Maven Wrapper `verify` 1468 项 PASS（Domain 219、Application 761、Infrastructure 275、
App 213；0 失败、0 错误、0 跳过），耗时 9 分 32 秒。聚焦回归 29 项 PASS。
前端 7 文件 / 80 项测试 PASS，typecheck / build PASS。原 GS REST 仓库只读检查仍在
`e38e4a1cc1f54c036d40dd111ab50d103b154c06`，工作树干净；没有执行其 Maven 或新的演化。
本机忽略日志：`logs/task-f-delete-regression.log`、`logs/task-f-delete-frontend-final.log`、
`logs/task-f-delete-typecheck-final.log`、`logs/task-f-delete-build.log`、`logs/task-f-delete-verify.log`。

## 手动验收

1. 停止旧后端并备份一致 SQLite；启动新版本，确认 Flyway 完成 V16/V17。刷新前端。
2. 在工作台新建不含 READY Step 的测试项目；删除前看到二次确认。删除后正常列表消失，
   刷新浏览器、重启后端仍不出现，已删除列表可恢复同一 ID。
3. 对保存成功结果的历史卡片执行删除/恢复，核对 Step 数量、原 Plan 状态和 Verified SHA。
   若 GS REST 曾被全局规则废弃，恢复仍应显示 SUPERSEDED，不测试继续 execute。
4. 在另一项目使用共享画像/资产，确认不会被删除。具有 READY、执行中或未决请求的项目
   删除应被拒绝，不为了验收触发付费 AI 或真实执行。
5. 两个标签页使用同一旧 version 删除，后一个必须收到版本冲突；刷新后只查询状态。

没有永久删除、级联清理、历史擦除或后台取消；本任务结束后等待用户手动验收。
Windows Maven 执行仍依赖可信仓库和宿主权限，不是 OS 沙箱。
