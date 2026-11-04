# 补丁冲突修正与原随机全栈试验续跑

日期：2026-11-04，America/Los_Angeles。事件原始时间另保留 UTC。

## 当前结果

工程修正与所列回归已验证；真实预约预检演化仍失败，未产生 Candidate 或 Delivery。

| 阶段 | 结果 |
| --- | --- |
| ENGINEERING FIXES VERIFIED | YES，限本文明确列出的覆盖范围 |
| AGENT EVOLUTION COMPLETED | NO；最新 Run 为 FAILED / POLICY_REJECTED |
| CANDIDATE FROZEN | NO，正式数据库 Candidate 数为 0 |
| PRODUCT ACCEPTANCE VERIFIED | NO，三项独立业务验收未到达 |
| WAITING FOR USER BASELINE-DEBT APPROVAL | NO，没有可供批准的 Candidate |
| DELIVERY COMPLETED | NO，正式数据库 Delivery 数为 0 |

本次续跑消息到达时，Run `1cab61f7-e8b9-469b-8544-87add6004c1b` 已在执行，记录为 34 次模型调用。
继续观察该正常授权 Run，没有为续跑消息另建重复请求。其后完成本次要求的 86 项复验与两项真实沙箱编排集成。
最终在第 160 次模型响应后的读文件动作中被现有路径格式规则拒绝。没有达到冻结阶段，也没有用选测通过追认成功。

## 固定身份与本地 Git

上游仍为 [Techd81 校园设备预约系统](https://github.com/Techd81/Campus-laboratory-equipment-reservation-system-based-on-Spring-Boot-Vue)。

- source commit：`307156ac41ed7b5e5c4b8070bceacf2b4dc2cd05`。
- source tree：`8f05690251b35365a5a5d60a5ddcd5d99bc38ec2`。
- EvolutionPlan：`7bc1be8e-d40a-4df4-9c45-fb83fe97c932`。
- EvolutionStep：`adb5edec-22a3-426f-bcbd-8ecf2563938d`。
- authoritative WorkingCopy：`c9086c3b-f5d7-4ec7-89dc-fd7f5218d794`。

未重新抽样、分析、画像、方向生成或规划；未重新创建正式副本。
Plan/Step/WorkingCopy 的持久记录及 Step 计划条目与本系列试验开始时一致，状态仍为 ACTIVE / READY / READY。

| 本地提交 | 意义 |
| --- | --- |
| `ecd87ff344cd5ccd9c8e29fe43303f82d2ec9f5c` | 正常合并原工程分支；包含已审阅的源码保护、旧 Maven 支持、v5 与打包修正 |
| `fd3e357fe6d1270af73025e9b9598b1acd14869d` | 可选、有界的本次验收说明，经普通启动 API 净化传递并审计，不变更持久授权 |
| `fa9d23b` / `83a59dd2191345b7efb4cad41fbd28296149bb93` | 同批写入冲突修正及其正常合并 |
| `7f19c6e` / `803340d537930031ff796953a640def85de19cba` | 本次新增真实沙箱编排覆盖及其正常合并 |

本次检查确认 `main` 已包含原修正，没有重复回放旧提交；仅合并新增测试覆盖。
`803340d` 相对 `83a59dd` 的生产代码没有变化。所有合并为普通可审计合并，没有强制操作、历史改写、分支删除或远程推送。
`frontend/design/` 和 `grilling/` 保持原样。

## 补丁冲突的已证实编排缺口

前一次 Run `ff9d8267-4439-4a30-9462-aee2371c23be`：27 次模型调用、48 次工具调用、812,273 Token，
以 PATCH_CORRECTION_LIMIT 失败，无 Candidate。该失败事实保留。

持久事件 601 是成功写入。随后同一模型响应 request 22 内，对同一 ReservationRuleEvaluator 文件的两个写入分别在
605、607 返回 CONTEXT_MISMATCH、CHANGE_ALREADY_PRESENT；两次都确认 mutationNotStarted。
读取当前文件后，request 27 的另一补丁在 627 冲突，628 触发停止。
原实现会在同一响应中继续执行冲突后的预生成写入，消耗纠错机会，却没有先让模型依据实际结果重生成动作。
原始补丁正文未保留，不能进一步断言具体哪段上下文错误或模型内部为何生成它。

局部修正位于 CodingAgentService、AgentPatchCorrections、AgentToolActivity：

- 首个已证明未开始修改的真实 PATCH_CONFLICT 后，同一响应的后续写入返回 PATCH_NOT_EXECUTED；读取仍可执行。
- 每个工具 ID 都有对应结果；未执行动作使用单独事件，不计调用尝试、真实冲突或成功修改，并按 ID 缓存结果。
- 下一模型响应可依据当前源码生成新编辑；此前成功修改仍保留。
- 成功实际修改重置原纠错状态；重复失败补丁、三次真实未解决冲突、凭据保护、取消及未知写入的停止规则不变。
- 历史压缩保留冲突和未执行反馈。edit_file 仍优先，apply_patch 和内部 unified-diff 路径均保留。

未增加累计调用、Token、编辑次数或编码轮数停止阈值。未增加模块、依赖、数据库表或通用审批框架。
设计补充见 [ARCHITECTURE.md](../ARCHITECTURE.md)；v5 合同仍见 [ADR-0023](../decisions/0023-baseline-aware-candidate-verification.md)。

## 确定性与真实沙箱验证

| 命令范围 | 实际结果 |
| --- | --- |
| 首次合并前 Maven Wrapper 定向 verify | 229 项，0 failure/error/skip；含 14 项真实 Docker 检查 |
| 冲突原因诊断定向 test | 11 项，0 failure/error/skip；含真实沙箱读取后修正场景 |
| 同批冲突修正后的定向 test | 86 项，0 failure/error/skip |
| 本次重跑上述 86 项，加 PatchConflictOrchestrationDockerTest | 88 项，0 failure/error/skip；两项集成均真实执行 |
| 受限 Linux 容器中的 test_patch_feedback、test_patch_witness、test_sandbox_policy | 42 项通过，无跳过 |

不同命令有覆盖重叠，不累加为唯一测试数量。未把全部门控集成套件描述为已执行。

新增两个集成场景使用确定模型动作及真实 GitWorkspaceAdapter、DockerAgentSandbox、Maven、SQLite 和 Candidate 冻结。
第一个场景首先冲突；第二个场景先有一次成功编辑再冲突。两者都跳过同文件及不同文件的两个后续写入，
逐一反馈工具 ID，下一响应读取当前源码，再正确编辑并运行完整夹具测试。
最终各自冻结 v5 FULLY_TESTED 合成 Candidate，原资产与正式夹具副本保持基线。
它们分别记录 5/6 次实际工具尝试，跳过均为 2；Provider 请求均为 0。
这两个合成 Candidate 不属于原校园预约试验，也未执行合成交付。

现有 60 项编排测试覆盖重复失败补丁、真实成功重置、三次冲突、历史压缩、工具身份和费用边界。
42 项 Linux/Git 测试覆盖预演与应用间前像变化、多文件写入前核验、重复应用、CRLF、应用后未知状态及源码保护。
没有删除测试、放宽断言或把不确定写入当作未发生。

负面验证也保留：最初跨模块编译与 Docker 门控配置不一致的命令不算成功；修正后完整重跑。
同批冲突回归在修正前确实失败。新增集成首次在证据导出 Instant 时产生两个错误，仅改时间字段投影后完整复跑；
Python 首次少挂载 Java 规则目录，补齐只读输入后原样复跑。失败日志及报告摘要均在结构化材料中。

## 实际可执行包与环境

真实 Run 使用从 `83a59dd` 的 main 构建并核验的应用：
`75a4317a5da9e4ac301af673a72bb3d13ccb639e075cd253aa7dee1657a11c87`。
该文件已作为本地实际运行包保留，没有在活动 Run 中替换它。

测试覆盖合并到 main 后，停止无占用的自建服务，从 `803340d` 再执行 Maven Wrapper package。
该次只打包，使用 -DskipTests，测试通过依据来自上表独立 test/verify 命令。
最新应用 SHA：`ecf6e76f188f97f3d63b6c6a2bf14ad7f97925a6f4205b86bcc5c71f748655b3`。

| 最新嵌套模块 | SHA-256；均逐字节等于当前 reactor JAR |
| --- | --- |
| Domain | `a3aaeeb4400f7d3b4ed21a44f23ac05a7672e9bba09d0d70046b92b9404cb44d` |
| Application | `d9acf2cbf1b426a5c1b5a7771f49f1fc04bda3869e44b0265781b8725ddeae73` |
| Infrastructure | `d72470fc1240e3b8aceb18ac20404881db601646c67993e3b5d04a6361d03b75` |

两个应用包的全部生产 class 字节相同：Domain 62、Application 284、Infrastructure 127、App 81，共 554 个。
包外壳因重新打包而摘要不同，不能称整个 JAR 字节相同。
检查了 PATCH_NOT_EXECUTED、两种编辑工具、ExactEditPatch、v5 比较、完成反馈和当前源码保护；
五个脚本/资源逐字节匹配源文件，镜像工具内容也与其匹配。并非只凭 BUILD SUCCESS 判断新鲜度。

实际 JDK17 镜像：`sha256:c3c1dc7b0acddf70a402768afa31693eb3fb5aaf86d04fb84bbe8ed36366055e`；
JDK21：`sha256:e95b5d2b0e8311313f910ad063ef9c7c822f6b50bd6df3fa6a700b54e8cbce44`。
协议 v8、固定 JDK/Node/npm/pnpm 工具及脚本摘要已核验；本次没有改镜像工具。
owner 为 delveforge-local-agent，Maven 缓存为 delveforge-maven-central-cache，归属标签一致。
真实 Run 记录 JDK17、2GiB、2CPU、PID256、无网络执行及只读根；原工具期限和隔离不变。

## 最新真实 Run 的事实

Run：`1cab61f7-e8b9-469b-8544-87add6004c1b`。
应用记录开始 `2026-11-04T15:53:35.247102Z`，终止 `2026-11-04T16:33:16.445680500Z`。
通过原 Step 的普通启动 API、费用确认、JDK17、backend/frontend 目录及真实配置 Provider 执行。
三项验收案例通过有界 acceptanceContext 传递，未变更计划授权或导入旧 19 文件补丁。

- MODEL_REQUEST 事件及接受响应各 160；记录 Token 10,357,386。
- 应用工具尝试计数 207；另有 1 个同批写入 PATCH_NOT_EXECUTED，不计入该数。
- 成功写入事件 81，真实补丁冲突事件 14；这些是执行事实，不能作为功能成功证据。
- 最新失败为 PATH_FORMAT / PATH_VALIDATION，源于 DockerAgentSandbox.requirePath；不是累计调用停止。

真实预检为 READY_BASELINE_FAILURE：后端 270 项、5 failure、31 error；前端 387 项全通过；
原脚本无独立 typecheck，build 实际包括 vue-tsc 与 Vite 并通过。
这些是本 Run 的原始基线，不复用旧 Run 的 5/32 或旧最终实现结论。

后续检查事实：最后一次后端通过仅执行 35 项，不能覆盖 270 项全量基线义务。
前端曾在 snapshot `ff0a6b00…` 构建通过；之后源已变化，不是最终构建证明。
最后前端测试为 snapshot `a8e38c49…`：392 项、7 failure、0 error、0 skip，仍未解决。
没有完整、当前、可比较的冻结证据；不能标为 BASELINE_DEBT，也不能把少量选测通过标为全量通过。

真实运行中确实记录到一次 TOOL_NOT_EXECUTED，说明当前包执行了新分支。
该修正未保证模型所有后续编辑或业务实现正确，后来的路径拒绝属于不同失败。

## 最终路径拒绝及证据限制

事件 1365 是仍有 7 项失败的前端检查；1367 记录 request 160 用量；1368 开始 read_file；
1369 在主机 Java 路径校验阶段以 PATH_FORMAT 拒绝，1370 将 Run 置 FAILED。
该次调用没有读出文件结果，也没有源码写入。没有模型 completed、FINALIZING 或冻结事件。

现有规则拒绝绝对路径、反斜杠、冒号、控制字符、引号、过长路径等不合规输入。
安全元数据将此次目标标为 withheld，没有保存原始被拒绝路径，因此无法确认是哪一种形态，
也不能证明某个合法路径被误拒。没有据此放宽校验、增加目录例外、改工具或再次付费运行。

Run 终态 occupied=0、retained=false；按既有策略，未冻结的该次隔离源已清理。
保留的是 Run、事件、检查元数据及日志，不能声称保留了完整失败源码、补丁正文或逐例最终清单。
本次没有把 FAILED 改回运行、强制冻结或调用未经支持的 resume。

## 独立业务验收与交付

| 原 Step 的验收项 | 本次结论 |
| --- | --- |
| 预检与创建共享权威规则，覆盖可用性、时间、冲突、限制及解释 | NOT_REACHED；代码写入记录不能证明共享与一致性 |
| 09:00 旧响应晚于 14:00 改选时不得覆盖当前结果 | NOT_REACHED；无冻结源码可独立验收 |
| 创建失败使旧预检失效，下一提交重新预检 | NOT_REACHED；未进行业务级或数据库集成验收 |

没有启动候选应用、一次性业务数据库或浏览器验收来凑结果；没有手写或补修目标功能。
正式 Candidate/Delivery 均为 0，verificationLevel 不适用，没有差异/证据哈希可供债务批准。
未代用户确认历史债务，未推进接受 revision。

## 完整性与后续边界

原仓库与正式副本各 360 个受跟踪文件，分别与本系列开始时的文件 SHA、index、HEAD/tree/status 相同。
原 ea9f4a76 失败 Run 的 100 次调用及 6,299,421 Token、前置失败记录、ff9d8267 的失败记录全部保持。
原保留卷 356 份物理材料再次只读核对一致，清单 SHA：
`5033b7a79ebb92fb6e569dc9768536b57dcc734418a7f249502db000adf4cf2b`。
本次自建服务已在确认没有占用后停止，未停止其他用户进程或清理其他项目资源。

当前无待批准 Candidate。下一次真实尝试前，需要先取得可安全定位的路径请求原因并建立可复现依据；
同时，前端失败和全量当前验证义务仍未完成。不能把放宽路径规则、再跑一次或人工修目标当作解决方案。
这次结果是工程纠正与真实失败证据，不是完整产品 E2E 成功。

结构化材料见 [执行证据 JSON](patch-conflict-resumption-evidence-20261104.json)。
本地完整事件、前后事实、构建包、失败日志及校验记录在 `target/original-random-completion-20261104/`；
合成沙箱证据在 `backend/delveforge-infrastructure/target/patch-conflict-orchestration/`。
这些忽略目录没有作为第三方源或模型正文提交到 Git。
