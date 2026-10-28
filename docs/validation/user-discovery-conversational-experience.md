# User Discovery 对话体验与充分性一致性（IMP-001 / IMP-002）

日期：2026-10-28。用户单独授权的冻结期有限例外。本文记录源码检查、确定性测试与交付范围，
不把替身回复当作真实模型会话质量或付费 Provider 的验收证据。

## 工作区与范围

- 分支：`feature/user-discovery-conversation`。
- 工作树：`E:\develop\DelveForge-worktrees\user-discovery-conversation`。
- 基线：已核实的本地 `main` = `ff17d22c2dbf7b75397ed37df316886232c11d36`。
- 创建时本地 `origin/main` 跟踪引用也为该提交；未把缓存引用称为新获取的远端状态。
- 原检出 `E:\develop\DelveForge` 的 `frontend/design/`、`grilling/` 与所有 FinTrack 工作树保留。
- 不合并、不推送，不操作真实业务数据库、WorkingCopy、付费 Provider 或外部 E2E。

## 已核实的改前限制

这些结论来自基线源码和现有测试，不是新执行的真实 Provider 对话：

1. `RunUserDiscoveryTurnUseCase` 只提取再评估；评估器看不到本轮用户输入，主要只有 Profile、
   Evidence 和 previousQuestions。Frontend 只把 nextQuestion 插入聊天，缺少独立回应正文。
2. 探索评估强制返回非空问题；解释、跳过、澄清不能自然结束一轮。
3. 确定性门槛只在模型声明充分后检查；模型声明不足时，基本缺口不一定出现在最终结果中。
   模型声明充分被推翻后，探索仍沿用原模型问题，已有 Ask 测试甚至固定了这种不一致行为。
4. 提取可以整区替换，但没有从当前 Evidence 撤回错误推断的操作，旧依据仍混在当前集合中。
5. 同版本曾通过后重新评估为不足，旧通过标记仍可能保留，显式审阅会复用它。

## 最小实现与职责

沿用四模块和既有 AI Gateway，没有新依赖、Agent 框架或跨模块反向依赖。没有需要新 ADR 的
基础设施或长期技术选择；产品行为、纠正语义和运行契约分别同步在 PRODUCT、DOMAIN_MODEL、
ARCHITECTURE，授权例外登记在 ROADMAP。

正常一轮仍为两次调用：

1. **事实提取**：当前 Profile + 当前用户输入 + 仅用于指代的有限语境 → 结构化提案。
   整轮提案新增 `hasNewFacts`；false 不得同时修改任何内容区、撤回或新增 Evidence；true
   必须提供当前输入支持的 evidenceClaims。不清楚的纠正先澄清；换方向保留其他既有事实。
2. **充分性与对话规划**：先从候选计算确定性缺口，再把该缺口、候选与 Evidence、本轮输入及
   有限语境交给同一次模型调用。语义判断、回应及可选问题有不同字段，均作为待校验提案。
3. **最终协调**：始终合并确定性缺口与语义不足。模型误报充分时改为不足，并用对应缺口说明
   替换肯定回复。非空问题的目标若偏离必需缺口，则选择尚未跳过且未重复的缺口问题；本轮
   可以只回复。模型标记的拒答维度被抑制，不把拒答解释为“没有能力/没有约束”。
4. **提交**：全部成功后，用候选副本执行一次 CAS 保存。回复只是输出，不具备状态转换权限。
   最终充分性在同一事务更新元数据；新的不足评估删除同版旧通过标记，不保存负面评估历史。

`ProfileSufficiencyEvaluator` 是最低门槛和语义评估的唯一实现；显式审阅、普通轮和按需提问复用它。
questionArea / deferredAreas 只有六个 Profile 维度、evidence 以及探索目标等有限规划值，
不是通用意图分类系统，也不存为 Profile 内容或用户偏好。

## HTTP 与前端契约

普通轮请求：

```json
{
  "input": "为什么需要技术背景？",
  "previousQuestions": ["你做过什么项目？"],
  "recentConversation": [{"role": "agent", "text": "你做过什么项目？"}]
}
```

普通轮响应（省略 Profile 内容）：

```json
{
  "profile": {"id": "…", "revision": 1, "status": "EXPLORING"},
  "sufficient": false,
  "missingAreas": ["还未说明当前可使用的技术基础"],
  "assistantReply": "技术背景用于判断可行范围，可以先跳过。",
  "nextQuestion": null
}
```

`assistantReply` 是回应正文；`nextQuestion` 是可选追问。前端合为一条 Agent 消息，已出现在
正文中的同文本问题不再重复；提问历史单独取实际问题，避免把解释正文当作旧问题。
`discovery-question` 仍携带当前 revision，新增相同可选语境并返回 assistantReply；只更新
充分性元数据，不保存 Profile、Evidence 或状态。

recentConversation 可省略，最多八条 user/agent 消息，每条最多 2048 字符；previousQuestions
仍最多八项、每项 512 字符。Frontend 排除当前待提交、失败和结果未决输入，只取本页最近
已接受消息与实际回复。服务端校验边界并将客户端历史作为不可信语境，不直接导入为事实或授权。
没有新增聊天持久化、浏览器正文存储或原始对话日志。

## 领域与持久化保证

- 最低条件仍为 projectGoals、technicalCapabilities、constraints 与 USER_INPUT Evidence，
  再结合语义判断；painPoints 等非强制维度可为空。明确“没有重要约束”可以成为真实内容，
  拒答和没有提供不能自动填充为“没有约束”。
- 充分不改变 EXPLORING；只有显式审阅且通过才 beginReview，确认仍绑定已审阅 revision。
  CONFIRMED 内容与 Evidence 不可变，必须显式 Reopen；后续方向的历史引用不变。
- 明确纠正通过合法、唯一的当前 Evidence 下标提出撤回，再经 Domain 的 retractEvidence
  修改候选集合，推进 revision。新依据 sourceType 固定 USER_INPUT、sourceRef 为当前原文，
  confirmed=false；模型不能制造来源或确认状态。旧 revision 的依据快照不被删除或覆盖。
- 无事实输入、解释和提问本身不增加内容或 Evidence，不推进 revision；允许更新当前版本的
  充分性元数据。无新事实但同时提出修改的响应作为矛盾提案失败。
- 提取、撤回、区块更新和评估在候选副本上进行；任何 AI、解析或领域失败都不保存半轮。
  原先从 Repository 取得的对象同样不被污染。保存继续核对原始状态、revision 和快照。
- 按需提问对元数据的更新同样原子核对快照；并发旧候选不能覆盖当前状态。
- 前端只在真实成功响应后标记本轮接受并插入回复。已知拒绝显示 failed，断线/未知结果保持
  uncertain、不自动重发；同画像变版、换项目、销毁会话的迟到响应不会覆盖新状态。

## 验收覆盖与证据范围

以下是替身驱动的契约/领域效果覆盖，不能证明模型必然正确理解每种自然语言：

| 验收项 | 确定性证据 |
| --- | --- |
| 1 正常事实更新 | RunUserDiscoveryTurnUseCaseTest、HTTP discovery-turn 集成测试 |
| 2 不知道，支持替代角度 | ConversationalDiscoveryTest.unknownAnswerOffersAnAlternativeWithoutFactsOrRevisionChange |
| 3 拒答不立即换说法重问 | declinedDimensionCannotImmediatelyReappearAsAParaphrasedQuestion |
| 4 解释为何需要信息 | clarificationSufficiencyQueryAndAmbiguousCorrectionCanBeResponseOnly、HTTP response-only |
| 5 明确纠正 | correctionReplacesInvalidCurrentFactAndRetractsOnlyItsEvidence；Domain 撤回测试 |
| 6 改变方向保留其他事实 | topicChangeKeepsOtherFactsAndExplicitAbsenceOfConstraintsIsMeaningful |
| 7 主动下一问 | explicitQuestionRequestUsesOneCallAndContextCannotSupplyNewFacts、Ask 用例与前端按需提问 |
| 8 询问是否足够 | clarificationSufficiencyQueryAndAmbiguousCorrectionCanBeResponseOnly |
| 9 无事实不增依据/revision | unknownAnswer…、responseOnlyNegativeAssessmentClearsTheCurrentReadinessWithoutARevisionChange |
| 10 可以仅回复 | HTTP responseOnlyTurnReturnsReplyWithoutInventingFactsOrForcingAQuestion、前端 messaging |
| 11 模型误报充分 | finalMissingRequirementsReplaceAnUnrelatedProposedQuestionAndPositiveReply |
| 12 必填均有标签但仍模糊 | populatedMandatorySectionsDoNotOverrideSemanticVagueness、原 Evaluator vagueNeed 测试 |
| 13 明确无重要约束 | topicChangeKeepsOtherFactsAndExplicitAbsenceOfConstraintsIsMeaningful |
| 14 最终问题与门槛一致 | finalMissingRequirements…、Ask.basicMissingGuardReplansQuestionAgainstFinalAssessment |
| 15 充分仍可连续探索 | 原 Run 与 HTTP 的 Continue/显式 Review/Confirm 回归 |
| 16 畸形/矛盾模型输出 | parser、malformedOrContradictoryProposals…、HTTP 非法语境回归 |
| 17 AI 失败不提交半轮 | failedAssessmentAfterRetraction…、原 workflow/HTTP AI 失败回归 |
| 18 审阅/确认 revision | 原 Review/Confirm/Assess 用例和 Endpoint 测试；SQLite 快照测试 |
| 19 迟到响应不覆盖 | 前端同画像变版、销毁与原项目切换测试；后端 concurrentChangeDuringAssessment… |
| 20 失败/未知不显示成功回复 | discovery-messaging 的明确拒绝、未知 HTTP、关联失败与无自动重发回归 |

SQLite 新增测试核对：撤回仅影响新版本集合，旧版本 claim/sourceRef 仍存在；过期保存被拒绝；
同版不足清除已通过标记而不改变 revision。真实持久化测试只使用测试数据库。

## 执行结果

最终源码验证（日志为本工作树中忽略的本地文件）：

| 检查 | 实际结果 |
| --- | --- |
| `mvnw.cmd verify -Dtest=*UserProfile*,*Sufficiency*,*Discovery* -Dsurefire.failIfNoSpecifiedTests=false` | PASS；361 tests，0 failures/errors/skips；Domain 75、Application 190、Infrastructure 23、App 73；四模块构建与应用重新打包成功 |
| `pnpm test` | PASS；19 files、274 tests |
| `pnpm typecheck` | PASS |
| `pnpm build` | PASS；83 modules transformed |
| `git diff --check` | PASS |

最终后端日志：`discovery-final-verify.log`，2026-10-28 15:22:38 +08:00 结束，24.616 秒。
前端最终测试从 15:22:26 开始，21.38 秒；类型检查和构建随后完成。

另执行过无筛选 `mvnw.cmd verify`（`discovery-backend-verify.log`）：1751 tests，
1711 passed、1 failure、0 errors、39 skipped；12 分 49 秒。唯一失败是旧 HTTP 充分性测试
仍期望只有模型返回的 2 个缺口，实际正确合并为 6 个。已更新为精确数量并检查新增目标/依据
缺口，最终上述 361 项验证包含该测试并全部通过。39 项跳过是既有环境/Live 条件，未启用。
全量运行期间之后增加的同版元数据修正与补充场景，也已在最终 361 项和四模块构建中验证。
没有第二次无筛选全量运行；不把这次广泛回归标为全量 PASS。

相关本地原始输出：`discovery-frontend-tests.log`、`discovery-frontend-typecheck.log`、
`discovery-frontend-build.log`。这些是本次 fresh 执行，不使用旧验证数字代替。

## 调用次数与费用

普通自然语言轮仍为提取一次 + 评估/回复一次；明确附和/下一问短语继续跳过提取，仅一次。
用户主动索取问题一次；显式审阅命中当前通过结果时零次，否则一次。没有自动付费重试。
新增有限语境及回复字段会增加一些输入/输出 tokens；未进行真实费用测量，不能承诺具体节省金额。
本次没有调用真实 Provider、消耗付费额度或启动外部 E2E。

## 兼容性、限制与人工验收

- 不需要数据库迁移，继续使用现有表、revision 快照与充分性标记。历史 Profile 可直接读取。
  内部 UserProfileRepository Port 新增 recordSufficiencyAssessment；当前 SQLite 和测试替身已实现，
  自定义持久化适配器需同步实现该元数据方法。
- 请求新增语境字段可省略；响应 assistantReply 为新增字段，nextQuestion 现在可以为空。
  新前端兼容旧响应缺少正文的情况；仅显示 nextQuestion 的旧客户端不能获得完整体验，应与后端一同更新。
- 新后端模型结构更严格：整轮提取必须有 hasNewFacts，对话评估必须有回应及合法规划字段；
  只会返回旧格式的自定义替身/兼容网关需要更新，违约返回 502，不静默接受不完整轮次。
- 意图识别、claim 是否忠于原文、语义重复和自然措辞仍依赖模型及用户审阅。下标合法性与
  sourceRef 绑定不等于自动证明一条撤回在语义上正确；该不确定性没有被单元测试抹去。
- 跳过意愿只存在于有限本页语境；刷新或超出最近八条后不保证记住更早的拒答。
  当前接口对结果未决请求仍无法证明是否已提交，因此继续保留 uncertain 与禁止自动重发。
- 没有新执行的真实模型会话或浏览器人工视觉验收。建议另行授权后按顺序人工体验：
  正常事实 → 不知道 → 拒答 → 问原因 → 明确纠正 → 模糊纠正 → 改做 AI 项目 → 主动下一问 →
  询问是否足够 → 明确无重要约束 → 充分后继续多轮 → 显式审阅/确认；同时检查原文依据与旧快照。

## 修改区域

完整修改清单（44 个文件；测试夹具仅随对应契约更新）：

- `backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/api/userprofile/DiscoveryQuestionRequest.java`
- `backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/api/userprofile/DiscoveryQuestionResponse.java`
- `backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/api/userprofile/DiscoveryTurnRequest.java`
- `backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/api/userprofile/DiscoveryTurnResponse.java`
- `backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/api/userprofile/UserProfileController.java`
- `backend/delveforge-app/src/test/java/com/ayywl/delveforge/app/api/userprofile/UserProfileDiscoveryTurnEndpointIntegrationTest.java`
- `backend/delveforge-app/src/test/java/com/ayywl/delveforge/app/api/userprofile/UserProfileSufficiencyEndpointIntegrationTest.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/port/persistence/UserProfileRepository.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/exploration/ProfileExtraction.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/exploration/UserProfileProposalParser.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/shared/DiscoveryContext.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/AskDiscoveryQuestionUseCase.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/ProfileSufficiencyEvaluator.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/ProfileSufficiencyProposal.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/ProfileSufficiencyProposalParser.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/SufficiencyAssessment.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/workflow/RunUserDiscoveryTurnUseCase.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/workflow/UserDiscoveryTurn.java`
- `backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/userdiscovery/shared/InMemoryUserProfileRepository.java`
- `backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/AskDiscoveryQuestionUseCaseTest.java`
- `backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/AssessProfileSufficiencyUseCaseTest.java`
- `backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/ProfileSufficiencyProposalParserTest.java`
- `backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/userdiscovery/workflow/ConversationalDiscoveryTest.java`
- `backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/userdiscovery/workflow/RunUserDiscoveryTurnUseCaseTest.java`
- `backend/delveforge-domain/src/main/java/com/ayywl/delveforge/domain/user/UserProfile.java`
- `backend/delveforge-domain/src/test/java/com/ayywl/delveforge/domain/user/UserProfileTest.java`
- `backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/userprofile/SqliteUserProfileRepository.java`
- `backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/userprofile/UserProfileMapper.java`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/persistence/userprofile/SqliteUserProfileRepositoryIntegrationTest.java`
- `docs/ARCHITECTURE.md`
- `docs/DOMAIN_MODEL.md`
- `docs/PRODUCT.md`
- `docs/ROADMAP.md`
- `docs/validation/user-discovery-conversational-experience.md`
- `frontend/README.md`
- `frontend/src/api/client.ts`
- `frontend/src/api/types.ts`
- `frontend/src/state/session.ts`
- `frontend/src/views/DiscoveryView.vue`
- `frontend/tests/client.test.ts`
- `frontend/tests/discovery-messaging.test.ts`
- `frontend/tests/session.test.ts`
- `frontend/tests/ux-refinement.test.ts`
- `frontend/tests/views.test.ts`
