# User Discovery 独立审查与验收

日期：2026-10-28。对象：`bcf06b8adcb4667a895e6524c40ed3e2844d9fbe`。

## 结论：FAIL

存在一个可复现的 Important 级验收阻塞（UD-IR-001）：主动索取下一问的专用入口，可以把
不含问题的模型提案作为成功结果返回。新画像有四个可提问最低缺口、没有拒答时同样复现。
另有一个非阻塞 Minor 边界（UD-IR-002）：长回复截断会丢掉最近真实问题，事实提取缺少回答语境。

这不是全量回归失败：本次无筛选 Maven 验证已完整通过，上一轮未重跑全量的验证缺口已关闭。
也不是对真实 Provider 措辞质量的结论：本次只使用受控本机 Provider，没有付费调用。

| 维度 | 结果 |
| --- | --- |
| 源码审查 | 四项职责有区分、架构改动有界；1 个 Important 阻塞、1 个 Minor 非阻塞；未发现新的 Critical 领域/数据完整性缺陷 |
| 原有自动化回归 | 聚焦 361 项通过；无筛选全量 1756 项中 1717 passed、39 skipped，0 failures/errors；前端 274 项、类型检查及构建通过 |
| 运行时烟测 | 新进程实际 HTTP + SQLite 的 18 个正向场景通过；真实 Vue 会话/组件经 HTTP 跑通多轮、确认、刷新、失败及结果未决；额外反例确认两个缺陷 |
| 真实 Provider 对话质量 | 未验证。无付费授权；合成回复、合成 token 计数不构成真实语义质量或费用证据 |

## 1. 核实的 Git 基线与只读边界

- 原仓库：`E:\develop\DelveForge`，main=`ff17d22c2dbf7b75397ed37df316886232c11d36`。
- 验收工作树：`E:\develop\DelveForge-worktrees\user-discovery-conversation`。
- 分支：`feature/user-discovery-conversation`。
- HEAD：`bcf06b8adcb4667a895e6524c40ed3e2844d9fbe`；merge-base(main,HEAD)=`ff17d22c2dbf7b75397ed37df316886232c11d36`。
- 开始时功能工作树干净，44 文件的提交差异与交付清单相符。先读交付报告，再核对源文件、基线差异及独立反例。
- 读取 PRODUCT、ARCHITECTURE、DOMAIN_MODEL、ROADMAP 的相关边界。未修改应用源码、生产测试、
  原交付报告、main 工作树或 FinTrack 工作树。没有合并、推送或新的实现提交。
- 临时脚本、运行日志、受控数据库均在本工作树忽略目录 `target/independent-acceptance/`；唯一新增
  的受版本控制范围文档是本报告，未提交，因此被审查 HEAD 仍为 bcf06b8。

## 2. 架构与契约检查

本改动不只是添加一个文本字段。事实提取新增 hasNewFacts 及精确 Evidence 撤回提案；统一评估器
先计算最低缺口，再合并语义不足，协调正文与可选问题；SQLite 原子替换同版充分性元数据。
Frontend 同时处理正文、可选问题、问题历史、有限语境及迟到响应。

逻辑责任可辨认：

1. 理解本轮输入：currentUserInput + 最多八条请求内语境，主要由提示词和结构化提案表达。
2. 事实更新：ProfileExtraction / UserProfileProposalParser → 候选 Aggregate；无事实标记与修改矛盾被拒绝。
3. 充分性/问题规划：ProfileSufficiencyEvaluator 是唯一门槛与语义判断实现，questionArea/deferredAreas
   只是有限规划值。UD-IR-001 表明普通轮与专用提问入口的义务还未完全区分。
4. 回应：同一次评估请求生成 assistantReply，前端在成功响应后呈现。未增加第三次模型调用。

保留的契约：四模块依赖、AI Gateway、候选副本/单次 CAS 保存、EXPLORING/REVIEWING/CONFIRMED、
显式审阅和绑定 revision 的确认、确认后不可直接修改、历史快照、Evidence 来源由服务端绑定、
未知 HTTP 结果不自动重发。没有新依赖、通用 Agent 框架或聊天持久化。

有意修订的契约：普通轮允许 nextQuestion=null；新增 assistantReply、请求内 recentConversation；
明确纠正可撤回当前精确 Evidence 项并推进 revision；新的不足评估原子清除同版旧通过标记。
这些变更已同步对应项目文档，无数据库迁移。内部 Repository Port 的新方法已由 SQLite 与替身实现。

客户端语境被当作提示数据，不直接转成 Evidence 或授权；但这不能证明模型一定正确理解拒答、
纠正或来源。hasNewFacts 与 Evidence 下标校验是结构/领域边界，不是自然语言事实蕴含验证器。

## 3. 发现

### UD-IR-001 — Important / 阻塞：主动索取下一问能成功返回空问题

**定位**

- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/AskDiscoveryQuestionUseCase.java:35-41`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/ProfileSufficiencyEvaluator.java:188-203`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/ProfileSufficiencyProposalParser.java:65-83`

**复现条件**：创建一个 EXPLORING、revision=1 的新画像；调用 discovery-question，无拒答维度。
受控模型返回以下完整结构：

```json
{"sufficient":false,"missingAreas":["需要明确项目目标"],
 "assistantReply":"我们可以开始探索。","nextQuestion":null,
 "questionArea":null,"deferredAreas":[]}
```

Application 已计算 projectGoals、technicalCapabilities、constraints、evidence 四个最低缺口，
每项都有可用问题。实际 HTTP 200，返回五项合并缺口，但 nextQuestion=null。完整已有事实的画像
也复现；真实 Frontend 的 continueExploring() 只加入一句正文，error 为空，仍没有可回答问题。

**期望**：普通对话可以只回复；用户明确索取下一问且有未跳过的缺口时，应得到一个问题，或将
缺少必需问题的模型提案作为本次调用失败处理。不能把没有完成明确动作当作成功。

**原因/影响**：该入口直接复用允许仅回复的 evaluateTurn；评估器只在 question 非空时校验，
缺口补问也被 question!=null 条件跳过。原先专用提问的非空要求被普通轮放宽规则一并移除。
会造成用户主动请求后仍拿不到问题，需要重新触发调用；这是 IMP-001 的明确验收项失败，
不需要假定真实 Provider 总会返回空值才能成立。

**最小建议**：在专用入口保留独立的提问义务；有可用缺口时选择相应问题，或在更新元数据/返回
成功前拒绝 null。普通 discovery-turn 的可选问题规则保持不变，不增加模型调用或新框架。
补一个“完整合法回应结构，但 nextQuestion=null”的专用入口回归，而不是仅测试字段缺失。

**证据**：`target/independent-acceptance/questionless-empty-profile.json`、
`http-scenarios.json` 的额外 challenge、`frontend-runtime-evidence.json` 的 questionless explicit request。

### UD-IR-002 — Minor / 非阻塞：长回复截断丢掉正在回答的问题

**定位**

- `frontend/src/state/session.ts:166-179`：正文与问题合成后，语境直接取前 2048 字符。
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/exploration/ProfileExtraction.java:165-169`：
  提取看到 recentConversation，但没有另带 previousQuestions。

**复现条件**：合法 assistantReply 恰为 2048 字符，nextQuestion=`每周可以投入多少小时？`。
前端正确显示正文及问题；随后用户答“三小时”。实际提取请求的最近 Agent 消息只有前 2048 字符，
完全不含该问题；问题只在第二次评估请求的 previousQuestions 中出现。

**期望/影响**：有界语境应保留最近真实追问，使简短回答可被正确解释。当前几何截断会丢掉关键
指代信息，可能导致事实漏提取或错误解释；本次只证明语境丢失，不声称真实模型必然误提取。
这发生在合法上限内，但属于长回复边界，列为非阻塞 Minor。

**最小建议**：构造语境时为实际 question 保留位置，或把该问题作为单独的有界上下文传给提取；
不需要扩大总历史或持久化聊天。

**证据**：`frontend-runtime-evidence.json` 的 long reply truncation，包含实际第一、二次模型请求的区别。

## 4. 领域与失败路径检查

已通过源码和本次 fresh 测试确认：

- sufficient=true 的普通轮仍 EXPLORING；Confirm 不能绕过显式 Review，过期 revision 被拒绝。
- 必填字段存在不会覆盖语义不足；缺失必填项不会被模型 sufficient=true 放行。错误肯定结论
  被最终缺口说明替换，不继续携带无关问题。painPoints 等非必填维度可以为空。
- 明确无重要约束能保存为真实约束内容；拒答本身不生成同等内容。
- 当前错误 Evidence 可按精确项撤回；新来源绑定当前输入、confirmed=false。实际 SQLite 旧版本
  claim/sourceRef 保留；纠正不会覆盖旧版本。兴趣/目标切换保留其他内容及历史输入；旧输入被保留
  不等于旧目标仍是当前字段。后续模型如何解释矛盾原文仍需真实语义验收。
- 评估失败（包含撤回后失败）不污染已加载对象或持久化画像；并发 PATCH 后旧轮保存返回 409，
  未覆盖新约束或保存旧请求目标。当前通过标记随修改失效，新负面评估也清除同版旧通过标记。
- Backend 保存完成才返回正文；Frontend 对 502 不显示成功回复。独立探针故意在后端已提交后
  丢弃成功响应，Frontend 保持 uncertain、重新 GET 也不把该消息认定为已接受、不自动重发。

没有故意制造磁盘故障或数据库损坏；不把上述测试外推为所有宿主故障的证明。

## 5. 本次独立测试执行

所有命令从功能工作树或其 frontend 目录执行，未使用原交付数字代替 fresh 结果。

| 命令 | 实际结果 |
| --- | --- |
| `mvnw.cmd verify -Dtest=*UserProfile*,*Sufficiency*,*Discovery* -Dsurefire.failIfNoSpecifiedTests=false` | PASS；361 tests，0 failures/errors/skips；20.050 秒 |
| `mvnw.cmd verify`（无过滤） | BUILD SUCCESS；1756 tests：1717 passed、39 skipped，0 failures/errors；12:45；2026-10-28 15:56:26 +08:00 完成 |
| `pnpm test` | PASS；19 files、274 tests；21.94 秒 |
| `pnpm typecheck` | PASS |
| `pnpm build` | PASS；83 modules transformed |
| `python target/independent-acceptance/http_acceptance.py` | 18 个正常 HTTP 场景通过；另一个挑战观察到 UD-IR-001 |
| `pnpm --dir frontend exec vitest run --config ../target/independent-acceptance/acceptance.vitest.config.mts` | 最终 5 个探针测试通过：3 项正向/故障处理断言，2 项是缺陷复现断言，不能算作 5 项产品验收均成功 |

全量分模块：Domain 209 / Application 840 / Infrastructure 491（36 skipped）/ App 216（3 skipped）。
最终聚焦分模块：75 / 190 / 23 / 73。旧的缺口数量断言在本次无筛选运行中通过，验证缺口已关闭。
独立前端探针第一次因 Vitest 虚拟模块 URL 无法作为文件路径加载，尚未执行测试；只修正了
验收脚本的路径解析后重跑成功，没有修改生产源码或测试来消除失败。

日志及可复核材料位于 `target/independent-acceptance/`：focused-backend.log、full-backend.log、
frontend-tests.log、frontend-typecheck.log、frontend-build.log、frontend-runtime.log、
frontend-runtime-results.json、frontend-runtime-evidence.json、http-scenarios.json、skipped-tests.json。

## 6. 场景验收矩阵

“受控通过”表示使用合成模型回应验证实际编排、呈现与持久化，不证明真实模型理解能力。

| 场景 | 实际证据及结果 |
| --- | --- |
| 正常事实 | 实际 HTTP 写入正确字段、USER_INPUT 来源、新 revision；受控通过 |
| 不知道 | 支持性回应与替代角度；Profile/Evidence/revision 不变；受控通过 |
| 跳过 | 受控 deferredAreas 抑制等价维度追问、没有虚构事实；受控通过；跨更早历史记忆不在承诺内 |
| 问为什么 | 仅解释即可结束轮次；实际 Vue 显示解释、不强制提问；受控通过 |
| 明确误解/纠正 | 当前技术字段及当前 Evidence 改正，SQLite 旧快照保留；受控通过 |
| 含糊误解 | 仅澄清，不凭空修改 Profile；受控通过 |
| 兴趣变化 | 当前 interests 更新、其他经验保留，评估使用候选；受控通过 |
| 目标变化 | 当前 projectGoals 替换，新输入可追溯，历史原文未覆盖；受控通过 |
| 明确无重要约束 | constraints 保存显式说明、没有一直判为空；受控通过 |
| 主动索取下一问 | 模型给出问题时实际端点/前端正常；合法仅回复提案被误接纳，UD-IR-001 FAIL |
| 询问是否充分 | 反映最终缺口和服务端状态；受控通过 |
| 模型充分但必填缺失 | 拒绝充分、纠正回复及问题目标；受控通过 |
| 必填存在但语义不足 | 保留不足，不凭非空区块强制通过；聚焦测试及实际 HTTP 通过 |
| 充分后继续 | 仍 EXPLORING，无自动审阅或确认；受控通过 |
| 用户请求审阅 | 同版通过标记才复用，显式操作进入 REVIEWING；受控通过 |
| 仅回复 | nextQuestion=null 可正常完成，无虚构 Evidence；受控通过 |
| 畸形提案 | 502、安全失败、无半轮保存；受控通过 |
| 并发/旧版本 | 实际 409、无覆盖；确认旧版本拒绝，前端旧响应保护回归通过 |
| 刷新 | Vue createSession/restore 实际 GET 恢复画像；聊天空、不调用模型；受控通过 |
| 长回复后简短回答 | 丢失正在回答的问题语境，UD-IR-002；非阻塞边界失败 |

## 7. 运行时与 Provider 范围

实际启动独立 Spring Boot 应用，Backend=`http://127.0.0.1:11322`，本机受控 Provider=`http://127.0.0.1:11320`。
使用新建 `target/independent-acceptance/runtime.db`，独立 workspace/agent-runs 路径，Agent disabled。
HTTP 经过正式 Controller、UseCase、Domain、SQLite 和正式 DeepSeek HTTP Adapter。
合成凭据只用于本机 Provider；不读取或输出真实凭据。全量回归未受该临时进程干扰，最终构建成功。

Frontend 使用真实 createSession、DiscoveryView 和生产 api/client，经实际 HTTP 连接上述新进程；
渲染环境为 jsdom，支持多轮正文/问题、失败、审阅与恢复的编排检查。这不是浏览器人工视觉/布局验收，
未将 DOM 断言冒充完整浏览器 E2E。

本机 Adapter 烟测临时覆盖 streaming=false。仓库默认 DeepSeek base-url=https://api.deepseek.com、
model=deepseek-flash、streaming=true，凭据从外部配置读取；源码/装配及全量协议回归可检查，
没有验证线上凭据、服务可达性或真实模型质量。Provider 返回的 token 数为夹具计数，不计作实际用量。

**未执行的有限 live 方案**：另行明确费用上限后，使用新的画像和临时数据库，六轮分别覆盖
事实、不知道、拒答、问原因、明确纠正和改方向，再一次主动索取下一问及一次显式审阅。
最多 14 次调用，每次输出上限可暂设 2048 tokens；无自动重试，遇失败或预算上限立即停止，
记录每轮实际输入/输出 tokens、最终 Profile/revision/Evidence/就绪状态及人工语义判断。
这是待授权方案，不是本次已运行结果。

## 8. 全部 39 项跳过的调查

以下来自本次 full verify 的 JUnit XML，跳过未计入 passed。没有为了变绿开启需要 Docker、真实
Provider、外部仓库或 FinTrack 副本的额外条件。三个 assumption 的源码分别检查
`delveforge.compatibility.evidence`（1 项）和 `delveforge.compatibility.fintrack`（2 项）系统属性。
这些门控未启用，不是本次发现的产品失败，也不构成其覆盖能力已通过的证据。

| 测试类 / 条件 | 数量 | 实际跳过原因 |
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


逐项名称（39 项；原始 XML 路径和原因见 skipped-tests.json）：

- `com.ayywl.delveforge.app.agent.AutonomousEvolutionLiveTest.publicRepositoryTwoConfirmedStepsUseRealAgentAndAutomaticDelivery`
- `com.ayywl.delveforge.app.agent.RealRepositoryEvolutionLiveTest.packagedApplicationAutonomouslyChangesPublicRepositoryAndDeliversFrozenTree`
- `com.ayywl.delveforge.app.api.evolutionplan.WorkingCopyActivationApiIntegrationTest.untouchedFinTrackPersistsActualProvenanceThroughNormalPreparationApi`
- `com.ayywl.delveforge.infrastructure.agent.CodingAgentLiveTest.actualModelAutonomouslyRepairsSandboxAndFreezesCandidateInSqlite`
- `com.ayywl.delveforge.infrastructure.agent.DockerAgentSandboxTest.realOfflineMavenFailureRepairRetestAndCompleteTreeReplayLeaveOfficialUntouched`
- `com.ayywl.delveforge.infrastructure.agent.DockerAgentSandboxTest.finalCaptureRejectsCredentialMutationOutsideTheEditingTool`
- `com.ayywl.delveforge.infrastructure.agent.DockerAgentSandboxTest.crlfUtf8AndExecutableModeAreReplayedFromActualBytes`
- `com.ayywl.delveforge.infrastructure.agent.DockerAgentSandboxTest.symlinkEscapeAndFinalSpecialFilesAreRejectedWithoutOfficialMutation`
- `com.ayywl.delveforge.infrastructure.agent.DockerAgentSandboxTest.testEvidenceCannotTurnSkippedOrContradictoryReportsIntoSuccess`
- `com.ayywl.delveforge.infrastructure.agent.DockerAgentSandboxTest.finalizationRejectsBinaryDeletionEvenWhenItBypassesTextToolHistory`
- `com.ayywl.delveforge.infrastructure.agent.DockerAgentSandboxTest.engineUnavailableIsAnEnvironmentFailure`
- `com.ayywl.delveforge.infrastructure.agent.DockerAgentSandboxTest.secretReadsAreMarkedEditsRejectedAndOutputIsBounded`
- `com.ayywl.delveforge.infrastructure.agent.DockerAgentSandboxTest.failedReadOnlyPatchInspectionCanBeCorrectedWithoutLosingTheRun`
- `com.ayywl.delveforge.infrastructure.agent.DockerAgentSandboxTest.configurationReferencesAndGeneratedTestValuesFreezeWhileActualCredentialsStayBlocked`
- `com.ayywl.delveforge.infrastructure.agent.DockerAgentSandboxTest.realCancellationStopsOwnedSandboxWithoutChangingOfficialIndexOrSources`
- `com.ayywl.delveforge.infrastructure.agent.DockerAgentSandboxTest.emptySharedCacheIsPreparedWithoutExposingProjectToExternalNetworking`
- `com.ayywl.delveforge.infrastructure.agent.DockerAgentSandboxTest.passingUnrelatedMavenDirectoryCannotClearKnownFailure`
- `com.ayywl.delveforge.infrastructure.agent.DockerAgentSandboxTest.actualReplayRejectsAnIncompleteDiff`
- `com.ayywl.delveforge.infrastructure.agent.DockerAgentSandboxTest.safeChangesBesideCredentialFreezeWithoutSecretInCandidateDiff`
- `com.ayywl.delveforge.infrastructure.agent.DockerAgentSandboxTest.sourceBudgetIsCheckedBeforeHostObjectCopyAndContainerMaterialization`
- `com.ayywl.delveforge.infrastructure.agent.DockerDependencyPreparationTest.ordinaryMavenTargetOutputsAndPositiveTestsRemainSupported`
- `com.ayywl.delveforge.infrastructure.agent.DockerDependencyPreparationTest.h2ErrorTraceAppendDuringMavenIsolatedCheckKeepsSourceAndFrozenEvidence`
- `com.ayywl.delveforge.infrastructure.agent.DockerDependencyPreparationTest.preparationDiscardsSourceEditsAndFormalChecksStillRejectThem`
- `com.ayywl.delveforge.infrastructure.agent.DockerDependencyPreparationTest.existingFileDatabaseTestCannotMutateTheAgentSourceDuringPreparation`
- `com.ayywl.delveforge.infrastructure.agent.DockerMavenCompatibilityTest.unmodifiedPinnedPublicSpringBootReactorBuildsWithJdk17WithoutUsingWrapper`
- `com.ayywl.delveforge.infrastructure.agent.DockerMavenCompatibilityTest.releaseEightProjectCompilesOnControlledJdk21WithoutPomRewrites`
- `com.ayywl.delveforge.infrastructure.agent.DockerProxyIsolationTest.clientProxyCredentialsAreAbsentFromEveryProjectFacingContainerAndColdMavenStillWorks`
- `com.ayywl.delveforge.infrastructure.agent.DockerRetentionRecoveryTest.failedInsertTerminalCrashAndInterruptedReleasePreserveMaterialOnEveryRestart`
- `com.ayywl.delveforge.infrastructure.agent.DockerRetentionRecoveryTest.ordinaryFailureCleansResourcesAndAllowsFreshReservation`
- `com.ayywl.delveforge.infrastructure.agent.DockerRetentionRecoveryTest.frozenCandidateSurvivesRestartWhileDisposableSandboxIsRemoved`
- `com.ayywl.delveforge.infrastructure.agent.DockerRetentionRecoveryTest.secondCrashAfterInterruptedCommitStillRetainsOnAnotherRestart`
- `com.ayywl.delveforge.infrastructure.agent.DockerSandboxProtocolTest.actualCompatibleImageIsCheckedBeforeInitializeAndReturnsSafePatchResults`
- `com.ayywl.delveforge.infrastructure.agent.DockerSandboxProtocolTest.actualLegacyImageIsRejectedBeforeInitializeOrPatch`
- `com.ayywl.delveforge.infrastructure.agent.PublicRepositoryBaselineLiveTest.unmodifiedPublicBaselineExecutesPositiveMavenTestsInControlledDocker`
- `com.ayywl.delveforge.infrastructure.agent.RepositoryPreparationDockerTest.actualDockerCandidatePreservesLargeOpaqueAssetsAndUsesPreparedBaseline`
- `com.ayywl.delveforge.infrastructure.agent.RepositoryPreparationDockerTest.finTrackPreparedSourceEntersRealSandboxAndClassifiesActualBaselineCheck`
- `com.ayywl.delveforge.infrastructure.agent.SpringAiAgentModelLiveTest.nativeToolCallIsReceivedExecutedOnceAndContinued`
- `com.ayywl.delveforge.infrastructure.workspace.RepositoryCompatibilityIntegrationTest.untouchedFinTrackIsAutomaticallyPreparedWithoutManualSourceChanges`
- `com.ayywl.delveforge.infrastructure.workspace.RepositoryCompatibilityIntegrationTest.genuinePublicRepositoriesReceivePinnedDiagnosticsWithoutHostBuilds`

## 9. 建议下一步

先由用户另行授权修复 UD-IR-001，并添加区分普通仅回复轮与明确提问入口的回归；UD-IR-002
可作为小范围随同修正或明确接受的非阻塞边界。随后重跑受影响回归并复审这两个反例。
真实 Provider 语义质量另按有限方案验收，不能从本次绿色回归推导。
本次不开始修复、不改原交付记录、不启动下一实现任务、不合并或推送。
