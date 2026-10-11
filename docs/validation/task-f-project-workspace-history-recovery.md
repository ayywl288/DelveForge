# Task F — 项目工作台与历史恢复

日期：2026-10-11。范围：Application 导航身份、只读历史 API、Vue 工作台与安全恢复。
基础版本：Task E `36beb37d7bf4aef87818efc9bf6828f01030bfeb`，独立分支
`codex/task-f-project-history`。没有付费 Provider、真实演化、M5 或已有业务库的迁移执行。
F1/F2 后端与架构提交：`8b182ac`。F3/F4 前端、使用文档与本报告在后续独立提交中交付；
这些开发提交不修改 GS REST 的 A / B，也不表示执行了新 Evolution Step。

## 1. 身份模型与恢复路径

以前浏览器只保存五类 UUID，后端没有保存探索阶段的项目归属。仅根据同一路径或共同画像
不能区分两个项目；当前实现采用 [ADR-0007](../decisions/0007-application-project-context-and-read-only-history.md)
的最小 Application 导航记录。

```text
ProjectFlowContext
  ├─ userProfileId                 可复用的当前 Profile 身份
  ├─ assetIds                     明确加入的资产身份
  ├─ repositoryProfileIds         明确加入的分析快照
  ├─ directionIds                 实际返回并保存的候选身份
  └─ planId                       一旦绑定不能替换

旧 EvolutionPlan
  ├─ productDirectionId ── UserProfileId + userProfileRevision / 分析与候选资产引用
  ├─ baseRepositoryProfileId ── baseAssetId + analyzedRevision
  ├─ workingCopyId ── sourceAssetId / sourceRevision / current / lastVerified
  └─ Steps ── 授权状态 / baseline / ExecutionResult / VerificationResult
```

一个上下文绑定的 Profile / Plan 不可重新指向另一身份，资源集合只可追加。新 Plan 使用
新上下文，不覆盖旧项目。复用当前 CONFIRMED Profile 时数据库条件插入核对其状态与版本，
不复制、修改或确认画像。更新用 `expectedVersion` CAS；同 Plan 的并发认领由唯一索引
保护。上下文没有 Domain 状态或执行权。

旧 Plan 沿保存引用形成历史投影，普通 GET 不建立上下文。第一次点击「打开并继续」才
幂等认领。未关联的 Profile / Asset / RepositoryProfile 可在新流程中明确选择；没有 Plan
的旧孤立 Direction 不猜测归属，可通过高级 UUID 入口读取。

## 2. API 与真实进度

| 操作 | API | 能力 |
|---|---|---|
| 历史摘要 | `GET /api/projects` | 持久上下文与未认领旧 Plan；真实阶段、Step 计数、revisions、问题 |
| 恢复完整对象 | `GET /api/projects/{key}` | `project:{id}` / `plan:{id}`，沿保存 ID 加载对象、步骤和检查结果 |
| 新流程 | `POST /api/projects` | 名称及可选 confirmedUserProfileId；不自动创建画像或调用 AI |
| 保存关联 | `PUT /api/projects/{id}/context` | expectedVersion 与完整 ID 集合；过期 / 矛盾关联为 PROJECT_CONTEXT_CONFLICT |
| 继续旧 Plan | `POST /api/projects/legacy-plans/{id}/resume` | 只新增导航记录，重复认领返回同一身份 |
| 可选择资源 | `GET /api/projects/resources?kind=...` | USER_PROFILE / SOFTWARE_ASSET / REPOSITORY_PROFILE，分页查询 |

列表默认 50、上限 100。阶段来自实际聚合：未确认画像为需求探索，已确认但没有分析为
仓库理解，已有方向为产品方向，已有 Plan 为规划，已绑定副本为验证演化。阶段是导航位置，
不意味着成功或执行授权。副本缺失仍展示绑定的阶段与明确问题。

摘要显示实际各 Step 状态数量；详细页面直接展示已持久化的 ExecutionResult / VerificationResult。
最近活动取上下文更新时间或实际执行 / 验证时间，不补造旧对象创建时间。分页主要按上下文
更新时间排序；旧 Plan 没有该时间时按 ID 稳定排列，卡片仍显示可读的结果时间。

`WorkingCopyStorageInspection` 只读检查允许 Root、规范化目录、存在性、链接重定向与可读性。
不执行 Git、Maven、mkdir 或源码读取，不证明 HEAD、cleanliness 或执行安全；正式操作保留
原完整守卫。历史缺失、关联不一致、授权不足、当前画像版本变化及未决执行检查点均显示提示。

## 3. Vue 操作与防串线

- 「项目工作台」为主要入口；新建可重新探索或复用已确认画像。仓库页可显式选择已有资产
  或分析。高级 UUID 恢复折叠保留，不自动把孤立记录拼成项目。
- 浏览器只保存活动项目 ID、原对象 ID 和执行等待标记；不保存 Profile 内容、源码、聊天、
  Provider 响应或密钥。刷新按 ID GET，不重放 AI 或执行命令。
- 切换项目清除上一个项目的聊天和组件草稿，同项目阶段切换继续保留内存草稿。请求正在
  提交时禁用项目切换；旧轮询响应依据上下文版本丢弃，卸载会话不再保存关联或浏览器 ID。
- 未决 execute 标记跨项目保留；可以打开其所属 Plan 查询，不因为切换项目便认为请求取消。
  自动轮询只有 GET，离开演化页面清理定时器；没有自动重试 execute 或执行下一 Step。
- 业务命令与关联保存是两次独立提交。关联 CAS 失败或断线明确说明业务可能已完成，阻止
  后续写入并要求重新打开项目核对，不伪装业务回滚，不自动重放付费请求。

检查发现原前端仅允许 READY 副本继续，不能操作已验证且仍为 EVOLVING 的副本。
后端既有 `StepExecutionPolicy` 已允许 READY / EVOLVING。前端执行入口现在匹配该规则：
Plan ACTIVE、Step READY、前序 SUCCEEDED、current 等于 lastVerified、无未决请求。
Step 的审阅授权仍与 execute 风险确认分离，没有修改 Domain、Git、Mutation 或 Verification。

## 4. 实际 GS REST 历史与离线联通证据

本任务用 `mode=ro`、query_only 及读事务检查 `data/m4-local/delveforge.db`，没有启动应用
使用原库。确认的保存状态如下，来源是实际 SQLite，不是测试夹具或模型自述：

| 字段 | 保存值 |
|---|---|
| Plan ID / 状态 | `1b50eed8-07ab-4836-a6e8-75f8f525a378` / ACTIVE |
| 原资产位置 | `E:\develop\local_repository\gs-rest-service` |
| A / sourceRevision | `e38e4a1cc1f54c036d40dd111ab50d103b154c06` |
| B / current / lastVerified | `c822cc384234342a95f5239f2b4ce060002f0d15` |
| WorkingCopy | EVOLVING |
| Step 1 | SUCCEEDED，baseline=A；BUILD PASS；两个 FOCUSED_TEST 各 executed=5 / failures=0 |
| Step 2–4 | PENDING_CONFIRMATION；没有 baseline 或验证结果 |

使用 SQLite backup API 从只读源连接生成一致副本：
`data/task-f-offline-history/history.db`，integrity_check=ok。该副本包含用户数据，仅位于
本机忽略目录，不提交 Git。临时后端只绑定 127.0.0.1:19086，Provider 指向不可用的本地地址，
旧 Root 显式列入允许列表。执行的请求只有历史 GET 与导航认领，不执行生命周期命令。

实际 API 验证：旧投影正确返回 1/4、B、完整 Plan 和真实结果；重复认领返回同一上下文；
重启后仍读回同一项目。临时 Vite 19087 通过既有 Proxy 正确读回相同结果，首页 HTTP 200。
这些是**离线历史回读**，不是新的 Verified Evolution，也没有重跑原 BUILD 或测试。
日志中 AI Gateway generate 操作数为 0；临时后端与 Vite 已停止。
结束后原库仍没有 project_flow_context 表；只读技术摘要前后相同。

本机证据（均不跟踪，路径相对仓库根）：

| 证据 | 路径 |
|---|---|
| 原库只读事实 / 检查后对照 | `logs/task-f-existing-history-readonly.log` / `logs/task-f-existing-history-after.log` |
| 实际 API 安全摘要 | `logs/task-f-offline-history-api.json` |
| 重启与 Vite Proxy | `logs/task-f-offline-restart-proxy.json` |
| 临时 API 启动 / 重启日志 | `logs/task-f-offline-api.stdout.log` / `logs/task-f-offline-api-restarted.stdout.log` |
| 后端聚焦 / 全量 | `logs/task-f-focused-backend.log` / `logs/task-f-full-verify.log` |
| 前端 / 类型 / 构建 | `logs/task-f-frontend.log` / `logs/task-f-typecheck.log` / `logs/task-f-build.log` |

## 5. 回归结果

| 验证 | 实际结果 |
|---|---|
| 聚焦 Project API / SQLite / Storage | 14 项 PASS（8 + 3 + 3） |
| 最终 Maven Wrapper verify | 1450 项 PASS：Domain 219、Application 761、Infrastructure 270、App 200；零失败 / 错误 / 跳过 |
| 前端 Vitest | 71 项 / 7 个文件 PASS，其中 Task F 新增 15 项 |
| pnpm typecheck / build | PASS；Vite 48 modules，主 JS 160.94 kB / gzip 57.59 kB |
| 实际旧数据副本 + 重启 + Proxy | PASS；无 Provider / 演化 / 验证请求 |

主要回归包括共享画像的不同仓库、同仓库不同 Plan、旧 1/4 检查点读取、空流程 / 确认画像
复用、显式资源选择、版本冲突、两线程 CAS / 同 Plan 认领、旧迁移数据不改写、Root 允许
列表和目录错误、慢响应丢弃、业务中切换禁用、未决执行标记跨项目保留、卸载边界、Verified B
后 EVOLVING 副本的显式继续。确定性夹具的 `c822cc3` 形状与真实数据库事实分别验证，不能
把 Mock execute 或新夹具 SQL 写入当作真实演化成功。

## 6. 迁移与手动验收

1. 确认没有仍在后台运行的 execute / Maven 请求，停止原后端，保存一致的 SQLite 备份。
   新版本启动时 Flyway V15 只新建表和索引，不清空或回填业务数据。指向原来实际使用的
   数据库；使用 m4-local 时仍按 [本地说明](../M4_LOCAL_USAGE.md) 配置，不能切换到空库后
   误认为历史丢失。运行中的新版本支持 GET，不需要 API Key 来恢复历史。
2. 新 WorkingCopy 可设置 `DELVEFORGE_WORKSPACE_ROOT=E:\df-workspaces`。若要继续旧 C 盘
   副本，额外设置 `DELVEFORGE_WORKSPACE_EXISTING_ROOTS` 为其真实父 Root，并重启后端。
   不自动搬迁、副本重建或改写 revision。目录不允许 / 缺失时仍可查看历史及真实警告。
3. 启动 Vue，打开「项目工作台」。GS REST 应显示 1/4、Verified B c822cc3。点击继续，核对
   Step 1 的 BUILD / 两个 FOCUSED_TEST 和 Step 2–4 待确认状态。刷新页面、重启后端再打开，
   不应出现重新分析、规划、prepare 或 execute 请求。第一步打开旧 Plan 会新增导航记录。
4. 新建两个流程，复用同一已确认画像，分别选择不同仓库；来回切换核对准确 Asset / Profile。
   新建全新探索时先显示创建画像入口；不修改旧确认画像。已分析仓库可直接加入，不重复 AI。
5. 对同仓库不同旧 Plan 逐个打开，核对各自 Step 和 B。已有 Profile 后续变化时应显示历史
   绑定版本差异；不把当前六区当作旧快照。授权不足或目录问题按提示处理，不能借新建绕过。
6. 本轮只验收恢复，不点击真实 execute。用户以后单独授权继续时，Step 2 仍需审阅范围并
   显式确认，再在演化页确认风险后执行；前序成功、当前可信版本及所有后端守卫仍必须通过。

## 7. 不能恢复的内容与限制

- 没有保存的聊天、模型下一问、原始 prompt / response、已接受 CodingProposal 无法恢复。
  本界面没有历史 Git diff 接口，也没有重新构造这些信息的 AI 调用。
- API 返回当前 Profile；历史方向绑定 revision 保留并单独显示。历史 Profile 快照仍在原
  持久化模型中，但本轮没有新增快照浏览接口，不把当前内容包装为当时的画像。
- 项目是导航身份。全局单 SELECTED Direction 限制仍在，选择新方向会使旧方向及相关 Plan
  按现有规则失效；不能通过工作台启动两个并行活动演化流程。
- 上下文 CAS 只保护导航记录。多聚合 GET 不是跨整个业务流程的事务快照；实际提交重新
  检查授权、状态和 revision。项目目录预检不认证 Git 状态或执行请求是否已结束。
- 原业务命令与上下文保存分离，极端失败可留下尚未关联对象；明确提示并查询，不自动重放。
  对没有 Plan 的孤立旧 Direction，用户需高级 ID 恢复，不猜测关联或自动删除。
- Windows Maven 在宿主运行，不是 OS 沙箱；仅使用明确授权、受信任仓库。M5 未实现，
  EXECUTING / VERIFYING / 失败 Candidate 不能通过“恢复项目”获得重试、回滚或修复能力。

本任务结束后停止扩展功能，等待用户手动验收；没有自动 push。
