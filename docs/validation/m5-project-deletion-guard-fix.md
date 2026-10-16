# 最终集成审查 Minor — Project deletion guard 修复

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
