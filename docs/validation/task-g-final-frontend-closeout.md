# Task G — Final Frontend Closeout

2026-10-12。**DONE / ACCEPTED / FROZEN（当前发行版 UI）**。
用户已接受 G1、G2 与后续细化，少量视觉不足有意延后；本次只做最终回归、阻塞审查、文档关闭和本地整合。
UI 接受不代表最新代码已通过真实 M1–M4 Provider E2E，也不代表部署、安装包或发布验收完成。

## 1. 实现基线与整合

- Pre-UI `main`：`40538b67da27f5b5e32e7c3b78bccf11831f4149`。
- G1：`508859e`（`codex/task-g1-workspace`）。
- G2：`798e15f`；视觉修正 `f0cc367`；长内容细化 `ef6f199`；最终实现 `dd3d24e`。
- `codex/task-g2-workflows` 的 `dd3d24e` 完整包含 G1。关闭前 `main...G2` 为 0 / 5，
  无分叉、未提交的受跟踪改动或未解决冲突，唯一未跟踪内容为六张设计参考 PNG。
- 本次将本地 `main` 从 `40538b6` fast-forward 到 `dd3d24e`，保留五个实现提交；
  关闭文档随后单独提交在 `main`。G1/G2 分支保留，没有重写历史、push 或远端整合。
- 六张 `frontend/design/task-g/` PNG 的 SHA-256 与关闭前基线一致，仍未跟踪、未提交。

## 2. 最终 UI 与复用决策

统一采用 **Graphite + Indigo**：石墨灰全局导航、浅色画布、靛蓝主操作，
成功、风险与错误保留语义颜色和文字。沿用 Vue 3 / TypeScript / Vite / pnpm，未增加依赖。

| 页面 | 已接受结构 |
| --- | --- |
| Project Workspace | 紧凑项目列表；计划状态、阶段与真实已验证 Step 计数分开；完整元数据放右侧 Inspector；新流程、打开、软删除与恢复保持明确操作 |
| User Discovery | 对话 / 审阅 / 确认信息视图；可收起六维 Profile Inspector；原文纠正和当前版本确认；恢复不编造聊天 |
| Repository Understanding | 六类 Explorer 分区；容器内 sticky 局部导航；报告随页面滚动；登记、复用与精确资产授权保留独立入口 |
| Product Direction | 桌面候选与原文独立滚动；查看与 Select / Reject 分开；历史和依据版本可查；窄屏查看后聚焦详情 |
| Evolution Planning | Current / Target 概览、完整原文阅读面板、有序垂直路线、单 Step 内联展开；Prepare、Step Confirm 和历史轮次独立 |
| Verified Evolution | 桌面 Step Navigator；目标 / 授权、Execution / Candidate、Verification / lastVerified 分区；实际等待与已保存状态分开，风险确认后才允许提交 execute |

复用 `ConfirmDialog`、`StatusBadge`、`RevisionText`、`ContentList`、`EvidenceList`、
`ProfileReview`、资源选择与资产授权表单；新增少量共享的 `NavigationIcon`、
`DirectionListEntry`、`StepPrerequisites` 和 `DetailPanel`。
辅助阅读面板固定关闭区、共用单一正文滚动，支持 Escape / 焦点返回；删除先关闭 Inspector，
再显示原有确认框。阅读不会代替审阅勾选、业务选择或授权。

Evidence 本地每页六条，保留原顺序、完整 claim、来源、confidence、confirmed 与 origin。
预览只限制显示行数，完整原文继续可读；没有改写 AI 原文或制造摘要、进度、验证结果。
路径 / ID / revision 安全换行，普通内容减少重复灰底，键盘焦点和窄屏单列布局保留。

## 3. 本次最终验证与阻塞审查

本次在 `dd3d24e` 实际重新运行，随后快进到相同源代码；关闭提交仅改 Markdown。

| 检查 | 结果 |
| --- | --- |
| `pnpm test` | PASS：10 文件 / 123 项，9.54 秒 |
| `pnpm typecheck` | PASS |
| `pnpm build` | PASS：56 modules；CSS 50.99 kB / gzip 10.74，JS 202.91 kB / gzip 69.82 |
| `git diff --check` | PASS；关闭文档及暂存差异另行复核 |

项目没有 lint script。本次未重跑 Maven、浏览器截图或真实后端 / Provider E2E。
确定性测试覆盖导航 / 恢复、跨项目迟到响应、长请求和断线、防重复 execute、
精确授权、显式确认、真实计数、原文完整性、Evidence 分页、辅助面板焦点与 KeepAlive 清理。
源码阻塞审查覆盖六页主操作和禁用原因、查看 / 选择区别、Prepare / Step Confirm / Execute
分离、历史绑定与 Candidate / Verification 呈现，没有发现需修改实现的发布阻塞问题。
本次没有新增 UI 修正或测试，也没有把 jsdom 当作浏览器布局证明。

此前 G2 已在合成预览中检查 1440×900、768px、390px、320px、长 Evidence、12 个方向、
长 Current / Target、键盘 / Escape / 焦点 / 滚动，并完成截图修正周期。
这些是已保存的合成 UI 证据；用户在本次关闭请求中明确接受当前 UI。
合成 PASS 和合成历史不证明真实 Provider、Git mutation、数据库升级或完整业务 E2E 成功。

## 4. 冻结边界、限制与复盘

相对 `40538b6`，五个实现提交只改变 `frontend/`；API client / DTO、`state/session.ts`、
依赖清单 / 锁文件、后端、Domain、Agent、Git mutation 和 Verification 均无差异。
M1–M4 的身份 / flowId / Planning Round、方向与 Plan 生命周期、授权 revision、
WorkingCopy 隔离、执行锁 / 轮询 / 请求隔离、baseline / current / lastVerified 语义保持不变。
本次未调用 Provider、执行真实 Step、启动用户数据库、移动 / 修改已有 WorkingCopy 或实现 M5。

- 少量视觉细节已由用户接受并有意延后，不作为继续打磨的入口。
- Inspector 是已保存元数据，不是实时 Git / 目录状态检查；完整原文仍可能需要长距离滚动，窄屏局部导航需横向浏览。
- Evidence 在完整 GET 集合上本地分页；聊天、模型问题、CodingProposal、源码 diff 的历史查询缺口沿用已有 API 限制。
- 最新 correctness 修复与 UI 完成后的完整付费 E2E 尚未执行；历史后端 1474 项结果见 Pre-UI 记录，本次没有重新验证该计数。
- M5 仍暂缓；正式产物托管、Desktop Shell、部署与发布验收仍未完成。本次不自动启动这些工作。

本轮有效的取舍是用少量共享展示组件改善长内容阅读，同时让主操作和授权继续留在原工作流。
交互回归与合成浏览器检查提供不同证据，分阶段报告保留各自时间和源代码基线。
**Task G 没有剩余冻结阻塞；UI 实现到此关闭。真实 E2E 和部署接受保持独立，须另行明确发起。**

## 5. 历史记录

不改写下列报告中的阶段结论或测试计数：

- [Pre-UI Closeout](pre-ui-final-closeout.md)、[Task G 历史技术交接](../UI_TASK_G_HANDOFF.md)
- [G2 实现验证](../../frontend/G2_VALIDATION.md)
- [G2 视觉修正](../../frontend/G2_VISUAL_CORRECTION.md)
- [G2 长内容 UX 验证](../../frontend/G2_FINAL_UX_VALIDATION.md)
- [G2 Fit & Finish 验收及截图](../../frontend/G2_FIT_FINISH_VALIDATION.md)

G1 的实现与验证沿提交 `508859e` 及前端说明保留。当前完成 / 接受状态以本关闭记录和 Roadmap 为准。
