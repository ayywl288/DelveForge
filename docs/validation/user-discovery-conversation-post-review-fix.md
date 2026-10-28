# User Discovery 复审后修复与真实会话验收

日期：2026-10-28。范围：UD-IR-001 / UD-IR-002；不扩展 IMP-003/004。

## 结论：CONDITIONAL PASS

两个确定性发现均已关闭：专用提问不再返回空问题成功，合法长回复后的实际问题保留在下一轮
提取语境中。新增回归先复现失败再通过；无筛选后端、完整前端及运行时复核均通过；本次还完成
12 个真实 DeepSeek 对话/操作动作、21 次 Provider 调用，无重试。

保留条件是已观察到的语义质量边界：同一 revision 的充分性在两次模型评估间有波动；一个已过时
的暂态约束在换方向后没有立即自动清理，用户自然纠正后已从当前 Profile 与当前 Evidence 移除。
每轮最终结论与服务端标记一致，最终画像已纠正，未发现新增 Critical/Important 的确定性行为缺陷；
但不能将一次成功会话当作语义稳定性的普遍证明。应继续保留已有的显式画像审阅与确认边界。
本结论可供人工集成评估，不自动合并或推送，也不启动额外优化任务。

## 1. Git 与证据保护

- 工作树：`E:\develop\DelveForge-worktrees\user-discovery-conversation`。
- 分支：`feature/user-discovery-conversation`。
- 开始 HEAD：`bcf06b8adcb4667a895e6524c40ed3e2844d9fbe`。
- main / merge-base：`ff17d22c2dbf7b75397ed37df316886232c11d36`。
- 开始时唯一未跟踪文档为独立验收报告；本次原样保留并一并归档到功能分支。
- 初次交付报告与独立 FAIL 报告均未改写，校验值在本地历史报告 hash 清单核对一致。
- 没有修改 main 工作树、FinTrack、真实业务数据库、WorkingCopy、依赖或无关模块；没有 reset、合并或推送。

## 2. 根因、最小修正与失败契约

### UD-IR-001

原 AskDiscoveryQuestionUseCase 直接调用 evaluateTurn，共享了普通对话允许 nextQuestion=null 的
契约。缺口补问又以 question!=null 为前提，因此新画像明明有四个可用缺口，仍可能 HTTP 200 空问题。

现在专用入口调用同一评估器的 evaluateQuestion，并传递内部 questionRequired=true：

- 普通 evaluateTurn 仍允许仅回复；没有把每轮强制提问的旧规则带回来。
- 模型未给问题、给出已跳过的问题或重复近期问题时，优先选未跳过且未问过的确定性缺口。
  若模型重复了已知问题目标，改选另一个可用维度，避免只换说法继续问同一目标。
- 允许模型提供有关联的其他探索角度；全部跳过、问题耗尽，或充分画像没有有效新问题时，
  明确返回既有 `502 EXTERNAL_CAPABILITY_UNAVAILABLE`。不会返回空问题成功或自动重试。
- 无有效问题时在元数据保存前失败；Profile、Evidence、revision、状态和原就绪标记均不被本次失败改写。
- 仍一次模型调用，保留前后 revision/状态检查及 Repository 的原子快照核对；没有复制最低充分性逻辑。

结构类型错误仍由原严格解析器拒绝。有限维度标签与提示词不等于通用语义重复检测器；不能保证
任意模型误标维度或任意自然语言复述都会被自动识别。

### UD-IR-002

原前端把 assistantReply 与真实 question 合成一条消息后，直接取前 2048 字符。正文达到上限时，
追加问题完全消失；第一阶段提取不另接收 previousQuestions，简短回答因此失去提问语境。

现在构造 recentConversation 时利用已有 message.question：正常短消息保留原文；若截断会丢掉
该条真实问题，则为问题及分隔留出位置，截短较长正文。最近最多八条、每条最多 2048 字符的
边界不变；消息展示不变；不增加 REST 字段、虚构对话或持久化聊天。待提交、失败和结果未决输入
仍不进入已接受语境，客户端历史也仍不是权威 Evidence 或授权。

## 3. 回归执行与实际结果

修复前新断言复现：Ask 用例 13 项中 4 failures；前端 discovery-messaging 20 项中 1 failure。
分别证明空问题/跳过耗尽未失败，以及 2048 字正文丢失问题。没有删除或弱化这些断言。
原“同版负面评估清理标记”的替身响应补上有效问题，以继续在修正后的专用入口契约下验证同一元数据保证。

| 检查 | 实际结果 |
| --- | --- |
| `mvnw.cmd verify -Dtest=*UserProfile*,*Sufficiency*,*Discovery* -Dsurefire.failIfNoSpecifiedTests=false` | PASS；369 tests，0 failures/errors/skips；75 / 196 / 23 / 75；27.203 秒 |
| `mvnw.cmd verify`（无筛选） | BUILD SUCCESS；1764 tests：1725 passed、39 skipped，0 failures/errors；12:47；2026-10-28 16:53:57 +08:00 完成 |
| `pnpm test -- discovery-messaging session client views` | PASS；4 files、47 tests |
| `pnpm test` | PASS；19 files、276 tests；22.27 秒 |
| `pnpm typecheck` | PASS |
| `pnpm build` | PASS；83 modules transformed |
| 真实 HTTP + SQLite 受控 Provider 场景 | 21 项通过，包含原始空问题复现、全跳过失败和普通仅回复轮 |
| 实际 Vue 会话/DiscoveryView + HTTP 的 jsdom 探针 | 5 项通过；长回复的实际提取请求保留问题；充分画像无问题提案不再显示成功回复 |
| `git diff --check`、历史报告 hash 对比 | PASS |

全量分模块：Domain 209、Application 846、Infrastructure 491（36 skipped）、App 218（3 skipped）。
新增测试覆盖：null/空白问题、未跳过缺口、全部跳过/耗尽、重复问题改选目标、其他合法探索角度、
充分画像无有效问题、旧版本/状态拒绝、失败不改标记；以及长正文、正文前部已有问题和普通短语境。
既有畸形输出、候选原子性、审阅/确认、失败/未知/迟到响应保护继续在本次回归执行。

原始输出位于本工作树忽略目录 `target/post-review-fix/`，包含 reproduction-backend.log、
reproduction-frontend.log、focused-backend.log、full-backend.log、focused-frontend.log、
frontend-tests.log、frontend-typecheck.log、frontend-build.log、http-scenarios.json、
frontend-runtime-evidence.json、skipped-tests.json。受控 Provider 数字不是实际付费用量。

## 4. 真实 DeepSeek 会话验收

自动化全部通过后才启动真实验收。用户已单独授权付费 Provider，临时凭据仅经无回显输入注入
隔离子进程环境，没有写入源码、报告、命令行参数或应用日志。沿用配置中的 DeepSeek 端点、
`deepseek-flash` 与 SSE；没有新增金额或 Token 配额，既有 Provider 传输/生成配置保留。

新库：`target/post-review-fix/live.db`；独立本机端口与 workspace，Agent disabled。
只有一个合成验收画像，没有访问既有项目、FinTrack 或真实业务库。输入为自然语言，未改写模型
输出、塞入预期提案或为结果变绿进行重试。第 9 轮依据第 8 轮的真实问题和已观察到的过时条目补充。
最后停止的仅为本次自行创建的验收进程。

| 动作 | 自然输入/操作 | 实际观察 | revision / 状态 | 充分 | API 秒 | 模型调用 |
| ---: | --- | --- | --- | --- | ---: | ---: |
| 1 | 不知道选什么项目，想准备后端实习 | 承接不确定性，给出例子并询问技术基础 | 5 / EXPLORING | false | 5.727 | 2 |
| 2 | 跳过当前技术背景问题 | 明确允许跳过；只回复、不追加问题，无新事实 | 5 / EXPLORING | false | 6.285 | 2 |
| 3 | 询问为什么需要这些信息 | 解释技术基础与可行范围的关系；只回复，无新事实 | 5 / EXPLORING | false | 9.467 | 2 |
| 4 | 说明 Spring Boot/CRUD 经验与阅读兴趣 | 记录已有经验和兴趣，追问阅读场景 | 11 / EXPLORING | false | 10.229 | 2 |
| 5 | 纠正为一个独立项目、另一个跟教程 | 当前能力改为一独立/一跟教程；撤回旧能力依据 | 15 / EXPLORING | false | 12.210 | 2 |
| 6 | 改做 AI 面试复盘工具，阅读笔记先搁置 | 转向 AI 复盘，保留阅读兴趣及技术事实 | 19 / EXPLORING | false | 13.092 | 2 |
| 7 | 说明无其他重要约束、五小时/周和四至六周 | 记录明确无额外限制及时间范围；首次充分 | 25 / EXPLORING | true | 21.370 | 2 |
| 8 | 主动索取下一问 | 返回新的后端展示能力问题；本次语义结论变为不足，旧通过标记撤销 | 25 / EXPLORING | false | 11.818 | 1 |
| 9 | 回答展示能力，并纠正过时的未定方向条目 | 明确接口/建模目标；删除过时约束及其当前依据，再次充分 | 33 / EXPLORING | true | 16.089 | 2 |
| 10 | 询问现有信息是否足够 | 解释够在哪里；不增 revision/Evidence，仍探索 | 33 / EXPLORING | true | 12.595 | 2 |
| 11 | 充分后继续探索，补充首版范围 | 新增不做转写/团队协作；仍探索，不自动审阅 | 39 / EXPLORING | true | 17.488 | 2 |
| 12 | 明确进入审阅，不替用户确认 | 复用当前通过结果，零模型调用；进入 REVIEWING，未确认 | 39 / REVIEWING | true | 0.015 | 0 |


### Profile / Evidence / 就绪状态观察

- 第 2、3、8、10 轮前后 Profile 完全相同，包含 revision、Evidence；没有把拒答、解释、提问请求
  或充分性询问当作新用户事实。第 8 轮虽然不改内容，允许按最终结论更新充分性元数据。
- 第 5 轮当前能力改为“一独立、一跟教程”，撤回旧能力依据；真实 SQLite revision 11 的原依据
  claim/sourceRef 仍完整存在。第 6 轮换方向没有抹除阅读兴趣或已有技术事实。
- 第 7 轮明确“无其他重要约束”被有意义地记录，同时保留用户真的说过的每周五小时、四至六周范围；
  不把先前拒答解释为没有技术基础或没有约束。
- revision 25 在第 7 轮充分、第 8 轮重新评估不足；服务端通过标记相应清除，未让旧结果继续授权审阅。
  这是实测的语义波动，不冒充模型评估具备确定性稳定性。
- 初期“还不知道做什么项目”被记作 constraints，换方向后未自动清理；第 9 轮用户自然指出过时，
  本轮从当前约束及当前 Evidence 撤回该项，历史版本保留。此现象说明显式审阅/纠正仍必要。
- 最终 revision=39、status=REVIEWING；21 条当前 Evidence 全部为 USER_INPUT、confirmed=false。
  behaviors、painPoints 仍为空，没有为了充分性填满六区；最终服务端标记精确绑定 revision 39。
- 第 12 轮只把 EXPLORING 转为 REVIEWING，未推进 revision、未确认用户画像；使用同版已通过结果，
  没有额外模型调用。

### Token 与延迟（观察值）

- 实际 Provider 调用 21 次：10 个普通轮各两次，一次专用提问一次，最后审阅复用结果为零次。
- 已记录 promptTokens=49,102，completionTokens=18,852；算术合计 67,954。
  reasoningTokens=15,532 是 completion 的细项，不再重复相加；未据此推算费用。
- 普通轮 API 耗时范围 5.727–21.370 秒，中位数 12.403 秒；主动提问 11.818 秒；审阅 0.015 秒。
  12 个动作 API 耗时合计 136.385 秒，不含人工观察间隔和应用启动。
- 每次用量来自真实 SSE usage 的既有安全诊断记录，无缺失项；原始 Provider 响应体、密钥与 reasoning
  文本没有进入交付证据。

已归档的可复核摘要：[真实会话证据](evidence/user-discovery-conversation-post-review-live.json)。
其中保留合成用户输入、实际 assistantReply/nextQuestion、Profile/Evidence 变化、状态及用量；
不包含凭据、Provider 原始包络或模型思考原文。

## 5. 保留的不确定性与验收边界

- 此次真实会话覆盖了十个要求场景，但只有一组画像/模型运行；不能证明其他用户、所有措辞或
  所有模型采样都同样自然、准确。已实测的同版充分性波动及暂态条目残留列为质量观察。
- 任意自然语言是否构成事实、修正是否完整、问题是否语义重复仍受模型质量和用户审阅约束；
  没有为这两个发现扩张通用意图分类、长期记忆或语义验证框架。
- 2048 字上限场景用受控 Provider + 实际提取请求验证；真实 DeepSeek 场景未强迫模型输出长文本。
  Vue 运行时烟测使用 jsdom；未新增浏览器人工布局验收，不能称为完整浏览器产品 E2E。
- 本次 39 项门控跳过没有因 User Discovery live 而变成已通过的 Agent/Docker/仓库演化验收。

## 6. 本次全部跳过项的原因

逐项身份及原因与上一轮独立验收的 39 项一致，已用本次新生成的 JUnit XML 逐项比对。
完整方法名保留在[历史独立报告](user-discovery-conversation-independent-acceptance.md)及本地 skipped-tests.json。
没有计入 passed，也未为减少跳过而启用额外 Agent、Docker、外部 clone 或 FinTrack 条件。

| 类 / 门控 | 跳过数 | 实际原因 |
| --- | ---: | --- |
| `com.ayywl.delveforge.app.agent.AutonomousEvolutionLiveTest` | 1 | Environment variable [DELVEFORGE_TASK2_E2E] does not exist |
| `com.ayywl.delveforge.app.agent.RealRepositoryEvolutionLiveTest` | 1 | Environment variable [DELVEFORGE_REAL_STEP] does not exist |
| `com.ayywl.delveforge.app.api.evolutionplan.WorkingCopyActivationApiIntegrationTest` | 1 | org.opentest4j.TestAbortedException: Assumption failed: Fresh public clone acceptance is separately enabled |
| `com.ayywl.delveforge.infrastructure.agent.CodingAgentLiveTest` | 1 | Environment variable [DELVEFORGE_AGENT_E2E] does not exist |
| `com.ayywl.delveforge.infrastructure.agent.DockerAgentSandboxTest` | 16 | Environment variable [DELVEFORGE_AGENT_DOCKER] does not exist |
| `com.ayywl.delveforge.infrastructure.agent.DockerDependencyPreparationTest` | 4 | Environment variable [DELVEFORGE_AGENT_DOCKER] does not exist |
| `com.ayywl.delveforge.infrastructure.agent.DockerMavenCompatibilityTest` | 2 | Environment variable [DELVEFORGE_AGENT_COMPATIBILITY] does not exist |
| `com.ayywl.delveforge.infrastructure.agent.DockerProxyIsolationTest` | 1 | Environment variable [DELVEFORGE_AGENT_DOCKER] does not exist |
| `com.ayywl.delveforge.infrastructure.agent.DockerRetentionRecoveryTest` | 4 | Environment variable [DELVEFORGE_AGENT_DOCKER] does not exist |
| `com.ayywl.delveforge.infrastructure.agent.DockerSandboxProtocolTest` | 1 | Environment variable [DELVEFORGE_SANDBOX_LEGACY_IMAGE] does not exist |
| `com.ayywl.delveforge.infrastructure.agent.DockerSandboxProtocolTest` | 1 | Environment variable [DELVEFORGE_SANDBOX_PROTOCOL_IMAGE] does not exist |
| `com.ayywl.delveforge.infrastructure.agent.PublicRepositoryBaselineLiveTest` | 1 | Environment variable [DELVEFORGE_REAL_BASELINE] does not exist |
| `com.ayywl.delveforge.infrastructure.agent.RepositoryPreparationDockerTest` | 2 | Environment variable [DELVEFORGE_REPOSITORY_COMPATIBILITY_DOCKER] does not exist |
| `com.ayywl.delveforge.infrastructure.agent.SpringAiAgentModelLiveTest` | 1 | Environment variable [DELVEFORGE_AGENT_LIVE] does not exist |
| `com.ayywl.delveforge.infrastructure.workspace.RepositoryCompatibilityIntegrationTest` | 1 | org.opentest4j.TestAbortedException: Assumption failed: Additional public clone matrix is separate |
| `com.ayywl.delveforge.infrastructure.workspace.RepositoryCompatibilityIntegrationTest` | 1 | org.opentest4j.TestAbortedException: Assumption failed: Fresh public clone acceptance is separate |


三个 assumption 分别依赖 `delveforge.compatibility.evidence`（1 项）及
`delveforge.compatibility.fintrack`（2 项）系统属性；其余为表中未启用的环境变量条件。

## 7. 文件与交付

- `backend/delveforge-app/src/test/java/com/ayywl/delveforge/app/api/userprofile/UserProfileDiscoveryTurnEndpointIntegrationTest.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/AskDiscoveryQuestionUseCase.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/ProfileSufficiencyEvaluator.java`
- `backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/AskDiscoveryQuestionUseCaseTest.java`
- `docs/ARCHITECTURE.md`
- `docs/PRODUCT.md`
- `docs/ROADMAP.md`
- `docs/validation/evidence/user-discovery-conversation-post-review-live.json`
- `docs/validation/user-discovery-conversation-independent-acceptance.md`
- `docs/validation/user-discovery-conversation-post-review-fix.md`
- `frontend/README.md`
- `frontend/src/state/session.ts`
- `frontend/tests/discovery-messaging.test.ts`

修复、回归与本报告提交至现有 feature 分支；两个历史报告保留原字节。无合并或远端推送。
后续仅建议人工评估本次集成，以及在另行选择后处理已记录的语义质量观察；本次不开始这些扩展。
