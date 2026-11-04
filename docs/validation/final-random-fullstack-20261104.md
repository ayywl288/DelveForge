# 本地合并、安全清理与随机全栈产品试验

日期：2026-11-03 开始，2026-11-04 续跑（Asia/Shanghai）。只操作本地 main，没有推送。

> 本文保留最初收尾及续跑时的历史结论。后续在独立工程分支恢复同一 Plan、修正环境配置并定位安全阻塞，见
> [原随机演化续跑记录](random-fullstack-resumption-20261104.md)；后续记录没有替换本文的失败或用量。

## 结论

**MERGED / CLEANED；随机真实试验已完成分析、画像、方向和规划，当前 BLOCKED 于官方 WorkingCopy 准备。**
用户补齐凭据并手动恢复 Docker 后，沿用同一仓库与 commit 继续，没有重抽。
Docker 清理已完成：累计删除 84 个旧 Agent 镜像、14 个旧容器、6 个空网络及最初证明可丢弃的 7 个测试私有卷。
现在保留 14 个镜像（2 个当前 Agent + 12 个其他项目/基础镜像）、3 个无关项目容器、3 个默认网络、55 个数据卷。

官方 prepare 返回 500，受控 Git clone 未能建立有界证据。相同参数的独立原生诊断克隆成功，
因此尚不能断言是上游仓库缺陷或 DelveForge 的通用克隆缺陷；正式服务执行上下文与适配诊断仍需定位。
没有修补系统或目标代码、重试产品 prepare、启动编码 Run 或交付，不具备完整产品演示通过证据。

## 合并与本次实际检查

- 原 main `7f74810`；已核对 feature `eb6ae12` 的祖先、唯一提交和变更范围。
- 非快进合并：`f4b69bbee7905bf663d43f7571590065887705fe`。
- 独立文档整理提交：`02b3bbf9bc87dd91b4417ea5a4366fc4b6889b50`。
- `git branch -d codex/native-build-preflight` 成功；仅剩 main，一个工作树 `E:/develop/DelveForge`。
- 原有 `frontend/design/`、`grilling/` 未动；清理前后对其中 8 个文件核对 SHA-256，相同。

历史 [原生预检记录](native-build-preflight-20261103.md) 与证据已核对，不称为本轮重跑。
其中后端 **1859 项总数、52 跳过**，实际非跳过 1807 项，0 failure/error；不能写成“1859 通过另加 52 跳过”。
历史五项真实 Docker Candidate/Delivery 使用确定性模型，不是本轮付费产品结果。

本轮合并后实际运行（2026-11-03 22:05–22:06）：

| 命令 | 本次结果 |
| --- | --- |
| `.\mvnw.cmd -pl backend/delveforge-domain,backend/delveforge-application -am test -DargLine=-Xmx512m`，MAVEN_OPTS=-Xmx384m | domain 209 + application 863，共 1072，0 failure/error/skip |
| `.\mvnw.cmd package -DskipTests` | 全模块打包成功；此条没有执行测试 |
| `pnpm --dir frontend test` | 22 文件、308 项通过 |
| `pnpm --dir frontend typecheck`、`pnpm --dir frontend build` | 均通过 |

没有重新运行完整 `verify`、五项 Docker 交付或声称随机目标的 Maven/前端检查通过。

## 安全清理与文档

已移除可重建的 frontend/dist（8 文件），三个旧 native fixture/采集脚本，以及本轮错误查询 scratch 和
已转为净化清单的原始 Docker inspect 文件；合计 13 文件、1,087,717 字节。
保留 Maven 测试日志、提交的验证记录、当前打包 JAR、主数据库及 WAL/SHM、workspaces、agent-runs、历史归档。

初次因 Docker Desktop 的 dockerInference 监听错误未执行清理；用户手动恢复后重新核对了实时身份和引用。
先清理两份旧失败 fixture：2 个容器、2 个网络、7 个私有卷。
用户随后明确要求继续检查并删除旧容器/镜像：剩余 12 个 DelveForge 旧容器全部停止、只读根，
可写层检查未发现脱离保留挂载的源码、数据库或 JAR；绑定材料、数据卷和旧运行记录均保留，另留存有限容器日志。
删除这 12 个容器后，逐个删除不被任何容器引用、且并非当前 JDK17/21 的 84 个旧 delveforge-agent 镜像，
并删除 4 个归属明确且无端点的空网络。没有按年龄直接删除，也没有使用 force 或全局 prune。

| 资源 | 本轮最初 | 当前 | 累计删除 |
| --- | ---: | ---: | ---: |
| 容器 | 17 | 3 | 14 |
| 镜像身份 | 98 | 14 | 84 |
| 网络 | 9 | 3 | 6 |
| 卷 | 62 | 55 | 7 |

当前两个 v8 final2 镜像、全部后续数据卷和共享 Maven/npm 缓存保持；mysql57、redis、libretv 容器及其镜像未动。
其余保留镜像包括 Maven 基础镜像、Alpine、Milvus/Attu、RocketMQ/dashboard、MinIO、etcd、registry，属于其他项目或基础设施。
历史恢复材料所在卷、宿主机数据库、源与交付材料未删。逐项 ID、镜像标签、删除理由、前后清单和可写层检查见
[清理与执行证据](final-merge-cleanup-evidence-20261104.json)。

两个独立操作文档已删除，现行 v8/v4 使用说明归入 [LOCAL_SETUP](../LOCAL_SETUP.md#agent-build-preflight)。
保留 ADR-0021/0022，初次 v4 验证迁入 [历史记录](frontend-checks-initial-20261102.md)；
PRODUCT、ARCHITECTURE、ROADMAP、ADR 和旧验证的引用均更新。旧版本语句标明历史语境。
归并后 49 个已跟踪 Markdown 文件检查了 113 个本地链接、16 个本地锚点，未发现失效；本报告新增 5 个本地链接及 ROADMAP 的报告入口也已核对。

## 一次随机选择

GitHub 两个预声明搜索合并去重 148 个仓库；按公开、非归档、许可证、同仓 Java/Maven + Vue3/Vite、
真实业务路径、固定 commit 与源码预算（10000 文件、128 MiB 总量、16 MiB 单文件）核实，冻结 32 个候选。
不根据锁文件、管理器、Maven profile/根数、镜像源、测试或预计成功率筛选，也未预运行候选的构建或兼容检查。
旧 26 仓兼容语料和既有演示被预声明排除；该旧名单与最终候选无交集。
完整名单、每个 commit/许可证/源码计数、资格证据路径、148 项排除审计和全量排名见
[随机抽样清单](final-random-fullstack-sampling-20261104.json)。随机性限于这个搜索发现池，不代表整个 GitHub 的均匀抽样。

冻结文件 SHA-256：`dd951cfdac35310a7c3a4cf21057c0fe38087ebc12ba962b105ff85dbd32237b`。
冻结之后仅生成一次 32 字节随机种子：

`d38ed9c95941942ed2cd9a5a60e740dd4dd60fda238cc9f2fbe9ffb55aec27f7`

对按名称排序的候选计算 SHA256(UTF8(seedHex + LF + full_name + LF + commit))，选择十六进制摘要最小者。
从保存的清单重算排名一致；重抽 **0 次**。一次终端 Unicode 输出错误发生在 selection.json 保存之后，没有再生成种子。

选中 [校园实验室设备预约系统](https://github.com/Techd81/Campus-laboratory-equipment-reservation-system-based-on-Spring-Boot-Vue/tree/307156ac41ed7b5e5c4b8070bceacf2b4dc2cd05)，MIT。
固定提交 `307156ac41ed7b5e5c4b8070bceacf2b4dc2cd05`，tree `8f05690251b35365a5a5d60a5ddcd5d99bc38ec2`。
360 文件、1,582,431 字节；backend/pom.xml、Vue ^3.5.0、Vite ^6.1.0。
抽样后核对 LICENSE 和预约前端 API/后端 Controller，确有设备预约、取消及查询业务连接；没有运行上游代码。

## 首次启动的历史阻塞（凭据补齐前）

以合并后正式 JAR 启动，端口 8300，独立权威数据根 `data/random-fullstack-20261104/`，没有使用测试替身或直接写数据库。
试验前声明虚构学生画像意图：课前可靠预约设备、理解时段/规则及预约变化反馈；不称真实用户研究，不指定易通过的功能。
只有分析成功后才由产品正常生成画像、方向与计划。操作边界为同一仓库、一次生命周期尝试，最多一个有意义 Step/编码 Run；首个阻塞即停，不自动付费重试。

| 阶段 | 实际结果 |
| --- | --- |
| 正式应用启动、GET /api/system/connectivity | 200 UP，仅证明 HTTP 可应答 |
| POST /api/software-assets | 201；显式读许可、MIT 与 ALLOWED；asset `8615a13e-31aa-44bc-91bf-ece127dccda1` |
| GET 该资产 compatibility（指定 commit） | 200 COMPATIBLE，识别 backend/JDK17；仅静态 Git/资源能力，不是预检就绪 |
| POST 该资产 analysis | 502 EXTERNAL_CAPABILITY_UNAVAILABLE，0.5 秒；当前进程/User/Machine 没有 Key，AiGateway 在 HTTP 请求前 requireApiKey 拒绝 |
| 画像、方向、选择、Plan、官方 WorkingCopy、Step 确认 | NOT RUN，分析前置条件未完成 |
| 付费确认、Environment Preflight、真实 Coding Agent | NOT RUN；不能把静态 COMPATIBLE 当 READY |
| Candidate、Delivery、应用业务验收 | NOT RUN，没有相关 ID 或交付 revision |

产品首个失败分类：**外部运行配置缺失**；Docker 启动故障是另一独立外部前提。
该次缺凭据启动没有真实 Provider 请求；后续真实用量见下方续跑记录。该次也没有 Agent Run，不能伪造零 token 编码 Run。
只读查验试验 DB：asset=1；RepositoryProfile、UserProfile、方向、Plan、WorkingCopy、Run、Candidate、Delivery 均为 0。
资产静态准入仍保留部分源码/测试为主机材料；其对真实检查的影响尚未执行验证。

## 补齐外部配置后的真实续跑

凭据仅注入本次应用进程，未写配置文件、源码或数据库。Docker 恢复后继续同一固定仓库和资产；未重新抽样。

| 正常产品 API 阶段 | 实际结果 |
| --- | --- |
| Repository Analysis | 201，62.141 秒；RepositoryProfile `cfb74ce5-67f2-41bd-a7f7-4fb48b65ac4c`，一次最终提取即接受 |
| User Discovery | 一次虚构学生画像对话，200；真实模型提取与充分性评估，不手写结构化画像 |
| 审阅与确认 | 正常 assessment/confirm，画像 `a088b619-1bf5-474a-ae3b-ee44fa2060a0`，CONFIRMED revision 19 |
| 方向生成与选择 | 201，45.359 秒；4 个候选，选择“预约提交前的规则预检与失败原因解释”，依据主要痛点而非构建难度 |
| Evolution Planning | 201，19.39 秒；Plan `7bc1be8e-d40a-4df4-9c45-fb83fe97c932`，一个纵向 Step `adb5edec-22a3-426f-bcbd-8ecf2563938d` |
| 官方 WorkingCopy 准备 | 500 INTERNAL_ERROR，24.25 秒；日志定位到 CompatibilityGit.run:69 → GitWorkspaceAdapter.provision:1073 的本地 clone 调用 |
| Step 确认、付费启动、Preflight、编码、Candidate/Delivery | NOT RUN，前置 prepare 已失败；没有跳过步骤继续 |

计划内容包括后端共享预约规则评估、受现有权限约束的预检 API、Vue 预约日历/表单集成及成功/规则拒绝/时段冲突/并发失败的必要验证。
这些均由正常规划生成，没有外部代写实现或修改目标源码。

Provider 成功响应 **6 次**：分析 2、画像提取/评估 2、方向 1、规划 1。
日志返回 promptTokens=79,445、completionTokens=21,650，合计 **101,095 tokens**；reasoningTokens=15,678 为输出中的细项，不重复相加。
这不是 Coding Agent 的用量：编码 Run 尚未创建，没有 Agent modelCalls/tokens 运行记录。
原始 API 回应、结构化领域状态及请求阶段用量保存在本地试验目录；提交证据保留摘要、完整计划和原始记录 SHA-256。

失败后正常 GET 与只读数据库确认：Plan 仍 PROPOSED，Step 仍 PENDING_CONFIRMATION，workingCopyId=null。
资产 1、RepositoryProfile 1、UserProfile 1、方向 4、Plan 1；WorkingCopy、Run、Candidate、Delivery 均为 0，托管目录为空。
失败补偿没有留下半完成的官方工作副本。

### 有限诊断与后续修复建议

产品日志只保留通用 WorkspaceException，没有具体 Git 退出码、stderr 或分支原因；不能据此确定根因。
独立诊断在 target 内创建一次无 checkout 的临时克隆，使用相同 Git 参数和不含凭据的启动设置，原生命令退出 0。
Node 诊断工具另报 EPERM，这属于工具层观察，也不能直接当作正式服务失败原因。
诊断未重试产品 prepare，未替换资产，两个临时诊断目录已清理；它的成功不算官方 WorkingCopy 准备成功。

后续应先用标准本机启动器对同一已保存 Plan 复现准备，区分受限启动上下文与 Workspace 实现。
如仍失败，在 Workspace 边界增加净化的 clone 阶段、退出码及失败类别诊断，覆盖 Windows/浅克隆/固定提交和失败补偿回归；
保持凭据过滤、Hook 禁用、预算与来源完整性，不靠放宽安全边界解决。此次没有实施修复或为演示继续重试。

## 保留与当前最终状态

随机原库 HEAD/tree 与登记时相同，git status 为空。没有补丁、构建产物或依赖安装进入原库。
`data/random-fullstack-20261104/source/`、试验 DB/SQLite 文件、已确认画像、方向、Plan、API 回应和启动日志保留。
含凭据的试验应用进程已经停止；本轮未创建运行容器或 Candidate。原有主数据库、workspaces、agent-runs 和两个受保护目录未改。
新增内容只提交文档和净化证据；没有重跑既有后端/前端套件，也没有把原生诊断克隆当作产品通过。

**MERGED/CLEANED/PASSED**：本地合并、前述冒烟、文档归并、安全 Docker 清理、真实分析/画像/方向/规划及原库完整性。
**RANDOMLY SELECTED / ACTUALLY EXECUTED**：同一随机固定仓库、6 次真实模型响应与上述正常 API 流程，重抽 0 次。
**FAILED/BLOCKED**：官方 WorkingCopy 准备，Workspace 执行上下文/适配失败，根因未确认。
**NOT VERIFIED**：目标原生环境预检、Maven/前端检查、编码 Agent、Candidate/Delivery、业务和视觉验收。
当前建议先定位 Workspace 准备问题；不宣称完整产品演示已就绪。
