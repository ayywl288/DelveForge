# User Discovery — 连续引导与画像审阅修复

日期：2026-10-10。针对用户在真实 DeepSeek + Vue 手动体验中报告的问题，本次仅修复 M1
对话和审阅契约。基线为 `5d5de1b`；此前充分性质量、显式审阅、聊天问题位置修复保留。
本次不调用付费 Provider，不修改 M2–M4 实现、Git、Workspace、Verification、M5 或业务数据库。

## 1. 从源码确认的原因

| 问题 | 实际根因 |
|---|---|
| 充分后 Agent 不提问 | `ProfileSufficiencyEvaluator` 的系统指令要求 true 时 nextQuestion 为空；`ProfileSufficiencyProposalParser` 将 true + 非空问题视为矛盾；Evaluator 的最终正面返回也固定为空。保持 EXPLORING 仅解锁输入，无法改变这个契约 |
| Continue / Reopen 后没有问题 | 两个用例只执行合法状态转换并保存 Profile，响应没有 nextQuestion；前端没有只读提问入口，也不应伪造用户输入触发提取 |
| 审阅像一句评估提示 | 画像与依据在右侧持续面板中，但进入 REVIEWING 后主要区域仍是聊天和禁用输入，没有专门展示当前版内容、原始依据及待核实结论 |
| 附和可能进入提取 | 原提取 Prompt 只有一般性的“不臆测”，所有非空输入都会交给事实提取模型。本次没有用户失败轮次的原始模型响应，因此不能证明当时已实际保存了哪些错误断言 |

回归先将 Parser 的“拒绝 true + 下一问”测试改为接受同一结构，原实现实际失败，证据为
`logs/discovery-continuation-red.log`。没有将测试替身的结果称作真实 Provider 行为。

## 2. 三个独立概念与实际调用

`sufficient` 判断当前内容是否已能指导个人相关的项目方向；`EXPLORING` 表示用户仍在探索；
`REVIEWING / CONFIRMED` 表示用户主动审阅或确认。这三者不能用“模型停止提问”相互替代。

结构化充分性提案保留 `{sufficient, missingAreas, nextQuestion}`：

- false 仍必须有缺失信息和下一问。
- true 仍不得包含缺失信息，但允许包含深入下一问。
- 持续探索模式必须有非空、至多 512 字符且未与传入近期问题完全相同的下一问；否则本次
  请求失败，不保存候选，不自动重试。显式审阅充分时不需要问题，响应 nextQuestion 为空。
- 目标、技术基础、约束或 USER_INPUT Evidence 缺失的正面建议仍被拦截为不充分。
  探索时保留已校验的模型问题，不用固定问题覆盖它；原有最低条件没有放宽。

以下 JSON 是请求契约示例，不是恢复的用户原始交互：

```json
{
  "input": "每周可以投入五小时，希望作品展示持久化与自动化测试",
  "previousQuestions": ["每周能投入多少时间？"]
}
```

普通 `POST /api/user-profiles/{id}/discovery-turn` 仍调用 Gateway 两次：先提取真实输入，
再评估候选兼提出下一问；没有第三次独立追问调用。成功后仅保存一次候选，保持 EXPLORING。
下一问显示在左侧实际 Agent 消息，充分后可继续回答多轮。用户选择继续深入时，已有当前
版本问题便滚动至聊天并聚焦输入，不调用 Provider。

没有问题、刷新后主动索取，或用户明确 Continue / Reopen 后，使用新增的小型只读入口：

```text
POST /api/user-profiles/{id}/discovery-question
request  {revision: 7, previousQuestions: [...]}
response {revision: 7, sufficient: ..., missingAreas: [...], nextQuestion: ...}
```

`AskDiscoveryQuestionUseCase` 先校验真实状态为 EXPLORING 且版本一致，再调用一次既有评估器；
返回前再读取版本/状态，变化则丢弃旧问题并返回 409。完全不调用事实提取、不保存 Profile、
Evidence 或状态。版本字段使用既有严格整数反序列化器。

问题上下文可省略，最多八项，每项非空且不超过 512 字符；仅为本页重复问题抑制，不是
可信用户事实或服务端记忆。Prompt 引导避免语义重复与无关追问，服务端检查近期相同文本，
前端抑制本页重复插入。没有自动循环、轮询提问或模型失败重试。

明确的“嗯”“嗯嗯”“呢”“下一个问题是什么/是啥”等附和及流程请求在提取前跳过，只有一次
评估兼提问，不能增加内容或 Evidence。其它非业务自然语言由提取指令要求返回空建议；
这不是穷举所有语言的事实识别算法，仍须用户核对。

| 用户动作 | Gateway 调用次数 | 状态 / 数据效果 |
|---|---:|---|
| 普通业务对话轮 | 2，与修复前相同 | 候选最后一次保存；无自动审阅 |
| 上述明确附和 / 流程输入 | 1 | 跳过事实提取；内容与 Evidence 不变 |
| 继续深入，已有本版问题 | 0 | 使用现有消息 |
| 按需索取，没有当前问题 | 1 | 只读评估与提问 |
| 主动 Continue / Reopen | 转换 0 + 提问 1 | 两个独立请求，合法转换后才提问 |
| 显式进入审阅 | 1，与修复前相同 | 充分才 Domain beginReview |
| 纠正 / Confirm / GET / 页面刷新 | 0 | 沿用现有接口，不自动调用探索模型 |

## 3. 当前版本的画像审阅

`DiscoveryView` 在 REVIEWING 的主体使用 `ProfileReview` 展示六区实际条目、画像版本、
当前可用的充分性结果及展开的全部 Evidence。每条依据显示真实 claim、sourceRef 和核对状态；
未确认依据标为“提取结论待核对”。没有另一次 AI 摘要调用，没有构造不存在的事实。

当前没有逐条画像内容与 Evidence 的关联字段，因此展示本版完整依据并明确这个限制；不
推测对应关系。纠正沿用现有 PATCH，不删除旧 Evidence，版本变化清除旧充分性结论并取消
确认勾选。用户仍需审阅当前 revision 后明确确认。Profile 确认不批量修改 Evidence.confirmed。

从已确认画像重新探索仍必须显式调用 `reopen-discovery`。CONFIRMED 下普通聊天或只读提问
被拒绝，原快照不会静默变化，既有 ProductDirection 的版本绑定不被覆盖。只有用户主动
重新开启后才提问；最终确认后不自动发起任何探索调用。

## 4. 失败处理与验证

Provider 调用/解析失败、无下一问或重复下一问都不会保存半轮候选。前端连接中断后只 GET
当前画像，不生成假问题、不重复 POST。若 Continue / Reopen 的状态请求成功，但后续提问
失败，保留真实 EXPLORING 并显示错误；两个请求不是一个事务，不声称失败回滚已完成的转换。
刷新只恢复 Profile，不能恢复未持久化的聊天或评估；可以主动请求新问题。

本次采用 Gateway 测试替身、真实 Domain 规则及 HTTP → Application → SQLite 集成回归：

- 覆盖充分前与充分后都有下一问，持续多轮保持探索；显式审阅、Confirm、Continue、Reopen
  的合法状态与非整数/过期 revision 拒绝。
- 覆盖只读问题不保存、不推进版本；AI 调用期间内容/状态变化时丢弃旧问题；缺失守卫不放宽。
- 覆盖流程输入不产生 Evidence、重复/缺失问题使整轮失败、Provider 失败不留下半轮结果。
- Vue 覆盖本版问题复用、左侧消息去重、无界调用抑制、断线/刷新不造问题、确认不触发提问，
  以及审阅六区/依据/版本和纠正后的确认失效。

| 检查 | 实际结果 | 本机证据 |
|---|---|---|
| 修复前 Parser 回归 | 原实现拒绝充分且有下一问的结构，测试实际失败 | `logs/discovery-continuation-red.log` |
| M1 聚焦后端回归 | 223 项通过；随后新增的基本缺失守卫测试由全套验证覆盖 | `logs/discovery-continuation-backend.log` |
| Maven Wrapper 全套测试 | 125 个测试集、1396 项，失败 / 错误 / 跳过均为 0 | `logs/discovery-continuation-verify.log`；`backend/*/target/surefire-reports/TEST-*.xml` |
| `pnpm test` | 四个文件、31 项通过 | `logs/discovery-continuation-frontend.log` |
| `pnpm typecheck` / `pnpm build` | 均通过 | `logs/discovery-continuation-typecheck.log`、`logs/discovery-continuation-build.log` |
| 停止旧后端后的 `mvnw.cmd -DskipTests verify` | 整个 Reactor BUILD SUCCESS，生成可执行 Spring Boot JAR | `logs/discovery-continuation-package.log` |

完整 `mvnw.cmd verify` 的测试阶段全部通过，但最后 repackage 因用户此前运行的后端占用
目标 JAR 而失败，不能把该次命令记为 BUILD SUCCESS。用户停止旧进程后，使用同一份源码
跳过重复测试完成打包；未修改测试断言。最终 JAR 的 manifest 包含 `JarLauncher` 和实际
`DelveForgeApplication`，且包含 `BOOT-INF/classes`。

此前受限工具沙箱中的全套尝试因 Git for Windows 无法创建 signal pipe（Win32 error 5）
出现基础设施错误。切换至获准的正常宿主执行权限后，真实 Git 集成测试通过；没有削弱 Git
守卫或将失败当作成功。该次失败保留于 `logs/discovery-continuation-verify-sandbox.log`。
上述日志及构建产物为本机忽略文件，不作为本次源码提交内容。

## 5. 已知限制

- 本次没有新付费 DeepSeek Smoke。测试证明契约、编排与边界，不证明真实模型每轮都能提出
  有价值、无语义重复的问题。近期相同文本检查不等同于语义去重。
- 不新增对话历史、评估存储、AI 记忆或后台任务。刷新不能恢复旧问题；提问需要用户动作。
- 有界上下文只含本页近期问题，跨刷新重复无法检测。基本缺失检查不证明自然语言真实性；
  任意附和/闲聊也不能靠小型明确流程输入集合全部识别。
- 原有 PATCH 无 expectedRevision 锁、Evidence 无撤销/逐条关联机制，本次不扩展这些能力。
  避免多个客户端同时编辑同一画像；只读提问的前后版本检查不是多请求的全局事务锁。
- 严格问题契约失败时不会通过重试或占位问题掩盖；显示错误，由用户决定下一次操作。

来源：[评估与只读提问](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/AskDiscoveryQuestionUseCase.java)、
[Parser](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/ProfileSufficiencyProposalParser.java)、
[整轮用例](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/workflow/RunUserDiscoveryTurnUseCase.java)、
[Controller](../../backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/api/userprofile/UserProfileController.java)、
[会话](../../frontend/src/state/session.ts)、[画像审阅](../../frontend/src/components/ProfileReview.vue)。
前轮历史记录仍在 [充分性与对话修复](user-discovery-sufficiency-conversation-fix.md)，其中旧下一问限制
描述的是该轮交付状态，以本次契约修订和当前实现为准。
