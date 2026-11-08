# Agent Loop Convergence：实施、验证与中文交接

## 版本和操作范围

- 本地分支：`codex/agent-loop-convergence`；实际起点为 `main` / `e80fda0a21a628730d5f0677d461dff36290e9e7`，未回退到历史版本。
- 本报告随实现提交；最终提交身份以 `git log main..HEAD --oneline` 为准。没有合并、推送或重启正式应用。
- 开始时仅有 `codex-migration/`、`frontend/design/`、`grilling/` 三个未跟踪目录，全部保留、不纳入提交。
- 使用临时仓库、临时 SQLite、隔离 Docker 和确定性模型替身；未修改所有者 live DB、目标项目、原资产、正式 Working Copy 或旧失败 Run。
- **付费模型：NOT RUN。真实用户项目/真实模型产品 E2E：NOT RUN。** 工程回归不等于模型任务成功率或目标环境已就绪。

## 四条工作线

1. **外部 Plan/Step 与内部笔记**：上游 Product Direction、RepositoryProfile、Plan 生成/审阅和逐 Step 授权不变。新增可选 `update_plan({notes})`；仅替换 Run 内最新工作笔记并记录既有事件，依据实际仓库记录发现、进度、阻塞和下一步。没有独立计划阶段、审批、笔记完成门槛或新的进展计数器。笔记不改变源码快照，不传入 Workspace，不清空源码无进展历史。
2. **提示与上下文**：压缩 SYSTEM，删除旧义务和冗余进展计数提示。任务、工具合同、环境与工作记忆分开；保留真实仓库检查、原生命令、调查失败、测试诚信和不可信数据边界。修复 `contextFact` 只读旧 `output` 丢失 `stdout/stderr` 的缺陷；保留诊断、invocationId、日志页位置/引用、源码身份及 Agent 归因说明。按最近观察保留，不再将旧 FAILED 排到所有新结果前；完整保留 assistant/tool 配对，最新笔记在压缩后单独注入一次。
3. **执行、日志与界面**：`exec_command` 只接收命令、目录和时限；输出实际进程终态、耗时、源码变化、执行视图和日志引用。新增 `read_log` 与对应只读 HTTP 接口；日志在 Candidate 之外、跨调用及清理后可读。去除终端控制序列，沿用凭据净化，Vue 按文本显示。提前验证已知沙箱/工件代理基础设施，但不宣称未知仓库依赖已准备。已证明未开始读写的普通路径拒绝可纠正，保留原 POLICY_REJECTED 分类；真实隔离/归属/清理不确定仍停止。命令行紧凑展示，单一详情区统一查看命令和双流/翻页日志；保留用量、提供者失败/重试、旧工具事件和未交付成果，没有重复成功请求清单。
4. **事实完成与独立 Delivery**：活动 v7 删除 command+cwd 的 `requiredChecks.putIfAbsent` 永久义务、scope/kind 注册、失败用例必须同名重过、测试清单/数量机械限制和 `withheldSourceCount>0` 一票否决。报告只引用具体执行，平台独立查验收据、最终源码和执行视图；失败历史保持失败。没有增加 Verification Plan、业务 DSL、check-ID 注册表、监督 Agent、语言适配框架或审批。冻结与自动 Delivery 的授权、受保护资源、diff 重放、可信 revision 和事务边界保持。

## 最终合同

| 项目 | 当前合同 |
| --- | --- |
| 活动工具 | `list_files / search_code / read_file / edit_file / update_plan / read_log / exec_command`；旧事件和 v1–v6 证据按旧版本解释，不追认旧失败 |
| 笔记 | `notes` 非空、最多 4000 字符，低上下文配置至多上下文四分之一；可不使用，可留未勾选项，不是验证事实，不支持恢复旧 Run |
| 命令 | `command`、相对 `directory`（默认 `.`）、可选 `timeout_seconds`；不解析或改写任意 Shell；管道的退出 0 不证明前置程序/业务要求通过 |
| 跨调用状态 | 源码、项目目录依赖和 Run `/m2` 缓存保留；cwd、环境变量、进程、`/tmp` 不保留；npm cache 和 pnpm store 在 `/m2` 下 |
| 日志 | 单流模型预览最多 3000 字符，默认预算下 1500；stdout 捕获最多 256 KiB、stderr 64 KiB；Docker 轮转最多 2×4 MiB，Run 日志文件总上限 16 MiB；明确区分预览截断与保留丢弃 |
| 读取日志 | `read_log(invocation_id, stream, offset, limit)`；stream 为 stdout/stderr/command，字符 offset，默认 4000、最多 8000，低工具输出预算可进一步缩小；通过 nextOffset/hasMore 翻页，不接受宿主路径 |
| 清理/失败保留 | 平台 Run 目录 `command-logs/<invocationId>.json` 不挂载项目容器；命令诊断先于源码捕获保留。清理仍释放容器/网络/Run 缓存，保留有界净化日志和安全捕获的 partial.diff/partial-metadata.json。部分成果为 UNVERIFIED_NON_DELIVERABLE |
| 执行视图 | FULL/PROJECTED、保留/只读/withheld 数量与 executionBaselineTree；私密资源始终不可访问，原 blob 经捕获/Delivery 保留。投影差异要披露，不自动否决；实际缺输入仍按真实失败报告 |
| 完成报告 | 现有结束 JSON：`status, summary, validationComplete, executions, limitations`。summary/limitations 属 Agent 判断。必要验证未完成须 `failed` / `validationComplete:false`，不可冻结 |
| 平台核验 | 引用执行存在且属于本 Run，无重复，实际退出 0、未超时/取消，前后快照均为最终快照，视图匹配，已知报告无失败；不能用模型自报的退出码/数量代替平台收据。摘要/笔记单独不足以完成 |
| Delivery | 合格结果按既有路径冻结，独立重验授权、源码捕获/受保护资源和 diff，正常自动交付，不增加人工批准。没有从失败 Run 升级 Candidate 的捷径 |

## 重要实现与测试入口

- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/agent/CodingAgentService.java`：`SYSTEM`、`executionContext`、`loop`、`compress`、`compactedSummary`、`contextFact`、`readLog`；对应 `AgentContextConvergenceTest` 和 `CodingAgentServiceTest` 的笔记、路径拒绝、压缩和协议回归。
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/agent/AgentTools.java`：`definitions/validate`；`AgentToolActivity.finished` 持久化命令引用和已知拒绝事实；`AgentCheckEvidence.requireAcceptable` 按版本分派；新增 `CompletionEvidence.requireAcceptable` 核验 v7，测试 `CompletionEvidenceTest`。
- `backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/agent/DockerAgentSandbox.java`：`executeCommand`、`preflight/checkCapabilities`、`completionFeedback/evidence`、`freezeTree/cleanup`；新增 `CommandLogs.save/read/readable/preview`，测试 `CommandLogsTest`。
- `backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/agent/CodingAgentController.log`：`GET /api/agent-runs/{id}/logs/{invocationId}`；`CodingAgentApiTest.retainedLogsArePagedAsJsonWithoutRequiringALiveSandbox` 使用临时数据验证 JSON 翻页、边界和不存在 Run。
- `frontend/src/components/AgentEventDetails.vue`、`AgentActivityRow.vue`、`AgentTestEvidence.vue`、`agentEventPresentation.ts`、`agentResultPresentation.ts` 和 `frontend/src/api/client.ts`；对应 timeline/result 三组回归。
- 真实隔离夹具：`GenericCommandDeliveryTest`（真实 C++ 编辑、命令、SQLite/Git、冻结和自动 Delivery）、`CommandUsabilityDockerTest`、`DockerAgentSandboxTest` 定向用例、`NativeWorkspaceCommandTest`、`DockerProxyIsolationTest`、`DockerRetentionRecoveryTest`。
- 当前语义见 [ADR-0025](../decisions/0025-agent-loop-convergence.md)。PRODUCT/ARCHITECTURE/DOMAIN_MODEL/ROADMAP 和 LOCAL_SETUP 同步，旧政策显式标为历史。

## 实际验证

原始日志保存在本地忽略的 `target/convergence-*`，不作为原历史 Run 的收据。

- Maven Wrapper 全量 `.\mvnw.cmd -q verify`：**193 个套件、1980 项，0 失败、0 错误、65 跳过**（1915 项执行通过），四模块构建成功。日志 `target/convergence-verify-final.log`；本轮 XML 与汇总分别保存在 `target/convergence-full-reports/`、`target/convergence-full-summary.json`。
- 全量完成后补充“全部跳过仍有已知报告”汇总修正，执行 `CompletionEvidenceTest` 4 项与 `DockerAgentSandboxTest#allSkippedReportsRemainAvailableWithoutClaimingTestsExecuted` 1 项，**5 项通过，reactor verify/构建通过**。该新增 Docker 用例不包含在上述 1980 项中；日志 `target/convergence-known-zero-verify.log`。它验证真实 Maven 返回 executedTests=0、skipped=1，冻结摘要为 NOT_TESTED / AVAILABLE，不捏造执行数量，也不设置数量门槛。
- 默认全量的 65 项跳过包括条件启用的 Docker、付费/真实仓库验收、公开仓库扩展矩阵及新旧镜像协议检查；Docker 另行定向执行如下。未设置 `DELVEFORGE_SANDBOX_PROTOCOL_IMAGE` / `DELVEFORGE_SANDBOX_LEGACY_IMAGE`，未运行额外 FinTrack fresh-clone/公开仓库矩阵、整套 FullStack/NativeFrontend smoke 或付费 Live 组。没有把这些跳过项计为通过。

- 真实 Docker 分批定向运行 **17 个不同场景最终通过**：CommandUsabilityDockerTest 3、DockerAgentSandboxTest 定向 4（公开只读资源、withheld 非 blanket veto、合法测试清单变化、全部跳过的明细）、NativeWorkspaceCommandTest 的 npm/pnpm 2、GenericCommandDeliveryTest 2、DockerProxyIsolationTest 1、DockerRetentionRecoveryTest 4、DockerMavenCompatibilityTest 的 Java 8/JDK 21 1。不是全部 Docker/公开仓库兼容用例。C++ 测试使用已有 `delveforge-agent:generic-v9-verified-cpp`；其余首批及已知零执行数用例使用已有 `protocol-v9-jdk21-05d760e3e30b9cdf00ef751d99bb44694db1a09bdf87311bfa43d8069ee336c6`，未重建产品镜像。
- C++ 夹具实际读取 README、失败命令后更新可选笔记、修改 counter.cpp、执行真实 g++ 检查，形成 v7 Candidate，再由独立 Delivery service 自动交付；原资产不变、仅当前 Step 成功、Plan 仍 ACTIVE、后续 Step 不被笔记授权。私有 Compose 原字节保留且不可访问，withheld 数量大于零不再全局否决。大输出、超时、取消、源变化、清理后日志读取和未验证 partial 非交付状态也在真实容器中核对。
- 首次尝试记录保留：默认镜像不含 g++，没有将该次失败当成功；改选事先已有的 C++ 夹具镜像。旧夹具缺少 v7 报告、旧清理断言要求整个 Run 目录删除、环境块合并后旧测试下标及新增读取夹具缺少路径，均按新合同修正后重验；没有降低隔离、凭据、源码或 Delivery 断言。日志：`target/convergence-docker.log`、`convergence-docker-final.log`、`convergence-docker-corrections.log`；XML 快照保留于对应 first/second/correction-reports 目录。

- 前端：`pnpm --dir frontend test`，22 文件、329 项通过；`pnpm --dir frontend build`（包含 `vue-tsc -b`）通过。项目没有 lint 脚本。
- 最终重新打包：`.\mvnw.cmd -q -DskipTests '-Dmaven.jar.forceCreation=true' package` 通过；清除了本次 Python 检查生成的缓存，核对基础设施 JAR 不含 `__pycache__` / `.pyc`。未修改沙箱镜像资源。
- Python 隔离策略：Linux 临时容器，无网络、仓库只读，`python3 -B -m unittest discover -s backend/delveforge-infrastructure/src/test/python`：60 项，0 失败/错误，7 条件跳过。这 7 项是未设置 `DELVEFORGE_SANDBOX_PROTOCOL_IMAGE` 的独立镜像协议组；容器未挂载 Docker socket。Windows 首次执行 2 项符号链接测试因 WinError 1314 失败，未降低安全断言；改在对应 Linux 执行环境验证。
- 浏览器实际 Vue 组件/现有样式与合成回放：1280/768/390px 无页面横向溢出，命令预览 25.5px 单行，单一详情无嵌套，58 条普通成功提供者记录不渲染；Enter 展开命令并读取保留日志，窄屏日志翻页区可访问，丢弃提示可见。截图及测量在 `target/convergence-ui/`。这不是正式业务 E2E。
- [固定权限评价](agent-loop-permission-evaluation-20261107.md)：注解反射检查不被算作匿名/非管理员拒绝的行为证明；不运行或修改旧产物，不新增生产监督规则。

## 环境与模型质量限制

**平台/环境**：保留受控公共 Maven/npm 工件网络；没有任意 egress、私有仓库凭据、宿主服务或 Docker socket。默认产品镜像没有 g++/Go，C++ 回归使用已有专用测试镜像。无法一般性提前识别任意仓库所有依赖；沙箱 READY 仅表明执行边界可用。没有持久 shell、PTY、后台进程框架或 durable resume。日志有明确容量上限。

**所有者目标环境**：历史报告记录过 `cdn.npmmirror.com` tarball E403、JWT_SECRET 缺失和 MyBatis 测试初始化错误。本任务没有修改该目标，也没有验证其当前是否已解决。移除 Compose/withheld blanket veto 不会使缺少的服务、秘密或不允许的下载自动可用；真正依赖隔离输入的检查仍会失败。不要仅因本次平台修改就再次付费重跑同一已知未解决的环境。

**模型质量**：模型仍可能误判失败相关性、选择不足的检查、写弱测试或夸大业务结论。收据核验不证明任意业务充分性，平台不解析任意命令等价性，也不证明自编测试覆盖全部需求。输出质量须独立运行后评价；确定性夹具不能证明模型会自主选择正确操作。

## 所有者使用与现场验收

本次没有修改 Dockerfile、沙箱 Python/代理或镜像资源，容器协议仍 v9，**不要求重建有效的现有 v9 镜像**，也没有数据库迁移。

1. 当前源码在上述本地分支。所有者准备采用时，停止旧前后端终端（Ctrl+C），按既有流程备份正式数据；本任务未替你停止或重启它们。
2. 在仓库根目录运行 `.\mvnw.cmd verify`、`pnpm --dir frontend test`、`pnpm --dir frontend build` 可复核源码；后者包含类型检查。
3. 运行 `.\setup-delveforge.ps1`，启动器重新构建后端并启动前端，核验/复用有效 v9 镜像；若使用自定义镜像，继续传 `-AgentImage21 <现有有效镜像标签> -AgentImage17 <现有有效镜像标签>`。这会启动正式配置，留给所有者操作。无需更改原资产或重置已有失败 Run。
4. 首次现场验收使用另行授权的隔离副本/数据，先确认已知下载、所需服务和测试配置能在当前政策下满足。不可解决的已知必要输入仍缺失时不要开始付费运行。
5. 按正常产品流程选择方向、审阅 Plan、确认一个 Step。运行期间系统及配置模型独立检查仓库、安装/编辑/执行/纠正；Astra 只观察，不改目标代码、测试、依赖或环境，不替模型补结果。
6. 保存实际事件、用量、报告、日志、执行视图和最终 diff。核对失败命令仍显示失败、后续处理有真实重验、笔记不充当证据、限制有归因。必要验证未解决时应无可交付 Candidate，并保留安全部分成果；合格结果应自动 Delivery。
7. 运行结束后独立核对业务行为（包含适用的匿名/非管理员拒绝）、冻结 diff 与正式 revision；模型若弱化测试或夸大结论，记为输出质量失败。评价者后续修复不能计入该次自主成功。

## 原保留材料

三个 Run 目录均只有原来的 partial-metadata.json / partial.diff；工作前后 SHA-256 一致：

| Run | metadata SHA-256 | diff SHA-256 |
| --- | --- | --- |
| 547b8153-d3e9-4161-9f2a-1f35b3e4c487 | b7b6f95bd4c8ee312ee2fc7e20b40b90362eaf164a8a30fb782cd7410121f97b | f18965944a8920be057721d4c89914c63644206b3c8af57e42ea96f1e47a9393 |
| 4266954b-95a1-4357-a379-8a6235c170ed | 18d8750cf45dc9e2455586ce1270020d44ac3fc894f8f9528b48397c74f91d73 | 5ee8f79d7eed0efff75ba1c79448cd4ee642111c8ee93069e7a6effb1d8df8f6 |
| b49e12d6-f75e-4d81-8441-75f36ddf0366 | a1c52f4fb85f387dbbbd978b8bebeb9182a44af2a6be401d688e0f7b5a228938 | 213398256a751690ab93f737cd97c3826693d651eb968b7ef940144b0b85cf04 |

没有补造历史日志/执行顺序，没有把旧失败解释成新版本已验证成功。

## 2026-11-07 审查跟进：当前运行时测试迁移

从 `e80fda0` 到 `2422a2f830b2e97eee3c7463e35d0910dc687f78` 的差异复核确认：
`ReadToolRecoveryDockerTest`、`PatchConflictOrchestrationDockerTest`、`NativeFrontendDeliverySmokeTest`
使用当前运行时和 FixtureCompletion 创建新 Candidate，但版本断言仍为 6。本次仅将三处断言迁移为 7，
并核对最终报告引用、实际工具返回的 invocationId、Run 归属及最终源码快照；前端夹具同时核对执行视图。
原有错误恢复、冲突延迟写入、真实源码修改、原生命令、隔离、冻结和 Delivery 断言保留。
其他直接受影响的条件测试未发现同类版本遗漏；历史证据兼容测试和生产代码均未修改。

实际执行（22:29 完成）：

```powershell
$env:DELVEFORGE_AGENT_DOCKER='true'
$env:DELVEFORGE_AGENT_IMAGE_21='delveforge-agent:protocol-v9-jdk21-05d760e3e30b9cdf00ef751d99bb44694db1a09bdf87311bfa43d8069ee336c6'
$env:DELVEFORGE_AGENT_IMAGE_17='delveforge-agent:protocol-v9-jdk17-05d760e3e30b9cdf00ef751d99bb44694db1a09bdf87311bfa43d8069ee336c6'
.\mvnw.cmd '-Dtest=ReadToolRecoveryDockerTest,PatchConflictOrchestrationDockerTest,NativeFrontendDeliverySmokeTest,CompletionEvidenceTest,CommandCheckEvidenceTest,AgentCheckEvidenceTest' '-Dsurefire.failIfNoSpecifiedTests=false' verify
```

| 测试类 | 实际用例 | 结果 |
| --- | --- | --- |
| ReadToolRecoveryDockerTest | 1 | PASS |
| PatchConflictOrchestrationDockerTest | 2：earlierSuccess=false/true | PASS |
| NativeFrontendDeliverySmokeTest | 4：npm/npm2/pnpm/pnpm6 | PASS |
| CompletionEvidenceTest | 4 | PASS |
| CommandCheckEvidenceTest | 7（历史 v6） | PASS |
| AgentCheckEvidenceTest | 9（历史证据） | PASS |

共 27 项，0 失败、0 错误、0 跳过；四模块 reactor `verify` / 构建成功，总耗时 13:56。
原始日志：`target/convergence-review-followup.log`；XML 位于对应模块 `target/surefire-reports/`。
本轮 7 个 Docker 用例均实际执行，更新了临时夹具的 proof；上文“未运行整套 NativeFrontend smoke”只描述前次验证，本轮已补齐。
没有镜像重建、生产代码修复或新增完成门槛；前端和其他全量测试未重复运行，前次结果不冒充本轮结果。
合并前 main 仍为 `e80fda0`，为本分支祖先；采用仓库已有的非快进合并方式保留实现与跟进提交。
没有推送、付费调用、真实用户项目 Run、正式数据或旧 partial 产物修改；三个无关未跟踪目录保持原状。
