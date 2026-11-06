# 工程可靠性审计（2026-11-05）

## 实施前结论

审计基线 `8658c1d`。范围：既有 Agent/Workspace/SQLite/前端调用链、预检和 Delivery 证据；不是重写产品。

| 优先级 | 已验证依据与机制 | 最小修正、风险与验证 |
|---|---|---|
| P0 | DockerAgentSandbox.requireOwner 只比较 owner，清理对象的 run 标签未比较；同 owner 错误身份可被操作。属于代码证明的边界缺口，未证明历史误删。 | 同时验证 Run 标签；真实 Docker 错配标签回归，保持共享缓存单独身份。 |
| P1 | AgentProcess 丢弃 IOException 的安全分类；execute finally 丢弃清理失败操作；C 只留下 ENVIRONMENT_FAILURE/CLEANUP_BLOCKED。 | 记录安全操作身份、OS 数字错误码和清理代码位置，不记录命令参数、环境或原始输出。历史细节无法反推。 |
| P1 | preflight 调用多个长操作却只最终持久化；异常诊断缺当前 directory/goal；重启协调释放占用没有成功事件。 | 既有事件存储增加有界里程碑；释放事务记录成功；保留未知占用和材料。用真实 SQLite/容器验证。 |
| P1（真实 Docker 新发现） | Node 内置 JUnit 使用 classname=test，frontend.py 误用为文件路径；正常 Node 测试通过后仍因清单不完整被 EVIDENCE_INCOMPARABLE 拒绝。旧镜像的 workspace/npm2/lockless 等输入均重现，模型调用为 0。 | 给内置报告器补真实事件来源，解析完整 suite 身份；不更换测试框架、不放宽 v5。真实 npm/pnpm 与两种全栈自动交付验证。 |
| P1 / 环境限制 | Maven 恢复最多 8 轮/32 身份，仅从最后 18000 字符提取缺失依赖；B 确有离线依赖缺失。后续冷缓存重放在持续下载依赖时达到准备时间上限。 | 已补进度与缺失工件诊断；保留可配置的预算边界。未证明历史 B 是同一原因，也未证明截断导致该次失败，不据此添加重试或扩大预算。 |
| P2 | EvolutionView 对历史 CLEANUP_BLOCKED 使用 some；RepositoryCompatibility 无界展开 issues。 | 按最后已确认资源事件显示；长明细折叠并限制滚动高度，阻塞信息保留。 |
| P1 / 明确延后设计调整 | BaselineCheckComparison.compare 对全部基线/最终 environment 要求相等；配置摘要覆盖完整 POM/manifest，因此依赖变更甚至 POM 描述变化也可能触发 EVIDENCE_INCOMPARABLE。Docker 的 configuration 负例锁定了此行为。 | 这是 ADR-0023 的既有保守比较契约，不是依赖准备失败。允许“所有测试通过时换配置”需要明确调整该契约及回归身份规则；本次不静默放宽。下一次试用应避免把依赖/构建配置演化当作已证明能力。 |

保留：Application 领域资格重查、检查副本、源码身份、凭据净化、基线逐例比较、不可变 Candidate 和独立 Delivery。它们分别保护授权变化、非可信构建、数据外泄与正式提交，不是可以整体删除的重复校验。

不做：通用服务编排、任意工具链安装、新工作流引擎、checkpoint/resume、模块重构。大类体积本身不构成拆分理由。现有 read-tool 与 patch-conflict 可恢复路径保持。

质量盲点：默认 verify 跳过显式 Docker 测试；部分历史 smoke 的证据版本断言已落后于 v5。测试数不代表跨仓库或自动交付成功；本次另行执行真实 Docker 与确定性完整链。

## 历史证据（实施前）

全部从本地产品 API 分页读至空页，SQLite mode=ro 独立核对占用。原始有界证据保存在未纳入 Git 的 target/reliability-audit。
所有时间为 UTC；用户本地时间为 America/Los_Angeles（本日 UTC-8）。三次 Run 均绑定同一 Plan/Step/WorkingCopy，baseline=a5dd1681dc3f1897865de28bc64e371d3beec0d3，modelCalls/toolCalls/tokens 均为 0，无 Candidate。

- A bfec6deb-3dee-4542-8c6d-ab9ef9691844：08:41:34.058 CREATED/PREPARING；08:41:52.451 SANDBOX_READY；08:47:08.105 CANCELLED。4 条事件，无预检结果。不能证明等待的具体原因；AgentProcess:81 只证明末尾中断。occupied=0，retained=0。
- B 7df8f69d-ca39-4fe1-a565-7e9c600302b6：08:54:50.844 CREATED/PREPARING；08:55:10.002 SANDBOX_READY；08:56:55.314 ENVIRONMENT_PREFLIGHT；08:56:55.317 FAILED。5 条事件。backend/bookstore / test / DEPENDENCY_MISSING / ENVIRONMENT_BLOCKED；0 tests/reports，phase=NOT_OBSERVED，TEST_RUNNER 未证明。保存的 Maven 诊断明确 byte-buddy-agent:1.14.10 未从受控镜像下载、离线不可访问；最后准备摘要显示 kotlin-stdlib:1.8.22 获取成功。不能以一次工件获取成功推断完整依赖就绪。occupied=0，retained=0。
- C 095fde92-f0a0-4236-9b55-95a35db91b2e：08:59:06.583 CREATED/PREPARING；08:59:25.088 SANDBOX_READY；09:02:34.680 ENVIRONMENT_PREFLIGHT；09:02:34.684 FAILED；09:02:34.688 CLEANUP_BLOCKED。6 条事件。Maven test=TESTS_FAILED / READY_BASELINE_FAILURE，3 executed / 0 failures / 3 errors / 2 reports，Surefire 3.0.0；frontend typecheck=NOT_DECLARED，build=READY / BUILD_PASSED_NOT_TESTED；随后 ENVIRONMENT_FAILURE / Cannot start controlled operation，没有 frontend test 结果。具体启动子操作及 OS 错误未保存，不能断言是 MySQL/Redis、超时或 Docker 引擎。occupied=1，retained=0。

B/C 源码 snapshot 都为 2fc44db9432b10fc47ae10118e5aea712e585bb713e7f8552c1db628af8d0881，镜像 sha256:f6688dd2a1ba3eb85b9b08ce3faadb24f51477112caab6bf473e13d59e7f4660，JDK17，2GiB/2CPU/256 PID，无网络、只读根。C 前端固定 npm10.9.2 / SOURCE_LOCK / resolution 730980d2b9f8c0f3746345e5134b8e65e5e2b88ea89b951c3f16d98f91c7df2e。

C 残留初查：agent、proxy、frontend-proxy 三容器均停止；Run network 和 source/m2/frontend-prepare-source/frontend-check-source 四卷存在；均精确标记 owner=delveforge-local-agent 和该 runId。私有目录保留 Git 对象及 execution.index。A/B 无对应 Docker 资源。应用从 08:20:04 启动后尚未重启，无协调事件，C 的警告此时仍真实有效。未触碰其他历史 Run、共享缓存或无关 Docker 资源。

## 实施与验证

### 已实施

- `AgentSandboxPort`、`CodingAgentService`、`DockerAgentSandbox`：既有事件流记录低频预检里程碑、耗时、目录与目标；依赖准备结束后界面继续显示尚未结束的父检查。模型/工具调用计数不变。
- `AgentProcess`：启动失败保留受控可执行文件类别和 OS 数字错误码；不存命令参数或自由文本异常。清理/协调事件保留分类与代码位置，失败仍占用资源。
- `DockerAgentSandbox.requireOwner`：同时核验 owner/run 标签；共享 Maven 缓存保留单独的 shared-cache 身份。没有全局 prune。
- `SqliteAgentStore.release`：与释放占用同一事务记录 RESOURCES_RELEASED；事件读取最终持久化的 retained 值，避免再次释放时误报材料已删除。界面按最后确认的资源事件显示警告，历史事件保留。
- `frontend-node-junit.cjs`、`frontend-report.cjs`、`frontend.py`、镜像构建脚本：复用 Node 内置报告器补入来源，修复同名嵌套测试身份。镜像内容指纹包含新文件。
- `RepositoryCompatibility.vue`、`decisions-v2.css`：非阻塞路径明细折叠、限制内部滚动高度；阻塞原因始终可见，路径未截断。`EvolutionView` 和活动呈现显示当前探针及具体诊断。
- 复用现有 `PublicRepositoryPreflightLiveTest`，增加显式固定仓库参数；未新增重复探针框架。更新编排、SQLite、Docker、Python 和界面回归。

没有依赖升级、模块调整、持久化迁移、通用服务启动器、恢复旧对话或新的授权语义。现有 read-tool/patch-conflict 恢复路径保持。Maven 8 轮/32 工件的准备边界未凭猜测扩大；增加缺失工件、已请求工件数和安全诊断。新重放证明冷缓存准备会达到时间上限，但不能证明历史 B 因同一原因退出，不能宣称其依赖问题已根治。

### 已取得的验证证据

证据均为本地本次执行；target 下日志不入 Git。测试失败的早期尝试保留，不作为最终通过结果。

| 项目 | 结果与证据 |
|---|---|
| 前端 | `pnpm test`：22 文件、313 tests 通过；`pnpm typecheck`、`pnpm build` 通过。见 target/reliability-audit/frontend-*-final.log、frontend-build.log。 |
| Python 前端策略 | 修复镜像内真实 npm/pnpm 测试进程，27 tests 通过；来源、嵌套名称、源码改写拒绝及缺失/伪造报告负例。python-frontend-final.log。旧伪造 Vitest fixture 补全来源和 fullName；源码修改测试改为真实 Node 脚本，未削弱拒绝断言。 |
| 完整确定性流程 | EnvironmentPreflightDockerTest 的 root/nested 两例通过，fullstack-final.log。真实 Git、SQLite、Docker、Agent 工具、Candidate 冻结与 CandidateDeliveryService 自动观察/交付；无人工创建 Candidate/Delivery。分别 6 次脚本模型响应，非付费模型。 |
| 源码与交付 | 两例断言正式 WorkingCopy 的新 HEAD 树等于 Candidate，父提交等于基线；原始仓库 HEAD/工作树不变，正式副本交付后干净，存在 DELIVERY_COMPLETED，资源占用已释放。 |
| Docker 与安全回归 | 修复镜像上的定向批次 30 tests 中 29 通过，覆盖归属错配、凭据/路径限制、基线债务及回归拒绝、资源保留恢复、read-tool/patch-conflict 恢复和环境预检。唯一失败为过时的依赖变更交付断言，按 ADR-0023 修正后单独通过，详见下文。docker-regression-final.log 保留原失败，不写成整批全绿，也不声称执行了全部 Docker 测试。 |
| 最后定向验证 | 2026-11-06 06:28（UTC-8）77 tests / 0 failures / 0 errors / 0 skipped；含 CodingAgentService 65、SqliteAgentStore 10、真实 Docker 依赖变更拒绝 1、安全进程启动失败 1。final-targeted.log。 |
| 浏览器 | 实际 Vue 组件、合成 250 条路径；1440/420 宽度无横向溢出，展开区高 240px 且可滚动；预检当前操作可读。截图与测量：target/reliability-audit/ui/；非生产历史数据验收。 |
| 后端全量 | 最终 mvnw.cmd verify 于 2026-11-06 06:41（UTC-8）BUILD SUCCESS，四模块 1,924 tests / 0 failures / 0 errors / 60 skipped（1,864 实际通过），含打包；见 backend-verify-final.log。显式 Docker/Live 测试的跳过不能当作通过。本轮包含最终 retained 事件修正；较早批次的 release 事件断言已按实际契约修正。 |

修复镜像 JDK21：`sha256:d9167d34e54132bc41e99f79baae1c1c972f7a10924005aab04336c84a501e25`；
JDK17：`sha256:cbaa4aebc1f63c8e9efb459968293358c53f3faa1132eec1a446a99ae535e183`。
均经 setup-agent-images.ps1 内容校验，协议 v8。
完整流程 Run：root `0b164b73-77f2-4223-84f6-65f76dfce8e6`；nested `9972467d-a43c-4feb-8209-3b66b7a07efa`。

### 跨仓库与运行环境

先按 GitHub 搜索结果选择并固定两个陌生样本，再检查结果；未为绿灯替换样本或修改其源码。

| 样本 | 固定版本/结构 | 实际观察 |
|---|---|---|
| [in28minutes/spring-boot-vuejs-fullstack-examples](https://github.com/in28minutes/spring-boot-vuejs-fullstack-examples) | 00acc5e72674629f94deaaaea15cf777050d1607；多组嵌套 Maven/Vue 示例 | 只读兼容检查 COMPATIBLE；真实隔离准备成功，5 个 Maven 入口可见。请求 CRUD 后端与 Vue CLI 前端组合时 SELECTION_REQUIRED，前端不满足 Vue3+Vite 检测条件；0 模型调用，源码未变，清理完成。不是构建通过或全栈可执行证明。 |
| [moksamedia/split-spring-boot-vue-fullstack](https://github.com/moksamedia/split-spring-boot-vue-fullstack) | 5dc9c94543c833738b9b376f5d4913f21e2b2cb1；Gradle/JHipster/Vue2/Webpack | 真实隔离准备成功；availableDirectories=[]，ENVIRONMENT_BLOCKED，modelRequests/tokens=0；源码未变、cleanup=COMPLETED。不是支持范围内全栈成功样本。见 infrastructure/target/preflight-public/moksamedia-split.json。 |

两个样本均通过产品 GitRepositoryCompatibility 的固定版本只读检查，源码/HEAD 前后相同；见 target/reliability-audit/public-*-compatibility.json。COMPATIBLE 只表示源码准入，不代表工具链支持。公开样本隔离记录在 infrastructure/target/preflight-public/。早期 Windows 参数错误与 Docker 不可达尝试不计为产品预检结论。

书店固定版本另行从只读官方 WorkingCopy 建立临时副本重放。结果为 ENVIRONMENT_BLOCKED / TIME_LIMIT，最后操作 backend/bookstore / test，实际停在 MAVEN_DEPENDENCY_PREPARATION，第 5 轮准备超出 540 秒。代理记录 818 次成功上游读取、无 OOM，说明此轮持续处理大量传递工件，而非已启动业务测试。总后端探针约 759 秒，0 模型请求，源仓库/官方副本未改，cleanup=COMPLETED。证据：bookstore-replay.log、infrastructure/target/preflight-public/bookstore-replay.json。没有把本次免费预检程序的 JUnit BUILD SUCCESS 写成书店环境 READY。没有扩大预算后反复追求绿灯；现有 check-seconds 配置可调整单次检查/准备预算，但不能提供缺失的外部服务。

2026-11-05 19:25（UTC-8）再次协调 C 时 Docker 不可达，新增 sequence=16 RECONCILIATION_BLOCKED / DOCKER_FAILURE；占用保留。启动现有 Docker Desktop 后，它在初始化 dockerInference 临时接口时崩溃；本机日志明确报无法访问该文件。官方仓库有[同版本同症状记录](https://github.com/docker/desktop-feedback/issues/532)，它不能证明历史 Run C 的原始原因。

用户于 11-06 恢复 Docker 后，在取得 AgentInstanceLock 且确认唯一待协调 Run 为 C、终态 FAILED、retained=false、无 Candidate 后，调用现有 CodingAgentService.reconcile。协调成功：sequence=17 RESOURCES_RELEASED / CLEANED，occupied=0。独立枚举该 run 标签的容器、网络和卷均为空，私有 Run 目录已移除；历史 FAILED 及原事件完整保留。官方 WorkingCopy 干净，可信基线未变。未操作其他 Run、共享缓存、未知资源；未直接写 SQL 释放占用。证据：reconciliation-final.log、occupancy-after.json。这里证明现有清理路径现在可完成，不证明历史第一次清理失败的具体 OS 原因。

### 下一次随机仓库测试判定

判定：可以继续随机选择真实 Java + Vue 仓库，先进行免费准入和预检；尚不能宣称已证明陌生仓库的自主交付可靠性。两种受支持布局的确定性完整生命周期通过，两个陌生样本证明了工具链范围的早期识别，未证明陌生全栈项目能够成功交付。书店冷缓存预检仍受准备时间预算限制。下一次真实模型 Run 应以该仓库实际预检就绪、明确检查入口和授权 Step 为前提；本次没有付费模型验证。

已证明的正常路径是根 Maven + frontend、嵌套 Maven + frontend，以及真实 Node 报告下的自动交付。现有能力覆盖受支持的 JDK17/21、受控公开 Maven 工件、npm/pnpm 依赖准备、声明的前端脚本及 Node/Vitest 结构化报告；多入口需要明确选择。可比较的历史测试债务不被伪装成全通过，带债务 Candidate 仍需显式确认。

MySQL、Redis、Milvus、外部 API 和需要原生系统库/私有依赖的工程，不会自动得到任意服务或凭据。当前检查容器无网络，不能以用户本机已启动服务推断容器可达；需要项目自包含测试或明确支持的环境，不能靠改写业务源码规避。预检发现的未知工具链、检查缺失、依赖失败、不可信证据和安全策略拒绝会在模型前阻塞。日志中的外部服务匹配并非全知诊断，输出被截断或服务异常被包装时仍存在识别局限。

下一次真实 Run 的主要剩余风险是大规模冷依赖准备超时、外部服务不可达、缺失或不受支持的前端报告，以及变更依赖/构建配置后与基线不可比较。后一种情况发生在 Agent 修改之后，可能已经消耗模型调用；不能把所有不可比较证据都说成在付费前发现。真实模型的实现质量也未由脚本响应测试证明。

本分支不替换 E:/develop/DelveForge-test/DelveForge-main 下的运行版本。用户于 2026-11-06 补充授权：常规测试完成后提交并合并本地 main，后续端到端验收由用户手动进行；不推送。frontend/design/ 与 grilling/ 保持不动。

测试契约修正：旧 dependencyEdit smoke 仅构建后就期待 Candidate，既未完成新的全套测试，也与 ADR-0023 的相同配置要求冲突。新版本保留“解析身份改变、没有写回源锁文件”的断言，增加真实最终测试通过、COMPLETION_CHECK_REJECTED=EVIDENCE_INCOMPARABLE、无 Candidate、原始/正式副本不变及占用释放断言。它明确测试已实现的准备能力和已建立的拒绝边界，不声称已实现配置变更交付。根/嵌套两条成功自动交付测试继续独立保留。
