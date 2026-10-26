# DelveForge 全新多 Step 独立 E2E 验收

日期：2026-10-26，Asia/Shanghai。分支：`codex/independent-multistep-e2e`；未合并、未推送。
起点为实际核对的最新实验实现 `b57cdbbb9455d3f1344134ab9a6976aa86a754d0`，不是 main 的旧版本。
完整本机证据：`E:/develop/DelveForge/data/independent-multistep-20261026/`。

## 结论

**完整独立验收未通过。** 全新数据下的真实 User Discovery、画像确认、仓库分析、方向选择、
四 Step 规划及 WorkingCopy 准备均完成。Step 1 的 DeepSeek 自主修改与编译实际发生，
但测试准备以 `DEPENDENCY_PREPARATION_MUTATED_SOURCE` 失败，没有 Candidate 或正式 Delivery。
后续 Step 按顺序与可信版本规则保持未执行；没有累计交付，也没有可供最终用户验收的演化产品。

这是一次真实失败的产品验收，不是一次已经完成的多 Step 交付。没有为得到成功结论而手工修目标代码、
改计划、追加实现提示、生成反馈轮次或重复付费尝试。没有确认 DelveForge 产品缺陷并实施修复。

## 仓库、隔离与冻结用户需求

选择 [KhaledAshrafH/Taskarium](https://github.com/KhaledAshrafH/Taskarium)，固定原始提交
`91f69ab1d7ab3a654814a1971cd3871ab0dffb1d`。实际代码包含 Spring Boot 3.2.5、Maven、Java 17、
Thymeleaf 页面、H2 文件持久化、注册登录、任务 CRUD、任务笔记与用户归属查询；共 73 个受跟踪文件、
752,250 bytes。已检查仓库 MIT LICENSE，并通过普通资产 API 显式声明读取权限、许可与使用授权。
原克隆的 POM、配置、依赖、源码及历史均未人工适配。

Lin，24 岁，初级 Java 开发者，同时为社区读书会做志愿者，每月组织两次活动。她每晚在笔记本上查看任务，
周日安排未来一周；事项和笔记散落在手机备忘录、聊天记录中。她懂 Java、Spring Boot、SQL 和基础 HTML/CSS，
能运行 Maven 应用，没有精力维护多台服务器、复杂前端工具链或付费 SaaS/通知服务；实际使用优先于作品集包装。

在任何 User Discovery 或规划前冻结的原始需求为：

- 创建、编辑任务截止日期，快速辨认逾期与即将到期事项。
- 查看未来一周安排，并看到完成任务后剩余工作量与进度的变化。
- 保留任务描述与关联笔记，重启后数据仍在。
- 不同账号不混用个人任务；保持本地、简单、个人使用。

原始浏览器验收包含注册登录、创建逾期/近期/较晚/完成任务及笔记、修改日期、完成任务、查看周安排与进度、
重启保留及第二账号隔离。没有规定 Step 数、代码结构、表结构、API、库或测试实现。
原始 persona 文件 SHA-256：`a5388af4997fe24c3fc9285dc56d4a63cbea3529cf9f0fe24d8d24b8ae63e57f`，
并在 `aff61ad` 提交冻结记录。确认计划后没有增加业务要求。

新数据库初次启动时项目与资产列表为空；FinTrack 的画像、分析、方向、计划、副本和执行状态均未复用。
本次数据库、WorkingCopy、Agent root、owner 和缓存名称均独立，未配置旧 WorkingCopy root 回退。

## 真实 M1–M3 与原始计划

| 对象 / 阶段 | 实际结果 |
|---|---|
| User Profile | `68eedc88-5611-41b4-9791-7411aa22a4c0`；一次完整真实需求输入，DeepSeek 提取并评估充分性；正常审阅后确认 revision 17 |
| Software Asset | `93d346df-8444-44e0-aa8b-cbf443ca33e7`；普通注册 API，MIT、读取允许、usage ALLOWED |
| Repository Profile | `987e185d-b1bb-471d-92f1-a251bb875f3a`；真实 Scout 与最终分析，固定到上述原始提交 |
| 兼容性 | 普通只读 API 返回 `COMPATIBLE`，自动选择 JDK 17，根 Maven 模块，无阻塞问题；不等同于构建、测试或演化保证 |
| 方向发现 | DeepSeek 一次生成四个方向：周计划个人任务板、读书会活动工作台、安全与持久化任务库、文本收件箱 |
| 选择 | `26fdf6fe-1cfc-4ea4-acf2-4cb5f9720c58`：Taskarium 周计划个人任务板；它对原始日期、周安排、进度需求覆盖最直接，选择时没有考虑或指定 Step 数 |
| EvolutionPlan | `281e9daa-e68c-48f1-8a8a-89326dffca29`；一次生成四 Step，无 `planningNotes`，没有编辑、拆分、合并或重生成 |
| WorkingCopy | `8ab00aed-43c3-4a57-aadc-2d3391cb6618`；正常 prepare 激活 Plan；IDENTITY 准备，A=P，无删除或元数据补丁 |

[完整原始规划响应](evidence/independent-multistep-e2e-plan.json)保留了原始请求、响应、时间及各 Step 内容；
本机原始快照 SHA-256 为 `bc23239993340200a87c740c5bad64e3d8f8a83db0696348ec0780dbb59125a1`，
在执行前提交为 `dd8f217`。下表仅概括内容，不改变正式计划：

| Step | AI 生成的能力增量 | 最终状态 / 证据 |
|---|---|---|
| 1 | 截止日期、完成状态、任务编辑可靠保存、相关列表/详情与校验 | 确认后 READY；真实 Run FAILED；无 Candidate、无 Delivery |
| 2 | 首页逾期/即将到期分类、未来一周视图与笔记可见性 | PENDING_CONFIRMATION，未执行 |
| 3 | 完成/取消完成、剩余数量与进度汇总 | PENDING_CONFIRMATION，未执行 |
| 4 | 重启持久化、密码哈希、登录和用户隔离回归 | PENDING_CONFIRMATION，未执行 |

**规划质量限制：** 仓库分析明确说部分配置、模板和测试未进入分析材料，方向与计划却在部分文字中把这种
材料缺失推成需要新增能力；原始 Task 也已经有 `TaskStatus` 字段，方向声称它没有完成状态并不准确。
这些原文均保留。没有由 Codex 改正后再提交给编码 Agent；Agent 实际自行读取了原仓库文件。

## Step 1：实际执行、检查与失败边界

Run：`a650c901-e265-4ab6-80e0-05339296e2c2`，22:05:32–22:18:06。
通过普通 Step 确认及启动 API 执行，`paidUsageAcknowledged=true`，JDK 17。
真实沙箱事件证明 network=none、只读 root、2 GiB、2 CPU、256 PID；没有增加累计调用、tokens 或总运行时限。

[持久化事件副本](evidence/independent-multistep-e2e-run-events.jsonl)包含 sequence 1–165：

- DeepSeek 自行完成 37 次文件探索：读取 36 次、列目录 1 次；25 次成功代码修改，1 次补丁格式冲突。
  冲突后 Agent 自行使用现有精确编辑工具继续，没有 Codex 业务代码或测试介入。
- sequence 160–161：实际 `run_checks compile` 返回 `BUILD_PASSED_NOT_TESTED`，0 项测试。
  约 385.9 秒的工具调用包含依赖准备，离线编译本体约 1.5 秒。
- sequence 163–164：`run_checks test` 在约 100.1 秒后以 `DEPENDENCY_PREPARATION_MUTATED_SOURCE` 结束，
  `ok=false`、`resultRecorded=false`；不是成功的测试结果。
- sequence 165：Run=FAILED。失败点是 `DockerAgentSandbox.prepareDependencies` 的前后源码快照比较。
  没有到达冻结或交付；不能把在线准备中可能发生的测试活动算成已验证的正向目标测试。

**已确认的终止机制是源码快照不一致；具体发生变化的路径未被现有事件保留。**
仓库内有文件型 H2 数据库，原始 SpringBootTest 使用默认应用配置，这是可调查线索，
但不能据此断定本次究竟改了哪个文件，也不能把 TLS 错误认定为该终态的根因。
本次没有在证据不足时修改 DelveForge 的安全合同或外部项目配置。
产品清理后 `retained=false`，Run 容器已移除；没有把未冻结的中间改动恢复成正式成果。

## Git、SQLite 与浏览器结论

[独立 Git/SQLite 证明](evidence/independent-multistep-e2e-git-sqlite.json)来自实际 Git 读取与只读 SQLite 连接：

- 原库 HEAD、WorkingCopy HEAD、sourceRevision、preparedRevision、currentRevision、lastVerifiedRevision
  均为 `91f69ab1d7ab3a654814a1971cd3871ab0dffb1d`；两个仓库 Git 状态均干净。
- 原克隆完整源文件 size/SHA-256 快照与执行前相同。
- Candidate=0、Delivery=0；所有 Step 的 baseline_revision / delivery_revision 都为空。
  AgentRun 自己的 baseline 记录为 A；未把它冒充已接受的 Step Delivery 基线。
- SQLite integrity=`ok`、0 外键问题、0 未释放 Run/Delivery 占用；一致备份为 `final-consistent-backup.sqlite`。
- 因此实际只有 **A=P=当前可信版本**，不存在 C1→C2→C3→C4 累计交付链；后续基线继承未被验证。

真实浏览器打开本次独立项目，并显示编码失败、准确原因、30 次模型调用、65 次工具调用、635,067 tokens，
以及没有 Candidate。见[失败终态截图](evidence/independent-multistep-e2e-failure.png)。
这证明 DelveForge 的失败展示与数据库事实一致，不是演化产品的浏览器可用性证明。
没有正式交付版本可供运行；最终 Taskarium 启动、业务页面、重启持久化与账号隔离验收均未完成。
原始用户需求没有得到可使用的交付实现。

## 用量与 Codex 操作

[实际用量汇总](evidence/independent-multistep-e2e-usage.json)：

| 范围 | 模型回复 | 工具调用 | 提供者报告 tokens |
|---|---:|---:|---:|
| User Discovery | 2 | — | 5,565 |
| Repository Analysis | 2 | — | 75,269 |
| Product Directions | 1 | — | 13,079 |
| Evolution Planning | 1 | — | 12,751 |
| 唯一 Coding Agent Run | 30 | 65 | 635,067 |
| 合计 | 36 | 65 | **741,731** |

reasoning tokens 已包含于 completion，没有再次相加。数字是已报告用量，不是金额或提供者完整账单。
失败后未启动新 Run、生成新 Plan 或继续付费演化。

Codex 的操作与干预全部单列如下：

1. 选择公共仓库、冻结 persona，建立隔离环境，通过普通 API 输入原始需求并作用户已授权的审阅、确认、选择、准备和启动。
   没有直接写 SQLite 领域记录。Key 通过不回显输入进入后端进程，未写入源码、日志、报告或提交。
2. 初次“确认画像并分析”的工具审批认为缺少人类确认。只读核对原请求明确授权
   “Codex may select and confirm ordinary user-facing choices consistent with the precommitted persona”后，
   正常审阅及确认操作获准；没有绕过领域确认或工具拒绝。
3. 观察到受信任依赖代理的 `SSLEOFError` 后，只读核对当前容器无代理变量；宿主及经既有本地代理的受信任镜像
   Central HEAD 均为 200。仅在证据目录创建独立 Docker 客户端代理配置，并验证其 HEAD=200；
   未改变全局 Docker 设置、目标源码、运行中容器或应用隔离规则。
4. 原计划是先正常取消网络阻塞再按新环境启动一次新尝试，但取消请求到达时 Run 已因上述源码快照原因失败，
   响应仍是 FAILED、cancelRequested=false。**没有实际取消成功，也没有启用新配置重启后端或发起重试。**
   网络诊断与真正终止原因分开保留，未用一个已改善的连通性探针代替未解决的源码变化问题。
5. 只做事件观察、数据库一致备份、Git/SQLite 证明和浏览器失败状态检查。
   验收辅助脚本曾修正模块名和时间戳解析问题，均未改 DelveForge 或 Taskarium 产品行为。

## 起点实现的独立回归与提交边界

本次新执行的 `mvnw.cmd -o verify`：165 XML，1,699 总计、33 门控跳过、**1,666 实际执行**，零失败/错误；
前端 17 个测试文件、253 项通过，类型检查及生产构建通过。两种 JDK 镜像经真实 Docker 协议 v3、脚本 hash 与
Java 版本检查。Provider/Docker/Public Repository 的门控跳过不称为通过；这些 DelveForge 回归也不替代目标业务验收。
构建 JAR SHA-256：`e70dac9bb39a01beb4e669c1692263629ddcaa6bcfe831e741a2ecbdb327839e`。

本分支只提交验收报告与证据，没有产品源码修复或依赖变更。main 保持 `80cd124`；
原有 8 份 `frontend/design/`、`grilling/` 草稿的 hash、size、mtime 核对相同。
未对 FinTrack 数据库或旧 WorkingCopy 执行写入，未合并或推送。
本次到此结束，保留实际失败状态与证据，不把验收扩展成新的稳定化工程。