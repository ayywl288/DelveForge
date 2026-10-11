# Task G — UI / UX Polish 技术交接

2026-10-11。业务基线为 `a2b722f2aa86da605a75485f00a8a2c64a10e301`，已 fast-forward
整合至 main；之后的 Closeout 提交仅改文档。M0–M4 结束业务开发，M5 暂缓。
最新确定性回归后端 1474 / 前端 85 PASS，typecheck / build PASS；最新修复未重跑完整付费 E2E。
历史两次真实 Verified B、证据与边界见 [Closeout](validation/pre-ui-final-closeout.md)。

## 1. 本轮可以改什么

允许调整视觉、排版、布局、共享组件、文案、空/加载/错误状态和交互呈现；沿用 Vue 3、
TypeScript、Vite、pnpm 与当前组件。不引入大型 UI 依赖、路由/状态框架、原生目录访问或
后端异步执行平台。任何真实 correctness blocker 单独定位，不能借 UI 收尾改业务规则。

先读 AGENTS、PRODUCT、ARCHITECTURE、DOMAIN_MODEL、ROADMAP 与实际 Controller / DTO。
素材不是新业务契约。Task G 不调用付费 Provider、不执行真实 Step、不修改演示仓库、
数据库或已有 WorkingCopy；真实验收须由用户明确发起。

## 2. 当前前端结构与六个界面

入口是 `frontend/src/main.ts` → `App.vue`；后者维护 `stage=-1`（项目工作台）及五阶段索引，
通过事件导航和 Vue KeepAlive 保留同项目视图，不使用 Vue Router。
`createSession()` 返回共享组合状态，由 App 包装为 reactive 并传给各 View；没有 Pinia。

| 界面 / 文件（相对 frontend/src） | 现有业务操作与只读结果 |
| --- | --- |
| `views/ProjectsView.vue` | 分页历史 / 已删除列表、新建空流程或复用 CONFIRMED 画像、按 project key 打开、显式删除/恢复、资源选择 |
| `views/DiscoveryView.vue` | 左侧会话与审阅 Tab、六区纠正、真实 nextQuestion、首次充分提示、显式审阅/confirm、continue/reopen |
| `views/RepositoryView.vue` | 绝对路径登记、已登记复用、独立触发分析、结构化 RepositoryProfile、原资产授权编辑及精确 Asset ID 定位 |
| `views/DirectionsView.vue` | 选择参与分析、明确请求候选、可展开业务字段和 Evidence、显式 Select / Reject |
| `views/PlanView.vue` | 选择 Base、明确生成 Plan、Current / Target / Reuse / Changes / Risks、有序 Steps / Criteria、Prepare、单独 Step confirm、旧 Plan 与新规划轮次 |
| `views/EvolutionView.vue` | 实际 WorkingCopy/revisions、Step/Execution/Verification、请求等待计时、只读轮询、风险确认后单次 execute |

请求集中在 `api/client.ts`，DTO 类型在 `api/types.ts`；实际字段以 App Controllers 为准。
Project、资源目录、轮次与关联 API 在 `app/api/project/ProjectController.java`；
Planning / Direction、UserProfile、SoftwareAsset、RepositoryProfile、WorkingCopy Controllers
分别拥有对应契约。不要从旧 smoke JSON 猜测请求字段。

复用组件：`ConfirmDialog`（原生 dialog）、`StatusBadge`、`RevisionText`、`ContentList`、
`EvidenceList`、`ProfileReview`、`ProjectResourcePicker`、`AssetAuthorizationForm`、
`LicenseDeclarationField`。Evidence 默认展示来源，原 confirmed 字段保留在技术详情；
不把它当整体 Profile 确认。许可证是用户声明，选择 SPDX 不授予权限。

## 3. 状态、历史与长请求

- `state/session.ts` 统一业务请求、已知 ID 恢复、项目关联 CAS、contextEpoch 与销毁保护。
  迟到响应不得写入切换后的流程；业务提交期间禁用项目切换。关联保存失败不等于原业务失败，
  不能自动重放；保存 Plan 后回读失败要保留 Plan ID。
- 项目是明确导航身份，不靠路径、共用画像或时间拼装。服务端持久化的 flowId 决定方向归属；
  每个 flowId 最多一个 SELECTED。上下文的 profileId / planId 不可任意替换，资源集合只追加。
- 同流程切换方向使旧 Plan SUPERSEDED；用 `planning-rounds` 明确创建同 flowId、
  previousContextId 相连的新上下文，再选择 Base 并生成新 Plan。创建轮次不调用 AI。
  旧 Plan / WorkingCopy / Verified revision 保留，不能直接恢复 ACTIVE 或自动用旧 B 作分析。
- 旧 Plan 根据已保存引用投影；继续时才认领导航。归属不明保留只读提示，缺失关联不伪造。
  页面显示当前画像与旧 Direction 绑定版本的区别；没有历史聊天、模型问题或 CodingProposal。
- 删除是单个导航记录的软删除，保留共享业务与 Git；版本冲突重新查询。READY 可能正在
  提案准备，故也保守禁止删除；恢复可见性不恢复生命周期、不取消执行。
- 浏览器只存 `delveforge.project.v1`、`delveforge.session.v1` 和 `delveforge.execution.v1`。
  最后一个是未决请求标记，不因项目切换或清除导航而删除。没有业务原文、源码、Key 或模型
  正文存储。Web Locks 防同 origin 标签页重复 execute；不支持锁或写标记失败就不提交。
- `client.ts` 使用相对 `/api`，GET 期限 15 秒；同步 AI / execute 写请求没有短前端期限，
  不自动重试。`vite.config.ts` 的 `/api` Proxy 默认 127.0.0.1:8080，可由 dev server 的
  `BACKEND_DEV_URL` 调整，timeout / proxyTimeout 为 0；该变量没有 VITE_ 前缀，不打入浏览器。
- 演化页激活时每 3 秒串行 GET Plan / WorkingCopy，有等待标记或 EXECUTING / VERIFYING
  时观察；暂停、离开/卸载时清理计时与轮询，停止轮询不取消后台请求。
  请求等待时长、最近读取时间和持久化状态是不同信息，不能画成虚构百分比或阶段日志。
  准备提案期间可能仍 READY；刷新、断线或未知结果先查状态，不重发 execute。

具体并发、迁移和恢复边界见 ADR-0007、[ADR-0008](decisions/0008-stable-evolution-flow-scope-and-planning-rounds.md)、
[ADR-0009](decisions/0009-project-navigation-soft-deletion.md) 和 `frontend/README.md`。

## 4. 样式与现有可访问性

全局入口为 `src/style.css`，包含颜色变量（ink / muted / accent / line / nav）、左侧导航、
卡片、表单、聊天、计划、revision 与状态时间线。当前是深绿导航、浅底和系统字体；
无外部字体、追踪脚本或大型动画。主要断点为 1550 / 1200 / 960 / 640 px；已有窄屏堆叠、
独立聊天滚动、固定输入区、长 Evidence 折叠滚动与 reduced-motion 规则。

保留 skip link、表单 label、键盘焦点、原生 dialog 的关闭/焦点行为、aria-current、
错误 role=alert 和加载禁用。Task G 可以改善层级与可读性，但不删除错误/风险/版本提示，
不以颜色作为唯一状态信息，不把后端状态错误藏进装饰性进度。

已有两处旧文案可以在 Task G 按真实 API 校正：App 的高级 ID 弹窗仍称“没有历史列表”，
Discovery 空会话仍称“充分性评估没有读取 API”。现在已有项目历史与 review-readiness GET；
它们仍不能恢复聊天或完整历史评估。这是文案遗留，本次未修改前端实现。

## 5. 不可改变的业务决策

1. sufficient 只代表可审阅；EXPLORING 仍有 AI 下一问。普通 Turn 两次 Gateway；
   同版本有效通过记录进入审阅零调用，无记录才评估一次；用户主动 Continue / Reopen
   后按需只读提问。禁止为了 UI 动画自动调用 AI，Confirm 不自动生成下一问。
2. Profile 确认必须用户审阅本版并提交真实 revision，CONFIRMED 不允许聊天静默修改；
   reopen 是显式操作，共享画像后续改变不能覆盖旧 Direction 的版本绑定。
3. 资产读取、使用授权、许可证与 authorizationRevision 必须显式提交。精准跳转按 Asset ID，
   不按同路径选另一个对象；未知许可可分析但不允许作为 M3 Base。
4. Select / Reject、生成 Planning、Prepare、Step confirm、execute 是分开的用户决策。
   方向归属、Context expectedVersion、CurrentState / TargetState / Evidence 严格校验保留。
   提案被拒绝与结果未知使用不同提示；无静默付费重试。
5. Mutation 仅在 Plan 绑定副本内；baseline / current / lastVerified 语义、顺序、验证覆盖与
   Verification Before Trust 不变。UI 不能解锁 FAILED / VERIFICATION_FAILED 或其它 M5 状态。

## 6. 设计素材、启动与交付检查

本机 `E:\develop\DelveForge\frontend\design\task-g\` 有六张未跟踪 PNG：
`01-project-workspace.png`、`02-user-discovery.png`、`03-repository-understanding.png`、
`04-product-direction.png`、`05-evolution-planning.png`、`06-verified-evolution.png`。
本次完整保留，未提交。下一对话可直接读本机素材；新 checkout 不会自动拥有它们。
不得通过 git clean/reset 清理它们，不能把图上的概念当成新增 API 的授权。

干净数据库与 E 盘 Root 的安全切换见 [本地数据环境](LOCAL_DATA_ENVIRONMENTS.md)，
后端运行配置见 [M4 本地使用](M4_LOCAL_USAGE.md)。新库选择由用户决定，不自动迁移历史副本。
前端在 `frontend/` 执行 `pnpm install --frozen-lockfile`（需要时）及 `pnpm dev`；
正式产物托管、Desktop Shell 与多用户登录仍未实现，不在 Task G 范围。

UI 修改后执行 `pnpm test`、`pnpm typecheck`、`pnpm build`，并针对桌面/窄屏、键盘表单、
审阅弹窗、项目切换、精确授权返回、长等待/断线/未知结果检查。
使用确定性响应验证交互，不将 mock PASS 当真实 AI 验收。只有实际后端行为修改才安排
对应 Maven 回归；当前无需重跑已保存的 1474 项 verify。

Task G 交付不开始 M5，不重新开放 M0–M4 设计；完成后等待用户真实手动验收。
