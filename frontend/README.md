# DelveForge Minimal Web Experience

Vue 3 + TypeScript + Vite + pnpm。项目工作台与五阶段界面调用当前后端 REST API，没有模拟运行结果或浏览器内 Provider 客户端。

**Task G：DONE / ACCEPTED / FROZEN（2026-10-12，当前发行版 UI）。** 用户已接受 Graphite + Indigo
和六页交互结构，少量视觉不足有意延后。最终实现 `dd3d24e` 已本地整合至 main，前端 123 项测试、
typecheck / build PASS；[最终关闭与复盘](../docs/validation/task-g-final-frontend-closeout.md) 区分
合成 UI 验证、真实业务 E2E 和部署验收。后两者未由本次 UI 接受确立，不自动发起新 UI 任务。

## 本地启动

先按 [M4 本地使用](../docs/M4_LOCAL_USAGE.md) 启动后端。API Key 只配置在后端，不写入前端 `.env`、聊天或浏览器。数据库与 Workspace 由后端配置，不依赖历史 Smoke ID。

要求 Node.js `^20.19.0 || >=22.12.0`、`pnpm@12.9.1`，使用提交的 `pnpm-lock.yaml`：

```powershell
cd E:\develop\DelveForge\frontend
pnpm install --frozen-lockfile
pnpm dev
```

打开 `http://127.0.0.1:5173`。Vite 绑定本机，`/api` 默认代理到 `http://127.0.0.1:8080`。其它后端端口可在启动前设置 `$env:BACKEND_DEV_URL = 'http://127.0.0.1:18080'`；变量没有 `VITE_` 前缀，不进入浏览器产物。

代理 `timeout` / `proxyTimeout` 为 0；所有 AI POST 与 execute 都没有客户端短期限或自动重试，GET 最多等待 15 秒。`pnpm build` 产出 `dist/`，`pnpm preview` 只预览静态文件，**没有 /api 开发代理**。正式静态托管与同源反向代理尚未交付。

## 项目工作台

打开页面后可在「项目工作台」查看历史摘要和真实 Step 计数。点击「打开并继续」按保存
的对象 ID 读回业务上下文；旧 Plan 第一次继续时只新增导航记录，不执行 AI、Git 或 Maven。
工作台使用紧凑项目列表，优先显示项目名称、已有方向、当前阶段、真实 Step 计数和保存时间。
仓库路径、完整 revision、Plan / WorkingCopy ID 与删除操作保留在「项目详情」；需核对的问题
有独立提示。展开「开始新流程」填写名称并选择新画像或复用方式，再明确提交。
新建流程时填写名称，选择重新探索或复用当前已确认画像。重新探索的新画像仍需主动创建；
复用保持原身份，不复制、不重新确认。进入仓库页可选择已有资产、已有分析，或登记新仓库。

阶段由后端数据推导，页面访问不会标记完成。单击恢复后，已成功步骤保持原结果，其余
步骤仍需逐步审阅授权和明确执行。每个 flowId 最多一个 SELECTED Direction；同流程选择
新方向会按原规则使旧方向及其 Plan 失效，切换项目不会自动重新选择。

旧的 UUID 输入恢复保留在「高级调试」中。关联缺失、历史 Root 未允许、目录缺失或画像
版本已变化会显示具体问题。只读目录预检不检查 Git HEAD，执行仍由完整后端守卫判断。

## 五阶段手动验收

| 阶段 | 用户操作 | 实际接口与决定 |
|---|---|---|
| 需求探索 | 创建画像，连续多轮回答实际问题；逐维纠正六维画像；自主进入审阅 | discovery-turn 充分仍保持探索；点击进入审阅调用 sufficiency-assessment，充分才 REVIEWING，勾选审阅并 confirm 当前画像版本 |
| 理解仓库 | 无分析时登记并授权；有分析时在 Explorer 分区阅读，可切换保存快照 | software-assets / analysis；完整 Evidence、analyzedRevision 与授权记录仍可查看 |
| 补充已有仓库授权 | 在原资产卡片展开「完善许可证与授权」，核对条款后明确保存；不再分析或生成方向 | PUT software-assets/{id}/authorization；按授权版本拒绝并发覆盖 |
| 产品方向 | 在候选列表查看方向，在详情区展开原文与三类依据；历史方向另行展开 | 浏览列表不调用 Select；Select / Reject 仍各自显式确认 |
| 演化规划 | 阅读紧凑 Current / Target 概览，逐个内联展开有序 Step；核对完整内容与历史 | planning；审阅后 prepare 激活 Plan 并创建 WorkingCopy；没有 Plan confirm API；Step 需各自 confirm |
| 验证演化 | 用 Step Navigator 查看目标与授权、执行状态、验证结果；核对副本完整 revision | execute；GET plan / working-copy 观察实际检查点、ExecutionResult / VerificationResult，不自动执行下一步 |

AI 操作会调用付费 Provider，只在实际获得授权后验收。本次联通检查仅创建空白画像、纠正无敏感测试条目、注册受限权限资产并 GET；多轮 AI 与代码演化交互只使用自动化合成响应，没有新付费 Smoke。

只使用用户明确授权、受信任、小型 Java / Maven Git 仓库；需根目录 Wrapper 和已缓存的离线构建、测试能力。现有 BUILD / TEST / FOCUSED_TEST / DIFF_CHECK 场景及限制见 [M4 本地使用](../docs/M4_LOCAL_USAGE.md)。Windows 插件与测试在宿主运行，WorkingCopy 不是 OS 沙箱。

## 刷新、断线与长请求

- 同一项目内切换阶段保留未发送草稿；切换项目清除上一项目视图和聊天，刷新会丢失草稿和聊天。已知业务对象用 GET 恢复，不重放命令。
- `delveforge.project.v1` 只保存活动项目 ID。`delveforge.session.v1` 仍只保存对象 ID，供高级恢复兼容；数据库与浏览器 origin 必须对应。新 origin 也可从后端工作台直接选择历史。
- `delveforge.execution.v1` 只保存 planId、stepId、startedAt。发送前必须成功写入并取得 Web Locks；不支持锁或存储的浏览器不发送 execute。锁仅协调同 origin 标签页。
- 等待期间每 3 秒串行查询 Plan / WorkingCopy。准备 CodingProposal 时可能仍为真实 READY，旁边显示本页的请求等待信息；不创建 PREPARING 或百分比进度。
- 明确后端错误且 GET 确认该 Step 没有 Candidate、仍为 READY，副本为 READY / EVOLVING 且 current 等于 lastVerified 时，可再次手动决定。这不是客户端 Git cleanliness 检查。断线、未知代理错误、无法读取响应或刷新后，保留待核对标记。READY 不证明原请求已结束，不能据此自动解锁或重发。
- 执行准备区分 `EXECUTION_CRITERIA_UNSUPPORTED`（无法自动验证，本次未调用 AI）、`EXECUTION_AI_FAILED`（Provider 通信 / 响应失败）和 `CODING_PROPOSAL_INVALID`（已返回内容但提案解析 / 契约不合格）。不支持的标准不能靠重复点击解决；其它失败再次调用也不保证成功且可能计费。这三个错误仍须按上述 GET 条件核对，不能绕过未知结果保护。
- 终态检查点可以解除等待标记；FAILED / VERIFICATION_FAILED / RECOVERY_REQUIRED 等没有 M4 恢复操作。长期 READY 需要在宿主核对原请求；不能通过清除浏览器存储推断后端取消或合法恢复。
- 其它浏览器、清除站点数据、直接 API 客户端不受此锁协调。避免同时操作同一 Plan，最终授权、revision 与 Git 检查仍由后端执行。
- 业务提交期间禁用项目切换；慢轮询响应按当前上下文版本丢弃。未知 execute 标记跨项目保留，先打开其所属 Plan 查询，不能切换后绕过防重复提交。
- 正常业务返回后单独 CAS 保存项目关联。冲突或断线会提示「业务可能已完成，关联尚未确认」并阻止继续写入；重新打开项目核对，不自动重放付费请求。未关联的 Profile / Asset / RepositoryProfile 可显式选择；保存的 Plan 可独立恢复。

画像纠正 API 没有 expectedRevision 参数，应避免多处同时编辑；确认画像则使用明确 revision。Profile、聊天、Evidence、源码和响应正文不写入浏览器存储或控制台；没有 `v-html`、外部追踪或第三方字体。错误页面仅使用稳定错误码，不回显原始错误正文。

需求对话达到最低充分条件后仍返回下一问；“继续深入探索”优先使用本页当前问题，没有问题才
显式调用只读 `discovery-question`（携带当前画像版本和最多八个已提问题）。Continue / Reopen
成功后调用一次只读提问；失败保留真实探索状态，不伪造用户消息、不自动重试。
进入审阅后切换到独立「画像审阅」Tab，展示本版六维内容、Evidence 与待核实状态；
本页聊天保留，可切回「本次对话」。可纠正、返回探索或确认当前版本，不重复右侧整份画像。
Evidence 没有逐条画像关联字段，界面不推断不存在的关联。Confirm 与页面刷新不调用 AI 提问。
刷新只读 review-readiness 恢复本版已通过提示；可主动请 Agent 提出新问题，不能恢复未保存的历史对话或模型问题。

## 工程与测试

```powershell
pnpm test
pnpm typecheck
pnpm build
```

Vitest + jsdom 是开发测试依赖，用于 DTO、Vue 表单决策、版本冲突、刷新、超过 702 秒的等待、断线、锁和存储失败验证。没有增加路由、状态管理或大型 UI 库。

```text
src/App.vue             项目导航、状态与高级 ID 恢复
src/views/              项目工作台与五阶段结构化视图
src/components/         原生确认对话框、Evidence、revision 与状态
src/api/                Controller DTO 与 HTTP 调用
src/state/session.ts    上下文隔离、ID 恢复、等待与只读轮询
tests/                  自动化合成夹具，非真实 Provider 证据
```

G1 共享视觉采用石墨灰导航、浅色画布和靛蓝操作色，业务成功、等待、执行及错误状态保留
独立颜色与文字。App 区分工作空间导航和当前项目的五阶段导航；窄屏阶段导航可横向浏览。
G2 在此基础上调整五阶段的信息结构：探索时以对话和可收起的六维 Inspector 为主；审阅时
左侧展示独立审阅内容，右侧只保留目录和确认，避免整份画像重复；已确认时优先显示确认信息
和下一阶段入口。六维条目、Evidence 与技术绑定通过折叠区保留完整内容。

仓库 Explorer 的局部导航限制在主内容区；登记、已有资源复用和授权均可单独展开。产品方向
采用列表与详情，列表预览截取原始文本的显示行数，不生成额外摘要。历史方向保留原状态。
规划路线每次只内联展开一个 Step，完整规划内容、历史轮次与 baseline 可查。
Step DTO 没有独立的规划前提字段，页面只展示原有顺序、Plan / WorkingCopy 状态与 revision
守卫，不补造前提。执行页缩小副本元数据，突出当前可用操作或禁用原因；Candidate、验证结果
与最近验证 revision 分区展示。窄屏查看方向或步骤后焦点定位到详情，可返回列表。

现有 API 提供项目历史、完整 Plan / Step 结果、资源目录和本版审阅就绪记录。没有完整聊天、
模型问题、已接受 CodingProposal 或 Git diff 的历史查询；没有 Plan 的旧孤立 Direction
不自动拼装为项目，可使用高级 ID 入口查看。返回的是当前 UserProfile，旧 Plan 的画像
绑定版本另行标明，不能把当前六区当作旧快照。`execution-preparation` 会调用 AI，execute
仍重新准备，因此本页未增加额外付费预览。Task F 的迁移、测试与恢复边界见
[项目工作台验证](../docs/validation/task-f-project-workspace-history-recovery.md)。M5 和正式部署未实现。

C1：首次真实充分结果显示一次选择弹窗；已有问题的继续探索不追加 AI 调用。
“本次对话 / 画像审阅”可切换；审阅视图不重复右侧大段画像，依据可折叠。
审阅 POST 携带 revision，同版服务端充分结果直接复用；无记录才评估，不插入新探索问题。
刷新只读 review-readiness 恢复提示，不恢复聊天或自动提问。

Task G 的实施入口、素材与不可改变的业务边界见
[Task G 技术交接](../docs/UI_TASK_G_HANDOFF.md)。新旧数据库与浏览器 ID 的安全切换见
[本地数据环境](../docs/LOCAL_DATA_ENVIRONMENTS.md)。业务基线保持冻结，不从 UI 整理启动付费 Smoke。
G2 的改动、合成测试与桌面 / 窄屏检查见 [G2 前端验收记录](G2_VALIDATION.md)。
后续视觉修正统一正文、标题、状态与技术信息的字号，扩展原有 SVG 图标，调整内容列表、
画像维度、方向条目、路线步骤与验证结果。工作台进度仅使用后端保存的已通过 / 总步骤计数；
桌面执行页的步骤列表随滚动保持可见。未增加 UI 依赖或改变业务确认条件。
此前六页截图与修正验证见 [G2 视觉修正记录](G2_VISUAL_CORRECTION.md)，保留该轮历史验证事实；当前接受状态以上方最终关闭记录为准。
最终长内容细化为 Evidence 集合提供每页 6 条的本地分页和逐条原文查看；桌面方向页的列表、
原文阅读区有独立滚动，主操作保持可见，窄屏继续使用列表 / 详情导航。
依据不排序、合并或推断新关联。该轮长夹具与分页验证见 [G2 最终 UX 验证](G2_FINAL_UX_VALIDATION.md)。
最后的 Fit & Finish 将完整项目技术信息移入右侧 Inspector，计划状态、工作流阶段与已验证
Step 计数分别呈现。完整 Current / Target State 及规划依据使用同一个辅助阅读面板，路线与
授权操作仍在原页；Escape 返回入口，项目删除先关闭阅读面板，再显示原有独立确认框。
仓库局部导航在 Explorer 内保持可用，正文仍使用页面滚动；窄屏为横向导航和单列阅读。
本轮截图、焦点与回归检查见 [G2 Fit & Finish 验收](G2_FIT_FINISH_VALIDATION.md)。
