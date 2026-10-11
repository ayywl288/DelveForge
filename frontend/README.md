# DelveForge Minimal Web Experience

Vue 3 + TypeScript + Vite + pnpm。五阶段界面调用当前后端 REST API，没有模拟运行结果或浏览器内 Provider 客户端。

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

## 五阶段手动验收

| 阶段 | 用户操作 | 实际接口与决定 |
|---|---|---|
| 需求探索 | 创建画像，连续多轮回答实际问题；逐维纠正六维画像；自主进入审阅 | discovery-turn 充分仍保持探索；点击进入审阅调用 sufficiency-assessment，充分才 REVIEWING，勾选审阅并 confirm 当前画像版本 |
| 理解仓库 | 输入绝对路径，明确读取权限、许可证、复用授权；注册后单独分析 | software-assets / analysis；展示分析结构、Evidence、analyzedRevision |
| 补充已有仓库授权 | 在原资产卡片展开「完善许可证与授权」，核对条款后明确保存；不再分析或生成方向 | PUT software-assets/{id}/authorization；按授权版本拒绝并发覆盖 |
| 产品方向 | 选择分析快照、生成候选、展开各业务字段与三类依据 | discovery；Select / Reject 独立确认，没有默认选择 |
| 演化规划 | 选择授权基础快照，查看 Current / Target / Reuse / Changes / Risks 和有序 Step | planning；审阅后 prepare 激活 Plan 并创建 WorkingCopy；没有 Plan confirm API；Step 需各自 confirm |
| 验证演化 | 查看 revision 与范围，确认费用及宿主风险后执行一个 READY Step | execute；GET plan / working-copy 观察实际检查点、ExecutionResult / VerificationResult，不自动执行下一步 |

AI 操作会调用付费 Provider，只在实际获得授权后验收。本次联通检查仅创建空白画像、纠正无敏感测试条目、注册受限权限资产并 GET；多轮 AI 与代码演化交互只使用自动化合成响应，没有新付费 Smoke。

只使用用户明确授权、受信任、小型 Java / Maven Git 仓库；需根目录 Wrapper 和已缓存的离线构建、测试能力。现有 BUILD / TEST / FOCUSED_TEST / DIFF_CHECK 场景及限制见 [M4 本地使用](../docs/M4_LOCAL_USAGE.md)。Windows 插件与测试在宿主运行，WorkingCopy 不是 OS 沙箱。

## 刷新、断线与长请求

- 页面内切换阶段保留未发送草稿；刷新会丢失草稿和聊天。已知业务对象用 GET 恢复，不重放命令。
- `delveforge.session.v1` 只保存对象 ID。「恢复已知对象」支持输入 Profile / Asset / RepositoryProfile / Direction / Plan ID；切换数据库或前端 origin 后需要重新恢复。
- `delveforge.execution.v1` 只保存 planId、stepId、startedAt。发送前必须成功写入并取得 Web Locks；不支持锁或存储的浏览器不发送 execute。锁仅协调同 origin 标签页。
- 等待期间每 3 秒串行查询 Plan / WorkingCopy。准备 CodingProposal 时可能仍为真实 READY，旁边显示本页的请求等待信息；不创建 PREPARING 或百分比进度。
- 明确后端错误且 GET 确认没有 Candidate、仍为 READY 且 current 等于 lastVerified 时，可再次手动决定。这不是客户端 Git cleanliness 检查。断线、未知代理错误、无法读取响应或刷新后，保留待核对标记。READY 不证明原请求已结束，不能据此自动解锁或重发。
- 终态检查点可以解除等待标记；FAILED / VERIFICATION_FAILED / RECOVERY_REQUIRED 等没有 M4 恢复操作。长期 READY 需要在宿主核对原请求；不能通过清除浏览器存储推断后端取消或合法恢复。
- 其它浏览器、清除站点数据、直接 API 客户端不受此锁协调。避免同时操作同一 Plan，最终授权、revision 与 Git 检查仍由后端执行。

画像纠正 API 没有 expectedRevision 参数，应避免多处同时编辑；确认画像则使用明确 revision。Profile、聊天、Evidence、源码和响应正文不写入浏览器存储或控制台；没有 `v-html`、外部追踪或第三方字体。错误页面仅使用稳定错误码，不回显原始错误正文。

需求对话达到最低充分条件后仍返回下一问；“继续深入探索”优先使用本页当前问题，没有问题才
显式调用只读 `discovery-question`（携带当前画像版本和最多八个已提问题）。Continue / Reopen
成功后调用一次只读提问；失败保留真实探索状态，不伪造用户消息、不自动重试。
进入审阅后主体展示本版六维内容、全部 Evidence 与待核实状态，可纠正、返回探索或确认当前版本。
Evidence 没有逐条画像关联字段，界面不推断不存在的关联。Confirm 与页面刷新不调用 AI 提问。
刷新后可主动请 Agent 提出新问题，不能恢复未保存的历史对话或充分性结论。

## 工程与测试

```powershell
pnpm test
pnpm typecheck
pnpm build
```

Vitest + jsdom 是开发测试依赖，用于 DTO、Vue 表单决策、版本冲突、刷新、超过 702 秒的等待、断线、锁和存储失败验证。没有增加路由、状态管理或大型 UI 库。

```text
src/App.vue             导航、状态与 ID 恢复
src/views/              五阶段结构化视图
src/components/         原生确认对话框、Evidence、revision 与状态
src/api/                Controller DTO 与 HTTP 调用
src/state/session.ts    内存数据、ID 恢复、等待与只读轮询
tests/                  自动化合成夹具，非真实 Provider 证据
```

现有 API 无历史列表、聊天、充分性评估、已接受 CodingProposal 或 Git diff 的历史查询。`execution-preparation` 会调用 AI，execute 仍重新准备，因此本页未增加额外付费预览。详情见 [Task B 验证](../docs/validation/minimal-web-experience.md)。M5、正式部署和其它前后端新功能不在本任务范围。

C1：首次真实充分结果显示一次选择弹窗；已有问题的继续探索不追加 AI 调用。
“本次对话 / 画像审阅”可切换；审阅视图不重复右侧大段画像，依据可折叠。
审阅 POST 携带 revision，同版服务端充分结果直接复用；无记录才评估，不插入新探索问题。
刷新只读 review-readiness 恢复提示，不恢复聊天或自动提问。
