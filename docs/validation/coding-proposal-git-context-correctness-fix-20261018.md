# CodingProposal / Git 上下文契约 correctness 修复 — 2026-10-18

## Changes

审查基线为本地 main `0f94eb89b8fd091ef9b391b70e88048553579624`。
本次仅修复提案生成指令与静态统一补丁校验之间的接受契约；未提交 Git。

- `PrepareEvolutionStepUseCase`：示例改为带上下文的补丁，说明局部变化通常保留前后各三行真实上下文；
  文件首尾按实际行数保留。未到旧文件末尾的 hunk 必须有尾部上下文。
- `UnifiedDiffValidator`：逐 hunk 跟踪最后一行是否为 context。已有文件的修改若没有尾部上下文，
  必须真正到达旧文件末尾，否则在返回 `ValidatedExecutionProposal` 前拒绝。
- 独立审查补正：新建／整文件删除仅接受单 hunk；Prompt 同步该限制。已有文件合法多 hunk 修改仍保留。
- 新增 Application 契约回归与 Infrastructure 真实受控 Git 对照测试；准备用例验证非法提案不返回、
  不冻结 baseline、不产生执行结果、不推进可信 revision。
- 两份既有测试的正向夹具补齐真实上下文，保留原有多 hunk 偏移检查、预算超限和全部状态断言。

生产改动仅有上述两个 Application 文件；没有改变 Git 应用参数、Domain、Migration、状态机、
Recovery、UI、依赖或验证标准。

## Reason

原始诊断为 `logs/step-retry-20261018-155108/diagnosis.md`，标题
`Current Step Retry Diagnosis — 2026-10-18`。已核对归档原补丁与隔离复现结果。
原补丁 SHA-256：`30e801e2afd8b93e50e62654c35d8f5781cf4b5c5c60490e3a2b2089f4ba9743`。

原 Prompt 鼓励单个局部 hunk，未约束上下文。`UnifiedDiffValidator` 检查完整旧内容、坐标和行数，
但不要求上下文。真实 `GitWorkspaceAdapter.applyCandidate` 在 `EXPECTED_TREE` 使用受控临时 index，
依次执行 `read-tree`、`apply --cached --whitespace=nowarn` 和 `write-tree`，不启用宽松补丁选项。

本次 `@@ -27,3 +27,1 @@` 的删除内容与基线逐字一致，但该局部 hunk 没有尾部上下文，且未到旧文件末尾。
Git 拒绝并返回 exit 1。仅添加真实上下文的副本可通过相同 Git 检查。

额外在全新合成 Git 仓库中核对了 12 种边界：没有尾部上下文时，当前受控 Git 会按文件尾匹配。
因此没有简单禁止全部零上下文补丁；合法文件尾修改、整文件替换、末尾追加、新建及完整删除仍保留。
生成指令中的“三行”是当前生成建议，校验器并不把三行设为统一最低要求。

## Verification

此前上下文修复的证据根：`logs/coding-proposal-context-fix-20261018-164128/`。
下列红测、99 项定向及 1,936 项完整回归均属于此前候选；本次独立审查补正的验证单列于后文，不能互相替代。
红测／定向快照：`target/coding-proposal-context-fix-20261018-164128/candidate/`；
完整回归快照：`target/cf164128/`。
使用仓库 Maven Wrapper、离线依赖及该目录下的专有 TEMP/TMP、JVM 临时目录；
没有覆盖正常后端 JAR，也没有重启用户服务。

### 先红后绿

修复前仅加入 `UnifiedDiffGitContractTest`，执行：

```powershell
.\mvnw.cmd -o -pl backend/delveforge-application -am `
  -Dtest=UnifiedDiffGitContractTest -Dsurefire.failIfNoSpecifiedTests=false `
  -DargLine=-Djava.io.tmpdir=E:/develop/DelveForge/target/coding-proposal-context-fix-20261018-164128/tmp test
```

原生退出码 1；20 项中 7 失败、0 错误、0 跳过，13 项通过。
失败包括第 27 行具体反例的 LF 与 CRLF、仅前文无尾部上下文、文件开头、插入及多 hunk 的不兼容形式。
失败原因均为旧校验器没有抛出应有的拒绝。原始日志与 XML 保留为 `red.log`、`red-reports/`。

修复后定向验证：

```powershell
.\mvnw.cmd -o -pl backend/delveforge-infrastructure -am `
  -Dtest=UnifiedDiffGitContractTest,CodingProposalValidationTest,ExecutionPreparationUseCaseTest,ExecuteEvolutionStepUseCaseTest,GitPatchContextContractIntegrationTest `
  -Dsurefire.failIfNoSpecifiedTests=false `
  -DargLine=-Djava.io.tmpdir=E:/develop/DelveForge/target/coding-proposal-context-fix-20261018-164128/tmp verify
```

原生退出码 0，BUILD SUCCESS；99 项通过，0 失败／错误／跳过：

| 测试类 | 数量 |
| --- | ---: |
| UnifiedDiffGitContractTest | 20 |
| CodingProposalValidationTest | 14 |
| ExecutionPreparationUseCaseTest | 20 |
| ExecuteEvolutionStepUseCaseTest | 29 |
| GitPatchContextContractIntegrationTest | 16 |

新测试覆盖合法局部上下文、仅一行尾部上下文、文件首尾、整体替换、末尾追加、空文件、新建、
完整删除、无末尾换行及 CRLF。真实 Git 对照使用合成仓库和生产的受控命令执行方法，
不把模拟 Git 结果当作集成证据。详见 `targeted-results.json`、`targeted-reports/`。

另用归档完整补丁、归档隔离仓库中的不可变基线文本，以及修复前／后两个校验器做独立检查：
原始 14,401 字节补丁由“接受”变为明确拒绝，添加上下文的归档副本前后均接受。
该检查只读归档证据，不调用 Provider，也不操作实际 WorkingCopy。
结果见 `archived-patch-before-after.json`。

### 完整后端回归

首次较长快照路径中的回归在属性安全测试夹具提交时出现 `fatal: could not parse HEAD`：
91 项均为夹具错误，尚未进入本次校验逻辑。该轮中断后，`long-path-results.json` 按该次运行筛选的
104 份已完成测试类 XML 合计 1,325 项、0 失败、91 错误、0 跳过。归档 `long-path-reports/`
实际有 105 份 XML，总计 1,341 项；额外 16 项来自此前定向运行残留的
`GitPatchContextContractIntegrationTest`，该 XML 与 `targeted-reports/` 的对应文件逐字节相同。
因此 1,325 是筛选后的该次运行数量，1,341 是含旧报告的归档目录总量；不是中断前后新完成了 16 项。
该轮不完整，工具返回 1，未取得 Maven 正常结束的原生退出码。原日志、XML 和原汇总均保留，
本次只更正文档口径。对账证据见本次补正证据根的 `historical-count-reconciliation.json`。

未改代码、测试断言、Git 全局选项或系统配置，改用仓库内短路径 `target/cf164128/`，
785 个文件逐项哈希与原受测快照相同。该路径下 91 项属性安全测试已全部通过，确认首次错误与夹具路径有关。
短路径完整验证实际命令：

```powershell
.\mvnw.cmd -o -DargLine=-Djava.io.tmpdir=E:/develop/DelveForge/target/cf164128/tmp verify
```

2026-10-18 18:24:12（北京时间）正常结束，原生退出码 0，BUILD SUCCESS。
四个模块全部测试、打包通过，Spring Boot repackage 成功；此前该轮总计 **1,936 项通过，0 失败／错误／跳过**。

| 模块 | 测试数 | 失败／错误／跳过 |
| --- | ---: | --- |
| delveforge-domain | 240 | 0 / 0 / 0 |
| delveforge-application | 801 | 0 / 0 / 0 |
| delveforge-infrastructure | 489 | 0 / 0 / 0 |
| delveforge-app | 406 | 0 / 0 / 0 |

数量从该轮四个模块的 147 份 Surefire XML 汇总，不使用历史 1,896 或其它旧数字代替。
定向 99 项与完整 1,936 项属于不同轮次，不能相加作为独立测试数量。
完整日志、退出码、XML 和汇总分别为 `short-regression.log`、`short-regression-exit.txt`、
`full-reports/`、`full-regression-results.json`。

完整回归耗时约 1 小时 30 分钟，其中应用模块约 1 小时 15 分钟。
长时间运行中只对已确认属于本轮隔离测试的 JVM 做了两次线程采样，分别观察到 Git 基线核验读取与
合成 SQLite 事务提交；未连接用户后端，也未改变超时、断言、测试选择或系统设置。
该轮最终正常结束，未将长时间运行或日志中的预期异常堆栈直接解释为测试成功。

### 独立审查补正：新建／整文件删除的多 hunk（2026-10-18）

独立审查结论为 Gate FAIL（Critical 0、Important 1、Minor 1）。本次在上述未提交修复上做最小补正，
等待独立复审，不将此前 Gate 自动改为 PASS。main / HEAD 仍为 `0f94eb89b8fd091ef9b391b70e88048553579624`。

按审查描述重建的最小反例（路径示意，LF / CRLF 正文均覆盖）：

```diff
--- /dev/null
+++ b/src/New.java
@@ -0,0 +1 @@
+one
@@ -0,0 +2 @@
+two
```

```diff
--- a/src/A.java
+++ /dev/null
@@ -1 +0,0 @@
-one
@@ -2 +0,0 @@
-two
```

删除反例的完整旧内容为 `one\ntwo\n`（另测 CRLF）。此前校验器接受这两类补丁：坐标、累计偏移、
内容及总行数均可满足，但真实受控 Git 拒绝。补正后，当任一路径为 `/dev/null` 时要求恰好一个 hunk，
并保留原有全部校验；既有文件修改不受该新增限制。没有修改 Git 参数或将静态接受当作 Git 成功证明。

证据根：`logs/coding-proposal-file-lifecycle-fix-20261018/`；全新短路径快照：`target/cl1018/`。
快照的 785 份文件逐字节复制并记录 SHA-256；TEMP、TMP 和 JVM 临时目录均位于该快照的 `tmp/`。
原草稿保存在 `before/`，补正前后清单分别为 `red-source-manifest.json`、`green-source-manifest.json`。

先只增加测试、保留此前生产校验器，执行下列命令（末尾使用 `test`）：

```powershell
.\mvnw.cmd -o -pl backend/delveforge-infrastructure -am `
  -Dtest=UnifiedDiffGitContractTest,CodingProposalValidationTest,ExecutionPreparationUseCaseTest,ExecuteEvolutionStepUseCaseTest,GitPatchContextContractIntegrationTest `
  -Dsurefire.failIfNoSpecifiedTests=false `
  -DargLine=-Djava.io.tmpdir=E:/develop/DelveForge/target/cl1018/tmp test
```

红测原生退出码 1：Application 执行 93 项，8 失败、0 错误、0 跳过，其余 85 项通过。
其中 4 项静态测试因校验器未拒绝而失败，4 项执行入口测试因提案进入后续验证能力检查而失败；
均为新建／删除 × LF／CRLF。Application 失败后 Infrastructure 被 Maven 跳过，红测未执行真实 Git 对照。
原始证据为 `red.log`、`red-exit.txt`、`red-reports/` 和 `red-results.json`。

加入最小生产补正后，同一选择器以 `verify` 替换末尾 `test` 运行；完整命令保存在 `green-command.txt`。
2026-10-18 21:47:02（北京时间）正常结束，原生退出码 0，BUILD SUCCESS；
Domain / Application / Infrastructure 编译打包通过，**119 项通过，0 失败／错误／跳过**：

| 测试类 | 本次数量 |
| --- | ---: |
| UnifiedDiffGitContractTest | 26 |
| CodingProposalValidationTest | 14 |
| ExecutionPreparationUseCaseTest | 24 |
| ExecuteEvolutionStepUseCaseTest | 29 |
| GitPatchContextContractIntegrationTest | 26 |

真实 Git 对照在合成仓库的独立 index 上调用生产受控命令方法：`read-tree` →
`apply --cached --whitespace=nowarn`，成功时另核对 `write-tree`。
新增的两类多 hunk 补丁在 LF / CRLF 下均返回原生 Git exit 1；同内容单 hunk 的新建／删除、
已有文件合法多 hunk 修改均返回 exit 0。每个测试同时核对真实 HEAD 和原 index / 工作树未变。
原上下文、文件首尾、CRLF、精确正文与 Scope 等相关回归继续通过。

执行入口测试通过真实 `ExecuteEvolutionStepUseCase.execute(planId, stepId)` 进入准备流程，使用确定性
Gateway 和 Port 替身。断言 `PROPOSAL_VALIDATION` 拒绝、检查点提交计数为 0、原 Plan / WorkingCopy
对象未替换、Step 仍 READY、baseline 仍为空、无结果、current / lastVerified / HEAD 保持 trusted、
文件未变，且未进入后续验证、mutation 或 rollback Port。未增加新的状态转换。

本次日志、退出码、5 份 XML 及汇总为 `green.log`、`green-exit.txt`、`green-reports/`、`green-results.json`。
此前 1,936 项完整后端回归未在本次补正后重跑；119 项不能作为新的全后端或发布验收结论。
App、前端、真实 Provider E2E 未重跑。本次不操作已成功的真实 Step / WorkingCopy，也不重启用户服务。

## Risks

本次静态检查针对已证明的上下文／文件尾匹配缺口，不声称能提前捕获所有运行时 Git 拒绝。
完整旧内容、严格 hunk 坐标与行数、Scope、净化、真实源文本、属性安全、Revision 和实际 tree 检查均保留。
没有自动补写模型的补丁，也没有新增宽松 Git 参数。

没有再次 Retry、Execute、Repair、调用付费 Provider 或修改实际 Plan、数据库和 WorkingCopy。
真实历史失败不会因本次源码修改自动变为成功。未重跑真实 Provider 演化或前端测试；UI 未改动。
本轮未替换或重启当前运行的后端，源码修复需要后续重新构建／启动才会用于真实请求。
本轮修复不证明业务提案能够通过构建或业务验收，也不改变 M5 PARTIAL 与 Recovery 延期边界。

## Follow-ups

等待用户审阅本次修复和验证报告。暂不提交、Push、发布或创建 Tag；不继续真实演化。
