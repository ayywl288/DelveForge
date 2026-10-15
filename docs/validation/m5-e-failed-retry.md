# M5-E — FAILED Retry

- Worktree：`C:\Users\28226\.codex\worktrees\m5-lab\DelveForge`
- 分支：`feature/m5-lab`
- 基线：`4fc6247b75f4c1166159a8e8cdb5f1a25eb14fa6`
- 日期：2026-10-15（Asia/Shanghai；最终验证时间见下文）。
- 状态：本地实现与确定性验证完成，等待独立复审；本报告不代替独立 Gate。

## 契约与实现选择

当前 Domain §6.5 / §8.14 明确定义 `FAILED → User Confirm Retry → READY`，并要求 WorkingCopy
恢复该 Step 的 baseline。M5-C/D 已分别通过 User / Automatic Rollback 证明恢复，M5-009 及此前
Git 属性、整树物理内容守卫原样复用。本次不修改 GitWorkspaceAdapter 或 Verification 架构。

本 Slice 采用现有 Step 内值对象历史模式，新增 `RetryAuthorization`，不新增 Attempt Entity。
这是与 M5-B/C/D 一致的当前实现选择，已记录于 Domain、Architecture 和 ADR-0001 refinement。
原文并未规定独立 Attempt Aggregate，未借 Retry 引入任务引擎或 Recovery。

授权与执行是两个明确动作：Retry 只保存 READY 及授权事实，不调用 Provider、不修改文件；
用户随后显式执行该已授权轮次。UI 对两个动作分别展示确认；没有 GET、刷新、启动、异常或
定时器触发的 Retry / Execute。首次 Confirmation 仍只授权首次执行。

## 领域、历史和守卫

1. `RetryEvolutionStepUseCase` 重新加载 Plan / Flow / Direction / Asset / WorkingCopy，检查 ACTIVE、
   SELECTED、完整资产权限、绑定关系、前序 Step 成功及无未决流程。FAILED 本身不能替代真实
   baseline 证明；授权前通过共享 inspection 核对完整实际内容和隔离。
2. WorkingCopy 必须 EVOLVING，currentRevision / lastVerifiedRevision / 实际内容都匹配原 Step
   首次 baseline。它可以不同于 WorkingCopy.sourceRevision；失败 Candidate、部分修改和其他
   verified revision 均不能成为新起点。
3. 授权请求绑定当前 FAILED 检查点 ID（ExecutionFailure.id 或 User Rollback 授权 ID）、原
   baseline 和新的 canonical UUID。旧检查点、同轮重复请求、跨轮已用动作身份均被拒绝。
4. `EvolutionStep.authorizeRetry` 先追加上一轮完整快照，再清空当前结果槽位并进入 READY；
   baseline 从不重设。归档含 ExecutionResult、VerificationResult、RepairAuthorization 列表
   （含各次前置失败快照）、User Rollback 授权 / 结果、ExecutionFailure / AutomaticRollback。
   更早 Retry 顺序保留，避免递归复制更早数组。没有伪造旧 Confirmation 的时间或用户事实。
5. 执行准备、校验提案、`AuthorizedStepExecution` 及后续 Repair / User Rollback 携带并核对
   当前父 Retry ID；后两者仍须自身独立新授权。旧轮次即使同 baseline，也不能继承新授权。
6. READY 后复用既有 Execution → Candidate → 全部适用 Criteria Verification。PASS 才推进
   lastVerifiedRevision；明确 FAIL 保留最新 Candidate；Confirmed Execution Failure 复用
   M5-D 诊断 / 自动精确恢复，成功才 FAILED，否则 RECOVERY_REQUIRED。
7. UNKNOWN 不制造 ExecutionFailure / Candidate 或自动恢复。数据库故障保留最后完整检查点；
   重启仅回读事实，EXECUTING / VERIFYING / ROLLING_BACK 不重放、不推断停止、不转换状态。

## Persistence 与 API / UI

- V21 增加 `retry_authorizations TEXT NOT NULL DEFAULT '[]'`。V20 老记录不改写、不补造授权。
- Step DO / Repository 回读全部快照；完整 reconstitution 校验原 baseline、历史状态与身份唯一性。
- 原子 Step / WorkingCopy commit 重放 Domain 操作；正常、诊断和阻塞 SQL 都比较完整旧 Retry
  JSON。READY / baseline 相同但轮次历史变化也会使 CAS 失败。外部 Git 与 SQLite 仍非一个事务。
- POST `/api/evolution-plans/{planId}/steps/{stepId}/retry`：
  `expectedFailureId`、`expectedBaselineRevision`、`authorizationId`；成功只返回 READY。
- Retry 后 POST `execution-preparation` / `execute` 显式带 `retryAuthorizationId`；
  `repair` / `rollback` 在原请求字段之外也带该父 ID。首次执行 / 首轮 Repair / Rollback 的旧
  无父 ID 请求保持兼容；有 Retry 历史时不得省略父 ID。
- Plan GET / POST 响应新增 `retryAuthorizations`。UI 显示独立授权、旧失败 / 修复 / 回滚历史。
- 前端沿用跨标签 Web Lock 与发送前持久化 receipt；仅被采纳、仍对应当前查询版本的 GET
  可用于核实。匹配的已保存 Retry 授权可清理“授权请求”receipt；READY 不能清理未知执行
  receipt。网络结果未知、真实未决授权、过期 GET 不会自动重送。明确授权前拒绝仍需同一
  FAILED 检查点、baseline 和“本次授权未保存”证明，才清除等待。

## 关键变更位置

| 层 | 文件 / 责任 |
| --- | --- |
| Domain | `EvolutionStep` / `EvolutionPlan` / `StepExecutionPolicy` / 新 `RetryAuthorization` |
| Application | 新 `RetryEvolutionStepUseCase`；`ExecutionPreparationState` / `ExecutionPreparationBasis`；`PrepareEvolutionStepUseCase` / `ExecuteEvolutionStepUseCase` / `RollbackEvolutionStepUseCase`；`AuthorizedStepExecution` |
| Persistence | `V21__retry_authorizations.sql`；`EvolutionStepDO` / `EvolutionStepMapper`；`SqliteEvolutionPlanRepository` / `SqliteStepExecutionCommitAdapter` |
| REST / composition | `EvolutionStepExecutionController` / `EvolutionStepPreparationController` / `EvolutionPlanResponse` / `ExecutionPreparationUseCaseConfiguration` |
| UI | `frontend/src/api/{client,types}.ts` / `state/session.ts` / `views/EvolutionView.vue` |
| 回归 | `StepExecutionLifecycleTest` / `ExecuteEvolutionStepUseCaseTest` / `RetryAuthorizationMigrationTest` / `EvolutionStepExecutionApiIntegrationTest` / `VerificationOutcomeTest`（空目录释放等待）/ `frontend/tests/retry.test.ts` |

## 隔离与命令

所有写入仅在本 Worktree 的 ignored target、任务拥有的 UUID Git / SQLite Fixture 及
`C:\dm5e-1015-96d2` 临时根内。Maven Wrapper 使用离线缓存，TEMP / TMP 与 java.io.tmpdir
绑定该根，MAVEN_OPTS 为 `-Xmx512m`；Provider Key 和 SPRING_APPLICATION_JSON 在子进程环境
移除。API 使用测试 AI Gateway，验证 Fixture 为预先定义的最小内容，不构建未知仓库。
未触碰真实数据库、原始资产、历史 WorkingCopy、旧 Smoke、main 或其他 Worktree。

实际命令（均在上述 Worktree 根执行；`-D` 参数在 PowerShell 中整体引用）：

```powershell
# 早期 Domain / Application / 迁移聚焦
.\mvnw.cmd -o '-Djava.io.tmpdir=C:\dm5e-1015-96d2' -pl backend/delveforge-infrastructure -am '-Dtest=StepExecutionLifecycleTest,ExecuteEvolutionStepUseCaseTest,RetryAuthorizationMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

# 首轮包含核心与 Retry API（19 个 API Fixture 失败）
.\mvnw.cmd -o '-Djava.io.tmpdir=C:\dm5e-1015-96d2' -pl backend/delveforge-app -am '-Dtest=StepExecutionLifecycleTest,ExecuteEvolutionStepUseCaseTest,RetryAuthorizationMigrationTest,EvolutionStepExecutionApiIntegrationTest#m5e*' '-Dsurefire.failIfNoSpecifiedTests=false' test

# Fixture 修正后的第二轮只运行 Retry API
.\mvnw.cmd -o '-Djava.io.tmpdir=C:\dm5e-1015-96d2' -pl backend/delveforge-app -am '-Dtest=EvolutionStepExecutionApiIntegrationTest#m5e*' '-Dsurefire.failIfNoSpecifiedTests=false' test

# 最终关键故障补测
.\mvnw.cmd -o '-Djava.io.tmpdir=C:\dm5e-1015-96d2' -pl backend/delveforge-app -am '-Dtest=StepExecutionLifecycleTest,ExecuteEvolutionStepUseCaseTest,RetryAuthorizationMigrationTest,EvolutionStepExecutionApiIntegrationTest#m5eRetryOutcomesRetainHistoryAndUnknownStatesRemainBlocked+m5eRetryRejectsPhysicalBaselineDriftWithoutExternalExecution' '-Dsurefire.failIfNoSpecifiedTests=false' test

# 既有真实超时 / 中断夹具的清理问题复核（两次，分别在修正前后）
.\mvnw.cmd -o '-Djava.io.tmpdir=C:\dm5e-1015-96d2' -pl backend/delveforge-infrastructure -am '-Dtest=VerificationOutcomeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

# 完整回归（首轮遇清理错误后，顺序重跑；没有并行 Maven）
.\mvnw.cmd -o '-Djava.io.tmpdir=C:\dm5e-1015-96d2' verify
pnpm --dir frontend test
pnpm --dir frontend typecheck
pnpm --dir frontend build
```

### 实际结果

| 检查 | 实际结果 | 证据 |
| --- | --- | --- |
| 早期核心聚焦 | 59 项通过；当时 Domain 29、Application 29、迁移 1 | `m5e-core-tests-final.log` |
| 第二轮 Retry API 聚焦 | 19 项中 17 通过、2 失败：新增 UNKNOWN 测试协议预期错误 | `m5e-api-focused-second.log` |
| 最终关键聚焦 | BUILD SUCCESS / exit 0；69 项、0 failure/error/skip，20:10:11 完成；Domain 30、Application 29、迁移 1、API 9 | `m5e-focused-final.log` |
| 前端全量 | 174 / 174 项、11 文件通过，20:00:09 完成 | `m5e-frontend-test-final.log` |
| 前端 typecheck / build | 均 exit 0；Vite 生产构建成功 | `m5e-frontend-typecheck-final.log` / `m5e-frontend-build-final.log` |
| 首轮完整 verify | BUILD FAILURE / exit 1；17:17 min，20:27:54；Domain 240、Application 780 通过；Infrastructure 450 中 1 个 JUnit 清理 error，App 未执行 | `m5e-full-verify.log` |
| 未修改的超时夹具复核 | 8 项、0 assertion failure、1 cleanup error；这次在 interruption 用例 | `m5e-timeout-recheck.log` |
| 夹具修正后复核 | BUILD SUCCESS / exit 0；8 项、0 failure/error/skip | `m5e-timeout-recheck-fixed.log` |

最终完整 verify：**BUILD SUCCESS / exit 0**；2026-10-15 20:33:36 至 22:10:20，
Maven 报告 01:36 h；所有模块 jar / App repackage 成功。证据 `m5e-full-verify-second.log`，
开始 / 结束 / 退出码另保存在 `C:\dm5e-1015-96d2\verify-second-{start,end,exit}.txt`。

逐一核对磁盘上全部 145 份 Surefire XML 的最后写入时间均晚于本轮开始；无旧 XML 混计：

| 模块 | 本轮 XML / 全部 XML | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| domain | 12 / 12 | 240 | 0 | 0 | 0 |
| application | 65 / 65 | 780 | 0 | 0 | 0 |
| infrastructure | 39 / 39 | 450 | 0 | 0 | 0 |
| app | 29 / 29 | 344 | 0 | 0 | 0 |
| 合计 | 145 / 145 | 1,814 | 0 | 0 | 0 |

`EvolutionStepExecutionApiIntegrationTest` 本轮 143 项、4661.386 s，其中新增 M5-E API 用例
22 项均通过；完整回归也覆盖此前仅以聚焦运行验证过的其余路径。没有以早期 69 项或旧 Gate
补算本轮总数。M0–M4 与 M5-A/B/C/D 当前确定性回归均在本轮重跑通过，不等于真实 Provider E2E。

完整 verify 后仅补齐文档及调整一组 import 顺序，无逻辑修改。
最终 `git diff --check` 通过；依赖、Git adapter、Verification 策略及 Recovery 均未修改。
测试日志、数据库、Fixture、构建产物不纳入提交；本次只在 feature/m5-lab 创建一个本地提交。
提交身份以最终交付 SHA 及 `git show HEAD` 为准。

### 故障与历史证据覆盖

- 两种 FAILED 来源：M5-C Verification FAIL → Repair 再 FAIL → User Rollback，及 M5-D
  真实 Git index.lock 确认失败 → Automatic Rollback；均可独立授权，READY 重启回读后显式成功执行。
- 重复 POST、已用 ExecutionFailure / Repair / Rollback / Retry 身份、错误父身份、旧无父请求、
  同 baseline 的旧提案与旧 SQLite 提交均拒绝。多轮失败 / Retry 在 Domain 与持久化中保留历史。
- 两个 Retry UseCase 调用在真实 SQLite commit 前同步竞争：一胜一 CAS 冲突，无 Provider / mutation；
  此项是并发 Application + SQLite 测试，非两路 HTTP 压测。重复动作另由 MockMvc / UI 覆盖。
- 权限在授权前、授权后、Provider 准备期间和 EXECUTING claim 后撤销；后者仍保存失败诊断，
  不调用恢复能力，保存 RECOVERY_REQUIRED。先前授权 / 历史不消失。
- 独立真实 Git Fixture 的 APPLY index.lock 与 PUBLISH HEAD.lock 拒绝；后者覆盖已应用但未
  发布的补丁恢复。mutation / verification / rollback 的结果丢失由 Port 故障注入，未冒充 OS 崩溃。
- 授权前 / 授权后同长度物理篡改并恢复 mtime、core.trustctime=false / checkstat=minimal：
  共享真实内容守卫拒绝，无 Provider、Apply 或 Rollback，用户额外修改保持。
- SQLite trigger 中止授权 Step 写入、授权 WorkingCopy 写入、EXECUTING claim、Candidate、
  Verification、失败诊断、FAILED / RECOVERY_REQUIRED 两种终态写入。检查原子性、历史不丢失、
  lastVerified 不误推进、诊断不可保存时不恢复、全部终态不可保存时保留 ROLLING_BACK。
- 同一隔离 SQLite 重新启动 Spring context，对 READY、成功、失败、UNKNOWN 与数据库故障
  检查点回读，比较全部历史和 WorkingCopy；没有重放。另检查未决状态不能发起 Retry。
- UI 覆盖独立勾选、保存 READY 无自动执行、显式父 ID payload、重复点击、资产拒绝、网络
  未知、浏览器重新加载、两个 GET 交错及真实 EXECUTING / ROLLING_BACK receipt 保留。
- 旧 M0–M4、M5-A/B/C/D、Git 属性 / BOM / LF/CRLF / 精确 baseline / 额外文件保护由完整
  verify 和前端全量回归确认；最终结果见上表，不将历史 Gate PASS 计作本次执行。

### 开发中出现的失败及处理

- 迁移测试初版为同一 plan 插入相同 position，触发既有唯一约束；改为独立递增位置，保留
  所有旧 JSON 字节哨兵断言。
- 第一轮 Retry API 19 项均在恢复 Fixture 的物理内容断言失败，未到 Retry：Windows 实际
  checkout 为 CRLF，Fixture 错误假定 LF。改为与本 Fixture 执行前保存的原始物理内容精确比较，
  未放宽生产内容守卫。第二轮 17/19 通过，两个 UNKNOWN 场景预期错用 409；核对现有
  ApiExceptionHandler 及 M5-D 原回归后改为既定 WorkspaceException 的 500，并增加目标阶段
  已进入、Candidate / Verification 空值及不调用回滚的断言，未修改生产错误映射。
- 前端初版调用旧方法时多传 undefined，使原调用断言失败；恢复旧轮次原参数个数。新 Vue
  测试初版误选隐藏执行弹窗 checkbox，改为 dialog[open]。类型检查发现 mock 可选参数类型及
  未使用 import，已修正；没有删除旧测试或放宽安全断言。

- 完整 verify 首轮与单独复核均出现 JUnit @TempDir 删除空根目录的 FileSystemException，
  分别发生在真实 timeout / interruption 用例；没有安全断言失败。故障目录随后已不存在，
  只读查询未发现 ControlledProcess 遗留；未能追溯具体持锁者，不声称已证明某个 OS 组件为根因。
  WorkspaceCommandRunner 已有进程退出等待，生产代码未变。本次仅在 VerificationOutcomeTest
  @AfterEach 对自身空目录执行 deleteIfExists，有界等待 5 秒，仍失败则原异常上抛；不递归删除、
  不吞错、不关闭用例、不改 timeout / interrupt / UNKNOWN 断言。这是完成验证所需的夹具可靠性
  修正，不是 Git/API 性能重构；修正后先单独复核 8 项，再顺序重跑完整 verify。

## 已知限制、未测试场景与 Decision Required

- 未验证 Windows / OS 强制崩溃、断电、任意外部写者之间的原子隔离；重启证据仅是同一任务
  SQLite 的进程内新 Spring context 回读，不是电源故障或 kill 过程。Git 与数据库没有跨技术事务。
- 未执行真实 Provider、付费 E2E、真实用户数据库升级或手工浏览器 E2E；前端证据为 Vitest /
  jsdom、类型检查与生产构建。历史迁移是隔离 V20→V21 行字节保持测试，合法历史由 API
  结果及重启回读覆盖。
- M4 在 EXECUTING claim 前进行 Provider 准备的既有次序保留；不宣称跨客户端 Provider
  exactly-once。新授权保存本身零 Provider；UI 未知请求保持等待，不自动重送。
- 每轮失败快照为 Step 内追加 JSON，历史随重试次数增长；未引入历史压缩或性能优化。
- 本 Slice 没有需要新增裁定的阻塞问题。既有后续 Recovery Decision Required：重启残留
  ROLLING_BACK / 未知执行调用的所有权与停止判据，仍遵循用户已裁定的只保留 / 阻塞 / 不重放。
  Recovery、FAILED 之外 Retry、Verification 重构及 Git/API 测试效率评估均未开展。
- 最终只创建实验分支本地提交，不 Push / 合并 / 移动 Tag；提交后停止等待独立审查。
