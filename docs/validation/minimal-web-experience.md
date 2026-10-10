# Task B — Minimal Web Experience 验证

日期：2026-10-10。基础后端：main `5c9aa93`，M0–M4 已冻结。本次只改 Vue 前端、前端配置 / 测试与使用文档。没有修改 Domain、Controllers、DTO、Prompt、Patch Validator、SQLite 历史可信状态或历史 WorkingCopy，没有启动 M5。

## 1. 可用流程与契约来源

以 `backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/api/` 中当前 Controller / DTO 为准，核对 UserProfile / SoftwareAsset / Direction / Evolution API 集成测试。测试数据只在 `frontend/tests/fixtures.ts`；运行代码无测试数据导入。

| Vue 视图 | 请求与真实结果 | 明确决定 |
|---|---|---|
| DiscoveryView | POST user-profiles，discovery-turn `{input}`；展示响应 profile / nextQuestion / missingAreas / sufficient；PATCH 单维条目；GET 恢复画像 | REVIEWING 时勾选审阅并 confirm `{revision}`；纠正使页面重新审阅；继续 / 重开探索独立确认 |
| RepositoryView | 注册 `{location,readPermissionAllowed,licenseInfo,usageAuthorization}`；单独 analysis；目的、技术栈、模块、能力、复用、限制、风险、Evidence 与 analyzedRevision | 权限和授权无预选；绝对路径输入，浏览器不读任意目录 |
| DirectionsView | discovery `{userProfileId,expectedRevision,repositoryProfileIds}`；完整候选字段及 userNeed / userFit / reusableCapability 依据 | select / reject 无请求体，需明确确认；不自动选择；保留 REJECTED / SUPERSEDED |
| PlanView | planning `{productDirectionId,baseAssetId,baseRepositoryProfileId}`；Current / Target / Reuse / Changes / Risks、有序 Step、Goal / Scope / Changes / Criteria | 页面审阅不冒充不存在的 Plan confirm API；prepare 后逐 Step confirm，无自动执行 |
| EvolutionView | GET Plan / WorkingCopy；source / Step baseline / current / lastVerified、真实状态、结果、文件、验证动作与标准索引 | 风险勾选后仅 execute 当前 READY Step；无下一 Step 自动执行、M5 恢复或前端 trust 推进 |

`src/api/types.ts` 保留字段名与结构，`client.ts` 统一相对 URL、稳定错误码与无自动重试策略。`state/session.ts` 只协调界面请求。列表、卡片、状态和 revision 来自后端响应，前端 eligibility 仅防止误操作，后端仍是规则执行者。

## 2. 同步长请求与刷新

AI POST 与 execute 不设置客户端超时；Vite Proxy 的 `timeout` / `proxyTimeout` 明确为 0。GET 有 15 秒期限，执行等待时每次读完才安排下一轮约 3 秒轮询，不引入 WebSocket 或后端异步任务。

execute 发送前重新 GET Plan / WorkingCopy，并核对 ACTIVE、目标 READY、先前步骤 SUCCEEDED、WorkingCopy READY、current 等于 lastVerified。获取同 origin Web Locks，并成功写入最小等待标记后才能发送。刷新只 GET，不重放 execute。另一标签页的等待标记优先加载它对应的 Plan。

已收到明确后端错误且 GET 确认未产生 Candidate、READY 与 revision 未推进，允许后续手动决定；断线、代理未知错误、响应无法读取后保留等待标记。READY 不证明后台准备已结束。终态记录可以消除等待标记，但失败 Candidate 不提供重试或强制回滚按钮。

标记只含 planId / stepId / startedAt，业务缓存只含对象 ID。Profile、聊天、Evidence、源内容与模型正文不进入浏览器存储或日志。原生 dialog 的风险勾选是 UI 决策，不替代后端 Step 授权。

## 3. 自动化与本机检查

最终前端回归 **19 / 19 PASS**（4 个测试文件，无失败或跳过），`pnpm install --frozen-lockfile`、`pnpm typecheck` 和 `pnpm build` 均通过。最终测试依赖 Vitest 4.1.9 / jsdom 26.1.0 与项目 Node 范围兼容；最初安装的最新版不兼容原有 Node 范围，已据包元数据调整。既有 Vue 3.5.42、Vite 8.3.0、TypeScript 6.0.3 等锁定版本未升级。

产物 JS 121.46 kB（gzip 44.76 kB），CSS 15.35 kB（gzip 4.41 kB）。原始输出保存在被忽略的 `logs/task-b/frontend-tests.json`、`frontend-build.log`；真实联通 / API / SQLite 结果分别在 `proxy-connectivity.json`、`profile-get.json`、`asset-get.json`、`database-check.json`。测试数据库在 `data/task-b-offline-20261010/`，通过 SQLite backup API 保存 `consistent-snapshot.sqlite`，integrity_check 为 ok。后端已停止后保留这些测试数据，未复制活动数据库文件。后端源码没有变化，因此本任务未重新执行 Maven 验证。

首次沙箱构建遇到 Windows Vite 子进程 `spawn EPERM`，正常授权执行环境重跑通过。浏览器已完成表单、刷新、权限、焦点和布局检查；停止后端后追加的「刷新错误状态」浏览器动作因账户额度导致自动审批无法完成，动作未执行。错误交互由确定性回归覆盖，不将该未执行动作记作浏览器 PASS。

自动化覆盖实际请求字段、无体 Select / Confirm、真实多轮 nextQuestion / Profile 更新、409 revision 冲突、表单明确授权、Plan 与 Step 决策区分、风险确认、710 秒虚拟时间等待、重复点击、跨标签页锁、存储失败、断线 / 代理错误后刷新只 GET、失败 Candidate 不执行，以及 untrusted HTML 作为纯文本展示。710 秒是前端虚拟时间回归，**不是一次真实 Provider 耗时证明**。

真实浏览器通过 Vite 连接仅本机后端（新库，Provider key 为空，endpoint 指向本机关闭端口）。实际 create、PATCH 测试条目、GET 与受限资产注册成功；刷新恢复 revision 2 和条目。登记权限 false / usage UNCLEAR，分析按钮禁用。没有触发 analysis、discovery-turn、planning 或 execute。SQLite 只读统计显示：画像 1、revision 2、条目 1、资产 1；RepositoryProfile / Direction / Plan / WorkingCopy 均为 0。

## 4. 有针对性的质量检查

实现后参考 [Front-End Checklist](https://github.com/thedaviddias/Front-End-Checklist) 的表单、键盘、响应式与安全条目，以及 [Web Interface Guidelines](https://github.com/vercel-labs/web-interface-guidelines/blob/main/command.md)。未执行完整规则审计，也未安装 MCP / Skill 或引入 SEO、PWA、新部署系统。

| 检查 | 实际结果 / 修正 |
|---|---|
| 表单 | 控件关联 label，权限 / 授权无默认；路径提示、required、加载禁用；补全 name / autocomplete；未阻止粘贴 |
| 键盘 / 可访问性 | 原生 button / dialog、skip link、可见 focus、异步 status / alert；实际 Escape 后焦点回到「纠正兴趣」按钮 |
| 响应式 | 双栏在窄屏折叠；导航可横向滚动但正文不溢出。实际约 325 CSS px 检查曾发现 sr-only 定位溢出，给 nav-number 定位上下文后 document 宽度 313，不超过 viewport 325 |
| 草稿 | 阶段切换原先卸载视图导致未发送文本丢失；改用 KeepAlive 保留内存草稿，并测试。刷新明确不恢复草稿，不为此持久化敏感内容 |
| 加载 / 错误 / 空状态 | 显示实际请求等待，不伪造 AI 推理、PREPARING 或百分比；错误给查询入口；断线不重发执行 |
| 安全 | 无 v-html、无 API Key UI、无原始错误正文回显；测试 HTML 注入为纯文本；同 origin 执行锁仅是客户端保护 |

没有据一次视觉检查声明跨浏览器认证或完整 WCAG 合规。

## 5. 未完成与后端接口限制

- 历史聊天、充分性评估没有读取 API。刷新只能恢复持久画像状态，本页明确评估结果不可恢复，不重新付费评估。
- 没有历史列表：本浏览器保存已知 ID，提供手动 ID 恢复。遗失的 POST 创建响应可能留下本页不知道的新对象；不能自动重复创建来假装成功。
- 没有已接受 CodingProposal 或 Git diff 历史 GET。页面展示实际 ExecutionResult、文件、Candidate revision 与 VerificationResult，不拼造原始补丁。未增加会额外调用 Provider 的 preparation 预览。
- 没有请求任务 / cancellation / idempotency API。断线后持续 READY 无法确认后台是否仍在准备，页面保留阻止重复的标记，需宿主核对。浏览器锁不覆盖其它 origin、浏览器、直接 API 或清除存储。
- 画像 PATCH 无 expectedRevision，只能提示单处编辑；confirm 有 revision，冲突后重新读并审阅。
- 正式前端产物托管、认证、公网部署未交付；`preview` 无开发代理。本任务继续采用本机 Vite Proxy。
- 未进行新付费 Web 全链路或真实代码演化，没有把合成 SUCCEEDED 当作真实 Verified B。后端历史 PASS 保留于 [M4 报告](m4-real-provider-verified-evolution-smoke.md)，并非本 UI 任务的新成功。
- 仍限授权、可信 Java / Maven 场景。Windows 宿主文件系统风险、非默认 Provider 成功配置成本、默认配置未获同等证明、M5 未实现继续适用，见 [本地使用](../M4_LOCAL_USAGE.md)。

本任务后停止新增前后端功能；新授权仓库真实体验须另行授权。
