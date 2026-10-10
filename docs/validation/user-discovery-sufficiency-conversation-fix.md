# User Discovery — 充分性与连续对话修复

日期：2026-10-10。Task B 先独立提交为 `c07b51d`，本次是用户明确授权的后续 M1 修复。
未更改 M2–M4、Provider Gateway、Workspace、Git、Verification 或持久化结构，没有付费调用。

本报告保留该轮的实际行为与证据。之后发现的“充分后没有下一问”与审阅体验问题，已另行
授权修复，见 [连续探索与画像审阅](user-discovery-conversation-continuation-review-fix.md)。
下文 nextQuestion=null、Continue 不提问的限制是历史状态，不再代表当前契约。

## 1. 从实际代码确认的原因

| 问题 | 修复前的实际行为 | 可以确认及不能确认的部分 |
|---|---|---|
| 过早充分 | `ProfileSufficiencyEvaluator` 的请求只有六区内容；`ProfilePromptContext.currentContent()` 不含 Evidence。提示词列出六维但没有明确需求具体性、成果与范围、原文依据标准。解析器只检查 sufficient / missingAreas / nextQuestion 自洽，true 即被接受 | 并不存在硬编码的“六区非空政策”。缺少 Evidence 上下文和质量标准是可确认的缺口；本次没有保存用户失败轮次的原始 Provider 响应，无法证明模型当时的内部判断原因 |
| 不断回到审阅 | `evaluateAndApply()` 对 true 调用 `candidate.beginReview()`，两种用例共用它；下一轮入口要求 EXPLORING，所以继续后又充分便再次 REVIEWING | 这是 Application 编排与原有契约共同造成，前端只是按实际状态禁用输入。不是仅靠隐藏 UI 可修复的问题 |
| 下一问不在聊天 | `session.turn()` 会追加 nextQuestion，`session.assess()` 只更新评估对象；DiscoveryView 右栏还重复渲染 nextQuestion | 单独评估的返回路径确实缺少 Agent 消息；不能由此断言所有用户报告轮次都调用了这一入口 |

主要代码：
[评估器](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/ProfileSufficiencyEvaluator.java)、
[整轮用例](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/workflow/RunUserDiscoveryTurnUseCase.java)、
[显式审阅入口](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/AssessProfileSufficiencyUseCase.java)、
[会话协调](../../frontend/src/state/session.ts)、[需求页面](../../frontend/src/views/DiscoveryView.vue)。

## 2. 最小修订与操作流程

充分性仍沿用现有 `{sufficient, missingAreas, nextQuestion}` 提案，不引入评分、最低条数或新存储。
评估输入新增当前 Evidence，含 claim / sourceRef / confirmed，要求核对原始依据；不改变这些字段，
也不将未确认提取结论标成已确认。提示词要求具体动机 / 场景、期望成果、实际技术基础与范围，
明确兴趣、学习、求职都可以充分，不强制要求传统痛点。

Application 对正面建议再做基本缺失检查：projectGoals、technicalCapabilities、constraints
缺失，或没有 USER_INPUT Evidence 时返回不足和一个明确的补充问题。没有重要约束可以显式说明。
这只拦截缺失事实，不用字数、关键词或数量冒充语义质量判断；六区都有模糊条目仍交给 AI
结合原文评估，不因区数自动通过。

```text
发送输入 → 提取候选 → 评估最低条件 → 一次保存 → EXPLORING
  不充分：聊天展示真实 nextQuestion，右栏展示缺失信息
  充分：没有模型下一问；提示可以补充或进入审阅，仍可连续发送

用户点击“进入审阅” → POST sufficiency-assessment → 重新评估当前画像
  不充分：仍 EXPLORING，问题加入聊天
  充分：Domain beginReview → REVIEWING

用户审阅当前画像版本 → confirm {revision} → CONFIRMED
审阅时继续探索 → continue-discovery → EXPLORING → 可连续多轮
已确认时重新探索 → reopen-discovery → EXPLORING
```

**涉及契约修订**：原 `sufficient=true ⇒ REVIEWING` 改为“达到最低条件不等于结束探索”。
没有新增状态、请求字段、Endpoint 或 Domain mutation API；`beginReview`、confirm 的状态守卫、
过期 revision 拒绝、CONFIRMED 内容只读和候选副本失败原子性保持不变。单独评估 API 被明确作为
用户请求审阅的入口，非法起始状态在调用 AI 前拒绝。同步更新 PRODUCT / DOMAIN_MODEL /
ARCHITECTURE / ROADMAP，历史报告保留当时行为，不重写历史成功证据。

问题集中由 `appendQuestion()` 加入本页聊天，同一真实文本只插入一次。右栏不再展示问题正文。
继续探索与 GET 没有 nextQuestion，不生成 Agent 问题；刷新只读取画像，不重构旧聊天或付费评估。
API 失败清除可能过期的评估，不自动重发。GET 发现画像版本变化也清除旧评估。
需求页状态显示中文、revision 显示画像版本；依据显示“来自你的输入 / 提取结论待核对”，
原始字段保留在详情。未改聊天滚动、输入区布局或其它阶段视觉风格。

## 3. 回归与本机检查

| 检查 | 实际结果 |
|---|---|
| Maven Wrapper M1 聚焦回归 | 201 项通过：领域画像、M1 应用编排 / 解析 / 审阅、SQLite 与画像 API；Provider 为测试替身 |
| 最终 `mvnw.cmd verify` | **1383 项通过，0 failures / errors / skipped**；124 个测试套件，耗时 9 分 40 秒，四个后端模块均 SUCCESS，重新生成可运行 jar |
| Vue 回归 | **23 / 23 通过**；4 个测试文件。`pnpm typecheck`、`pnpm build` 均通过 |
| 代理 / 启动 | 新打包 jar 启动于 127.0.0.1:18080，既有 Vite 15173 代理返回 `delveforge / UP`；全新独立 SQLite / Workspace |
| 实际浏览器 | 创建空画像成功，显示“探索中 / 画像版本 1”；Provider 未启用时审阅请求实际失败，错误可见，未产生 Agent 问题或 REVIEWING；刷新恢复真实画像，聊天 / 评估不重构。更换库后旧资产 ID 如实报无法恢复，通过 ID 恢复表单移除本页无效引用后正常；未删除后端资产 |

新回归覆盖六区已填但缺少用户依据时拦截 AI 的 true；六区含模糊需求时把原文与质量要求
发送给评估器并保留不足结果；明确兴趣 / 求职成果且无痛点仍可充分；继续后多轮充分仍探索；
显式审阅前 confirm 被拒；过期或非整数 revision、已确认画像写入仍拒绝；失败不留下半轮更新；
评估与对话的同一问题在左侧只出现一次；确认勾选随版本和状态重置；刷新 / 请求失败不产生问题。

证据位于被忽略的 `logs/task-b/`：`m1-focused-backend.log`、`m1-final-verify.log`、
`m1-test-summary.json`、`m1-frontend-tests.json`、`m1-frontend-build.log`、
`m1-proxy-connectivity.json`、`m1-profile-get.json`、`m1-database-check.json` 与 `m1-backend.log`。
测试数据在 `data/m1-fix-offline-20261010/`；通过 SQLite backup API 保存 `consistent-snapshot.sqlite`，
integrity_check=ok。画像 1，SoftwareAsset / RepositoryProfile / Direction / Plan / WorkingCopy 均 0。
本机后端与任务 Vite 已停止。没有访问历史 Smoke 库或 WorkingCopy，也没有制造 Verified B。

新 jar SHA-256：`cf7b807d2f1084ff22a3720016406fbb5b74c6099d3992b590c9d9296f0e792c`。
启动时 API Key 为空、Provider endpoint 为本机关闭端口；没有真实 DeepSeek HTTP 请求。
首次聚焦 Maven 命令因 PowerShell 未引用参数被拒，未运行测试；修正引用后通过。
前端检查曾发现两个新增测试夹具的类型错误，已修正，最终类型与构建通过。

## 4. 已知限制

- 模糊自然语言的具体性、Evidence 原文与提取 claim 的对应仍包含 AI 语义判断；基础缺失检查
  不能识别每一种虚构或空泛描述。最终用户审阅仍不可省略。
- 模糊六区测试使用受控 AI 不充分响应，并检查实际原文 Evidence 和质量指令进入请求；
  无用户依据时即使 AI 返回 true 也被拦截。这证明编排和检查，不是实测 DeepSeek 质量提升。
- 没有新增对话历史、评估历史、自动记忆或服务端“继续模式”字段。刷新恢复当前画像，聊天不可恢复。
- 达到最低条件时评估契约仍返回 nextQuestion=null。继续探索允许主动补充，不虚构新问题；
  不足时只返回一个关键问题，没有自动循环。没有声称每个用户都在固定轮数内完成。
- 旧客户端若将 discovery-turn 的 sufficient=true 视为已经 REVIEWING，需改成显式请求
  sufficiency-assessment；返回结构未变，但审阅触发行为有意改变。既有已确认记录不追溯重评。
- 当前审阅中的内容纠正沿用原契约，仍保持 REVIEWING；用户必须重新审阅新的画像版本并确认，
  本次未增加 Evidence 撤销/纠正架构或 PATCH 的版本锁。
