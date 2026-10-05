# M3 Retrospective — Evolution Planning & Working Copy

**Status:** Retrospective / **Not Source of Truth**

**Completed / Frozen:** 2026-10-05

**Validated code revision:** `912d50665cd17c5f5d703b542a1e4157675e6182`

**依据:** [PRODUCT.md](../PRODUCT.md)、[DOMAIN_MODEL.md](../DOMAIN_MODEL.md)、
[ARCHITECTURE.md](../ARCHITECTURE.md)、[ROADMAP.md](../ROADMAP.md)、[AGENTS.md](../../AGENTS.md)、
[ADR-0001](../decisions/0001-separate-workspace-read-and-mutation-capabilities.md) 与
[M3 focused smoke](../validation/m3-evolution-planning-working-copy-smoke.md)。

**连续性:** 延续 [M2 复盘](m2-product-direction.md) 的写法与 §H 交付节奏。

> 本文保留 M3 最终做成了什么、审查修正了什么、真实输出支持哪些结论，以及下一阶段
> 应保留和调整的做法。领域语义与架构规则仍由各自的权威文档定义。

---

## A. M3 的目标与最终结果

M3 将用户已选择的产品方向转化为增量计划，再准备与原资产隔离的演化环境。
两个相对完整的 Task 分别交付：

```text
Task 1 — Evolution Planning
Selected ProductDirection + Base SoftwareAsset + Base RepositoryProfile
        ↓
AI 提案 → 结构解析 → 引用还原 → 领域接受 → 事务保存
        ↓
EvolutionPlan（PROPOSED，未绑定 WorkingCopy）

Task 2 — Working Copy Provisioning & Plan Activation
EvolutionPlan（PROPOSED）
        ↓
基线 / 许可校验 → 独立 Git clone / checkout
        ↓
WorkingCopy（READY）→ 绑定 → 领域激活 → 原子提交
        ↓
EvolutionPlan（ACTIVE）
```

计划与步骤可持久化、经 API 回读，WorkingCopy 有真实托管位置与精确 source revision。
方向切换同时使旧方向的 PROPOSED / ACTIVE Plan 进入 SUPERSEDED，保留其内容、Evidence、
步骤及既有 WorkingCopy 绑定。最终步骤仍为 PENDING_CONFIRMATION，baselineRevision 为空。

里程碑审查问题、规划边界重构与回归验证已完成。最终代码的既有全量 `mvnw.cmd verify`
通过：1127 tests、0 failure / error / skipped。随后同一代码完成一次真实 Memos focused smoke。
后续提交补录保存的 Plan 与 Profile 输入；文档收尾没有重跑 Maven 或 Provider smoke。

## B. 最终实现的设计理解

### B.1 CurrentState、TargetState 与演化内容

CurrentState 是与本次规划相关的 RepositoryProfile 事实投影。capabilities / modules /
limitations 逐项保留 Profile 原文，Domain 校验成员关系；summary 是规划摘要，其自然语言
推理仍需审阅。它不是重新分析源码的结果，也不代表整个仓库已被完整理解。

TargetState 的 problem / targetProduct / differentiation 保留 Selected Direction 原文，
规划不能自行替换产品意图。reusableCapabilities 来自 Base Profile 的 capabilities 或
reusableAssets；changes 表达需要新增、替换或调整的主要能力。Gap 是推理过程，以复用能力
和 changes 表达，当前没有单独持久化的 Gap 对象。

EvolutionStep 应表达有范围、有前置条件、可独立检验的工程增量。正式定义保留 goal、scope、
plannedChanges、preconditions、verificationCriteria 与顺序；plannedChanges 和
verificationCriteria 非空，preconditions 显式出现，首步可以没有专属依赖。
这些检查能拒绝空定义，不能自动证明步骤粒度和职责划分合理。

VerificationCriteria 表达 **WHAT**：完成后应具备哪些可观察行为或满足哪些结果。
具体文件、类、编辑方式与验证命令属于后续执行阶段的 **HOW**。M3 只保存验收目标，
没有运行 Verification，也没有把字段存在当作验收成功。

### B.2 Planning 的信任链

```text
Selected Direction + Base RepositoryProfile 的既有语义内容
        ↓
AI Gateway 返回 raw String
        ↓
PlanningProposalParser → AiPlanningProposal（含调用内 Evidence 引用）
        ↓
PlanningProposalResolver → PlanningProposal（引用换回既有 EvidenceBasis）
        ↓
EvolutionPlanningService 校验规划基线、目标、事实来源与依据
        ↓
正式 EvolutionPlan / EvolutionStep
        ↓
SQLite 原子保存；读取直接还原正式领域内容
```

Resolver 不创造 Evidence 的来源、confidence 或 confirmed 值；还原引用后仍是候选。
Domain 决定接受，Plan / Step 身份由服务端生成，模型不能授予生命周期状态或执行权限。
Application 在外部调用前后核对可变资格，持久化写入再次检查方向仍被选择。
Planning 只使用已有 Direction / Profile，不额外运行 Repository Map / Scout 或读取源码。

### B.3 WorkingCopy revision 与能力边界

M3 创建独立对象存储的本地 clone，在 analyzedRevision 上 detached checkout，核对副本
HEAD 与干净状态，并在准备前后检查源 HEAD。源仓库未提交内容不进入副本；源状态偏离
规划基线时，应重新分析、重新规划，再准备。

初始 READY 状态满足：

```text
sourceRevision = analyzedRevision
currentRevision = sourceRevision
lastVerifiedRevision = sourceRevision
```

这里采用源 revision 作为初始可信基线，没有执行 Step Verification。后续软件状态演化、
Candidate State 与 currentRevision / lastVerifiedRevision 分离都未在 M3 实现。

Workspace 能力按真实调用需求分开：

| Port | M3 最终职责 |
|---|---|
| WorkspaceReadPort | Repository Analysis 的读取能力 |
| WorkingCopyProvisioningPort | 独立 clone / checkout，以及本次候选目录的补偿清理 |
| WorkspaceMutationPort | 保留给后续获授权 Step 的代码修改；M3 没有实现或使用 |

三个 Port 互不继承。GitWorkspaceAdapter 实现读取与准备，准备不是步骤授权。
这一细化已作为 ADR-0001 的 M3 extension 记录，无须另建同类 ADR。

### B.4 Policy、生命周期提交与补偿

PlanActivationPolicy 只判断跨 Aggregate 条件，不修改对象：Plan 必须仍为 PROPOSED，
方向仍为 SELECTED，Base Asset 是 Candidate Asset，Plan 引用的 Profile 属于该资产，
使用许可成立；激活还要求绑定的 WorkingCopy 为 READY，三个 revision 与规划基线一致。
实际 Git 状态和隔离由 Application 协调 Workspace 核对，状态变化由 Plan 自身操作完成。

EvolutionLifecycleCommitPort 是 Application 的跨 Aggregate 持久化提交边界，接受 Domain
已经形成的候选。它既用于 READY WorkingCopy 元数据与 ACTIVE Plan 绑定一起提交，也用于
方向切换与历史 Plan 失效一起提交。SQLite 用一个事务保证对应写入全部成功或全部回滚，
并检查生命周期及授权依据是否过期；它不替代 Domain 做产品决策。

Git / 文件系统不参与 SQLite 事务。准备先产生外部候选，Application 在隔离的 Plan 副本上
形成领域候选，最后提交权威状态。领域拒绝或数据库失败时尽力删除本次准备目录；provision
自身失败由 Adapter 清理。清理限定托管位置与本次所有权凭据，不采纳或删除其他目录。
清理失败保留原异常，并附加 suppressed exception；残留目录不是有效 WorkingCopy。
M3 没有引入持久化 CREATING、后台残留协调或跨数据库 / 文件系统事务。

**Plan ACTIVE != Step authorization。** ACTIVE 表示计划已经绑定可用环境；每个 Step
仍待明确用户确认，不能据此获得代码写能力。

## C. 审查发现与聚焦修正

### C.1 R1 发现方向，R2 作为后续规划基线

最初 PlanActivationPolicy 要求 Plan 的 Base Profile 属于 Direction 的历史 Profile 列表。
这使 R1 发现并选择方向后，同一资产的新 R2 可以生成合法 Plan，却在准备时被拒绝。
发现基线是历史事实，规划基线是本次演化的依据，两者不能混为一个成员资格条件。

修正移除历史列表限制，保留 Plan → Profile 身份、Profile → Asset 归属、Candidate Asset、
许可和 revision 校验。领域与真实 Git / SQLite / HTTP 回归覆盖 R2 规划并成功准备；
过期的 R1 仍不能在源 HEAD 前移后准备。Direction 的原始发现依据继续保留。

### C.2 Proposal 不能长期承担正式状态的角色

最初 Plan / Step 持有 Proposal，使同一种类型同时表达「未接受候选」和「正式领域内容」。
walkthrough 暴露了这处理解障碍。修正后 Proposal 只用于接受之前的输入，Aggregate / Entity
直接拥有正式字段，数据库读取直接还原正式对象。核心经验是先说清对象何时获得权威性，
再决定字段归属，不能只凭字段形状相同复用候选类型。

### C.3 Parser 与 Resolver 分离

原 Planning Parser 同时解析 raw String 并还原 Evidence 引用，偏离既有 Parser + Resolver
模式。修正后 Parser 负责结构，Resolver 负责闭集引用还原，Domain 负责接受。
这样能分别解释和验证「格式合法」「引用真实」「业务可接受」，避免解析成功看起来就等于接受。

### C.4 可读性与语言一致性

聚焦 cleanup 将控制流、明确类型和逻辑阶段整理为可读形式，解释性注释及 Prompt 自然语言
与项目中文约定一致，保留 schema 标识与领域术语。它服务于读懂当前链路；本轮冻结后不继续
扩大命名、抽象或组织方式的优化。

## D. 真实 smoke 的证明范围与观察

真实 DeepSeek 调用针对 Memos 的「增量归档守护进程与归档完整性校验」方向产出 Plan，
随后由正常 prepare API 创建真实 Git WorkingCopy。完整数据见验证记录，不在复盘重复输出。

本次实际证明：

- synthetic smoke UserProfile 经正常确认，与 RepositoryProfile 共同支持经 select API 显式选中的方向；
  实际 Profile 输入、Direction、接受的 Plan 和 WorkingCopy 构成可回查的因果链。
- planning 返回 201，prepare 返回 200；API 与只读 SQLite 回读一致：Plan ACTIVE、Copy READY、
  五个 Step 全部 PENDING_CONFIRMATION、baselineRevision 全为空、三个 revision 等于 analyzedRevision。
- CurrentState 的选中事实、六项复用能力、TargetState 三字段与已有输入匹配；21 条 EvidenceBasis
  全部可追溯到本次 Profile 或 Direction 的既有依据，没有创造新事实或确认状态。
- 五步计划依次组织完整归档、增量、完整性、双源支持与守护进程集成，范围和验收目标可供用户审阅。
- 副本 detached、干净、无 alternates，源 / 副本 object 文件物理身份集合无交集；源仓库的
  2807 个文件（含 .git）及 HEAD / status 在准备前后不变。

可用规划质量支持本次 PASS，但仍保留以下非阻塞观察：

- Step 1 涉及 store/API 来源选择，Step 4 再完成双源等价支持，有局部职责重叠；执行前应审阅首步范围。
- Evidence 21 条、16 条唯一值，五组各重复一次；值与 origin 都合法，追溯完整，不在此扩展去重设计。
- summary 中关于缺少周期 / 增量 / 校验的判断部分来自 Direction；Profile 承认材料覆盖不完整，
  不能把摘要当作整个仓库不存在相关实现的证明，执行前仍需核实相关源码。
- 单仓库、单方向、单次输出不能证明普遍规划质量。自然语言的工程判断仍需人参与。

smoke 没有对每个内部阶段插桩，也没有扩展失败矩阵；事务、补偿、授权拒绝和 R1→R2 路径
依靠已有确定性测试。保留的数据库与 API 产物是实际接受结果，未捕获的原始 Provider 响应
不作为主验证产物。文档补录恢复既有数据，没有重复调用 Provider。

## E. 过程复盘：保留节奏，提前澄清边界

M3 实际采用：

```text
设计讨论
    ↓
两个相对完整的实现 Task（规划；准备与激活）
    ↓
一次里程碑级主审查
    ↓
聚焦正确性修正与边界复核 / walkthrough
    ↓
一次真实 focused smoke
    ↓
补录权威输出 → 复盘 → 冻结
```

相比 M2 的许多小任务与反复审查 / smoke，这次围绕可运行的完整产品链路评审，审查能看到
规划成功却无法准备的跨阶段问题；真实 smoke 放在修正后，集中验证 Provider 质量与 Git 环境。
同一次运行还支撑后续输出补录，避免因文档缺项重复调用模型。这里是过程观察，没有耗时统计，
不据此宣称量化效率提升。

应保留：先讨论 What / Why / Invariants，按完整垂直切片交付，再做里程碑审查、聚焦 smoke
与复盘；验证重要失败边界，主链路通过后接受非阻塞质量观察，并明确停止条件。

应调整：实现前用一条信任链明确每种类型的权威性、Parser / Resolver / Domain 的职责，
并同时检查历史发现基线与当前规划基线。walkthrough 应帮助理解已明确的设计，减少事后才
发现边界混合的返工。首次验证记录就应保存输入身份、实际接受输出、请求结果和追溯实例，
使后人能检查结论；本次需要两轮补录 Profile / Plan 才完整呈现因果链，是记录方式的改进点。

## F. 冻结与 M4 交接边界

**M3 STATUS: FROZEN。** 验收已满足，审查问题已解决，focused real smoke PASS，权威文档
与最终实现一致。ARCHITECTURE / DOMAIN_MODEL / ADR-0001 已包含所需边界，本次无需重写
或新增 ADR。冻结后不追加 M3 优化或 cleanup，除非发现具体阻塞 M4 的正确性问题。

交接起点是 ACTIVE Plan + READY WorkingCopy + PENDING_CONFIRMATION Steps，尚无步骤授权。
M4 仍为 TODO，本轮没有启动；以下是留待下一阶段的责任范围，不是新增实现设计：

| 留待 M4 的事项 | 交接边界 |
|---|---|
| Step confirmation | 用户明确确认具体 Step，建立其执行授权 |
| Step-specific Repository Understanding | 执行前围绕当前 Step 核实相关代码与规划假设 |
| WorkspaceMutationPort implementation / use | 评估并落实与已授权 Step / WorkingCopy 对应的受控写能力 |
| code mutation | 只在该 Plan 绑定的 WorkingCopy、当前 Step scope 内修改 |
| baselineRevision | 在首次执行前记录 Step 起始软件状态 |
| Candidate State | 修改完成后形成尚未通过 Verification 的候选状态 |
| Verification execution | 执行验证并记录结果，满足标准后才形成成功状态 |
| currentRevision / lastVerifiedRevision divergence | 区分当前代码状态与最后可信状态，按验证结果推进 |
| failure / rollback / recovery | 处理 M4 成功路径所需的失败安全语义；完整 Repair / Retry / Recovery 仍属 M5 |
