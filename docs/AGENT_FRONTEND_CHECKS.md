# Coding Agent：产品上下文与受控前端检查

本任务是 MVP 冻结后的用户授权增强。保留逐 Step 确认、独立 Agent 源副本、冻结 Candidate 和确定性 Delivery；
没有浏览器、应用自动启动或产品验收 Agent。技术检查通过不等于用户工作流或业务验收通过。

## 上下文与授权

方向与规划提示要求面向用户的 Web 功能交付有界纵向切片，包含实际需要的交互、后端和测试。
CLI、SDK、API-only 产品仍有效，不强制增加 UI 或视觉重设计。JSON、Evidence 引用和领域生命周期不变。

`CodingAgentService` 从当前已保存的 `EvolutionPlan.targetState()` 取得 problem、targetProduct、differentiation，
各字段先净化、再最多保留 3000 字符。它们与当前 Step 的 goal/scope/plannedChanges、可信 baseline 一起，
作为 JSON 数据进入 user 消息。整体产品意图只解释当前工作，**不提供额外修改授权**。
不额外调用模型做摘要，不携带完整画像、仓库分析、其他 Step 或历史聊天。原资格及 baseline 重查继续执行。

## 支持范围与工具

同一 `run_frontend_checks({"directory":"frontend","goal":"typecheck|build|test"})` 支持 npm 和 pnpm。
省略目录时只在唯一 Vue/Vite 工程下自动选择，多工程必须明确指定；根工程使用 `.`。
工程必须声明 Vue 3、Vite；TypeScript、vue-tsc 和 Vitest 不作为整体准入前提；Node 原生测试不需要 Vitest。
允许独立前端仓库，也允许与 Maven/Spring Boot 组合；没有 Maven 时不制造 POM。兼容性预检只识别固定 Git tree
中的前端元数据，实际脚本和锁文件仍在沙箱内校验；预检通过不是依赖安装或测试通过。

受控镜像固定 Node **22.16.0**、npm **10.9.2**、pnpm **12.9.1**；Node 构建来源固定到镜像 digest。
检测要求且仅允许一种锁文件：

| 包管理器 | 锁文件 | 依赖准备 |
| --- | --- | --- |
| npm | `package-lock.json`，lockfileVersion 3 | `npm ci --ignore-scripts` |
| pnpm | `pnpm-lock.yaml`，lockfileVersion 9 | `pnpm install --frozen-lockfile --ignore-scripts --ignore-pnpmfile --no-runtime` |

`packageManager` 可省略并由唯一锁文件确定；若存在，必须声明与锁对应的明确版本，当前接受 npm 9–11 或 pnpm 9–12。
镜像实际执行版本仍固定，准备 manifest 不携带 `packageManager`，不会自动下载仓库指定的包管理器。
兼容性判断依据受支持锁格式，不再要求仓库元数据与镜像版本逐字符相同；版本范围、未知工具或不匹配的工具仍拒绝。
pnpm 12 的双文档锁可以包含同版本 pnpm 工具链文档，但不能包含 configDependencies。
所有包必须具有规范、摘要长度正确的单一 SHA-1/256/384/512 SRI，并由真实安装器校验；缺失或不匹配不放行。
历史 SHA-1 不提供现代抗碰撞或发布者真实性保证，依赖仍作为不可信代码隔离；固定 Git/SHA-256 快照不替代包摘要校验。
仅支持公共 npm registry 的常规包，不支持本地、git、URL、link 或私有来源。
npm 锁的根依赖必须与 manifest 一致；两种安装器也独立执行各自的冻结一致性校验。

项目 scripts 作为不可信代码在一次性、无网络容器中执行，使用固定 npm/pnpm 的 `run <声明脚本名>`。
不解析脚本语法来证明安全；允许现有 wrapper、shell 组合及项目工具。Agent 只能选择目标和工程，不能提交宿主命令。
类型脚本名称支持 `typecheck` / `type-check`，构建支持 `build` / `build-only`，测试支持 `test` / `test:unit`，优先使用前者。
没有脚本时返回 `CHECK_NOT_AVAILABLE`；声明脚本的工具缺失会形成实际执行失败，不自动添加依赖或改写配置。

测试由镜像内 `frontend-report.cjs` 通过 NODE_OPTIONS 在实际 Node/Vitest CLI 入口附加报告：
Node --test 使用内置 JUnit，Vitest 使用单次运行的 JSON。保留 NODE_OPTIONS 的多层 npm/pnpm、shell 和 Node 子进程包装可以采集。
每次 runner 使用新文件，报告数量最多 32、每份最多 2 MiB，验证退出状态和计数；Vitest 汇总须匹配 assertionResults，
Node 按 test case 计数，嵌套 suite 不重复累计。主脚本成功不能掩盖已采集 runner 的失败。
固定 Node 22.16.0 实测 `node --test tests/` 会 MODULE_NOT_FOUND，Agent 应根据真实诊断改为有效测试文件或 glob，检查器不代修源码。

其他声明脚本也执行，但没有可验证报告时，成功退出为 `TEST_EXECUTION_UNVERIFIED`，不生成 `TESTS_PASSED` 或虚构计数。
覆盖 NODE_OPTIONS 的包装、程序化 runner API 和其他框架可能无法采集；反馈明确说明可用路径，不要求已有仓库安装 Vitest。

仅根包的 `pnpm-workspace.yaml` 可以含 packages（空或 `.`）及依赖安装构建许可列表；许可不复制到安装目录，始终禁用安装脚本。
项目 `.npmrc` 只在内容能力策略校验为公开只读资源时可用：固定公共 registry、关闭安装脚本，以及受限布尔设置。
受校验的 pnpm peer/hoist 设置通过固定参数进入安装器；原配置文件不复制到联网安装目录，源与关闭脚本的约束不可覆盖。
未知或敏感 `.npmrc` 留在主机，前端检查明确不可用/不支持，不会启用私有 registry、凭据或 hooks。
多包 workspace、其他 pnpm 设置、pnpmfile、混用锁文件及不支持的工具链版本仍明确拒绝。
需要安装期脚本或 native build 才能工作的依赖可能无法使用；不会放开生命周期脚本来修复它们。

## 依赖与执行隔离

准备容器只读挂载权威源卷，在一次性卷的独立目录只放经过筛选的 manifest 和锁文件；不复制项目 scripts 到安装 manifest。
生命周期脚本关闭，pnpm hooks 关闭。准备网络是现有受控 internal network，只能通过固定代理访问 npm registry 的
规范包 metadata 与 tarball。代理不提供 CONNECT、任意上游或重定向；安装器按冻结锁校验包 integrity。
代理不执行项目代码。新增镜像内 `python3-yaml` 用于安全解析 pnpm 锁；标准库不能解析 YAML，手写行匹配不能可靠校验它。
采用 SafeLoader，并拒绝 alias、anchor 和显式 tag；没有新增 Maven 或前端应用依赖。

同一 Run、同一目录及相同 manifest/锁文件/受支持配置快照可复用已准备依赖。准备写入者停止后，后续检查只读挂载该卷；
项目代码不能污染复用依赖；前端容器不挂载 Maven 缓存。pnpm 12 以 pnpm_config_pm_on_fail=ignore 关闭自动包管理器下载，并设置 pnpm_config_verify_deps_before_run=false，禁止执行前自动重装已冻结依赖。元数据变化立即销毁重建，Run 清理时移除私有依赖与缓存。
共享代理缓存只存公开 artifact，不能替代锁文件 integrity 校验。

每次检查在新的完整源副本中执行，网络为 `none`，非 root、只读容器根、cap-drop、no-new-privileges、
CPU/内存/PID、输出和单次超时限制继续生效。关闭 core dump；PID 1 工具在报告与源码核对前杀死、回收剩余进程，包括 detached 子进程。
宿主取消或超时后销毁容器及检查卷，停止所有容器内写入者。
正式 WorkingCopy 不挂载给容器，也不接收依赖、生成输出或测试产物。

输入和权威源码快照不能包含前端依赖及已分类的前端生成物。只有一次性检查副本可跳过所选工程的 node_modules、dist、coverage、
.vite、.df-check 及 tsbuildinfo；生成目录根不能是符号链接。依赖树内部链接因此无需放宽权威源的链接规则。
检查前后快照中出现的其他源码变化触发 `CHECK_MUTATED_SOURCE`，即使构建成功也拒绝结果。权威源在检查前后再次完整核对。
修改源码会改变完整 snapshot；旧检查不能证明新源码通过。最终冻结、凭据保护、独立 index 重放及 Delivery 守卫保持。

## 证据与诊断

v6 将完整 Git 保留与实际执行材料分开。sourceView 明确 FULL / PROJECTED、仅主机保留与公开只读资源的数量，
executionBaselineTree 记录初始执行树 E；可信 R 仍是 Candidate baseline。快照同时绑定实际材料摘要与不可物化原条目的身份，
不把 E 称为完整字节相同 checkout。被保留文件若属于源码、测试或构建描述，当前视图不能生成检查 PASS。
UI 展示投影范围；类型/构建/测试成功均只限于实际执行视图，不能推断被保留内容已执行。

执行前模型收到当前工程的有界检查声明：Maven 目录、Vue/Vite 目录和每个 goal 的
DECLARED_SUPPORTED / ABSENT / UNSUPPORTED / UNAVAILABLE，以及管理器、脚本名、具体不可用原因和报告采集说明。
依赖仅存在于检查副本。源码工具对 node_modules 等生成材料的只读询问返回 CHECK_MATERIAL_UNAVAILABLE 和检查入口说明，
不访问这些路径；写入继续由生成物守卫拒绝。该反馈不是检查证据，也不解决已有失败。
configurationSnapshot 绑定配置身份，检查反馈重读能力并指示配置变化；声明不验证完整锁文件或安装结果，不是 PASS。
错误目录或无可选检查返回 CHECK_NOT_AVAILABLE 并附可用目录；前端独立工程不制造 Maven 配置。
Maven 声明沿用受控入口和 reactor 角色，不把测试资源内的 POM 当作可执行工程。

新 Candidate 使用 `evidenceVersion: 3`，每条记录包含 checkType（MAVEN/FRONTEND）、directory、goal、packageManager
（前端）、classification、测试/报告计数、snapshot 与 stale；新增 executionStatus、runner 与 configurationSnapshot。旧记录缺少类型时仅按 Maven 解释，不迁移或重写历史。

- typecheck/build 成功记录 `BUILD_PASSED_NOT_TESTED`，不生成测试通过事实。
- 只有新 Node JUnit / Vitest JSON report 记录实际执行数大于零且退出成功，才记 `TESTS_PASSED`；零测试、报告缺失和测试失败分开处理。
- 类型、构建、测试、锁文件、缺依赖、不支持配置、超时和无法确认结果分别返回结构化分类；输出先净化再给模型。
- 前端失败按类型、目录、goal 及已知 runner 独立追踪，必须用当前源码的同一检查修复。Maven 成功不能清除前端失败，build 不能清除 test 失败。
- 新证据明确 `NOT_STARTED` 的配置/环境拒绝保留历史但不制造已执行测试失败；旧 v1/v2 保持原保守解释。
- `CHECK_NOT_AVAILABLE` 与 `TEST_EXECUTION_UNVERIFIED` 不产生通过证据，也不能清除任何已有失败；删除脚本或测试工具不能绕过冻结守卫。
- Maven 原有同目录完整 test/verify 重验规则保留。已知测试失败不能由零测试的构建结果清除。
- 冻结和 Delivery 共用 `AgentCheckEvidence` 规则；未解决失败阻止接受。没有执行检查可保持 NOT_TESTED，不伪造 PASS。

UI 展示独立工程/目标、实际计数和旧源码标签；总览中的测试结果明确限定到所显示的检查范围。

本轮 v5 的通用修正、26 个固定公开样本、Maven-only / npm / pnpm 交付回归和现存接入限制见
[通用兼容性审查](validation/general-compatibility-20261102.md)。该审查明确区分当前技术验证、保留的旧运行和未启动的真实产品试验。

## 镜像更新与验证

工具协议已升至 **v7**。新 Java 会在传入源码前拒绝旧镜像。正常启动使用 `setup-delveforge.ps1`，
其 `setup-agent-images.ps1` 根据当前构建输入选择新标签、构建 17/21 镜像、核对脚本内容并固定 image ID，核验包含新增报告 preload。
自定义镜像须从当前 `backend/delveforge-infrastructure/src/main/resources/agent/Dockerfile` 用新标签构建，
再通过 `-AgentImage21` / `-AgentImage17` 或对应配置显式使用并重启应用。不要覆盖正在使用的旧镜像标签。

可执行回归包括 `AgentCheckEvidenceTest`、`AgentContextTest`、`FrontendPolicyTest`、`FrontendProxyTest`、
`RepositoryCompatibilityIntegrationTest`、`NativeFrontendDeliverySmokeTest` 和 `FullStackAgentSmokeTest`。后者设置 `DELVEFORGE_AGENT_DOCKER=true`、
`DELVEFORGE_AGENT_IMAGE_21=<当前v7镜像>` 后运行，分别实际执行纯 Maven、npm/pnpm 安装、失败检查、源码修正、
Maven/Vue 检查、Candidate 冻结和 Git/SQLite Delivery。模型动作是确定性脚本，检查结果不是替身；不调用付费模型。
实际执行结果见下方记录；仅有这些测试文件不代表运行通过。

v6 资源能力回归另含 `RepositoryContentPolicyTest`、独立 Delivery 的资源伪造负例与
`test_resource_capabilities.py`。本轮真实运行及未通过的公开样本见
[安全仓库接入验证](validation/safe-admission-boundary-20261102.md)；下方 v4/v5 记录保留历史范围。

## 2026-11-02 初次 v4 实现验证记录（历史）

实现分支为 `codex/full-stack-evolution-checks`。以下为初次实现时的本地运行，不是后续 v5 的重新执行结果。
该批验证未调用付费模型或操作真实业务数据库、
WorkingCopy；Git/SQLite 交付使用测试创建的隔离 fixture。

| 检查 | 实际结果 |
| --- | --- |
| Maven Wrapper 完整 `verify` | 1818 项，0 failure、0 error、40 conditional skip；domain 209、application 857、infrastructure 530、app 222 |
| 启用 Docker 的定向 Java 回归 | 97 项，0 failure、0 error、3 conditional skip；包含下方实际 smoke 两项 |
| npm/pnpm 完整全栈 smoke | 两项均执行并通过，0 skip；真实安装、Maven/Vue 检查、Candidate 冻结及 Delivery |
| 最终镜像 Python 策略与代理回归 | 48 项通过，包含锁文件、缺依赖、失败结果、源码修改、超时及代理限制 |
| 最终镜像协议回归 | 7 项通过 |
| DelveForge 前端 | 22 文件、303 项测试通过，typecheck 与 build 通过 |
| 最终本地打包 | `mvnw.cmd -q -DskipTests package` 通过；打包前端检查资源与源文件 SHA-256 一致 |

完整 `verify` 的条件跳过不代表 Docker 集成已执行；Docker 回归在单独启用环境变量的运行中完成。
每种包管理器的 smoke 都真实经历锁文件不一致、Vitest 失败、类型错误及构建失败，再由确定性模型动作修改源码，
取得当前源码的 Maven test、Vue typecheck/build/test 结果。最终两份交付均为 `COMPLETED`，
原始资产与正式交付仓库均无未提交变更，正式仓库没有 node_modules。

真实 smoke 使用当次 v4 镜像 `sha256:c7fc26c21b235bf1e85c36deb64da045b460f5ee309dbc0d3cc26dee067d8166`。
此后仅增加 pnpm 锁中非常规包名/本地来源 key 的拒绝规则；两份真实 fixture 锁通过最终规则验证，
最终镜像另行通过上述 48 项策略/代理与 7 项协议测试，未把这次负向校验补充描述为重新执行完整 smoke。
正式镜像初始化流程已构建并核对当前 17/21 镜像脚本及固定工具链版本：

- JDK 17：`sha256:d35b880463265ffc100a4054b5bdb28e5f1402c37cf70beaa8fc719b45471927`。
- JDK 21：`sha256:4aed4db85923dfbf66173c3a78f9f63f07e8bc51826526a5b4426312ce4d361b`。

本地可再生成的记录保存在忽略的 `target/backend-final-verify-summary.json`、`target/fullstack-focused-summary.json`、
`target/fullstack-smoke-junit.xml`，以及 infrastructure 的 `target/fullstack-smoke-{npm,pnpm}.json`。
默认后端测试再次运行会覆盖其 Surefire 条件跳过记录，因此真实 smoke JUnit 已单独保留。
以上证明受控工具及交付链路；没有执行浏览器交互、应用启动或最终产品业务验收。
