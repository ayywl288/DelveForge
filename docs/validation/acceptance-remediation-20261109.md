# Guided Discovery 独立验收缺陷修复记录

修复基线 `52ecf28cca98422d75b26089fcc00fdc6e904f16`，分支 `codex/guided-discovery-conversation`。复核了 Sol 的验收报告、约束版本行、首次 Delivery 读取记录及只读 SQLite 快照。本轮只使用确定性提案、本机 HTTP 和隔离的浏览器夹具。

## F-001 · 手工修订的画像值被覆盖

**复现。** 独立快照的 r31 约束是“一个晚上完成”，r32 已改为“约两小时完成”。README 对话后，r38 和 r40 恢复旧值。r32 仍有 8 条 Evidence 的来源或判断文本含“一个晚上”。这些记录描述先前的用户输入。无法从快照确定模型生成旧值的具体原因。

**原因与修复。** 手工 PATCH 按原有规则写画像并推进内容版本。原提取器仅校验本轮提案整体有 Evidence，随后按完整数组替换分区，未核对具体被移除或恢复的值。现在提取器在撤回 Evidence 或应用任何条目之前检查变更：

- 替换须在 `replacements` 中标出当前旧条目和本轮原文里的新条目。删除须指向旧条目、给出 null，并且本轮提到旧值。模型遗漏旧条目不能充当删除指令。
- 使用历史版本和当前版本的已保存条目，查出存在已被取代内容的分区。该分区再增加条目时同样要求新文本来自当轮原文。这也覆盖已清空的分区以及升级前保存的画像。在独立 r32 快照上，该查询识别出 constraints 和 projectGoals。
- 用户仍可手工修改，也可在后续自然语言输入中明确给出替换值。没有新增或删除人工修订的 Evidence，没有为审阅制造事实或额外推进 revision。旧快照、真实输入及旧 Evidence 保留。校验不通过时本轮失败，规划阶段不会被调用。

**验证。** `DiscoveryConversationIntegrationTest` 使用真实 SQLite 和显式审阅、纠正、确认、重开用例。受控提案尝试回填旧时间、连同新时间追加旧时间、恢复旧兴趣，或拿 README 原文直接替换约束。另含 Evidence 撤回和其他分区改动。每次拒绝都检查请求状态、模型调用次数、完整历史、版本及 Evidence。随后正常接受 README 补充，再接受“三小时完成”的显式修改，并读回修订前版本。

## F-002 · Candidate 已冻结，尚无 Delivery

**复现和原因。** 独立验收记录的首次 GET 返回 HTTP 200、零字节、无 Content-Type。Spring 将 Java null 写为空响应，通用客户端严格解析 JSON。原页面又等待 Candidate 与 Delivery 的组合请求，两者一起失败时丢失了可展示的冻结文件。

**修复和验证。** 已有 Candidate 暂无 Delivery 时，GET 明确返回 HTTP 200、`application/json` 和四字节 `null`。未知 Candidate 仍为 404。有记录时返回原 Delivery 对象。页面先校验并展示 Candidate，再读取并核对对应 Delivery；已知暂无记录时显示“等待自动交付”。`CodingAgentApiTest` 通过真实 HTTP 和 SQLite 检查未知、暂无、INTENT、COMPLETED。前端测试用实际 JSON 客户端读取 null、损坏的 JSON 和网络错误，随后刷新到 INTENT、COMPLETED，核对冻结文件与历史行。另一条用例验证未选中的运行历史能在刷新后从暂无记录更新到已完成。通用 JSON 读取、自动交付及基线校验均沿用原实现。

## F-003 · 回复正文与替代追问重复

**复现和原因。** 独立数据库的第 6 条消息在正文结尾和 nextQuestion 中分别询问项目类型、生活中的小场景。原逻辑核对过往提问及唯一待答状态，未比较这两个当轮字段。

**修复和验证。** ASK 的正文承接、解释或举例，追问由 nextQuestion 承载。接受前比较直接提问、邀请作答的分句与下一问。对明显重复和共享较多相邻字词的改写，使用已有的一次局部规划修正。修正仍无效则失败，原 ASKED 状态保留。界面没有删除问句。SQL 集成测试使用验收原句，分别验证修正成功、连续无效及“不知道”仍可 REPLACED；解释问句与下一问可以共享“小场景”等词汇；同主题的“为什么”与选择性追问完整保存。组件与真实浏览器均核对了即时消息及重新读取后的呈现。

该比较是有界的文字相似检查。词汇完全不同的同义问法、对本轮意图的理解和真实模型的措辞质量仍需独立复验。画像校验能验证引用来自本轮，语句的否定、假设和指代含义仍由提取阶段理解。

## 浏览器实查

Windows Chrome 读取生产前端与本机 HTTP 夹具。桌面 1440×960、窄屏 390×844。交付场景分别返回真实 JSON null、INTENT、COMPLETED 或故意破损的正文；对话场景模拟一次已接受的 POST，刷新后经 GET 恢复。截图滚动至被核对的控件。桌面与窄屏分别核对。连续采集时曾有一批窄屏夹具读取超时，独立 Chrome 会话重跑了全部六个窄屏场景。两种宽度均无横向溢出。等待与读取错误时冻结文件仍可查看；替代问题在两次渲染中都恰好出现一次。

| 场景 | 桌面 | 窄屏 |
| --- | --- | --- |
| 暂无交付 | [1440](acceptance-remediation-20261109/delivery-waiting-1440.png) | [390](acceptance-remediation-20261109/delivery-waiting-390.png) |
| 交付处理中 | [1440](acceptance-remediation-20261109/delivery-progress-1440.png) | [390](acceptance-remediation-20261109/delivery-progress-390.png) |
| 交付已完成 | [1440](acceptance-remediation-20261109/delivery-completed-1440.png) | [390](acceptance-remediation-20261109/delivery-completed-390.png) |
| 响应不可读 | [1440](acceptance-remediation-20261109/delivery-broken-1440.png) | [390](acceptance-remediation-20261109/delivery-broken-390.png) |
| 替代问题即时显示 | [1440](acceptance-remediation-20261109/replacement-immediate-1440.png) | [390](acceptance-remediation-20261109/replacement-immediate-390.png) |
| 刷新恢复替代问题 | [1440](acceptance-remediation-20261109/replacement-restored-1440.png) | [390](acceptance-remediation-20261109/replacement-restored-390.png) |

## 执行与范围

用修复前的 52ecf28 和相同的新增后端测试做了反向验证。三个 Discovery 测试实例均断言失败：旧实现对无依据的提取继续调用规划，或直接接受重复提问。Delivery HTTP 用例因旧响应缺少 Content-Type 出错。基线合计 4 项，0 通过、3 断言失败、1 错误、0 跳过。

前端定向回归 90 项，完整回归 374 项（23 个测试文件），均为 0 失败、0 跳过。类型检查和生产构建通过。后端定向测试 246 项通过，0 失败、0 错误、0 跳过。最终源码在工作目录另运行 DiscoveryConversationIntegrationTest 和 CodingAgentApiTest，36 项通过并重新生成 Spring Boot 包。各次测试独立计数。

最终后端源码的 16 个变更文件已与隔离构建副本逐字节核对。在 Windows / Java 21 上用 Maven Wrapper 运行离线全量 `mvnw.cmd -o verify`，成功打包。总数来自这次 reactor 的四个模块汇总：

| 模块 | 总数 | 通过 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Domain | 209 | 209 | 0 | 0 | 0 |
| Application | 929 | 929 | 0 | 0 | 0 |
| Infrastructure | 642 | 590 | 0 | 0 | 52 |
| App | 230 | 227 | 0 | 0 | 3 |
| 合计 | 2,010 | 1,955 | 0 | 0 | 55 |

后端修改集中在 Profile 提取/解析、两处探索入口、现有快照查询、对话规划校验和 CodingAgentController 的 Delivery GET。前端改动限于 EvolutionView、运行摘要文案及回归测试。没有数据库迁移。未运行付费 DeepSeek 请求，未重新执行真实 Coding Agent Run。Sol 的独立验收结论仍待其重新验证。

## 变更文件

| 范围 | 文件 |
| --- | --- |
| 后端实现 | [CodingAgentController.java](../../backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/agent/CodingAgentController.java)、[UserProfileRepository.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/port/persistence/UserProfileRepository.java)、[ExploreUserProfileUseCase.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/exploration/ExploreUserProfileUseCase.java)、[ProfileExtraction.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/exploration/ProfileExtraction.java)、[UserProfileProposalParser.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/exploration/UserProfileProposalParser.java)、[ProfilePromptContext.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/shared/ProfilePromptContext.java)、[DiscoveryQuestions.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/DiscoveryQuestions.java)、[ProfileSufficiencyEvaluator.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/ProfileSufficiencyEvaluator.java)、[RunUserDiscoveryTurnUseCase.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/workflow/RunUserDiscoveryTurnUseCase.java)、[SqliteUserProfileRepository.java](../../backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/userprofile/SqliteUserProfileRepository.java)、[UserProfileSectionItemMapper.java](../../backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/userprofile/UserProfileSectionItemMapper.java) |
| 后端回归 | [CodingAgentApiTest.java](../../backend/delveforge-app/src/test/java/com/ayywl/delveforge/app/agent/CodingAgentApiTest.java)、[UserProfileDiscoveryTurnEndpointIntegrationTest.java](../../backend/delveforge-app/src/test/java/com/ayywl/delveforge/app/api/userprofile/UserProfileDiscoveryTurnEndpointIntegrationTest.java)、[InMemoryUserProfileRepository.java](../../backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/userdiscovery/shared/InMemoryUserProfileRepository.java)、[ConversationalDiscoveryTest.java](../../backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/userdiscovery/workflow/ConversationalDiscoveryTest.java)、[DiscoveryConversationIntegrationTest.java](../../backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/persistence/userprofile/DiscoveryConversationIntegrationTest.java) |
| 前端实现 | [agentResultPresentation.ts](../../frontend/src/components/agentResultPresentation.ts)、[EvolutionView.vue](../../frontend/src/views/EvolutionView.vue) |
| 前端回归 | [agent-workflow.test.ts](../../frontend/tests/agent-workflow.test.ts)、[discovery-messaging.test.ts](../../frontend/tests/discovery-messaging.test.ts) |
| 架构说明 | [ARCHITECTURE.md](../../docs/ARCHITECTURE.md) |
