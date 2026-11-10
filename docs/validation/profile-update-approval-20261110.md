# Guided Discovery 画像整轮批准实现记录

本轮基线为 `167995c2cf9afcb672473b0387c617a145c8ea8d`，分支
`codex/guided-discovery-conversation`。开始时只有 `codex-migration/`、`frontend/design/`、
`grilling/` 三个无关未跟踪目录；未修改、清理或加入提交。

本记录是本轮自行执行的工程验证，不是独立 Sol 验收结论。此前报告仅用于理解基线，
不作为本次执行证据。未调用付费 DeepSeek，也未重新执行真实 Coding Agent 产品演化。

## 更新规则与 F-001

模型继续建议六个有序完整 `List<String>` 分区，省略或 null 表示不变。Application 分类如下：

| 分类 | 确切条件与结果 |
| --- | --- |
| UNCHANGED | 建议与当前分区按内容及顺序完全相等，不改内容。 |
| AUTO_APPEND | 未人工保护；原有全部条目原文、顺序不变且构成精确前缀；只追加新条目；不引入新的精确重复；全部通过领域校验。 |
| REQUIRES_APPROVAL | 删除、改写、重排、受保护区的任何变化，或其他不满足自动追加条件的变化。 |

任一区需要批准时，全部变化区及对应依据一起暂存；不部分更新画像、Evidence 或画像内容版本。
没有实际分区变化的模型输出不单独制造 Evidence 或 revision。合法新增可转述真实输入，
没有要求新值逐字存在于用户原文中。精确比较只判断允许的追加形状，不证明语义真实性。

移除历史差异推断、`groundedSections`、`replacements` 字符串授权及模型按下标撤回 Evidence。
旧字段即使被模型夹带也没有额外权限。人工保护完全来自实际 PATCH、接受提案或最终确认，
所以人工修订后的约束即使被模型改写、回填，或以精确前缀追加旧值，也只能进入待批状态。

`CorrectedProfileAuthorityRegressionTest` 使用同一输入和模型提案在原始基线的隔离源副本运行：
用户要求 README 引用旧文案而不改变约束，模型伪造 replacement 映射。旧实现将“约两小时完成”
覆盖成“一个晚上完成”，1 项回归断言失败。相同测试在当前实现验证人工内容及版本保持不变。
隔离副本位于忽略的 target 下，未改写原分支或无关文件。

## 人工保护、提案及事务

- PATCH 保护请求明确提供的每个分区，包括清空或无内容变化的保存；不推断其他分区。
- 最终确认保护全部六区，接受提案保护所有变化区。重开探索不解除保护。
- `ProfileUpdateProposal` 保存服务器 ID、画像和来源请求 ID、可选项目身份、基准版本和状态、
  变化区的当前/建议完整快照、原始输入、待接受的依据判断、处理状态及接受结果版本。
- 提案只有 PENDING、ACCEPTED、DISMISSED、SUPERSEDED；数据库唯一部分索引限制每画像一个 PENDING。
- 主产品入口将提案、已接受聊天轮、版本判断和请求完成结果在同一短事务提交。
  提取和规划调用都在事务外；结构或规划失败不留下部分提案或画像。
- 批准只读取服务器保存的快照，不接收客户端修改内容，不重新调用模型。
  Application 经既有领域整区方法形成候选，事务内重新核对身份、状态、版本和项目关联。
  提案结果、画像快照、Evidence、人工保护一起提交。失败全部回滚。
- 重复或并发重复批准返回记录的 ACCEPTED 结果版本，不再推进版本；DISMISSED、SUPERSEDED 不被重新激活。
- 忽略保留当前画像和 Evidence。人工编辑包括无内容变化 PATCH 都使待批提案失效；
  内容/生命周期变化触发事务内 supersession。旧批准不会覆盖新版本。
- 自动提交在取得写锁后重新检查保护，因此模型等待期间的无内容变化人工保存也能阻止迟到自动追加。

HTTP 入口：

```text
POST /api/user-profiles/{id}/profile-updates/{proposalId}/accept?projectId=...
POST /api/user-profiles/{id}/profile-updates/{proposalId}/dismiss?projectId=...
```

返回完整提案及真实处理状态、记录的 resultingRevision。`conversation.pendingProposal` 恢复当前待批快照；
完成轮响应的 `pendingProposal` 与请求查询的 `proposal` 返回对应来源提案的当前状态。
待批时新的 TURN / QUESTION 在调用模型前被拒绝，重复的原 Discovery 请求仍读取原记录。
旧 `/explore` 使用同一策略及提案存储，不再绕过批准；其待批内容也从 conversation 读取。

## Evidence 与对话规划

自动接受时提交对应完整更新的依据，待批整轮批准时才提交依据；忽略不新增或撤回依据。
新依据的 sourceRef 固定为真实本轮输入，confirmed 保持 false，不把接受分区等同于确认任意模型判断。
旧 Evidence、原始用户消息和版本快照保留；批准后的当前分区是现行事实。

仍为 Thinking Disabled 提取、Thinking Low 规划，保留原有已接受历史及请求预算。
规划的权威画像始终是实际接受的候选；待批只提供非事实标记，使用 PAUSE、禁止竞争下一问及首次审阅邀请。
服务器给出“尚未应用”的明确回应。上一问按真实输入处理，未解决时保持 ASKED，提案不进入问题或 deferred topic。
接受/忽略不自动规划，用户可继续回答原问、补充输入或显式索取下一问。

已移除 assistantReply / nextQuestion 的语义重合拒绝，保留避免重复的提示、输出边界、历史问题检查、
ANSWERED / DEFERRED / REPLACED 语义和每画像唯一 ASKED 的数据库约束。轻微语义重复为接受的非阻塞限制。

## V30 迁移与兼容

只新增 `V30__profile_update_approval.sql`，未修改已应用迁移。新增小型分区保护表、提案快照表、
唯一 PENDING 索引及内容/状态变化的失效触发器。旧数据缺少可靠修改人信息，迁移时保守保护所有已有画像
的六区，包括空区和曾被清空的区，不重构历史来源。代价是旧画像后续新增信息也可能需要一次批准。
迁移后新建画像没有预置保护，普通发现继续自动追加。

现有迁移用例保留原快照、Candidate、占用和来源完整性断言，仅同步 V30 增加后的迁移数量。
没有改变 Delivery HTTP、自动 Delivery、Coding Agent、隔离、Candidate 或执行语义。

## 前端与浏览器

沿用既有 Graphite + Indigo 设计。对话中展示受影响分区、当前值、建议值和“接受更新 / 忽略并继续 / 去画像审阅中编辑”。
待批暂停发送新轮次，允许保留输入草稿。服务器是批准状态来源，不在 localStorage 保存提案内容或批准事实。
刷新、项目恢复、未确定请求恢复均读取服务器状态。进入画像审阅只切换视图，探索中可编辑；
正式 REVIEWING 和最终 CONFIRMED 仍走原显式操作及充分性领域规则。

浏览器验证使用生产构建、隔离 Spring Boot/SQLite、仅回环 HTTP 的确定性 Provider，未触及用户数据库。
用于浏览器的隔离应用从相同基线与本轮改动构建，29 个改动后端文件与当前源码逐字节核对。
最终 1440×960 和 390×844 两种宽度完成 66 项交互/布局检查，包括真实鼠标命中、Tab 到忽略按钮、
多区建议恢复、批准与忽略后不调用模型、原 ASKED 保留、探索中编辑和过期批准返回 SUPERSEDED。
这些场景合计使用14次本地模拟 Provider 调用；批准、忽略、刷新与手工编辑本身均为0次模型调用。
另用同一隔离环境检查 768 宽度。三宽度检查0失败；1440对话与侧栏有一条独立面板高度不齐提示，
它们承载不同工作面内容，按保留既有布局的要求维持原设计。没有横向溢出或浏览器脚本错误。
窄屏最终使用并列的当前/建议值、紧凑间距和44px处理按钮，原问题与草稿入口仍可见。

| 场景 | 桌面 | 窄屏 |
| --- | --- | --- |
| 两区待批建议 | [1440](profile-update-approval-20261110/pending-1440.png) | [390](profile-update-approval-20261110/pending-390.png) |
| 刷新恢复相同建议 | [1440](profile-update-approval-20261110/restored-1440.png) | [390](profile-update-approval-20261110/restored-390.png) |
| 批准后进入查看/编辑视图，仍 EXPLORING | [1440](profile-update-approval-20261110/accepted-review-1440.png) | [390](profile-update-approval-20261110/accepted-review-390.png) |
| 手工编辑为六小时，旧建议失效 | [1440](profile-update-approval-20261110/manual-review-1440.png) | [390](profile-update-approval-20261110/manual-review-390.png) |

详细断言及本机 HTTP 请求记录见 [交互证据](profile-update-approval-20261110/interaction-evidence.json)。
截图均为实际生产前端与隔离后端；模型内容是确定性夹具，不是付费模型措辞验收。

## 本轮命令与结果

最终源码在 Windows / Java 21 上执行 Maven Wrapper 离线验证；前端使用项目声明的 pnpm 脚本。
完整结果只从该次 reactor 的四个模块汇总读取，未累计旧 surefire 文件或历史报告。

```powershell
.\mvnw.cmd -o verify

.\mvnw.cmd -o '-Dtest=ProfileUpdatePolicyTest,CorrectedProfileAuthorityRegressionTest,DiscoveryConversationIntegrationTest,ProfileUpdateApprovalApiTest,UserProfileDiscoveryTurnEndpointIntegrationTest,DeliveryMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' verify

# frontend 目录
pnpm test
pnpm typecheck
pnpm build
```

| 最终执行 | 结果 |
| --- | --- |
| 全量 reactor verify + 打包 | 2,025 项，1,970 实际执行，55 条件跳过，0失败、0错误 |
| 上述定向 verify + 打包 | 65项全部通过，0跳过 |
| 前端完整回归 | 23个文件、380项全部通过，0跳过 |
| 前端 typecheck / 生产 build | 均通过 |
| 浏览器两宽度交互 | 66项检查通过；另检查768宽度 |
| git diff --check | 通过 |

| 全量模块 | 总数 | 实际执行 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Domain | 209 | 209 | 0 | 0 | 0 |
| Application | 933 | 933 | 0 | 0 | 0 |
| Infrastructure | 651 | 599 | 0 | 0 | 52 |
| App | 232 | 229 | 0 | 0 | 3 |

定向65项包含分类策略3、原始F-001反证回归1、SQLite对话/批准集成38、旧库迁移2、
实际监听端口的批准HTTP2、原Discovery接口集成19。覆盖正常追加、转述、改写/重排、受保护区旧值回填及追加、
伪造替换映射、整轮暂存、批准/忽略/重复与并发批准、重启恢复、失败回滚、并发人工编辑、
REVIEWING/CONFIRMED/重开、旧库空分区保护、问题暂停与F-003非阻塞重复。
前端另覆盖项目状态加载、丢失响应恢复、待批时草稿和发送边界、探索中编辑及不自动调用AI。

首次受限环境的完整 verify 因 Git 无法创建 Windows 通信管道失败，另发现两处迁移数量断言需同步 V30；
这些失败没有作为通过证据。修正数量后，在正常本机环境重新完整执行并通过，未跳过失败用例或修改Git行为。
原始基线反证命令为相同的 Application 定向测试，在隔离副本执行：

```powershell
.\mvnw.cmd -o -pl backend/delveforge-application -am '-Dtest=CorrectedProfileAuthorityRegressionTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

旧基线预期为1项断言失败，当前实现同一项通过。两次结果分别记录，没有把旧失败当作当前失败或把新测试替身当作真实模型验证。

## 变更文件

- [backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/api/userprofile/DiscoveryTurnResponse.java](../../backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/api/userprofile/DiscoveryTurnResponse.java)
- [backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/api/userprofile/UserProfileController.java](../../backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/api/userprofile/UserProfileController.java)
- [backend/delveforge-app/src/test/java/com/ayywl/delveforge/app/api/userprofile/ProfileUpdateApprovalApiTest.java](../../backend/delveforge-app/src/test/java/com/ayywl/delveforge/app/api/userprofile/ProfileUpdateApprovalApiTest.java)
- [backend/delveforge-app/src/test/java/com/ayywl/delveforge/app/api/userprofile/UserProfileDiscoveryTurnEndpointIntegrationTest.java](../../backend/delveforge-app/src/test/java/com/ayywl/delveforge/app/api/userprofile/UserProfileDiscoveryTurnEndpointIntegrationTest.java)
- [backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/port/persistence/DiscoveryConversationRepository.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/port/persistence/DiscoveryConversationRepository.java)
- [backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/port/persistence/UserProfileRepository.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/port/persistence/UserProfileRepository.java)
- [backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/conversation/DiscoveryConversationService.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/conversation/DiscoveryConversationService.java)
- [backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/exploration/ExploreUserProfileUseCase.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/exploration/ExploreUserProfileUseCase.java)
- [backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/exploration/ProfileExtraction.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/exploration/ProfileExtraction.java)
- [backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/exploration/ProfileUpdatePolicy.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/exploration/ProfileUpdatePolicy.java)
- [backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/exploration/ProfileUpdateProposal.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/exploration/ProfileUpdateProposal.java)
- [backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/exploration/UserProfileProposalParser.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/exploration/UserProfileProposalParser.java)
- [backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/profile/UpdateUserProfileUseCase.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/profile/UpdateUserProfileUseCase.java)
- [backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/review/ConfirmUserProfileUseCase.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/review/ConfirmUserProfileUseCase.java)
- [backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/DiscoveryQuestions.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/DiscoveryQuestions.java)
- [backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/ProfileSufficiencyEvaluator.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/sufficiency/ProfileSufficiencyEvaluator.java)
- [backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/workflow/RunUserDiscoveryTurnUseCase.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/workflow/RunUserDiscoveryTurnUseCase.java)
- [backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/workflow/UserDiscoveryTurn.java](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/userdiscovery/workflow/UserDiscoveryTurn.java)
- [backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/userdiscovery/exploration/CorrectedProfileAuthorityRegressionTest.java](../../backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/userdiscovery/exploration/CorrectedProfileAuthorityRegressionTest.java)
- [backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/userdiscovery/exploration/ProfileUpdatePolicyTest.java](../../backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/userdiscovery/exploration/ProfileUpdatePolicyTest.java)
- [backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/userdiscovery/shared/InMemoryUserProfileRepository.java](../../backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/userdiscovery/shared/InMemoryUserProfileRepository.java)
- [backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/userdiscovery/workflow/ConversationalDiscoveryTest.java](../../backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/userdiscovery/workflow/ConversationalDiscoveryTest.java)
- [backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/userprofile/ProfileUpdateMapper.java](../../backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/userprofile/ProfileUpdateMapper.java)
- [backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/userprofile/SqliteDiscoveryConversationRepository.java](../../backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/userprofile/SqliteDiscoveryConversationRepository.java)
- [backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/userprofile/SqliteUserProfileRepository.java](../../backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/userprofile/SqliteUserProfileRepository.java)
- [backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/userprofile/UserProfileSectionItemMapper.java](../../backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/userprofile/UserProfileSectionItemMapper.java)
- [backend/delveforge-infrastructure/src/main/resources/db/migration/V30__profile_update_approval.sql](../../backend/delveforge-infrastructure/src/main/resources/db/migration/V30__profile_update_approval.sql)
- [backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/DeliveryMigrationTest.java](../../backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/DeliveryMigrationTest.java)
- [backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/persistence/userprofile/DiscoveryConversationIntegrationTest.java](../../backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/persistence/userprofile/DiscoveryConversationIntegrationTest.java)
- [docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md)
- [docs/DOMAIN_MODEL.md](../../docs/DOMAIN_MODEL.md)
- [docs/PRODUCT.md](../../docs/PRODUCT.md)
- [docs/ROADMAP.md](../../docs/ROADMAP.md)
- [docs/decisions/0019-durable-discovery-conversations.md](../../docs/decisions/0019-durable-discovery-conversations.md)
- [docs/validation/profile-update-approval-20261110.md](../../docs/validation/profile-update-approval-20261110.md)
- [docs/validation/profile-update-approval-20261110/accepted-review-1440.png](../../docs/validation/profile-update-approval-20261110/accepted-review-1440.png)
- [docs/validation/profile-update-approval-20261110/accepted-review-390.png](../../docs/validation/profile-update-approval-20261110/accepted-review-390.png)
- [docs/validation/profile-update-approval-20261110/interaction-evidence.json](../../docs/validation/profile-update-approval-20261110/interaction-evidence.json)
- [docs/validation/profile-update-approval-20261110/manual-review-1440.png](../../docs/validation/profile-update-approval-20261110/manual-review-1440.png)
- [docs/validation/profile-update-approval-20261110/manual-review-390.png](../../docs/validation/profile-update-approval-20261110/manual-review-390.png)
- [docs/validation/profile-update-approval-20261110/pending-1440.png](../../docs/validation/profile-update-approval-20261110/pending-1440.png)
- [docs/validation/profile-update-approval-20261110/pending-390.png](../../docs/validation/profile-update-approval-20261110/pending-390.png)
- [docs/validation/profile-update-approval-20261110/restored-1440.png](../../docs/validation/profile-update-approval-20261110/restored-1440.png)
- [docs/validation/profile-update-approval-20261110/restored-390.png](../../docs/validation/profile-update-approval-20261110/restored-390.png)
- [frontend/src/api/client.ts](../../frontend/src/api/client.ts)
- [frontend/src/api/types.ts](../../frontend/src/api/types.ts)
- [frontend/src/components/ProfileUpdateCard.vue](../../frontend/src/components/ProfileUpdateCard.vue)
- [frontend/src/state/session.ts](../../frontend/src/state/session.ts)
- [frontend/src/views/DiscoveryView.vue](../../frontend/src/views/DiscoveryView.vue)
- [frontend/src/workflows-v2.css](../../frontend/src/workflows-v2.css)
- [frontend/tests/discovery-messaging.test.ts](../../frontend/tests/discovery-messaging.test.ts)

## 交付与边界

一份聚焦本地提交；不合并 main、不 push。三个既有无关未跟踪目录保留。
独立 Sol 验收待另行执行；真实付费模型的措辞质量未在本轮测量。
