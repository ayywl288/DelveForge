# Project deletion guard — 修复与复审记录

> 第 1～5 节保留 2026-10-16 首轮 Minor 修复及其当时自验记录。该版本随后被独立复审发现
> JSON `null` 筛选绕过 Important，不能据首轮自验宣称独立复审 PASS。
> 2026-10-17 的修复、红绿证据与本轮实际验证见第 6 节；当前等待独立复审。

日期：2026-10-16（Asia/Shanghai）。Worktree：`E:\develop\DelveForge-worktrees\m5-lab`。
分支：`feature/m5-lab`；本轮基线：`73bf6b88e7eb47ba25d83d8e0109e9080d5e6d6e`。
状态：实现者定向验证 PASS，等待独立复审；不是发布验收或完整 M5 完成声明。
最终 Commit SHA 由交付消息和 Git 历史提供，避免自身提交的自引用。

## 1. 复现与根因

先读取当前 AGENTS、PRODUCT、ARCHITECTURE、DOMAIN_MODEL、ROADMAP、ADR-0001／0009、
M5-D 报告，以及 Controller → ProjectWorkspaceService → SqliteProjectContextRepository →
ProjectContextMapper 调用链、Step/WorkingCopy 持久化重建和已有删除 API／SQLite 测试。

合法的 Confirmed Execution Failure 不形成 Candidate，也没有普通 `execution_result`。
M5-D 保存 `execution_failure_result`，再保存匹配的 `automatic_rollback_result`；实际精确恢复成功后
Step 为 FAILED，WorkingCopy 的 currentRevision / lastVerifiedRevision 等于首次 baseline。
旧 SQL 把所有 `FAILED AND execution_result IS NULL` 当作未决，错误拒绝该合法终态。
User Rollback 保留已有 Candidate ExecutionResult，因此没有触发这条错误条件。

本轮先仅加真实 Spring MVC DELETE API + 隔离 SQLite 回归，未修改生产代码即执行红色复现：
旧 Plan 和已登记 Project 两个身份均得到 `409 PROJECT_DELETION_BLOCKED`，预期 200。
2 tests / 2 failures / 0 errors / 0 skipped；Maven BUILD FAILURE，退出 1。
证据：本轮 E 盘资料根中的 `red-api.log`、`red-api.xml`。
夹具使用正式值对象及数据库记录，不是真实 Git 执行／物理恢复实验证据。

## 2. 最小修复与既有语义

生产修改仅两处：

- [ProjectContextMapper](../../backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/project/ProjectContextMapper.java)：
  保留状态、缺失 Plan、损坏普通结果、WorkingCopy 状态和可信 Revision 的既有 SQL 阻塞条件；
  将 FAILED / NULL ExecutionResult 的 Plan 按原 planId／flowId 范围单独查询，交由同一事务中的检查处理。
- [SqliteProjectContextRepository](../../backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/project/SqliteProjectContextRepository.java)：
  原 no-op UPDATE 取得 SQLite 写锁之后，使用现有只读 EvolutionPlanRepository / WorkingCopyRepository
  重建完整检查点，再提交导航 CAS。未删除任何授权或未决执行检查，没有增加外部写能力。

没有在 SQL 中另写一套“恢复成功”JSON 判断。既有 StepResultJson → ExecutionFailureResult /
AutomaticRollbackResult → EvolutionStep.reconstitute 验证诊断完整性、已确认退出、原 baseline／执行起点、
failureId、精确 observedRevision、恢复结果、Repair／Retry 历史与状态之间的一致性。
只有能按该既有 FAILED 规则重建的自动回滚记录，才进入额外的绑定核对：WorkingCopy 必须存在、
sourceAssetId 匹配 Plan，且 currentRevision 与 lastVerifiedRevision 都等于该 FAILED Step 的 baseline。
缺失、无法读取或不匹配时继续返回原 `PROJECT_DELETION_BLOCKED`，不推断修复或回滚成功。

AutomaticRollbackResult 现有模型没有独立 INCONCLUSIVE 枚举；本轮不添加新语义。
测试用 `restored=false`、空观测和 INCONCLUSIVE 诊断表达未知结果：与 FAILED 矛盾的记录被重建拒绝；
合法 RECOVERY_REQUIRED 检查点同样保持阻塞。

调用链仍为原 DELETE API 和既有异常契约；仅软删除导航，全部 Step／WorkingCopy 数据不变。
未补写 ExecutionResult，未改变 Domain 状态、SQLite migration（包括 V1～V17）、API contract 或依赖。
`session.ts#deletionBlocked` 已允许无 receipt、Revision 对齐的 FAILED，没有同一错误，前端未修改。
Recovery、F1/F1b、Workspace isolation 和 M5-A～E 的执行／授权行为均未修改。

## 3. 本轮回归与故障覆盖

[ProjectWorkspaceIntegrationTest](../../backend/delveforge-app/src/test/java/com/ayywl/delveforge/app/api/project/ProjectWorkspaceIntegrationTest.java)
新增 36 个 API 执行用例，和原有 13 项一起共 49 项：

- Automatic Rollback 精确成功的 FAILED：旧 Plan／已登记 Project 两种身份允许删除；
  `execution_result` 仍为 NULL；删除前后的所有 Step／WorkingCopy 列相同。
- User Rollback 精确成功的 FAILED：同样两种身份允许删除，历史结果和可信 Revision 保留。
- 26 个故障：诊断／结果缺失或两者均缺失、损坏或空 JSON、failureId／baseline／起点不符、
  非确认退出码、空诊断或缺失时间、恢复观测不符、restored=false／INCONCLUSIVE、
  current／lastVerified／两者同时偏离 baseline、未绑定／缺失副本、资产不符、RECOVERY_REQUIRED 副本。
- 合法可重建的 EXECUTING、VERIFYING、ROLLING_BACK、RECOVERY_REQUIRED 检查点继续阻塞；
  原始未知枚举值 UNKNOWN 的不可读记录也阻塞。未决用例先通过领域重建，避免仅以无效夹具读错冒充覆盖。
- 当前导航没有 Plan、同 flow 的另一轮包含不完整自动回滚事实时，也阻止删除；版本和可见性不变。
- 原有 READY／VERIFICATION_FAILED、未验证 Candidate、损坏成功结果、过期 CAS、重复删除／恢复、
  共享资源和已废弃历史保留回归随整类执行。

[ProjectContextPersistenceTest](../../backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/persistence/project/ProjectContextPersistenceTest.java)
共 8 项，包含新增 FAILED 拒绝分支的真实独立事务回滚，确认临时 legacy visibility 行不残留；
并发删除 CAS 单赢家、同 flow 未决阻塞、新连接／Adapter 回读和既有升级保留测试均通过。

开发过程如实记录：首次修复后 49 个 API 用例中 48 项通过、1 项夹具错误；
该夹具试图 UPDATE 已不可变的 plan.flow_id，被现有 SQLite trigger 拒绝。
改为 INSERT 时绑定 flow，未禁用触发器或放宽断言。对应日志为 `green-api-persistence.log`。
随后定向 verify 151 项通过；收尾时将未决状态夹具改成可合法重建的完整检查点，再重跑同一集合通过。

## 4. 实际命令与最终结果

从本 Worktree 根运行 Maven Wrapper，离线使用本地依赖；TEMP、TMP、java.io.tmpdir 均设为：
`E:\develop\DelveForge-worktrees\m5-lab-artifacts\deletion-guard-20261016\tmp`。
MAVEN_OPTS=`-Xmx512m`；测试 shell 移除 Provider key / SPRING_APPLICATION_JSON，不输出凭据。
测试数据库使用各模块 target 下 UUID 目录，AI 使用既有 Mock／Fake，不调用付费 Provider。
本轮资料根：`E:\develop\DelveForge-worktrees\m5-lab-artifacts\deletion-guard-20261016`。

红色复现：

```powershell
.\mvnw.cmd -o '-Djava.io.tmpdir=E:\develop\DelveForge-worktrees\m5-lab-artifacts\deletion-guard-20261016\tmp' -pl backend/delveforge-app -am '-Dtest=ProjectWorkspaceIntegrationTest#exactAutomaticRollbackAllowsDeletionWithoutManufacturingExecutionResult' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

最终受影响模块回归／构建：

```powershell
.\mvnw.cmd -o '-Djava.io.tmpdir=E:\develop\DelveForge-worktrees\m5-lab-artifacts\deletion-guard-20261016\tmp' -pl backend/delveforge-app -am '-Dtest=ProjectWorkspaceIntegrationTest,ProjectContextPersistenceTest,ProjectDirectionScopeIntegrationTest,StepExecutionLifecycleTest,StepExecutionPolicyTest,ExecuteEvolutionStepUseCaseTest,SqliteEvolutionPlanRepositoryIntegrationTest,StepResultJsonCompatibilityTest,ExecutionFailureMigrationTest,RollbackMigrationTest,RetryAuthorizationMigrationTest,EvolutionFlowScopeMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' verify
```

最终日志 `final-checkpoint-verify.log`：**BUILD SUCCESS，退出 0，25.602 s，22:55:26 +08:00 完成**。
全部 Reactor 构建及 Spring Boot repackage 成功。下表仅来自该次新生成的 12 份 Surefire XML，
已逐份检查时间，独立复制到 `final-surefire/`，并记录在 `validation-summary.json`；重复运行不相加。

| 模块 | XML | Tests | Failures / Errors / Skipped |
| --- | ---: | ---: | --- |
| Domain | 2 | 37 | 0 / 0 / 0 |
| Application | 1 | 29 | 0 / 0 / 0 |
| Infrastructure | 7 | 25 | 0 / 0 / 0 |
| App | 2 | 60 | 0 / 0 / 0 |
| 总计 | 12 | 151 | 0 / 0 / 0 |

其中删除 API 整类 49 项、Project SQLite 整类 8 项；另有作用域 API、执行状态／策略／编排、
Plan 持久化、历史结果兼容、Rollback／Execution Failure／Retry／flow migration 回归。
`git diff --check` 通过。没有生产前端变更，本轮未运行前端测试／typecheck／build。
没有运行不带测试筛选的完整后端 verify，**此前 1,814 项历史 verify 不算作本次修改的覆盖**。

## 5. 剩余风险、保护与停止边界

本轮 API 是真实 Spring MVC／SQLite 集成，终态夹具为合成正式历史，不重跑 Git Execution／物理回滚、
真实浏览器、OS 崩溃、付费 Provider 或生产数据库升级。此删除守卫只读取已保存的可信恢复事实，
不重新验证物理文件，不为旧 UNKNOWN／未决调用补造停止证明；Recovery 继续延期并阻塞。

本轮无新增领域决策或 Decision Required。发布验收／最终集成审查仍需独立任务；不自行发布。
只在 feature/m5-lab 提交上述两处生产修复、两处测试与本报告。
main HEAD／原有未跟踪设计图保持；Release Tag 未改。任务开始前已存在的
`tools/spikes/m5-f1/__pycache__/ownership_spike.cpython-311.pyc` 保留，不纳入提交或清理。
无本轮遗留源码修改／测试进程；日志、UUID 测试数据库和构建产物不纳入提交。
完成本地提交后停止，等待独立复审，不 Push、不合并、不开始发布或其他 M5 功能。

## 6. 2026-10-17 Important 复审修复：JSON null 筛选绕过

本轮基线：`5663fa9ee7ef1704ec6a37bf8b314be2285c901f`，同一 E 盘 Worktree / feature/m5-lab。
本轮状态：实现者定向测试 PASS，等待独立复审。不作发布验收或完整 M5 完成声明。

### 6.1 实际调用链与红色复现

再次核对相关文档、Mapper、Repository、Domain 重建、删除 API 和 SQLite 测试：
`DELETE /api/projects/{key}` → ProjectWorkspaceService.changeVisibility →
SqliteProjectContextRepository.changeVisibility → SQLite 写锁 → SQL 状态守卫 → FAILED 检查 → 导航 CAS。
前置 service.get 只沿当前项目引用读取，不替代写锁内对同 flow 其它轮次的检查。

原 `failedWithoutExecutionPlanIds` 限定 `execution_result IS NULL`。
SQLite 中 TEXT `'null'` 非 SQL NULL，`json_valid('null')=1`；但 StepResultJson.read 经 Jackson
反序列化后为 Java null。由此，同一个领域空值在数据库原文筛选和领域读取中进入不同路径：
该 Plan 不进入事务内的重建和 WorkingCopy 绑定检查，单纯 currentRevision == lastVerifiedRevision
又不能证明二者等于 FAILED Step 的原始 baseline；当前项目的预读也不能覆盖其它轮次。

生产代码未改前新增并执行三个真实 Spring MVC DELETE / 隔离 SQLite 反例，均持久化
`execution_result='null'`，预期 `409 PROJECT_DELETION_BLOCKED`：

1. Automatic Rollback baseline 为 A，WorkingCopy current / lastVerified 同为 B。
2. WorkingCopy 的 sourceAssetId 为另一个资产。
3. 当前导航没有 Plan，同 flow 的另一个 FAILED Step 缺少 automatic_rollback_result。

实际旧版本三例均错误返回 200：**3 tests / 3 failures / 0 errors / 0 skipped，退出 1**。
红色证据为本轮资料根的 `red-api.log`、`red-api.xml`。

### 6.2 最小修复与安全边界

- Mapper 改为按 FAILED Step 状态选取其所属 Plan（`failedPlanIds`），不再根据 execution_result 的
  SQL NULL、文本或 JSON 形态决定是否需要检查；原 planId / flowId 范围不变。
- Repository 对这些 Plan 继续复用既有 StepResultJson → 各结果值对象 → EvolutionStep.reconstitute
  的完整检查点与历史校验，绑定 WorkingCopy 的 sourceAssetId 必须匹配 Plan。
  所有 FAILED Step 均核对 currentRevision / lastVerifiedRevision 与其原始 baseline 一致，
  不再以普通 ExecutionResult 是否为空决定是否核对 baseline。这与既有 FAILED 的精确恢复语义一致，
  不新定义 Automatic / User Rollback 成功标准。
- 既有 SQL 已能确定不安全时仍直接拒绝；其余 FAILED 只有通过重建和绑定核对后才可删除。
  新检查保持在原 no-op UPDATE 取得的 SQLite 写锁与导航 CAS 之间；没有新增并发窗口、外部调用、
  JSON SQL 判定规则、结果补写或数据库修复。

仅修改上述两个生产文件。Domain、StepResultJson、SQLite schema / migration、API contract、
前端、Recovery、F1/F1b 和其它 M5 功能均未修改。合法自动回滚和 User Rollback 的删除能力保留；
无法重建、缺失或矛盾的结果及未知检查点继续 fail-closed。

### 6.3 本轮新增回归与实际执行

新增 API 执行用例 17 项，整类合计 66 项：上述三个审查反例转绿；合法 JSON null / 带空白 null
两项仍可删除且原持久化字节不变；另有 12 项同 flow 其它轮次夹具，覆盖 SQL NULL、JSON null、
带空白 null、空对象、空数组、含 null 的数组、布尔、数字、字符串 `"null"`、空串、损坏 JSON、
尾随额外 token。它们不能隐藏 baseline 不匹配或绕过检查点重建，拒绝后导航版本、可见性与历史不变。
原合法 Automatic / User Rollback、26 项诊断／恢复／Revision 故障、RECOVERY_REQUIRED、
EXECUTING / VERIFYING / ROLLING_BACK / UNKNOWN 等原回归完整保留。

SQLite 整类 11 项：新增 `'null'`、`'{}'`、`'[]'` 三种非 SQL NULL 表示的独立事务回归，
验证非法 FAILED 不能提交 legacy visibility 行，且 execution_result 保持原样；原并发 CAS 单赢家、
同 flow 阻塞、回读与迁移测试保留。

本轮资料根：`E:\develop\DelveForge-worktrees\m5-lab-artifacts\deletion-guard-json-null-20261017`。
TEMP / TMP / java.io.tmpdir 为其 `tmp` 子目录；MAVEN_OPTS=`-Xmx512m`；
测试 shell 移除 Provider key / SPRING_APPLICATION_JSON，使用既有 Mock/Fake 与 UUID 测试数据库，
未调用付费 Provider、读取真实业务数据库或修改真实 WorkingCopy。

从 Worktree 根执行红色复现：

```powershell
.\mvnw.cmd -o '-Djava.io.tmpdir=E:\develop\DelveForge-worktrees\m5-lab-artifacts\deletion-guard-json-null-20261017\tmp' -pl backend/delveforge-app -am '-Dtest=ProjectWorkspaceIntegrationTest#jsonNullExecutionCannotBypassFailedCheckpointOrWorkingCopyBinding' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

修复后的最终定向回归：

```powershell
.\mvnw.cmd -o '-Djava.io.tmpdir=E:\develop\DelveForge-worktrees\m5-lab-artifacts\deletion-guard-json-null-20261017\tmp' -pl backend/delveforge-app -am '-Dtest=ProjectWorkspaceIntegrationTest,ProjectContextPersistenceTest,ProjectDirectionScopeIntegrationTest,StepExecutionLifecycleTest,StepExecutionPolicyTest,ExecuteEvolutionStepUseCaseTest,SqliteEvolutionPlanRepositoryIntegrationTest,StepResultJsonCompatibilityTest,ExecutionFailureMigrationTest,RollbackMigrationTest,RetryAuthorizationMigrationTest,EvolutionFlowScopeMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

`targeted-test.log`：**BUILD SUCCESS，退出 0，28.913 s，2026-10-17 11:28:12 +08:00 完成**。
这是 Maven test 阶段，包含所需编译与测试，不是完整 verify 或打包证据。
逐份核对本轮新生成的 12 份 Surefire XML，并归档到 `final-surefire/`；
`validation-summary.json` 包含文件时间、哈希及计数，`targeted-test-exit.txt` 保存退出码。

| 模块 | XML | Tests | Failures / Errors / Skipped |
| --- | ---: | ---: | --- |
| Domain | 2 | 37 | 0 / 0 / 0 |
| Application | 1 | 29 | 0 / 0 / 0 |
| Infrastructure | 7 | 28 | 0 / 0 / 0 |
| App | 2 | 77 | 0 / 0 / 0 |
| 本轮合计 | 12 | 171 | 0 / 0 / 0 |

其中删除 API 66 项、Project SQLite 11 项；另外覆盖作用域 API、执行状态／策略／编排、
Plan 持久化及历史结果兼容、相关 migration。未将前一轮 151 项或历史 1,814 项当作当前覆盖。
`git diff --check` 通过。按本轮边界未重跑完整后端 verify、前端测试／typecheck／build。

### 6.4 风险与停止边界

API 为真实 Spring MVC / SQLite 集成，终态使用合成正式历史；没有重跑物理 Git 恢复、真实浏览器、
OS 崩溃或生产升级。现有 Domain 能解释的合法 null 表示可在完整重建和绑定匹配后删除；
没有新增原文正规化规则或完整 JSON 解释器。安全判断不再依赖 execution_result 的原文筛选。

本轮无新增 Decision Required。只提交本次两个生产守卫、两个测试和本报告；原有未跟踪
`tools/spikes/m5-f1/__pycache__/ownership_spike.cpython-311.pyc` 保留，不清理、不提交。
main、其它 Worktree、Release Tag、真实数据不改；不 Push、不合并、不开始发布验收。
最终 Commit SHA 由 Git 与交付消息给出，提交后停止等待独立复审。
