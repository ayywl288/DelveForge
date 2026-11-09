# 无进展与保留生命周期评审修正（2026-11-08）

## 执行边界

检查时为 `main` / `c273d96` 加上一轮未提交改动。本次保留其他改动，没有提交、合并或推送。
用户明确告知真实 Step 正在运行：本次没有调用 Docker、运行启动脚本、访问运行数据库或操作活动资源；没有重建原仓库的 target/JAR、替换服务或调用真实模型。

为执行安全测试，仅复制四个模块的源码、POM 和 Maven Wrapper 到独立目录：
`C:\Users\28226\.codex\visualizations\2026\11\08\01a1bba9-9a3d-7ad2-9813-fb052bff3ef6\progress-retention-validation`。
构建与测试产物只写入此副本及测试自身的临时目录。SQLite 测试使用临时数据库，沙箱 Port 为替身；Maven 使用离线模式。

## 发现与修正

1. **无进展漏洞已确定性复现。** 原代码把 stdout/stderr 与完整原始诊断哈希纳入精确比较。同一错误仅改变时间戳、Total time、Finished at 和 Progress 行，第三次不抛 NO_PROGRESS，第二次也不警告。修正前新增的两项回归均失败。
2. 新增 Application 所有的 `AgentCommandDiagnostics`，只处理常见时间戳前缀、明确耗时字段、结束时间和纯数字进度行，不建立命令分类器。错误文本、严重级别、断言数值、源码位置及嵌入诊断正文的日期仍保留。
3. `DockerAgentSandbox` 在预览截断前生成 `progressDiagnosticDigest`；原始输出和原始 `diagnosticDigest` 不变。`AgentProgress` 使用比较摘要而非可能被噪声移动截断位置的预览；缺少比较摘要时对文本使用同一规则。原始摘要不再影响循环判定。既有两次重复警告、三次重复停止、源码变更重置保持。
4. **旧 Docker 恢复断言确实过时，但生产保留逻辑无需改成删除。** 原 `ordinaryFailureCleansResourcesAndAllowsFreshReservation` 在进入 RUNNING 后没有成功捕获证据，却期待 retained=false 和目录删除。替换为先产生实际修改、失败并重启后保留原始文件、无 Candidate、确认停止后允许新预约的用例。另补成功 partial 捕获后清理运行资源但保留 partial 的真实 Docker 用例。

## 保留生命周期结论

以下来自活动代码审查及真实 SQLite/替身沙箱的确定性测试，Docker 文件/卷行为仅完成静态检查与测试编译。

| 场景 | 预期结果与依据 |
| --- | --- |
| PREPARING 中失败，尚未允许 Agent 写入 | retained=false；停止、清理、释放。既有预检与重启测试覆盖。 |
| RUNNING 中失败，partial 未成功、返回 null 或重启时无法确认捕获 | RUNNING 事务已置 retained=true；先停止，保留原始材料，无 Candidate；停止确认后释放占用，允许新 Run。 |
| 普通失败且成功捕获 partial | 停止写入并确认持久终态后清理可丢弃资源；保留 partial、日志及 retained=true，不成为 Candidate。 |
| 停止失败或终态持久化失败 | 不清理唯一现场、不释放占用；后续协调必须重新确认停止及持久状态。 |
| partial 已捕获并清理资源，但 release 持久化失败 | 保留 partial 与占用；重启停止后释放，不再次清理 retained 材料。新增 SQLite trigger 故障注入测试覆盖。 |
| 成功冻结 Candidate | freeze 事务解除原始材料保留；正常清理，Candidate 持久记录独立保留。 |

`SqliteAgentStore.release(false)` 使用 MAX，不会抹掉此前的保留事实。FAILED 只是失败终态，不是可删除证明。
`CodingAgentService.close` 只处理其自身 worker；协调不恢复旧模型对话。未改变 FINALIZING 的 NO_CHANGES 语义。

## 本轮文件

- `backend/delveforge-application/.../agent/AgentCommandDiagnostics.java`（新增）：比较归一化及完整比较摘要。
- `backend/delveforge-application/.../agent/AgentProgress.java`：比较用副本处理，保持停止/警告门槛。
- `backend/delveforge-infrastructure/.../agent/DockerAgentSandbox.java`：保留原始摘要，同时生成比较摘要。
- `AgentCommandDiagnosticsTest.java`、`AgentProgressTest.java`：噪声、真实新错误、预览截断、原记录保持及回退路径。
- `CodingAgentServiceTest.java`：警告到达模型且活动记录仍含原时间；成功捕获清理顺序；重启/新预约；release 持久化失败。
- `SqliteAgentStoreTest.java`：RUNNING 保留意图跨重载/失败/release(false) 保持。
- `DockerRetentionRecoveryTest.java`：修正未知捕获时的保留期望，增加成功 partial 清理/重启用例。**未执行。**
- ADR-0027 与本验证记录。未修改生产保留状态迁移、内容政策、完成合同、冻结或 Delivery。

## 实际执行的命令与结果

下列 Maven 命令全部在上述独立目录运行，使用 `JAVA_HOME=E:\develop\jdks\jdk-21.0.12.1`。

```powershell
# 修正前：只新增两项噪声回归，验证漏洞。
.\mvnw.cmd -o -pl backend/delveforge-application -am '-Dtest=AgentProgressTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

# 同步本轮修正到独立副本后：显式禁用 Docker，执行确定性测试及受影响模块构建。
$env:DELVEFORGE_AGENT_DOCKER='false'
.\mvnw.cmd -o -pl backend/delveforge-infrastructure -am '-Dtest=AgentProgressTest,AgentCommandDiagnosticsTest,CodingAgentServiceTest,SqliteAgentStoreTest' '-Dsurefire.failIfNoSpecifiedTests=false' verify
```

- 修正前 `progress-before.log`：7 项，2 失败、0 错误；分别为未停止和未警告，其余 5 项通过。
- 修正后 `review-after.log`：111 项，0 失败、0 错误、0 跳过。Diagnostics 2、Progress 9、CodingAgentService 89、SqliteAgentStore 11。Domain/Application/Infrastructure 编译及打包成功。
- `git diff --check` 通过。本轮没有运行完整后端、前端或真实端到端验收；上一轮测试结果不是本轮补充验证的替代品。

## 延期与剩余不确定性

**DockerRetentionRecoveryTest 实际执行延期。** 用户的真实 Step 仍按活动状态处理，没有以时间流逝推断完成，也没有为判断状态访问其运行环境。测试已编译，但没有声称容器、卷、停止或真实 partial 清理用例通过。
等待明确可安全使用 Docker 后，需在独立测试环境中执行该类；本轮没有执行这一步，也没有启动另一个真实 AgentRun。

归一化是有限规则：未知格式的动态噪声可能仍被视为新诊断；不会抹掉所有数字或自由文本以追求重复判定。保留完整日志供解释。
当前活动 Step 继续使用其已加载的代码，本次修改未部署进该运行。

## 后续复核：断言正文中的 Duration

独立副本复现了审查中的反例。两条 `[ERROR] expected Duration: 2 s but was Duration: …` 日志中的实际值分别为 `3 s` 与 `4 s`，旧摘要相同。先重复前一条两次，再返回后一条，第三次触发了 NO_PROGRESS。

`AgentCommandDiagnostics` 现仅识别整行 `Total time` 或带测试计数的 `Tests run` 汇总中的耗时。`AgentCommandDiagnosticsTest` 和 `AgentProgressTest` 覆盖了断言数值变化、新错误清除重复警告，以及同一新错误再次重复后按原门槛停止。比较摘要覆盖采集上限以内、预览截断之前的输出。

以下命令均在前述独立副本中运行。

```powershell
.\mvnw.cmd -o -pl backend/delveforge-application -am '-Dtest=AgentCommandDiagnosticsTest,AgentProgressTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
$env:DELVEFORGE_AGENT_DOCKER='false'
.\mvnw.cmd -o -pl backend/delveforge-infrastructure -am '-Dtest=AgentCommandDiagnosticsTest,AgentProgressTest,CodingAgentServiceTest#noisyCommandLoopWarnsModelThenStopsWithoutChangingRecordedOutput' '-Dsurefire.failIfNoSpecifiedTests=false' verify
```

修正前 `duration-before.log` 共 14 项，其中 1 项断言失败、1 项意外抛出 NO_PROGRESS。修正后 `duration-after.log` 共 15 项全部通过，受影响模块构建成功。此前记录的 111 项属于上一轮运行。

Docker 恢复套件继续延期。成功捕获 partial 的 Docker 用例在调用 reconcile 前已手动清理并释放占用，只覆盖此顺序下的材料保留。真实服务在清理或释放中途崩溃后的 Docker 恢复链仍待验证。

## Surefire 方法失败行复核

在同一独立副本中，使用包含测试汇总、方法失败行、断言正文、堆栈位置和构建失败结果的完整命令输出。仅改变耗时，第二轮从 `0.003 s` 变为 `0.004 s`，旧代码未发出重复警告。`surefire-method-before.log` 记录 16 项中 2 项失败。

新增规则只匹配标明方法名、`-- Time elapsed` 和行末 `<<< FAILURE!` 或 `<<< ERROR!` 的完整行。测试核对方法名、参数化用例编号、失败类型和断言正文，并验证相同失败在第二轮警告、第三轮触发 NO_PROGRESS。

本轮在独立副本以 Docker 禁用、Maven 离线模式运行以下两条命令。

```powershell
.\mvnw.cmd -o -pl backend/delveforge-application -am '-Dtest=AgentCommandDiagnosticsTest,AgentProgressTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -o -pl backend/delveforge-infrastructure -am '-Dtest=AgentCommandDiagnosticsTest,AgentProgressTest,CodingAgentServiceTest#noisyCommandLoopWarnsModelThenStopsWithoutChangingRecordedOutput' '-Dsurefire.failIfNoSpecifiedTests=false' verify
```

修正后 `surefire-method-after.log` 记录 Diagnostics 5 项、Progress 11 项、服务层 1 项全部通过，受影响模块构建成功。此前的 111 项属于历史验证。本轮 Docker 恢复套件继续延期。

## 本地归并时的 Docker 恢复补跑

归并前只读检查显示应用数据库中被占用的 Run 和 Delivery 均为 0，Docker 当时没有运行中的容器。恢复套件使用已有的 JDK 21 测试镜像、独立的测试缓存、每例独立的 Run ID、临时 Git 仓库和 SQLite 数据库。在独立源码副本执行：

```powershell
$env:DELVEFORGE_AGENT_DOCKER='true'
$env:DELVEFORGE_AGENT_IMAGE_21='delveforge-agent:repository-input-v10-test'
.\mvnw.cmd -o -pl backend/delveforge-infrastructure -am '-Dtest=DockerRetentionRecoveryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

`consolidation-recovery.log` 记录 5 项通过，0 失败、0 错误、0 跳过。断言覆盖停止与持久化故障后的重启协调、冻结 Candidate 的清理、捕获不确定时源卷中仍可读取修改，以及成功 partial 清理后保留差异与元数据。成功 partial 用例在清理和释放后才调用 reconcile。测试通过受控故障注入建立中断点，未执行真实服务进程强杀或业务端到端验收。

同次归并在短路径临时副本运行 `mvnw.cmd -o verify`。各后端模块合计 1,973 项，通过 1,918 项，条件跳过 55 项，失败和错误均为 0，构建成功。默认跳过的用例中包含上面单独通过的 5 项 Docker 恢复测试。先前较长路径的一次尝试遇到 Git 的 `Filename too long` 后中止。短路径副本通过了该测试类的全部 6 项。

独立前端副本运行 `pnpm test` 和 `pnpm build`，23 个测试文件中的 365 项全部通过，类型检查和构建成功。Python 沙箱测试首轮通过 54 项、条件跳过 7 项，随后启用本地镜像单独运行这 7 项，全部通过。
