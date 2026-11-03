# Native Build Compatibility & Preflight Readiness 实施记录

日期：2026-11-03。分支：`codex/native-build-preflight`。不调用付费 Provider，不改历史 Run/Candidate/Delivery。

## 旧分支处理

先审查 `codex/sandbox-script-evidence` 与其祖先 `codex/full-stack-evolution-checks`。
前端 306 项测试、类型和生产构建通过；旧镜像策略 82 项与协议 7 项通过。
旧分支完整 verify 的 Domain 209 / Application 862 通过；Infrastructure 出现两项 Windows/Git 夹具失败，
这两项独立重跑通过。随后全模块跳过测试构建通过，App 222 项（3 跳过）通过。
因此不把旧分支原始完整 verify 记为通过。

旧修复通过 `--no-ff` 合入本地 main，合并点 `7f74810`；确认包含关系后删除两个旧分支。
从该合并点建立当前开发分支。没有推送，也没有把本轮新功能合入 main。
原有未跟踪目录 `frontend/design/`、`grilling/` 保留。

## 根因与改动

原流程在 `sandbox.prepare` 后直接请求模型，安装/运行问题到模型调用后才暴露。
Workspace 与 sandbox 重复的静态格式检查又把安全 Git、构建格式和运行环境混在一起。

- `CodingAgentService` 在 PREPARING 调用现有 `AgentSandboxPort` 的系统 preflight，记录真实结果后才进入 RUNNING。
  新 Run 的 tokens 初始为 0，真正请求模型后才使用原有未知用量语义；不回填旧记录。
- `GitRepositoryCompatibility` 保留 Git 身份、路径/类型、秘密、资源能力、预算和生成物逐项证明，
  将 Maven 根数/profile/JDK 静态未知与前端锁格式/缺失移到环境层。准备和 sandbox 再检查均已对齐。
- `MavenRepositoryLayout` 提供独立根候选和显式选择；Maven 自己求值 profile 和 Reactor。
  JDK 仅使用受控 17/21，没有运行时自动下载。
- `DockerAgentSandbox` 使用后续工具同一路径执行基线探针；声明、零退出、只启动包管理器均不足以证明就绪。
  Maven 实际 compiler/Surefire 阶段或报告、前端实际项目 Node 入口或 Node/Vitest 报告才构成观察证据。
- `sandbox.py` 的项目构建及 go-offline 始终断网。固定 dependency-plugin 3.8.1 从离线错误发现坐标，
  联网阶段仅在固定空 POM 中 dependency:get，最多 8 轮/32 身份，不加载项目 POM/.mvn/生命周期。
- `frontend.py` 使用原生 npm ci/install、pnpm frozen/install；安装输入不带项目脚本/hooks，脚本只在断网副本执行。
  npm v2/v3、shrinkwrap、无锁、pnpm generation 6/9 和普通 workspace 使用固定可用管理器处理。
  无锁解析保留本 Run 的冻结锁及摘要，不写回权威源码、原资产或正式 WorkingCopy。
- `proxy.py` 将已核对身份的公共镜像锁 URL 及原生 metadata tarball 映射到固定代理；保留 integrity，
  不接收任意源、凭据、重定向、Git/任意 tarball。实际 Vue metadata 600 个版本已完成映射探针。
- manifests、锁、workspace、受控配置和 hooks 配置的摘要变化使旧准备失效，后续仍按同样限制重新准备。
- API 可选 `checkDirectories` 复用现有启动入口（最多 8 × 200 字符）；界面显示确切入口与诊断，保留原费用和 Step 授权。

## 首次模型请求的条件

已确认 Step、可信当前 WorkingCopy、固定基线和沙箱身份必须仍成立。
唯一入口可自动选择，多个独立工程需显式选择；只对所选工程运行真实基线检查。

| 预检结果 | 模型行为 | 证据含义 |
| --- | --- | --- |
| READY | 可以开始 | 必要检查路径实际执行；测试未声明仍明确标注 |
| READY_BASELINE_FAILURE | 可以开始修复 | 源码/测试明确失败；后续能力未执行的部分不作证明 |
| ENVIRONMENT_BLOCKED | 0 请求 / 0 tokens | 缺依赖、环境、外部前提或缺乏可归因证明 |
| SECURITY_BLOCKED | 0 请求 / 0 tokens | 可信资源/执行边界未成立 |
| SELECTION_REQUIRED | 0 请求 / 0 tokens | 尚未选择所需独立工程 |

例如 Java 编译失败可允许修复，但 TEST_RUNNER 尚未证明。模糊失败保守阻塞。
预检不会保证依赖修改后的构建仍成功。

## 证据和协议

工具协议 v8；启动器核验真实脚本摘要、JDK、Node 22.16.0、npm 10.9.2、pnpm 12.9.1 / 8.15.9。
本轮最终镜像：

- JDK 21：`sha256:17acc970ff133c1979fa4338da7c477c6cf3e14055e31efd93ca61a9e69307f8`
- JDK 17：`sha256:04e620c63aaa21697baf9162f982569dc79b36415dd83aaa9d5954f4b050f20c`

前期 A–I、纯前端交付与公开样本使用相同协议的前一镜像（21：`55ecbd6…`，17：`42818f6…`）。
最终镜像仅增加已核验 pnpm 工具链文档与项目锁的副本分离，策略和真实协议测试重新执行；
包含该格式的前后端 pnpm 交付在最终镜像单独复测，不能把旧镜像结果当作新内容摘要一致性证明。

evidenceVersion=4 增加 `baselineFailures`。基线成功不进入正常 checks，不贡献最终测试数或通过证明。
基线失败保持未解决，须当前 snapshot 的匹配工程、目标及适用 runner 重验解决。
测试未声明、检查不可用或 build 通过不能擦除实际测试失败。v1–v3 保持原读取规则，无数据库迁移和历史改写。

## 执行结果

前端 308 项测试、类型检查、生产构建通过；最终镜像内策略 84 项通过，另外 7 项真实镜像协议测试通过。
预检界面已在桌面和 390 × 844 浏览器尺寸检查，无横向溢出；工程目录可输入，费用确认边界保留。
Impeccable detector 对改动界面报告 `[]`。

完整 `mvnw verify` 首次通过：Domain 209、Application 863、Infrastructure 563（48 跳过）、App 222（3 跳过），
共 1,857 项，0 失败/错误。公开样本随后暴露 Maven 坐标提取问题，定向回归与实际同样本重验已通过。
再次完整验证启动时出现本机 JVM 原生内存分配失败，未运行测试；保留日志，不记为源码测试失败或成功。
为验证进程设置 `MAVEN_OPTS=-Xmx384m`、`-DargLine=-Xmx512m` 后重新完整验证通过：
Domain 209 / Application 863 / Infrastructure 565（49 跳过）/ App 222（3 跳过），共 1,859 项、0 失败/错误、52 跳过。
未改产品 JVM 配置。最后增加的预算诊断与恢复身份针对性测试 17 项也通过。

| 场景 | 实际验证与结果 |
| --- | --- |
| A 健康环境 | 真实构建/测试的预检事件先于首次确定性模型请求 |
| B 基线源码失败 | Java 编译失败允许修复；修复后实际 Maven 测试通过，Candidate 仅含当前重验 |
| C 环境不可用 | 缺失公开工件：实际模型请求/modelCalls/tokens 均 0，原资产/正式副本不变并清理 |
| D npm v2 | 原生生成且含旧 dependencies 图的 v2 锁，真实 ci/build/test，通过；权威锁不改写 |
| E 无锁 | 真实 npm install 后 build/test 通过；锁只在私有卷，记录 RUN_FROZEN_RESOLUTION |
| F workspace | pnpm 原生安装 apps/web + workspace:* 本地依赖，实际 Vue/Vite 检查通过 |
| G Maven 根/profile | 无选择 0 请求/0 tokens；选择 server 后真实测试，未检查无关 JDK 25 工程 |
| H 修改依赖 | 真实修改 manifest 后新解析摘要不同，重新安装/检查并冻结，无权威生成锁 |
| I 安全负例 | 恶意 registry 锁被预检阻塞，0 请求/0 tokens；安装脚本、资源、断网、写入保护回归通过 |
| J 交付 | 纯前端 npm/pnpm 两项，加 Maven/npm/pnpm 三项前后端完整 Git/SQLite Delivery 均通过 |

`EnvironmentPreflightDockerTest` 初次最终镜像组 10 项全部通过；原生完整 v2 锁另单项通过。
`DockerDependencyPreparationTest` 实际执行 3 项通过（在线不执行项目测试、离线超时、源码写入阻断、普通 Maven 输出），
2 项需要特定历史数据库夹具而跳过，不伪装已执行。
pnpm generation 6 的实际锁由固定 8.15.9 原生生成，实际使用该版本安装并通过 build + 2 个 Node 测试。
首轮全栈回归两项失败完整保留：npm 的准备次数断言未计入新增基线准备；pnpm 的双文档锁被原生项目锁读取器拒绝。
前者更新为明确的 1 次基线 + 3 次配置失效；后者仅在私有副本分离已核验工具链文档，再跑完整交付。
最终镜像上的 `FullStackAgentSmokeTest` 三项全部通过，0 失败/错误/跳过，日志 `target/preflight-fullstack-final2.log`。
五个交付场景均验证原资产不变、正式副本只经 Delivery 更新、Candidate tree/snapshot 和当前重验匹配。

## 固定公开仓库评估

沿用 `general-compatibility-corpus-20261102.json` 中的固定版本，选定以下三个代表样本做实际预检，
不是重新测完原 26 个仓库，也不报告总体支持率。保留全部三个样本、失败和修复前记录。

| 样本 / 固定修订 | 安全准入 | 环境准备 | 实际执行 | 源码/测试结果 |
| --- | --- | --- | --- | --- |
| gs-rest-service / e15fec6… | 通过，正式隔离副本 | 完成 | READY，实际 Maven 测试 | 当前基线测试通过 |
| Homer / daa017d… | 通过，正式隔离副本 | 完成 | READY，实际项目 Node 构建 | build 通过；test NOT_DECLARED |
| Sa-Token / cb19134… | 通过，正式隔离副本 | 受控 Maven 元数据预算阻塞 | NOT_RUN | UNPROVEN |

Sa-Token 明确选择 remember-me 的 server 和 Vue 3/Vite page_project，覆盖普通 Java + Vue 全栈样本。
该仓库超过当前 Maven 128 描述 / 8 KiB 路径元数据预算，保留阻塞，不提高预算以制造通过。
本轮没有得到该公开全栈样本的构建成功证明；前后端执行/交付由自有真实 Docker 回归另外验证。
该实际诊断同时促使发现入口将预算异常映射为明确的 MAVEN_MODULE_LIMIT；针对性回归覆盖，未修改安全准入预算。
gs-rest-service 首次实际探针阻塞：坐标解析错误把本项目身份当成工件，导致准备失败。
修复后在相同修订、相同 complete 目录复测 READY；首次失败另存，不替换样本或隐藏失败。
公开样本不创建模型客户端、Candidate 或 Delivery，原仓库 HEAD 和 status 在结束时核对不变。
本轮没有调用付费 Provider；确定性模型夹具的计数/usage 仅用于验证系统边界。

原始日志保留于根 `target/preflight-*.log`，结构化场景/公开样本输出位于 Infrastructure 的
`target/preflight-evidence/`、`target/preflight-public/` 以及交付 JSON。
随报告提交 [结构化证据摘要](native-build-preflight-20261103-evidence.json)，含场景序列/用量、五个交付、
三个固定公开样本以及保留的 Maven 首次失败。

本轮修改在 `codex/native-build-preflight` 提交；确切提交见该分支 Git 历史。本地 main 保持旧修复合并点 `7f74810`。
未推送或合并本轮功能；除原有 `frontend/design/`、`grilling/` 外，任务改动全部提交，工作区状态在最终回复说明。

## 限制与不变边界

当前支持范围为受控 Java/Maven 和 Vue 3/Vite/npm/pnpm。私有 registry、凭据、安装期原生构建、
未知 pnpm 设置/catalog/hooks、自定义运行时和外部服务仍可能阻塞。静态声明支持不代表每个仓库实际通过。
公开样本若因受保护可执行资源被保留而不能执行，继续阻塞，不能减少源码来制造 PASS。

原资产只读、不可变 Candidate、snapshot、生成物和秘密保护、受控 WorkingCopy 来源及 Git/SQLite Delivery 守卫保留。
本轮不改领域生命周期、数据库结构、付费 Provider 策略，不增加 shell 工具、通用目标模型或外部服务编排。
设计见 [ADR-0022](../decisions/0022-native-build-environment-preflight.md)，使用见 [环境预检](../LOCAL_SETUP.md#agent-build-preflight)。
