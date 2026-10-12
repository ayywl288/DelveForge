# Task G2 — Final Fit & Finish 验收

2026-10-12，分支 `codex/task-g2-workflows`，基线 `ef6f199`。
Graphite + Indigo、六页结构、Evidence 每页六条、方向双阅读区、演化路线与 Step Navigator
继续沿用已接受方案。本记录描述本轮实现，之前的 G2 文档保留各自提交时的历史验证事实。

## 实现范围

仅改 `frontend/` 的 Projects / Repository / Plan 视图、CSS、一个共享 `DetailPanel`、
测试夹具、交互测试和前端文档。没有新增依赖；未改 API / DTO / `state/session.ts`，
未改后端、Domain、Agent、Git mutation、Verification 或 M5。

项目列表继续采用紧凑行布局。计划状态、工作流阶段、已验证 / 总 Step 数各自呈现；
状态颜色来自原 `planStatus`，删除标记来自 `deletedAt`，进度只取原 `succeededSteps / totalSteps`。
没有 Plan 的记录不制造业务状态或进度。标题、方向、时间与主次操作分开对齐。

「项目详情」和问题入口打开同一个右侧 Inspector，列表不再内联展开长技术信息。
Inspector 完整保留已有 DTO 中的身份、导航版本、历史来源、方向、阶段、时间、Plan 状态、
Step 状态计数、Plan / WorkingCopy ID、完整 current / lastVerified revision、全部仓库路径和
资产 ID、删除时间与问题代码 / 提示。未额外查询或推断服务端未提供的 flowId 等字段。
警告在阅读区顶部，管理区与元数据分开。已授权或未决执行的删除提示在列表与管理区均保留。
删除先退出 Inspector，再显示原有确认框；取消恢复到列表入口，确认前不提交删除。

共享辅助面板使用原生 modal dialog，固定标题 / 关闭按钮，只有一个正文滚动区。
打开聚焦关闭按钮，正文可用 Tab、PageDown、Home / End 阅读；Escape 或关闭返回入口。
项目刷新后入口消失时，焦点回到项目列表标题。离开 KeepAlive 视图会关闭面板，返回不会
自行重新打开。面板不承担授权动作，也不制造「已审阅」状态。

完整 Current / Target State 与规划改动 / 风险 / Evidence / 基础绑定移入此阅读面板。
概览继续显示原文预览，完整正文、数组顺序与原有 metadata 全部可查；没有 AI 改写或新摘要。
演化路线、Step 内联展开、操作前提、审阅勾选、Prepare / Step Confirm 仍在原位置。
查看详情不会勾选审阅或打开准备 / 执行确认。

仓库 Explorer 使用容器内 sticky 导航：14px 文字、44px 按钮、明确 active 状态。
长报告仍随页面滚动，没有新增报告内滚动容器。导航到 Explorer 结束时退出视野，
不悬浮在登记 / 授权区上方；全局侧栏独立保留。960px 以下使用横向局部导航与单列正文。
切换分区定位到正文起点，焦点保留在导航按钮。窄屏 Evidence 翻页定位避开 sticky 导航。

正文和普通内容行统一采用约 15px / 1.65–1.7 行高，长段落保留原始换行。
普通列表使用留白与轻分隔线，路径 / ID / revision 保留等宽字体和安全换行；
风险背景、真实阻塞提示与验证结果颜色保留。仓库长文和阅读面板限制适合阅读的行宽。

## 折叠区审计

| 区域 | 本轮处理 |
| --- | --- |
| 项目工作台 | 技术信息移入 Inspector；新流程表单保留短内联入口 |
| User Discovery | 对话、六维 Profile Inspector、纠正 / 确认区保留；Evidence 分页不变 |
| Repository | 分类不变；短身份绑定、登记、资源选择与授权表单保留内联，不把必需授权藏进阅读面板 |
| Product Direction | 双滚动区已经约束长原文；原有折叠和历史方向保留，查看 / Select 继续分开 |
| Evolution Planning | 完整现状 / 目标 / 规划辅助信息移入面板；Step 内联展开、Prepare、历史轮次动作保留 |
| Verified Evolution | 视图代码不变；共享普通列表样式随本轮统一，验证事实和风险确认不变 |

## 浏览器实测与截图修正

临时本地 Vite 预览使用现有 App / Vue 页面与合成夹具；只有模拟 GET 响应，
所有非 GET 请求均拒绝，没有后端代理。没有访问开发数据库、Provider 或任何真实副本。
合成 PASS、候选、画像与历史只用于检查呈现，不是业务 E2E 或真实验证证据。

测试数据包含六条不同实际字段值的项目列表、九条长仓库路径 / 资产 ID、完整 40 位 revision、
23 条 Profile Evidence、27 条 Repository Evidence、12 个当前方向及两条历史方向、
42 条长仓库能力，以及完整 Current / Target State 的多段原文和 24 条现状能力。
演化回归使用一个合成 SUCCEEDED Step、一个 READY Step 和一致的已验证 revision。

实际完成 1440×900 桌面截图，检查列表、Inspector 顶部 / 末端、Current / Target State
顶部 / 末端、仓库深读 / 容器退出边界、Evidence 末页、12 个方向和 Verified Evolution 结果。
确认 Enter 打开、Tab 访问、Escape 关闭、入口焦点恢复、阅读区滚动和原确认框可用。
只打开 / 取消删除与执行风险确认，没有提交它们；执行风险勾选未自动选中，执行按钮禁用。

完成首轮截图后的修正：普通列表去重复灰底、警告图标统一语义颜色；320px 实测发现
分页说明顶部被 84px sticky 导航覆盖，增加局部定位间距。修正后说明顶部约 100px，
位于导航下方且焦点正确。这是实际浏览器发现与复查，不以 jsdom 作为布局证明。

320×844、390×844、768×1000 检查六页和项目 / Current / Target / 规划依据面板，
共 38 条宽度观测（包含 Verified Evolution READY / 结果回归）。每条 documentWidth
均等于 clientWidth；已打开面板的 scrollWidth 也等于 clientWidth，没有页面横向溢出。
局部导航可横向浏览；正文不被压成窄双列。窄屏查看 Step 后焦点仍到详情。
浏览器 warning / error 记录为空。

桌面深读实测：全局侧栏 top=0，局部导航 top=20，正文 overflowY=visible；
滚到登记区后 Explorer bottom=-99.5、导航 bottom=-100.3，局部导航已退出视野。
项目 Inspector 的九条仓库和管理区共用正文滚动，不再套第二层仓库滚动。
长方向查看第十二条时仅改变本地查看对象，第一条仍 SELECTED，第十二条仍 CANDIDATE。

截图保存在本机的版本控制外目录：
`C:/Users/28226/.codex/visualizations/2026/10/11/01a12b6f-1a97-76f0-b6c1-dd7a1f928bc5/g2-fit-finish/`。
关键截图文件：

- `workspace-final.jpg`、`project-inspector-final.jpg`、`project-inspector-end.jpg`
- `repository-deep-final.jpg`、`repository-navigation-boundary.jpg`
- `current-state-final.jpg`、`current-state-end.jpg`、`target-state-top.jpg`、`target-state-end.jpg`
- `profile-evidence-last.jpg`、`repository-evidence-last.jpg`、`directions-twelve.jpg`
- `verified-evolution-1440.jpg`、`verified-evolution-results.jpg`、`execution-confirmation-regression.jpg`
- `narrow-320-project-inspector.jpg`、`narrow-320-repository-pagination.jpg`、`narrow-320-target-state.jpg`
- `narrow-390-current-state.jpg`、`verified-evolution-390.jpg`、`narrow-768-project-inspector.jpg`

## 自动化验证

| 检查 | 结果 |
| --- | --- |
| `pnpm test` | PASS，10 files / 123 tests；基线 115，本轮新增 8 |
| `pnpm typecheck` | PASS |
| `pnpm build` | PASS，56 modules；CSS 50.99 kB / gzip 10.74，JS 202.91 kB / gzip 69.82 |
| `git diff --check` | PASS |

新增交互测试覆盖完整原值、Escape / 焦点返回、删除的单独确认、READY / 未验证版本删除
守卫、刷新身份变化、长现状 / 目标不自动审阅、规划依据分页、Plan 切换清理、仓库分区定位
和 KeepAlive 退出关闭。旧项目测试按 Inspector 入口更新，原删除、恢复、无 AI / Prepare /
execute 调用、真实计数与无虚构状态的断言继续保留。原分页、长请求、跨项目隔离测试均通过。
项目没有 lint script。未改后端，未运行 Maven；未运行付费 Provider E2E。

## 已知限制与停止边界

- Inspector 读取列表中已有字段，不是实时 Git / 文件系统检查；保存的 revision 不代表物理目录当前状态。
- 辅助面板是原生模态阅读面板，继续原页授权 / 执行前需关闭；Escape 和关闭按钮保持可达。
- 特别长的单条原文仍需滚动；窄屏局部导航需要横向浏览。没有丢弃信息或替换成生成摘要。
- Evidence 仍是完整 GET 集合上的本地六条分页，后端加载方式不变；已有页面重挂载重置本地页码行为不变。
- 六张设计 PNG 保持原 SHA256、未跟踪且未提交。没有 push / merge；真实 Provider / Step / 数据库 / WorkingCopy 未操作。
- 本轮完成后停止；正式业务 E2E 和发布验收另由用户发起，不继续广泛重设计或扩展 M5。
