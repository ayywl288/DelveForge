# M5-D — Execution Failure Handling & Automatic Rollback

日期：2026-10-14（Asia/Shanghai）。本地实验分支 `feature/m5-lab`。
Worktree：`C:\Users\28226\.codex\worktrees\m5-lab\DelveForge`。
基线：`a2bd5fe828a5e1f287b3f4f2c5a57b786f20435e`；开始时干净。
状态：实现者自验 Gate PASS；独立审查待执行。不是完整 M5 完成声明。

## 1. Contract / Scope

读取当前 AGENTS、PRODUCT、DOMAIN_MODEL、ARCHITECTURE、ROADMAP、ADR-0001、M5-A/B/C 与两轮修复报告，
并检查实际 Domain、Application、Git、SQLite 与前端代码。领域依据为 §6.4/6.5、§8.9/8.13、
INV-W05/06、INV-E04/05/08、INV-V07/08。Execution Failure 没有合法 Candidate，先保存诊断再系统回滚；
Verification Failure 的 Candidate 保留和 User Rollback 独立授权保持。

本轮只补已确认受控 Execution Failure；不实现 FAILED Retry、Recovery、Verification 重构或后台任务。
Provider / 提案准备错误、未知 mutation、Candidate / Verification 检查点异常继续保留现有完整检查点，
没有按通用 Exception 或非零退出推断 Failure。启动与 GET 不重放未决 EXECUTING / ROLLING_BACK，
也不推断旧调用已经停止。后续 Recovery 的所有权 / 停止判据仍需裁定。

测试根：`C:\dmd-1014-84ab`。JUnit Git Fixture 及模块 target 下本轮 UUID 数据库 / WorkingCopy 为任务自有。
Maven 使用 Wrapper 与离线缓存；完整回归 shell 删除 Provider key / SPRING_APPLICATION_JSON，
不输出凭据，不调用付费 Provider。实际 Maven 子仓库均为仓库已有测试生成的已知 Fixture。
没有访问或修改真实业务数据库、历史 WorkingCopy、原始软件资产、Smoke 证据、main 或其他 Worktree。
未移动 Release Tag，不 Push、不合并，不清理其他测试根。

## 2. Changes / Reason

| 层 | 关键文件 | 行为 |
| --- | --- | --- |
| Domain | ExecutionFailureResult / AutomaticRollbackResult、EvolutionStep / EvolutionPlan | 独立失败与系统恢复事实；无 Candidate，无虚构人类授权；原 baseline / Repair 历史保留 |
| Application | ExecuteEvolutionStepUseCase、ExecutionPreparationState、AuthorizedStepExecution / AuthorizedStepRollback、ConfirmedExecutionFailureException | 只捕获确认失败类型；先 CAS 诊断与 ROLLING_BACK，再当前资格复查与单次恢复；终态真实回读 |
| Infrastructure | GitWorkspaceAdapter | 阶段分类、真实 byte/index Diff、完整未发布 tree 恢复、共用精确 baseline 证明 |
| Persistence | V20、Step DO / Mapper / Repository、SqliteStepExecutionCommitAdapter | nullable 新槽，重放 Domain；Step / WorkingCopy 原子 CAS，诊断 / 阻塞安全提交 |
| REST / UI | EvolutionPlanResponse、frontend types / EvolutionView | 只读结果字段、执行失败与系统恢复展示；保留现有 GET 采纳与等待保护 |
| Tests / Docs | 生命周期、UseCase、真实 Git、迁移、API/SQLite、Vue/session；Domain / Architecture / Roadmap / ADR-0001 | 记录实际路径、技术选择和本 Slice 边界 |

未增加依赖、修改 Maven module 方向或 Provider，也未新增 Rollback / Retry / Recovery API。

## 3. Confirmed / Unknown Boundary

当前技术适配仅将完整读取、正常结束的受控 Git 拒绝判为已确认失败：exit 1 且 error 标记，
或 exit 128 且 fatal 标记。保存有限诊断，不保存原始 stderr、完整源码、补丁或凭据。
这是当前有限适配，不声称覆盖全部 Git / OS / Provider 失败。信号退出（137/139/Windows 崩溃码）、
缺失结果、无法认证的负向输出、timeout、interruption、output budget / reader failure 仍 UNKNOWN。

失败 VO 保存首次 baseline、本次起点、阶段、Git exit、观测 HEAD、预期补丁 tree 与实际 Diff identity。
Diff 对比原 baseline 的 blob / mode 与当前 index mode/blob/stage、重新读取的实际文件 byte identity；
忽略 stat/status 缓存，不执行 filter。CRLF 的原始 byte identity 可不同于 canonical blob，记录不等同于
原始 unifiedDiff 历史接口。额外用户文件不读取或删除；证据无法完整读取明确标记不可用。
已确认主失败不能被 finally 的临时产物清理异常覆盖；同一失败身份保留 Diff 与安全清理诊断。
完整 Candidate 已形成但结果未交付、或原结果未知时的清理异常继续 UNKNOWN。

## 4. Rollback / Checkpoints

1. 仅匹配已保存 EXECUTING 的完整元组允许诊断 CAS 与 ROLLING_BACK；失败则不启动恢复。
2. 诊断与已有 blockRollback 一样只写安全事实，不授予外部能力。执行后撤权 / Plan 失效仍保留事实，
   当前资格复查拒绝时记录 RECOVERY_REQUIRED，不调用恢复；响应回读真实父 Plan 状态。
3. 干净起点沿用 M5-C 非强制 checkout。完整未发布补丁必须同时匹配固定预期 tree 的 index 与整个
   真实工作树、原失败现场、隔离 / 路径 / mode / 属性 / 额外文件守卫，才恢复 baseline..tree 的已跟踪
   Step 文件。原 Step 已跟踪新增与删除属于该范围；其他用户文件保留。
4. 不使用 reset --hard / clean。恢复后共用 M5-001/003 修复的全树实际内容 / index / mode 证明，
   Application 再检查完整资格与物理 baseline；成功原子保存 FAILED / current=baseline，lastVerified 不推进。
5. 恢复失败、未知、伪结果或成功检查点拒绝只保存 RECOVERY_REQUIRED。两种终态都拒绝时保留原
   ROLLING_BACK。中断标志在安全事实保存后恢复，不重跑 Git。

## 5. Executed Evidence

- 真实 Git HEAD.lock 拒绝 Candidate 发布：补丁已应用但没有发布 HEAD，保存实际 Diff，无合法 Candidate。
- 完整未发布 tree 的普通修改及新增 / 删除文件恢复到最初 baseline；未修改文件也完整检查。
- tracked 用户修改、额外用户文件、index.lock 令恢复阻塞并保留内容；unset / unspecified filter 不执行。
- 初次执行与 Repair 的真实 index.lock 拒绝；Repair 失败保留旧 Execution/Verification 与本次授权，恢复 A。
- Rollback 未知 / 错误“成功”返回不变成 FAILED。实际恢复仍需本版整树证明与最终检查点。
- SQLite 诊断、成功、全部终态触发器故障：没有半个检查点；保留 EXECUTING / RECOVERY_REQUIRED /
  ROLLING_BACK 的对应事实，诊断失败不调用恢复。
- 并发请求观察已保存 ROLLING_BACK；第二 execute / repair / user rollback 被拒绝，只有一次恢复调用。
  前置准备在计时前完成，15/30/60 秒屏障及并发断言没有放宽。
- 新 Spring 上下文 / 连接从同一任务数据库读回 FAILED、RECOVERY_REQUIRED、ROLLING_BACK、EXECUTING
  及完整失败 / Repair 历史；启动不重放。是应用上下文回读，不是 OS 崩溃实验。
- V19→V20 隔离迁移保留八类旧状态、原结果 / Repair / User Rollback JSON 字节，只增加 null 新槽。
  原文哨兵只证明迁移，不冒充有效 Domain 记录；有效回读由 API 测试证明。
- 合成前端夹具：系统回滚中保持等待，终态基于已采纳 GET；没有自动 POST / Retry 或假的 Candidate / Success。

## 6. Commands / Actual Results

后端从 Worktree 根运行，TEMP/TMP/java.io.tmpdir=`C:\dmd-1014-84ab`，MAVEN_OPTS=-Xmx512m。
所有聚焦命令另带 `-Dsurefire.failIfNoSpecifiedTests=false`；PowerShell 将 -D 参数整体单引号包裹。
前端从 frontend 运行；没有 lint 脚本。

| 检查 | 实际命令 / 参数 | 结果 |
| --- | --- | --- |
| 编译/打包检查 | `mvnw.cmd -o -Djava.io.tmpdir=C:\dmd-1014-84ab -DskipTests package` | BUILD SUCCESS，17:55:28；测试跳过，不作回归证据 |
| 聚焦 | `mvnw.cmd -o -Djava.io.tmpdir=C:\dmd-1014-84ab '-Dtest=StepExecutionLifecycleTest,ExecuteEvolutionStepUseCaseTest,ExecutionFailureMigrationTest,GitEvolutionExecutionIntegrationTest#confirmedPublishFailureRecordsPhysicalDiffAndRestoresOnlyProvenStepTree+unrecognizedProcessExitCannotBecomeConfirmedExecutionFailure,EvolutionStepExecutionApiIntegrationTest#m5d*' test` | 71 / 0 failures/errors/skipped，05:57，18:23:15；m5d-focused-final.log |
| 新增/删除与旧历史迁移 | 同一 Wrapper / 根，`-pl backend/delveforge-infrastructure -am '-Dtest=ExecutionFailureMigrationTest,GitEvolutionExecutionIntegrationTest#confirmedPublishFailureRecordsPhysicalDiffAndRestoresOnlyProvenStepTree' test` | 8 / 0 failures/errors/skipped，01:04，18:28:15；m5d-addition-migration-final.log |
| 撤权 / Plan 与检查点最终聚焦 | 同一 Wrapper / 根，`'-Dtest=StepExecutionLifecycleTest,ExecuteEvolutionStepUseCaseTest,EvolutionStepExecutionApiIntegrationTest#m5d*' test` | 62 / 0 failures/errors/skipped，07:41，18:52:28；m5d-checkpoint-final.log |
| 主失败 / 清理异常 | 同一 Wrapper / 根，`-pl backend/delveforge-infrastructure -am '-Dtest=GitEvolutionExecutionIntegrationTest#confirmedPublishFailureRecordsPhysicalDiffAndRestoresOnlyProvenStepTree' test` | 8 / 0 failures/errors/skipped，01:43，19:13:02；m5d-cleanup-retention.log |
| 最终后端 | `mvnw.cmd -o -Djava.io.tmpdir=C:\dmd-1014-84ab verify` | BUILD SUCCESS，1631 / 0 failures/errors/skipped，01:08 h，20:24:09 完成，退出 0；m5d-verify.log |
| 前端全量 | `pnpm test` | 10 files / 161 passed，11.90 s，18:27:10 开始；m5d-frontend-test-final.log |
| 类型 | `pnpm typecheck` | PASS，退出 0；m5d-frontend-typecheck-final.log |
| 构建 | `pnpm build` | PASS，退出 0，56 modules，420 ms；m5d-frontend-build-final.log |

聚焦和全量重复不相加。以下只汇总最终 verify 的 143 份 Surefire XML；逐文件核对修改时间
晚于本次运行日志创建时间，没有混入旧报告。全部 Reactor 与 Spring Boot repackage 均 SUCCESS。

| 最终模块 | XML | Tests | Failures / Errors / Skipped |
| --- | --- | --- | --- |
| Domain | 12 | 231 | 0 / 0 / 0 |
| Application | 65 | 777 | 0 / 0 / 0 |
| Infrastructure | 37 | 321 | 0 / 0 / 0 |
| App | 29 | 302 | 0 / 0 / 0 |
| 合计 | 143 | 1631 | 0 / 0 / 0 |

M0–M4 与 M5-A/B/C 当前确定性回归全部重跑通过。真实 Git Execution 31 项（348.1 s）、
原精确恢复 GitRollbackContent 21 项（114.2 s）、Execution API 101 项（3373 s）全部通过。
没有删除旧测试或放宽旧并发 / 权限 / 精确内容断言。M5-001 至 M5-004 原回归包含在全量中。
新增数量为后端 34、前端 6；这只是对本轮基线同一测试集的数量差，不与聚焦重复相加。
`git diff --check` 通过；测试日志 / Fixture / 数据库 / 构建产物不进入提交。

开发失败如实记录：第一次 PowerShell 未整体引用 dotted -D 参数，Maven 解析阶段失败，未运行测试；
修正后首轮真实 Git 8 项中一个新断言误把 source LF 与 WorkingCopy CRLF 直接比较，改为初始物理
WorkingCopy 快照。原恢复和全树检查断言保留。初版全 Slice 聚焦 API 13 项中一项新并发用例等待超时，
改为完成前置提案准备后再计时检查点；修正后通过，没有放宽原有授权 / 并发测试屏障。

## 7. Risks / Decision Required

- 实测 Windows / Git 2.53.0.windows.2 / Java 21；没有其他 OS、真实用户库升级、手工浏览器 E2E 或付费 Provider。
- timeout/crash/interruption/lost-result 的 Application 故障注入不等于真实 JVM / OS 断电实验；真实 Git 锁、
  部分未发布执行、属性、内容与 SQLite 故障已直接运行，其他 OS 崩溃场景未测试。
- 不支持任意不完整 patch 的破坏性恢复；不能证明归属时保持 RECOVERY_REQUIRED，不删额外文件。
- 失败 Diff 保存 identity 与技术 tree 证据，不提供原始源码/补丁历史 API。未测试外部 Git GC 后对象保留、
  大型仓库性能、持续恶意改写文件/配置、磁盘损坏；本系统不是宿主文件系统沙箱。
- 数据库拒绝全部安全检查点时无法制造持久诊断，保留最后完整检查点并返回错误；只查询，不重试。
- 无新增阻塞性业务 Decision Required。后续 Recovery 仍需裁定未决调用停止 / 所有权；本 Slice 不扩展。

日志、XML、临时 Git / SQLite、构建产物不提交。最终 Commit SHA 见交付回复及 git log -1。
完成后停止，等待独立审查，不自动开始下一 Slice。
