# 普通 Vue 仓库兼容性与真实模型试验

日期：2026-11-02。分支：`codex/full-stack-evolution-checks`。

## 试验前边界

先完成通用兼容性修正和确定性验证，提交固定实现基线，再启动一次真实 DeepSeek 产品流程。
真实试验开始后不修改 DelveForge 实现、提示或规则，不改原仓库，不代写目标功能，不重写生成的方向或计划。
技术检查、Candidate/Delivery、独立功能验收分别记录。失败保留，不换仓库追求成功。
用户已明确授权本次 API 使用并另行提供凭据；凭据仅进入隔离进程环境，不进入本报告或 Git。

## 兼容性抽查

主样本为三个持续维护的普通公开项目；仅读取公开文件，并对两个原样 Git 副本调用真实兼容性适配器。
没有运行样本安装脚本，也没有修改它们的源文件、manifest 或锁文件。

| 项目 | 观察 | 分类及处理 |
| --- | --- | --- |
| [v3-admin-vite](https://github.com/un-pany/v3-admin-vite/tree/c2c895d71d571e9c18c2453fa85469a479c79fc6) | `vue-tsc && vite build`、`test: vitest`；锁为 v9/SHA-512。固定 tree 含环境文件、`.npmrc` 及符号链接 | 常见脚本被拒绝是人为限制，已通用修正。受保护路径/符号链接仍使实际预检 BLOCKED，不清理或绕过 |
| [IT-Tools](https://github.com/CorentinTh/it-tools/tree/d505845f918e946ec300af7b36efc107e2f66e9e) | pnpm 9.11.0、v9 锁；工程预检 COMPATIBLE；`concat-map@0.0.1` 锁记录为 SHA-1；构建含 NODE_OPTIONS，test 转调 test:unit | 精确版本匹配是人为限制，已修正；SHA-512 限制及未识别复合脚本保留，不能把预检通过说成安装/构建通过 |
| [Vue Pure Admin](https://github.com/pure-admin/vue-pure-admin) | 缺 Vitest，Node 要求 >=22.22.1，工作区配置与复合构建 | Vitest 不是工程准入前提，已修正；当前固定 Node 22.16.0、工作区及自定义构建不支持，延期 |

另对 mini-qr、vue3-ts-vite-starter-template 的根元数据作补充筛选：前者含环境文件/`.npmrc`，后者含工作区配置。
没有进入真实模型流程，也没有为它们加适配。未因寻找演示成功而外部改造项目或无限增加支持范围。

必须保留的安全边界包括固定受控下载入口、锁完整性、禁用安装生命周期/hook、离线检查、源码快照及不可变 Candidate。
当前对全部环境文件、符号链接、工作区的拒绝是现有保守支持边界，不表示这些公开项目本身不安全。
放宽它们需要独立评估，不在本次有限稳定化中实现。

## 通用修正

- 工程准入只要求 Vue 3/Vite/TypeScript；vue-tsc、Vitest 随对应检查选择，不强迫项目添加工具。
- `packageManager` 与唯一锁对应，接受 npm 9/10、pnpm 9–12 的明确版本及 Corepack hash 后缀；实际镜像仍固定 Node/npm/pnpm。
  联网准备不携带此字段，不能自动下载或切换仓库指定工具链。
- 增加明确列举的 `vue-tsc` 构建组合、`vitest` 单次运行，以及 `type-check` / `test:unit` 名称。
  不提供通用脚本解释器、环境赋值、任意转调或 shell。
- 缺少可选检查返回 `CHECK_NOT_AVAILABLE`，不伪造 PASS；NOT_TESTED 合法。
  已声明但未安装、执行失败和配置不支持仍独立分类；不可用记录不能清除已有失败。

## 固定真实试验设计

试验前确定性验证：`mvnw.cmd -q verify` 退出 0，1819 项、零失败/错误、40 项按条件跳过
（domain 209、application 858、infrastructure 530、app 222）。单独启用 Docker 的 npm/pnpm
全栈 smoke 为 2 项、零失败/错误/跳过，真实执行耗时 583.412 秒；不是实际模型试验。
前端 304 项测试通过，typecheck/build 通过。最终 Linux 镜像策略 46 项及代理 4 项通过。
最终固定镜像的原生协议 7 项通过，本地最终 package 退出 0。
宿主 Python 缺少 YAML 依赖的初次尝试保留日志，成功策略运行使用正式镜像的 Python/YAML 环境。
smoke 后补充了非法元数据/脚本不得伪装为不可用检查的负向拒绝规则，该规则经最终 46 项策略回归验证，
没有把负向规则补充描述为重新执行整个 smoke。

实际试验镜像：JDK 21 `sha256:b4a12ae2fc98d198ac624062c2efc93459b46913ca3854d392acadc26e00b97c`，
JDK 17 `sha256:2e321fb37187a992779be7bc5e2585cfb99abde457ce4538ca7135adff31999b`。
均由正式启动辅助流程核对当前脚本和 Node 22.16.0 / npm 10.9.2 / pnpm 12.9.1。

唯一目标仓库：`https://github.com/CorentinTh/it-tools.git`，GNU GPL v3，固定 revision
`d505845f918e946ec300af7b36efc107e2f66e9e`。只在本机隔离试验中使用，不发布目标工程产物。
这是工程预检通过的普通 Vue/Vite/TypeScript 单工程，安装和脚本限制已知，不能预先宣称端到端受支持。
保留 SHA-1 旧依赖及原脚本，真实试验可能在检查阶段失败；不因此更换项目或放松锁完整性。

在观察任何模型结果前固定用户目标：在现有 JSON 查看/格式化工具中，支持按对象路径和数组下标提取字段值，
可复制提取结果；非法 JSON、非法路径、路径不存在及空输入有明确反馈；保持原格式化、排序和缩进能力。
内容只在浏览器本地处理，不增加后端或上传，不扩大为整站重构。方向和具体 Step 必须由 DelveForge 正常生成与确认。

## 实际结果与协议变更

**总体展示结论：BLOCKED；ASTRA_READY = NO。前置小试的实际 Run 结果：FAILED。**

运行时实现固定为 `0a2c7b0012664f06f9771ca1460892ede1675169`，应用 JAR SHA-256 为
`e0316935cb5edf8175de835b82cdc6fa906bbf192fdcdea5dfec644499dc0e5f`。
试验中没有修改 DelveForge 代码、提示、规则、模型响应、目标源文件、生成的方向或计划。
后续提交仅记录结果，不是新的运行基线。

本小试按此前已授权的“JSON 路径提取”目标启动。运行期间用户追加了“完整产品、自主发现、虚构人物、展示验收”协议。
此前输入已明确指定功能，不能追溯改称跨领域自主产品发现，也不能把实际生成的一步计划包装为完整产品展示。
该输入是模拟开发者需求，不是真实用户访谈；没有冒充已完成新版要求的虚构人物实验。
用户随后调用 Stop That Shit，工作收敛到保全已经发生的事实，不再继续筛选仓库、补适配或另开付费实验。
新版完整产品实验没有启动；下面均为前置小试的真实结果。

### 正常产品流程

真实运行使用独立应用进程、数据库与工作区，端口 8196，真实 `deepseek-flash`，没有脚本化模型。
通过现有 HTTP 产品接口进行用户操作，未直接写领域数据库或 Git 状态。

| 阶段 | 真实结果 |
| --- | --- |
| User Discovery | 真实模型提取需求并评估充分性，UserProfile revision 17，经正常审阅和确认进入 CONFIRMED |
| 注册与分析 | 正常登记 GPL-3.0 资产并授权本地复用；模型读取 36 份仓库材料，形成 RepositoryProfile |
| 方向 | 一次生成四个方向，正常选择第一个，没有重生成或改写候选 |
| 规划 | 一次生成一个有界 Step，涵盖可见 UI、解析/取值、复制、错误反馈与测试，没有外部拆写计划 |
| 准备与确认 | 正常 prepare 与 Step confirm，WorkingCopy 为 IDENTITY 准备，A=P=原始 revision |
| Agent | `106fa137-3575-430b-8f32-999bb9d3c76f`，38 次模型响应、48 次工具调用；自主读取和修改源码 |
| 冻结 | `FAILED / UNRESOLVED_CHECK_FAILURE`；没有生成 Candidate |
| Delivery / Plan | 没有 Delivery，不是 Delivery 已失败或成功；Plan=ACTIVE，唯一 Step=READY，无 deliveryRevision |

保存的真实方向为：

1. **在现有 JSON 查看/格式化工具中加入路径取值与复制面板**（选中）。
2. Data 分类下新增独立的 JSON 字段提取小工具。
3. 抽取 JSON 路径解析与安全取值的 TypeScript 核心模块。
4. 路径提取功能的可演示验收测试与示例夹具。

第三、第四项本身不提供原需求的完整用户界面，不把它们算作完整产品方向。
选中的方向与用户输入匹配，但因目标事先被明确指定，不能据此证明“发现惊喜”。

Plan 为 `e4d286e7-af00-47e4-9840-9e73837e6689`，唯一 Step
`b2da7431-57ea-4af8-8862-6ad9a73c65c4` 的实际目标是：
“在现有 JSON 查看/格式化工具页面交付可用的本地路径提取与复制功能。”
未增加、删除或重写任何 Step，也未因检查失败启动新 Run。

### 实际源码与检查

Agent 在私有副本留下四个文件、420 行新增：`json-path.models.ts`、`json-path.models.test.ts`、
`json-viewer.e2e.spec.ts`、`json-viewer.vue`，均位于 `src/tools/json-viewer/`。
这些是**未接受、未验证的提议代码**，不能称为交付成果或可用功能。
目标工程原有 Playwright 的测试文件被 Agent 编写，不代表 DelveForge 实现或使用了浏览器能力。

| 实际工具请求 | 真实结果 |
| --- | --- |
| frontend test，默认 frontend 目录 | UNSUPPORTED_FRONTEND_CONFIGURATION，执行测试数 0 |
| frontend typecheck，根目录 `.` | UNSUPPORTED_FRONTEND_CONFIGURATION，执行测试数 0 |
| Maven test，根目录 `.` | INVALID_ARGUMENTS，未执行 Maven 测试 |
| frontend typecheck，再次使用 frontend 目录 | UNSUPPORTED_FRONTEND_CONFIGURATION，执行测试数 0 |

没有实际前端 build 调用，没有任何当前源码测试通过事实。
只读锁审计另已确认 `concat-map@0.0.1` 的 SHA-1 条目；现有 typecheck/build/test 脚本也超出固定白名单。
错误默认目录、对纯前端调用 Maven、不能完成适用验证，也属于这次 Agent 行为的实际局限。
模型虽然报告 completed，冻结守卫仍拒绝未解决失败，未把自述转换成成功领域状态。

### 独立核对与费用

原始仓库 498 个文件逐一核对大小和 SHA-256，0 个变化；HEAD 与工作区状态不变。
正式 WorkingCopy 仍在原始 `d505845f918e946ec300af7b36efc107e2f66e9e`，干净，currentRevision 与 lastVerifiedRevision 未前进。
失败材料保留；180 条连续事件完整保存，两个保留容器实际处于 Exited 状态。

| 用量范围 | 真实遥测 |
| --- | --- |
| 编码 Agent | 38 次响应，770483 input + 9185 output = 779668 tokens；cacheHitTokens 730112 |
| Discovery / 分析 / 方向 / 规划 | 6 次 Gateway 响应，61450 input + 21193 output = 82643 tokens |
| 合计 | 44 次模型响应，862311 reported tokens；不将思考 tokens 再次相加 |
| 费用 | 免费余额接口观察到账户减少 CNY 0.47；这是账户差额，不是隔离的本试验账单，不能排除同期其他账户活动 |

没有交付后的新应用可接受，因此未进行最终应用启动、功能交互或渲染评审，没有截图或视频成功证据。
不能用原版应用、编写了测试文件、绿色夹具回归或推测的 UI 代替这些证据。

### 五维评价

| 维度 | 结论 |
| --- | --- |
| Discovery quality | 小试建议贴合预设需求；新版自主发现未执行，不能证明个性化完整产品发现 |
| Planning quality | 小试的一步计划有完整前端交互与测试；不是新版完整产品规划实验 |
| Engineering execution | 实际做出源码修改，但验证不受支持，冻结失败，0/1 Step 交付 |
| Functional usability | 未验证，没有正式交付应用 |
| Showcase quality | 无功能/视觉验收证据，ASTRA_READY = NO |

代码改进已完成本地提交，确定性回归支持其有限合同；普通仓库端到端兼容性仍有明确缺口。
不建议把当前结果宣传为完整产品自主演化成功。需要新的、有明确范围的兼容性工程决策或重新设计实验，
不能在本次失败记录上补写成功历史；本任务不继续实现这些后续工作。

完整本地证据在 `target/practical-compatibility-20261102/`：`evidence/` 保存原样 API 请求/响应、全部方向/计划、
180 条 Agent 事件、未接受 diff、用量与完整性核对；`database/`、`agent-runs/`、`workspaces/`、`logs/` 保留真实现场。
这些私有、可含用户输入的记录不提交到 Git；报告作为可审查的持久摘要提交。
