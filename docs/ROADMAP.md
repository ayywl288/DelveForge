# ROADMAP.md

> 本文档回答：DelveForge 准备按照什么顺序发展，以及当前阶段最重要的开发目标是什么。

**Status:** Active  
**Last Updated:** 2026-10-12

---

## 1. Current Goal

当前最重要目标：

> M0–M4 核心闭环与 Task D–F 后续 correctness 修复已完成。Task G UI / UX Polish 已完成、由用户接受并冻结；本地 main 已整合 G1/G2。后端业务开发继续停止，M5 暂缓。

业务阶段关闭、两次历史 Verified Evolution、Pre-UI 时点 1474 / 85 项确定性回归及尚未重跑的付费 E2E
见 [Pre-UI Closeout](validation/pre-ui-final-closeout.md)。
Task G 当前发行版 UI 的最终结果与 123 项前端回归见 [最终关闭记录](validation/task-g-final-frontend-closeout.md)。
UI 接受不代表最新代码的完整付费 E2E、部署或发布验收；M6 仍为 TODO，不自动开始后续工作。
实施前的技术结构与冻结边界保留在 [历史 UI 交接](UI_TASK_G_HANDOFF.md)；
干净数据库切换由用户按 [本地数据环境说明](LOCAL_DATA_ENVIRONMENTS.md) 决定，不自动清理旧库或副本。

以下保留第一阶段的原始闭环目标和验收流程，不代表继续扩展后端功能：

当前阶段不追求：

```text
大量 Software Asset 来源
+
复杂 UI
+
完整 Coding Agent 能力
+
高度自动化
```

而优先保证以下链路真实可运行：

```text
Explore User
    ↓
Confirm User Profile
    ↓
Analyze Local Repository
    ↓
Generate Product Directions
    ↓
Select Product Direction
    ↓
Generate Evolution Plan
    ↓
Prepare Working Copy
    ↓
Activate Evolution Plan
    ↓
Confirm Evolution Step
    ↓
Execute Evolution Step
    ↓
Verify Evolution Step
    ↓
Verified Software State
```

只有这条链路能够稳定完成，才认为 DelveForge 的第一阶段产品假设具备进一步验证价值。

---

## 2. Development Principles

### 2.1 Complete the Loop Before Expanding It

优先完成一条真实可运行的 MVP 闭环，再增加更多资产来源、更多 Agent 能力或复杂交互方式。

不得为了单个模块的完整性长期推迟 End-to-End Flow。

### 2.2 Domain Rules Before Implementation Convenience

`DOMAIN_MODEL.md` 中已经确定的领域语义、状态转换和 Invariant 是实现约束。

不得为了降低代码实现难度而绕过：

```text
User Confirmation
Working Copy Isolation
Verification
Rollback / Recovery
Traceability
```

等核心领域边界。

### 2.3 Evolution Before Rewrite

优先利用 Software Asset 已有能力形成增量演化路径。

MVP 不以“从零生成一个完整项目”作为目标。

### 2.4 AI Proposes, Domain Decides

LLM / AI Gateway 可以产生：

```text
Profile Analysis
Repository Analysis
Direction Proposal
Planning Proposal
Code Change Proposal
```

但 AI 输出不能直接成为合法领域状态。

所有关键结果必须经过系统领域规则校验和结构化处理。

### 2.5 Original Asset Must Remain Safe

Repository Analysis 原则上只读。

所有 Evolution Execution 必须发生在对应 Working Copy 中，不得直接修改原始 Software Asset。

### 2.6 Human Controls Code Evolution

Product Direction 的最终选择属于用户。

每个 Evolution Step 进入代码修改前都必须获得用户明确确认。

一次确认不得隐式授权后续 Step、Repair 或 Retry。

### 2.7 Every Evolution Step Must Be Verifiable

代码成功写入不代表 Evolution Step 成功。

每个 Evolution Step 都必须具有明确的：

```text
Goal
Scope
Planned Changes
Verification Criteria
```

并在执行完成后进入 Verification。

### 2.8 Do Not Optimize Before Evidence

MVP 优先：

```text
Correctness
Safety
Traceability
End-to-End Completion
```

性能优化、复杂并发、分布式拆分等工作只有在实际问题出现后才进入 Roadmap。

---

## 3. Milestones

### M0 — Project Foundation

**Goal**

建立 DelveForge 可以持续开发的最小工程基础，并解决阻塞 MVP 开发的关键实现决策。

**Deliverables**

- [x] 建立可构建、可运行的项目骨架。
- [x] 根据 `ARCHITECTURE.md` 建立主要模块边界。
- [x] 建立 Application / Use Case Orchestration 基础结构。
- [x] 建立 AI Gateway 抽象边界。
- [x] 建立 Workspace Gateway 抽象边界。
- [x] 建立基础 Persistence 能力。
- [x] 建立统一配置、错误处理与日志基础设施。
- [x] 建立自动化测试基础设施。
- [x] 确定 MVP 阶段 Java / Python 的职责边界。
- [x] 确定 JDK 基线：Java 21。
- [x] 确定 Build Tool：Maven + Maven Wrapper。
- [x] 确定 Java Root Package：`com.ayywl.delveforge`。
- [x] 确定代码仓库采用 Monorepo。
- [x] 确定 Backend 采用小规模 Maven Multi-Module。
- [x] 确定 Backend 初始 Module：Domain / Application / Infrastructure / App。
- [x] 确定代码组织方式：Architectural Layer Boundary + Feature-first Package Organization。
- [x] 确定 MVP 第一版用户交互方式：localhost Web UI + Local Java Backend。
- [x] 确定 Frontend 技术栈：Vue 3 + TypeScript + Vite。
- [x] 确定最终产品目标形态：Local-first Desktop Application。
- [x] 将 Desktop Shell Technology 延后至 Core MVP 稳定后决定。
- [x] 确定 MVP Persistence 技术方案：SQLite + MyBatis-Plus + Flyway。
- [x] 确定 MVP Working Copy 默认技术方案：Independent Local Git Clone。
- [x] 确定 Initial LLM Provider：DeepSeek；默认 model id 为 `deepseek-flash`。
- [x] 对需要长期保留的重要架构决策建立 ADR。

**Acceptance Criteria**

- [x] 项目能够在全新开发环境中成功构建。
- [x] 应用能够正常启动。
- [x] 自动化测试框架可以运行。
- [x] Maven Module 依赖关系符合既定架构边界。
- [x] Domain Module 不依赖 Application、Infrastructure 或 App。
- [x] Application Module 不依赖 Infrastructure 或 App。
- [x] 业务模块不直接依赖具体 LLM Provider SDK。
- [x] 业务模块不直接执行 Git / Shell / Filesystem 操作。
- [x] Frontend 与 Backend 可以在本地开发环境中独立启动并完成基本通信。
- [x] 新开发者或 Coding Agent 可以依据现有文档理解基本模块边界和构建方式。

**Out of Scope**

- 完整 User Discovery。
- Repository 深度分析。
- Product Direction 生成。
- 实际代码修改。
- 完整产品 UI。
- Desktop Shell、安装包与桌面端发布能力。

---

### M1 — Discovery Inputs

**Goal**

形成 Product Discovery 所需要的两个可信输入：

```text
Confirmed User Profile
+
Repository Profile
```

并使系统能够判断当前 User Discovery 是否已经收集到足够支持 Product Direction Discovery 的信息。

**Deliverables**

#### User Discovery

- [x] 支持创建和持续更新 User Profile。
- [x] 支持通过用户交互收集兴趣、行为、痛点、技术能力、项目目标和约束。
- [x] 支持 User Profile revision。
- [x] 支持 Profile Sufficiency Assessment，判断当前用户信息是否已经足以支持具有个人相关性的 Product Direction Discovery。
- [x] 当信息不足时，能够识别重要缺失信息并继续探索。
- [x] 当信息足够且用户显式请求审阅时，使 User Profile 从 `EXPLORING` 进入 `REVIEWING`。
- [x] 支持用户 Review / Correct / Confirm Profile。
- [x] 保存关键 Profile 判断对应的 Evidence。

#### Repository Analysis

- [x] 支持用户指定本地 Git Repository。
- [x] 注册对应 Software Asset。
- [x] 确定 Repository 当前 analyzedRevision。
- [x] 通过 Workspace 只读访问 Repository。
- [x] 生成结构化 Repository Profile。
- [x] 提取 purpose、techStack、modules、capabilities、reusableAssets、limitations 和 risks。
- [x] 保存关键分析结果对应的 Evidence。
- [x] 保证 Repository Analysis 不修改源 Repository。

**Acceptance Criteria**

```text
Given

用户开始一次 User Discovery，
并选择至少一个可访问的本地 Git Repository。

When

系统持续分析当前 User Profile，

如果信息不足：
继续识别缺失信息并与用户交互；

如果信息已经足够：
停止继续无目的探索，
形成可供用户检查的 User Profile，
用户可继续补充，或显式请求进入 REVIEWING。

同时系统完成 Repository Analysis。

用户检查、修正并最终确认当前 User Profile。

Then

系统能够得到：

Confirmed User Profile @ Revision
+
Repository Profile @ analyzedRevision

并且两个结果都具有可追溯 Evidence，
可以作为 Product Direction Discovery 的可信输入。
```

同时：

- [x] Profile Sufficiency 不依赖固定对话轮数，而取决于当前信息是否足以支持具有个人相关性的项目方向发现。
- [x] 信息不足时系统不会过早结束 User Discovery。
- [x] 信息足够后系统不会为了增加对话轮数继续进行无明显价值的探索。
- [x] User Profile 必须经过用户确认后才能作为 Product Direction Discovery 的正式输入。
- [x] User Profile 后续修改不会覆盖历史上已经被引用的 revision 语义。
- [x] Repository Profile 可以追溯到确定的 Software Asset 和 analyzedRevision。
- [x] Repository Analysis 全程不产生源代码修改。

**Out of Scope**

- 为 Profile Sufficiency 建立复杂评分模型。
- 自动搜索 GitHub Repository。
- Product Direction 选择。
- Working Copy。
- Coding Execution。

---

### M2 — Product Direction Discovery

**Goal**

基于一个由调用方显式给出的 Product Discovery 输入基线——Confirmed User Profile 身份、确定的 `expectedRevision`，以及一个或多个 Repository Profile 身份——发现具有个人相关性和可实施性的候选 Product Direction。Application 在调用 AI 之前校验该基线；系统不自行判断「什么时候应该开始推荐」，也不自行决定基线由哪些对象组成。

**Deliverables**

- [x] 建立 Product Direction Discovery Use Case。
- [x] 由调用方显式提供完整的 Product Discovery 输入基线：Confirmed User Profile 身份、确定的 `expectedRevision`、一个或多个 Repository Profile 身份。
- [x] Application 在调用 AI 之前校验该基线，不成立时不调用模型、也不写入任何方向。
- [x] AI Gateway 能够产生结构化 Direction Proposal。
- [x] ProductDirectionDiscoveryService 对 Proposal 进行领域校验和转换。
- [x] 每次生成 3–5 个具有明显差异的 Product Direction。
- [x] 每个 Direction 说明：
  - [x] Problem
  - [x] Target Product
  - [x] User Fit
  - [x] Candidate Software Asset
  - [x] Differentiation
  - [x] Technical Value
  - [x] Estimated Complexity
  - [x] Risks
  - [x] Evidence
- [x] 支持用户查看候选 Direction。
- [x] 支持用户明确 Select / Reject Direction。
- [x] 保留 Product Direction 对 User Profile revision 和 Repository Profile 的追溯。

**Acceptance Criteria**

```text
Given

调用方给出完整的 Product Discovery 输入基线：

Confirmed User Profile（明确的 UserProfileId）
+
确定的 expectedRevision
+
一个或多个明确的 Repository Profile 身份

When

系统在调用 AI 之前校验该基线：

请求形状合法
Repository Profile 至少一个
User Profile 存在、revision 与 expectedRevision 一致、状态为 CONFIRMED
每个 Repository Profile 都存在

Then

如果基线成立：

系统执行 Product Direction Discovery，
生成 3–5 个具有明显差异的候选方向，
且每个方向能够解释：

为什么适合当前用户
+
利用了哪些已有软件能力
+
与原项目有什么差异
+
大致需要付出什么成本和风险

随后整批持久化为 CANDIDATE 并向用户展示，
由用户决定选择、拒绝或继续考虑。

如果基线不成立：

系统拒绝执行，且在写入任何方向之前失败——
既不先付出一次模型调用的代价，也不留下半批结果。
```

并且：

- [x] Product Direction Discovery 不得在 User Profile 尚未 `CONFIRMED` 时开始。
- [x] Product Direction Discovery 至少需要一个明确的 Repository Profile（INV-D05）。
- [x] 所有新 Direction 初始状态均为 `CANDIDATE`。
- [x] 系统可以生成和推荐 Product Direction，但不能自动将某个 Direction 标记为 `SELECTED`。
- [x] 只有用户明确操作才能完成 `CANDIDATE → SELECTED`。
- [x] 关键推荐理由具有可追溯 Evidence。

**Out of Scope**

- Evolution Plan。
- Working Copy。
- 自动选择 Product Direction。
- 自动修改代码。
- 后端自动触发 Product Direction Discovery（DEFERRED / REVISIT，见下）。

### Deferred — 后端自动触发 Product Direction Discovery

当前 MVP 中，Product Direction Discovery 是一个显式的 Application 操作：

```text
调用方选择基线
        ↓
Application 校验基线
        ↓
Domain 校验结果 Proposal
```

显式发起不等于弱校验：调用方给出完整基线，Application 仍在调用 AI 之前逐项校验该基线
（User Profile 存在、revision 匹配、已 `CONFIRMED`，且每个 Repository Profile 都存在），
失败时不调用模型、也不写入任何方向。

状态：

```text
DEFERRED / REVISIT
```

原因：

自动触发需要为 Product Discovery 输入基线建立一个持久的、明确的语义定义，
包括当前 UserProfile 的选择、RepositoryProfile 的参与集合、同一基线的重复消费语义，
以及失败 / 重复 / 重启后的行为。当前 MVP 没有足够的产品证据来支撑引入这部分额外的工作流状态。

重新评估条件（REVISIT）：

当出现真实的产品流程，需要系统自行确定并**持久化**以下内容时，再重新考虑：

- 哪一份 UserProfile 是当前的发现对象
- 哪些 RepositoryProfile 属于当前发现基线
- 该基线是否已经产生过一次 Discovery
- 失败 / 重复 / 重启后的自动尝试如何处理

本文件不规定将来的实现方式；自动触发是否需要新的领域概念，属于届时按
`DOMAIN_MODEL.md` §14.12 的判断规则重新决定的问题。

---

### M3 — Evolution Planning & Working Copy

**Goal**

把用户已经选择的 Product Direction 转化为可执行的增量演化计划，并准备安全的代码演化环境。

**Status:** DONE — 2026-10-05。完成记录与 Lessons Learned 见 §9。

**Deliverables**

#### Evolution Planning

- [x] 支持确定 Base Software Asset。
- [x] 实现最小 AssetUsagePolicy。
- [x] 生成 Current State。
- [x] 生成 Target State。
- [x] 分析二者之间的 Gap（组织为复用能力与主要变化）。
- [x] 识别 Reusable Capabilities。
- [x] 识别 Required Changes。
- [x] 生成按顺序组织的 Evolution Steps。
- [x] 每个 Step 具有：
  - [x] Goal
  - [x] Scope
  - [x] Planned Changes
  - [x] Verification Criteria

#### Working Copy

- [x] 根据 Base Software Asset 创建隔离 Working Copy。
- [x] 保证 sourceRevision 与 Base Repository Profile 的 analyzedRevision 一致。
- [x] 初始化 currentRevision。
- [x] 初始化 lastVerifiedRevision。
- [x] 将 Working Copy 与 Evolution Plan 建立绑定。
- [x] 实现 PlanActivationPolicy。
- [x] 支持 Evolution Plan 从 `PROPOSED → ACTIVE`。

**Acceptance Criteria**

```text
Given

一个 Selected Product Direction
+
合法 Base Software Asset
+
对应 Repository Profile

When

系统生成 Evolution Plan，
并准备实际演化环境。

Then

系统能够得到：

Evolution Plan
status = ACTIVE

+
Working Copy
status = READY

+
One or More Evolution Steps
status = PENDING_CONFIRMATION
```

并且：

- [x] Working Copy 与原 Software Asset 相互隔离。
- [x] Working Copy 的 sourceRevision 可以稳定追溯。
- [x] `sourceRevision == analyzedRevision`。
- [x] 创建 Working Copy 不得修改原 Software Asset。
- [x] Plan ACTIVE 不代表其中所有 Step 已获得执行授权。

**Out of Scope**

- 自动执行所有 Evolution Steps。
- 无人值守长期 Coding Agent。
- 复杂 Recovery Strategy。

---

### M4 — First Verified Evolution Step

**Status:** DONE / FROZEN（2026-10-10）— Task 1 / Task 2、独立审查、真实受控 Verified B、复盘与交付整合已完成。证据见 §9 M4 完成记录。

**Goal**

第一次真正跑通：

```text
User Confirmation
        ↓
Code Change
        ↓
Verification
        ↓
Verified Software State
```

从而完成 DelveForge MVP 最关键的代码演化能力。

**Deliverables**

- [x] 支持用户确认一个 Evolution Step。
- [x] 实现 `PENDING_CONFIRMATION → READY`。
- [x] 实现 StepExecutionPolicy（确认、准备与执行资格；生命周期、前序步骤与可信副本守卫）。
- [x] Step 首次执行前记录 baselineRevision。
- [x] AI / Coding Capability 根据 Step Scope 产生代码修改方案（调用内理解、有限上下文扩展与确定性提案校验；不应用补丁）。
- [x] Workspace 在 Working Copy 中应用修改。
- [x] 形成 Execution Result。
- [x] 记录 affectedPaths / changes / warnings / errors。
- [x] Execution 完成后形成 Candidate State。
- [x] 支持运行 Build / Test / Diff 等 Verification。
- [x] 形成 Verification Result。
- [x] Verification Passed 后将 Step 标记为 `SUCCEEDED`。
- [x] 成功后更新 Working Copy.lastVerifiedRevision。
- [x] 保证后续 Step 只能建立在 Verified Revision 上。

**Acceptance Criteria**

```text
Given

Evolution Plan = ACTIVE

Evolution Step = PENDING_CONFIRMATION

Working Copy = READY / EVOLVING

When

用户明确确认当前 Evolution Step，
系统完成代码修改并执行预定义 Verification。

Then

至少一个真实代码变更：

1. 只发生在 Working Copy；
2. 产生 Execution Result；
3. 经过 Build / Test / Diff 或其他明确验证；
4. 满足 Verification Criteria；
5. Evolution Step 最终进入 SUCCEEDED；
6. Working Copy.lastVerifiedRevision 推进到新的软件状态。
```

并且：

- [x] 未经用户确认不得修改代码。
- [x] Execution Complete 不等同于 Step Success。
- [x] Verification Passed 才允许推进 lastVerifiedRevision。
- [x] 原 Software Asset 始终未被 Evolution 修改。

**Out of Scope**

M4 = 成功执行 / 验证路径 + fail-closed 安全。

Task 2 当前自动验证仅支持 DOMAIN_MODEL §8.11 明确定义的技术标准；没有可信对应证据的
业务 Criteria 拒绝执行，不能用 BUILD / DIFF_CHECK / 无关 TEST 的索引覆盖推进软件信任。
M3 → M4 契约修正采用 kind + description 的 WHAT，动作与测试 target 留到 M4 决定；
不从自然语言猜测类别，不将既有业务标准降低为“测试通过”。历史文本保留为 UNSUPPORTED。

M5 = 完整 Failure / Repair / Retry / Rollback / Recovery 流程。

`DOMAIN_MODEL.md` 定义完整 MVP 目标失败语义，本 Roadmap 定义分阶段实现顺序。
M4 在 `EXECUTING` / `VERIFYING` 后发生异常时，可以暂留最后完整持久化检查点；
不得产生虚假 Candidate、推进 `lastVerifiedRevision` 或标记 Step `SUCCEEDED`，
必须阻止不一致或未解决状态上的后续 Evolution，且不得声称已完成 Rollback / Recovery。

---

### M5 — Failure Safety & Recovery

**Status:** DEFERRED（2026-10-10）— 本轮后端交付结束后暂缓，不开始实现。

**Goal**

补齐 Evolution Execution 的主要异常路径，使一次失败的代码修改不会破坏后续演化的可信基础。

**Deliverables**

#### Execution Failure

- [ ] 保存 Execution Failure 的诊断信息。
- [ ] 保存必要 Diff / Execution Result。
- [ ] 自动进入 Rollback。
- [ ] Rollback 成功后恢复 baselineRevision。
- [ ] Step 进入 `FAILED`。
- [ ] 支持用户明确 Retry。

#### Verification Failure

- [ ] Verification Failure 后进入 `VERIFICATION_FAILED`。
- [ ] 保留 Candidate State。
- [ ] 不更新 lastVerifiedRevision。
- [ ] 阻止后续 Evolution Step 执行。
- [ ] 支持用户明确选择 Repair。
- [ ] Repair 后重新 Verification。
- [ ] 支持用户明确选择 Rollback。
- [ ] Rollback 成功后进入 `FAILED`。

#### Recovery

- [ ] Rollback Failure 后进入 `RECOVERY_REQUIRED`。
- [ ] 阻止新的 Execution / Repair / Retry。
- [ ] 支持尝试恢复 baselineRevision。
- [ ] Recovery Success 后恢复可重试状态。
- [ ] 无法精确恢复 baselineRevision 时标记 Working Copy 为 `UNRECOVERABLE`。
- [ ] UNRECOVERABLE 时禁止当前 Evolution Plan 继续执行。

#### Audit / Traceability

- [ ] 保存重要用户授权事实。
- [ ] 保存重要 Step Execution / Verification 事件。
- [ ] 保存 Rollback / Recovery 结果。
- [ ] 重要历史事件不得被后续状态变化覆盖。

**Acceptance Criteria**

至少通过自动化测试或可重复场景证明以下路径成立：

```text
Execution Failure
→ Rollback
→ FAILED
```

```text
Verification Failure
→ Candidate State retained
→ Repair
→ Verify Again
```

```text
Verification Failure
→ Rollback
→ FAILED
```

以及：

```text
Rollback Failure
→ RECOVERY_REQUIRED
→ Recovery / UNRECOVERABLE
```

并保证：

- [ ] 未验证 Candidate State 永远不会成为后续 Step 的执行基线。
- [ ] `lastVerifiedRevision` 只有 Verification Success 才能推进。
- [ ] FAILED 后 Retry 需要新的用户确认。
- [ ] VERIFICATION_FAILED 后 Repair 需要新的用户确认。
- [ ] Recovery 失败不会被系统静默忽略。

---

### M6 — MVP End-to-End Validation

**Goal**

把此前 Milestone 形成的能力连接成稳定、可演示、可实际使用的 DelveForge MVP，并验证最初的产品假设。

**Deliverables**

- [ ] 跑通完整用户主流程。
- [ ] 补齐核心 Application Use Case 之间的连接。
- [ ] 检查关键领域状态的持久化与恢复。
- [ ] 检查历史 User Profile revision 可追溯。
- [ ] 检查 Repository Profile snapshot 可追溯。
- [ ] 检查 Product Direction Evidence。
- [ ] 检查 Evolution Plan Planning Basis。
- [ ] 检查 Step Confirmation Audit。
- [ ] 检查 Execution / Verification 结果。
- [ ] 增加必要集成测试。
- [ ] 增加至少一个真实 Repository 的 End-to-End Test / Demo Scenario。
- [ ] 修复 MVP 流程中暴露出的明显可靠性问题。
- [ ] 根据实现结果同步 PRODUCT / ARCHITECTURE / DOMAIN_MODEL / ROADMAP。
- [ ] 对产品假设进行第一轮实际验证和记录。

**Acceptance Criteria**

完整场景必须可以重复完成：

```text
User
 ↓
Explore User
 ↓
Confirm User Profile
 ↓
Select Local Repository
 ↓
Analyze Repository
 ↓
Generate 3–5 Product Directions
 ↓
User Selects Direction
 ↓
Generate Evolution Plan
 ↓
Prepare Working Copy
 ↓
Activate Plan
 ↓
User Confirms Evolution Step
 ↓
Execute Real Code Change
 ↓
Verification
 ↓
Step SUCCEEDED
```

并满足：

- [ ] Product Direction 与用户真实信息有关，而不是泛化项目推荐。
- [ ] Product Direction 能够利用已有 Software Asset。
- [ ] Evolution Plan 能清楚解释 Current State → Target State。
- [ ] 至少一个 Evolution Step 完成真实代码修改。
- [ ] 至少一个 Evolution Step 成功通过 Verification。
- [ ] 原 Software Asset 未被修改。
- [ ] 整条链路关键决策均能够追溯其依据。
- [ ] 应用重启后仍能够恢复必要业务状态。
- [ ] 核心异常路径不会让系统继续基于不可信代码执行。

完成该 Milestone 后：

> DelveForge MVP 可以视为完成第一版产品闭环。

---

## 4. Milestone Overview

| Milestone | Goal | Status |
|---|---|---|
| M0 | Project Foundation | DONE |
| M1 | Discovery Inputs | DONE |
| M2 | Product Direction Discovery | DONE |
| M3 | Evolution Planning & Working Copy | DONE |
| M4 | First Verified Evolution Step | DONE / FROZEN |
| M5 | Failure Safety & Recovery | DEFERRED |
| M6 | MVP End-to-End Validation | TODO |

统一状态：

```text
TODO
IN_PROGRESS
BLOCKED
DONE
DEFERRED
```

`FROZEN` 是已完成里程碑的开发冻结标记；`DEFERRED` 表示明确暂缓，不表示功能已经实现。

状态变化原则：

```text
TODO
  ↓
IN_PROGRESS
  ↓
DONE
```

如果存在明确外部阻塞：

```text
IN_PROGRESS
    ↓
BLOCKED
    ↓
IN_PROGRESS
```

Milestone 只有满足其 Acceptance Criteria 后才允许进入 `DONE`。

---

## 5. Current Sprint / Iteration

当前处于：

```text
M3 — Evolution Planning & Working Copy：DONE / FROZEN（2026-10-05）
M4 — First Verified Evolution Step：DONE / FROZEN（2026-10-10）
M5 — Failure Safety & Recovery：DEFERRED
Task D–F / F-Fix / 软删除 / Final Planning Fix：DONE
Task G — UI / UX Polish：DONE / ACCEPTED / FROZEN（2026-10-12，当前发行版 UI）
后端业务开发停止；不追加 M4 优化或开始 M5。
真实 Provider E2E、部署与发布接受尚未完成；不自动启动。
```

M0、M1、M2 与 M3 的完成记录已归档到 §9 Completed Milestones，不再在此处维护执行清单。

### Current

**Task G 已关闭并冻结（2026-10-12）。** Graphite + Indigo、项目工作台和五工作流页面已由用户接受，
少量视觉不足有意延后。本地 main 快进整合 G1/G2；最终前端 123 项测试、typecheck / build PASS。
见 [Task G 最终关闭](validation/task-g-final-frontend-closeout.md)。该接受仅针对 UI，不改变 M1–M4 业务边界，
不启动新 UI 任务、M5、付费 E2E 或部署。

**M3 已完成并冻结（2026-10-05）。** Task 1 / Task 2、里程碑审查、聚焦修正与真实 Memos
smoke 均已完成：Selected Direction + Base Asset + Base Profile → PROPOSED Plan →
隔离 WorkingCopy → ACTIVE Plan / READY WorkingCopy / PENDING_CONFIRMATION Steps。
R1 发现方向、同一资产 R2 重新规划并准备的路径已修复；Parser / Resolver / Domain 接受
以及候选 Proposal / 正式 Aggregate 的职责已分离。

最终代码 revision `912d506` 的既有全量 `mvnw.cmd verify` 通过，1127 项测试、
0 failure / error / skipped。真实 Provider smoke 验证同一代码 revision；后续仅补录
持久化输出与完成文档，没有重跑 smoke。

实际输入、接受的 Plan、准备结果与 Git 隔离证据见
[M3 focused smoke](validation/m3-evolution-planning-working-copy-smoke.md)；
设计修正、过程经验与下一阶段交接见
[M3 retrospective](retrospectives/m3-evolution-planning-working-copy.md)。
M3 不再追加优化或 cleanup，除非发现具体阻塞 M4 的正确性问题。

### Delivery Closeout

M4 已完成一次真实直接 Provider → CodingProposal → WorkingCopy mutation → Candidate B →
BUILD / TEST / DIFF_CHECK → SUCCEEDED / lastVerifiedRevision=B。成功执行器为
`9efee445`；其维护修复已通过保留历史的 merge 整合，Candidate B 的业务改动没有合入 main。
完整业务输入、Plan、代码 diff 和排障过程见 [M4 真实案例](validation/m4-real-provider-verified-evolution-smoke.md)。

当前仅支持受信任、授权、根 Maven Wrapper 离线可运行的小型 Java/Maven Git 仓库及有限技术
Verification；未知依赖、无法证明的测试选择器和未支持的业务 Criteria 保守拒绝。显式本地
`m4-local` profile 复用成功 SSE / timeout / token / response 和 Verification 预算，所有 AI 阶段
共用其预算；默认非流式配置没有同等真实成功证明，详见 [本地使用](M4_LOCAL_USAGE.md)。

完整失败恢复仍属于暂缓的 M5。M4 异常保留 EXECUTING / VERIFYING 最后完整检查点，
阻止未解决状态上的继续执行，不模拟成功、重试或回滚。Windows 宿主执行不是 OS 沙箱。
历史 BLOCKED 与 UNKNOWN 继续保留，整合、最终验证和归档见
[交付关闭记录](validation/m4-delivery-closeout.md)。后端功能开发到此冻结。

---

## 6. Definition of Done

任何 Feature / Task 被标记为完成前，至少必须满足：

- [ ] 需求已经实现，而不是仅存在接口或 TODO。
- [ ] 相关领域规则与 Invariant 未被破坏。
- [ ] 对行为变化存在必要的自动化测试。
- [ ] Build 成功。
- [ ] Tests 通过。
- [ ] 不破坏已有核心流程。
- [ ] 没有绕过既有 Aggregate / Domain Policy 直接修改领域状态。
- [ ] 没有绕过 AI Gateway 直接依赖具体 LLM Provider。
- [ ] 没有绕过 Workspace 直接执行 Git / Shell / Filesystem 操作。
- [ ] Repository Analysis 没有产生非预期源代码修改。
- [ ] Evolution Execution 没有直接修改原始 Software Asset。
- [ ] 用户确认边界没有被绕过。
- [ ] 必要异常路径已经处理。
- [ ] 无临时 Debug 代码。
- [ ] 无未解释的新依赖。
- [ ] 无与当前 Task 无关的大规模重构。
- [ ] 相关文档在必要时已经同步更新。

涉及以下变化时必须更新对应文档：

```text
Product Scope
→ PRODUCT.md

Architecture / Module / Dependency
→ ARCHITECTURE.md

Domain Concept / State / Invariant
→ DOMAIN_MODEL.md

Milestone / Current Task
→ ROADMAP.md

Long-term Architecture Decision
→ ADR
```

---

## 7. Deferred Ideas

### IDEA-001 — Automatic Software Asset Discovery

未来自动搜索：

```text
GitHub
Other Code Hosting Platforms
Other Software Assets
```

并根据用户需求筛选适合作为项目起点的 Software Asset。

**Why deferred**

MVP 首先验证：

> 用户信息 + 已知 Repository 是否足以产生有价值的 Product Direction。

自动 Asset Discovery 会显著扩大搜索、授权、License 和 Repository Ranking 问题。

---

### IDEA-002 — Long-Running Autonomous Coding Agent

允许系统连续执行多个 Evolution Step，甚至长时间自主修改项目。

**Why deferred**

当前 Product Principle 明确要求：

```text
Human Chooses Direction
+
Incremental & Verifiable
```

MVP 中每个 Evolution Step 都必须独立确认和验证。

---

### IDEA-003 — Full Project Generation From Scratch

支持在不存在合适 Software Asset 时从零生成完整项目。

**Why deferred**

DelveForge 当前核心差异化是：

```text
Evolution Before Rewrite
```

而不是通用 AI Coding Generator。

---

### IDEA-004 — Cloud Repository Hosting

由 DelveForge 托管用户代码或 Working Copy。

**Why deferred**

MVP 优先本地运行，不承担远程代码存储、权限、安全和运维成本。

---

### IDEA-005 — Multi-user / Team Collaboration

支持：

```text
Accounts
Organizations
Shared Assets
Team Plans
Permissions
Collaboration
```

**Why deferred**

当前领域明确为单用户本地 MVP。

---

### IDEA-006 — Production Deployment Automation

Evolution 完成后自动完成：

```text
Build
Package
Deploy
Release
```

**Why deferred**

当前 Verification 的目标是证明软件改造可信，而不是建立完整 CI/CD 平台。

---

### IDEA-007 — Commercial Value Prediction

自动预测 Product Direction 的：

```text
Market Size
Revenue Potential
Commercial Success
```

**Why deferred**

当前核心目标是帮助用户发现真正愿意开发、能够实际落地且具有个人意义的项目。

商业价值预测不属于 MVP 核心问题。

---

### IDEA-008 — Complete Execution Attempt History

将每次：

```text
Execution
Repair
Retry
Verification
```

独立建模并长期保存完整 Attempt History。

**Why deferred**

当前 MVP 中 Execution Result 与 Verification Result 作为 Evolution Step 内部 Value Object 已经足够支撑核心闭环。

如果实际开发暴露出更复杂的历史追踪需求，再考虑引入 Execution Attempt Entity。

---

### IDEA-009 — Advanced Recovery Strategies

支持：

```text
Multiple Recovery Strategies
Checkpoint Selection
Partial Recovery
Automatic Recovery Planning
```

**Why deferred**

MVP 首先实现最小安全语义：

```text
Rollback
→ Recovery
→ UNRECOVERABLE
```

避免过早构建复杂 Recovery Framework。

---

### IDEA-010 — Distributed Event Architecture

将 Domain Event 通过：

```text
Message Broker
Transactional Outbox
Distributed Consumers
```

进行异步传播。

**Why deferred**

MVP 当前采用 Modular Monolith。

Domain Event 首先服务于：

```text
Traceability
Audit
Diagnostics
```

没有证据表明当前需要分布式事件架构。

---

## 8. M0 Decisions & Known Blockers

当前不存在已经确认会阻止项目继续开发的外部 Blocker。

在进入主要功能实现前，M0 需要明确部分基础技术决策，以避免后续模块开发建立在不稳定的工程假设之上。

当前决策如下。

### 8.1 Core Language & Runtime

**Decision**

DelveForge MVP 采用 Java-first 技术路线。

```text
Core Runtime
→ Java

Application / Orchestration
Domain Model
User Discovery
Repository Analysis
Opportunity Discovery
Evolution
AI Gateway
Workspace Gateway
Persistence
→ Java
```

Python 不作为 MVP 的必需运行时，也不承担核心领域或应用逻辑。

如果未来某个 Repository Analyzer、Code Tool 或其他 Workspace Tool 明确依赖 Python 生态，可以将其作为独立 Tool Process 引入，并通过明确接口由 Workspace 调用。

**Rationale**

- DelveForge 当前核心能力可以由 Java / Spring 生态完成。
- 保持单一核心运行时可以降低 MVP 的部署、调试和模块协作复杂度。
- 项目主要技术方向保持为 Java Backend + AI / Agent Engineering。
- 不因为潜在的未来需求提前引入 Python Runtime。

**Status**

```
DECIDED
```

### 8.2 MVP User Interaction

**Decision**

DelveForge 的最终目标产品形态为：

```text
Local-first Desktop Application
```

但 Desktop Shell、安装包和桌面端发布能力不属于当前 Core MVP 的前置条件。

MVP 开发与核心产品验证阶段采用：

```text
Vue Web UI
    ↓
localhost
    ↓
Local Java Backend
```

Frontend 使用：

```text
Vue 3
+
TypeScript
+
Vite
```

Frontend 与 Backend 在开发阶段保持逻辑与工程结构分离，但共同维护在同一个 Git Repository 中。

当 Core MVP 闭环稳定后，再选择：

```text
Tauri
Electron
Other Desktop Shell
```

并将成熟的 Frontend 与 Local Java Backend 封装为单一 Desktop Application。

**Rationale**

- DelveForge 需要访问用户本地 Repository、Git、Filesystem、Build 和 Test Tool，这些能力由 Local Java Backend 提供。
- Frontend 主要负责用户交互和结果展示，不承担本地代码操作与领域逻辑。
- Web UI 足以支持 MVP 阶段的 Agent Chat、Profile、Direction、Plan、Diff、Confirmation 和 Verification Result 等交互。
- Desktop Packaging 与核心 Agent / Evolution 能力相互独立，不应阻塞 MVP 核心闭环开发。
- 在核心产品能力稳定后再选择 Desktop Shell，可以避免过早投入安装、打包、进程管理和跨平台适配工作。

**Status**

```text
MVP Interaction
→ DECIDED

Frontend Stack
→ DECIDED

Final Product Form
→ DECIDED

Desktop Shell Technology
→ DEFERRED
```

### 8.3 Persistence

**Decision**

DelveForge MVP 使用：

```
SQLite
+
MyBatis-Plus
+
Flyway
```

其中：

```
SQLite
→ Local Embedded Database

MyBatis-Plus
→ Persistence Adapter / Data Access

Flyway
→ Database Schema Migration
```

主要持久化：

```
User Profile Revisions
Repository Profile Snapshots
Product Directions
Evolution Plans
Evolution Steps
Working Copy Metadata
Execution / Verification Results
Audit / Domain Event Records
```

实际 Repository 与 Working Copy 中的代码仍由 Git / Filesystem 管理，不存入关系数据库。

**Rationale**

- MVP 为单用户、本地运行的 Modular Monolith。
- SQLite 不要求用户额外安装和维护独立数据库服务。
- 当前业务数据规模较小，但具有明确结构、Revision、Snapshot 和 Traceability 需求。
- MyBatis-Plus 可以减少常规 CRUD 样板代码，同时保留对 SQL 与 Persistence Adapter 的控制。
- Flyway 用于保证数据库 Schema 可以随项目演进稳定升级。

**Status**

```
DECIDED
```

------

### 8.4 Working Copy

**Decision**

MVP 默认通过独立 Local Git Clone 创建 Working Copy。

```
Original Software Asset
        ↓
Repository Analysis
        ↓
Repository Profile
@ analyzedRevision
        ↓
Create Independent Git Clone
        ↓
Checkout analyzedRevision
        ↓
Working Copy
        ↓
Evolution Execution
```

Working Copy 放置在 DelveForge 管理的独立 Workspace Directory 中。

创建 Working Copy 时优先保证与原始 Software Asset 的物理与操作隔离。

MVP 默认仅基于已提交的 Git Revision 建立 Repository Profile 和 Working Copy。

源 Repository 中未提交的修改不自动进入 Working Copy。

**Rationale**

- Working Copy 必须与原始 Software Asset 隔离。
- Evolution Execution 不得直接修改用户源 Repository。
- 独立 Git Clone 与 baselineRevision、currentRevision、lastVerifiedRevision、Rollback 等领域语义自然对应。
- 相比 Git Worktree，独立 Clone 可以提供更清晰的源 Repository 与 Evolution Workspace 边界。
- MVP 优先保证 Safety 和可理解性，而不是最小化磁盘占用。

**Status**

```
DECIDED
```

------

### 8.5 Initial LLM Provider

**Decision**

DelveForge MVP 的第一个 LLM Provider Adapter 使用：

```
DeepSeek
```

初始默认 model id：

```
deepseek-flash
```

模型名称、API Endpoint、API Key 和其他 Provider 参数必须通过 Configuration 管理，不允许写死在业务代码中。

调用关系保持：

```
Business Module
    ↓
AI Gateway
    ↓
DeepSeek Adapter
    ↓
DeepSeek API
```

业务模块不得直接依赖 DeepSeek SDK 或 DeepSeek-specific API。

**Rationale**

- DeepSeek 具有较低的使用成本和较强的 Agent / Tool Use 能力。
- DeepSeek 对国内用户具有较低的接入门槛。
- Initial Provider 仅作为 MVP Adapter 实现，不改变系统 Provider-Agnostic 的架构原则。
- 后续应能够在不修改核心业务模块的情况下增加或替换其他 LLM Provider。

**Status**

```
DECIDED
```

------

### 8.6 Initial Project / Module Structure

**Decision**

DelveForge 采用 Monorepo 管理完整产品代码与文档。

初始 Repository Structure：

```text
DelveForge/
│
├── pom.xml
├── mvnw
├── mvnw.cmd
├── .mvn/
│
├── backend/
│   ├── delveforge-domain/
│   │   ├── pom.xml
│   │   └── src/
│   │
│   ├── delveforge-application/
│   │   ├── pom.xml
│   │   └── src/
│   │
│   ├── delveforge-infrastructure/
│   │   ├── pom.xml
│   │   └── src/
│   │
│   └── delveforge-app/
│       ├── pom.xml
│       └── src/
│
├── frontend/
│   ├── package.json
│   └── src/
│
├── docs/
├── AGENTS.md
└── README.md
```

Desktop Shell 尚未确定，因此 MVP 初始工程不创建无实际用途的 Desktop Module。

Core Java Package Namespace：

```text
com.ayywl.delveforge
```

Backend 使用四个 Maven Module。

#### delveforge-domain

负责核心领域模型与领域规则。

主要包含：

```text
Aggregate Root
Entity
Value Object
Domain Service
Domain Policy
Domain Event
```

该 Module 不依赖其他 DelveForge Maven Module，也不得依赖：

```text
Spring Web
DeepSeek
Spring AI Provider Adapter
MyBatis-Plus
SQLite
Git
Filesystem
Shell
```

#### delveforge-application

负责 Application Use Case 与业务流程编排。

主要包含：

```text
Use Case
Application Service
Orchestration
Input / Output Port
AI Gateway Interface
Workspace Gateway Interface
Persistence Repository Interface
```

依赖：

```text
delveforge-domain
```

不得依赖 `delveforge-infrastructure` 或 `delveforge-app`。

#### delveforge-infrastructure

负责外部技术能力的具体实现。

主要包含：

```text
DeepSeek / AI Adapter
SQLite / MyBatis-Plus Persistence Adapter
Git Adapter
Filesystem Adapter
Shell Adapter
Build / Test Adapter
```

依赖：

```text
delveforge-application
+
delveforge-domain
```

Infrastructure 实现 Application 定义的 Outbound Port。

#### delveforge-app

负责系统启动与 Interface Adapter。

主要包含：

```text
Spring Boot Bootstrap
REST API
Controller
Configuration
Dependency Wiring
Exception Mapping
```

依赖：

```text
delveforge-application
+
delveforge-infrastructure
```

`delveforge-app` 作为 Composition Root 负责组装系统。

Backend Module Dependency：

```text
delveforge-domain

        ↑

delveforge-application

        ↑

delveforge-infrastructure


delveforge-app
    ├── delveforge-application
    └── delveforge-infrastructure
```

Maven Module 用于约束主要 Architecture Layer Dependency。

各 Module 内部继续按照 Feature / Domain Concept 组织 Package，而不是把整个项目组织成传统全局：

```text
controller/
service/
mapper/
entity/
```

例如（当前实际结构见 `docs/ARCHITECTURE.md` §6.1）：

```text
com.ayywl.delveforge.domain
├── user
├── asset
├── repositoryprofile
├── evidence
├── direction
└── evolution
com.ayywl.delveforge.application
├── userdiscovery
├── repositoryanalysis
├── opportunitydiscovery
├── evolution
└── port
    ├── ai
    ├── persistence
    └── workspace
com.ayywl.delveforge.infrastructure
├── ai
├── persistence
└── workspace
```

不创建通用 `delveforge-common` Maven Module。

共享类型应首先根据其业务语义归属到明确的 Domain / Application / Infrastructure 边界，而不是统一放入无明确职责的 Common Module。

**Rationale**

- 四个 Module 足以形成明确的编译期架构边界。
- 相比 Single Maven Module，可以降低 Domain / Application 直接依赖 Infrastructure 的风险。
- 相比为每个业务能力建立独立 Maven Module，可以显著降低单人 MVP 的模块管理与依赖复杂度。
- Feature-first Package Organization 与现有 User Discovery、Repository Analysis、Opportunity Discovery 和 Evolution 业务边界保持一致。
- Monorepo 便于 Coding Agent 同时理解 Frontend、Backend、Documentation 与未来 Desktop Integration。

**Status**

```text
DECIDED
```

### 8.7 Remaining M0 Decisions

当前核心技术栈与 Initial Project Structure 已基本确定。

后续仍需要在实际 M0 Implementation 中逐步明确：

| Decision                                    | Current State | Target                     |
| ------------------------------------------- | ------------- | -------------------------- |
| AI Framework 路线与版本基线                 | **DECIDED**   | 见 ADR-0003                |
| AI Gateway 第一版具体 Adapter Design        | **DONE**      | M1 Task 5                  |
| Workspace Gateway 只读 Adapter              | **DONE**      | M1 Task 3                  |
| Workspace Gateway provisioning Adapter      | **DONE**      | M3 Task 2                  |
| Workspace Gateway mutation Adapter          | OPEN          | M4                         |
| Initial Database Schema                     | **DONE**      | M1 Task 3                  |
| Frontend / Backend Development API Contract | **DONE**      | M1 Task 4                  |

Workspace 分成读取、环境准备与步骤代码修改三个 Port，互不继承。
Repository Analysis（M1）只需要读取；M3 Task 2 实现独立 clone / checkout 的准备能力，
不开放步骤写能力；mutation Adapter 与 Step / Working Copy 授权作用域留给 M4。

#### AI Framework 路线与版本基线

**已决定：不引入 Spring AI / Spring AI Alibaba。**
首个真实 AI Adapter 直接调用 DeepSeek 的 OpenAI 兼容 HTTP API，见
[ADR-0003](decisions/0003-first-ai-adapter-uses-deepseek-http-api.md)。

M0 时把该决策标记为 DEFERRED 的理由是「首次实现真实 AI Adapter 之前必须评估」。
M1 Task 5 完成评估，结论如下：

```text
需求侧     当前只需要一次 messages → JSON completion。
           Tool Calling、多模型路由、Streaming、Agent 能力均在当前范围之外。

版本侧     Spring AI 2.0.x 要求 Spring Boot 4.x / Spring Framework 7；
           项目基线是 Spring Boot 3.5.16，因此选择 Spring AI 就等同于绑定 1.1.x，
           并在将来升级 Boot 4 时再迁移一次。
           Spring AI Alibaba 稳定线同样停在 Spring Boot 3.5.x。

能力侧     DeepSeek 只支持 {"type":"json_object"}，不支持严格 json_schema，
           结构化输出的解析与校验无论如何都由 DelveForge 自己完成
           （这本来就是 AiGateway 契约里划给 Application 的职责）。
```

因此原候选基线（Spring AI 1.1.2 / Spring AI Alibaba 1.1.2.2）**未被采用**。
该决定被 AI Gateway Port 隔离：将来出现上述需求、或升级到 Spring Boot 4.x 时，
只需替换 Infrastructure 内的 Adapter，业务代码不受影响。

Desktop Shell Technology 已明确延后，不属于 M0 Blocker。

如果其中任何未决事项实际阻止当前 Milestone 继续推进，再将其提升为正式 Blocker。

---

## 9. Completed Milestones

完成 Milestone 后保留历史记录，不从 Roadmap 删除。

### M0 — Project Foundation

**Completed**

```text
2026-09-15
```

**Outcome**

```text
Backend 四个 Maven Module 与依赖方向，由 maven-enforcer 在构建期校验
Application 层的 AI Gateway 与 Workspace Gateway 抽象（读写能力分离）
SQLite + MyBatis-Plus + Flyway 的 Persistence 基础与迁移管线
统一配置绑定、错误映射与日志规则
Vue 3 + TypeScript + Vite 的 Frontend 基础工程与最小通信链路

Acceptance Criteria 全部验证通过。全新克隆执行 ./mvnw clean verify
与 npm ci && npm run build 均成功，40 个自动化测试通过。
```

**Lessons Learned**

```text
原设计问题
- 日志与凭据规则最初按"异常来源"分类，依赖对未来代码写法的假设，
  不是可执行保证；改为只记录异常类型链与堆栈位置
- 日志最初记录原始请求 URI，而路径可能承载用户输入或凭据；
  改为记录路由模板，未匹配时使用兜底 pattern 或固定标识
- 框架自身可能在应用代码之外输出敏感运行时文本
  （实测 ExceptionHandlerExceptionResolver、Tomcat 的 DEBUG 输出），
  仅修改应用代码不足以满足安全要求，必须在配置层面固定其级别

被验证的假设
- 拆分为两个 Port 能让只读流程在编译期拿不到写能力
- 使用官方脚手架而非自行挑选版本是必要的：Vite 8 配套的 TypeScript
  为 6.x，而当时 npm 上最新为 7.x，自行选择存在兼容风险

被推翻的假设
- "Windows 上本地可执行 ./mvnw" 不等于 "Git 中记录了可执行位"。
  实际记录为 100644，Linux / macOS 全新克隆会直接失败。
  Git 的文件模式必须单独验证，不能由本地可运行推断

后续调整
- Workspace mutation capability 尚未绑定 Working Copy 授权作用域，
  见 ADR-0001，M3 / M4 必须重新评估
- AI Framework 路线与版本基线尚未验证，见 §8.7
```

---

### M1 — Discovery Inputs

**Completed**

```text
2026-09-22
```

**Outcome**

```text
形成 Product Discovery 需要的两个可信输入：

Confirmed User Profile @ Revision
Repository Profile @ analyzedRevision

两条链路都由自动化测试覆盖，并各自做过真实环境验证：

User Discovery        docs/retrospectives/m1-user-discovery.md
Repository Analysis   docs/validation/m1-repository-analysis-smoke-test.md
```

**Lessons Learned**

```text
原设计问题
- Repository 分析材料最初按「目录层级 + 路径排序」筛选。前者让按工程惯例放在深目录的
  主源码树整体不可见（真实仓库 96 个 Java 文件全部落选），后者让字典序靠前的文件类别
  占满预算。两轮真实仓库验证暴露后，改为「不按层级筛选 + 按用途类别轮转」。
  这一类问题只有在真实仓库上才会暴露：自动化测试用的是替身，其目录结构由测试自己决定。

被验证的假设
- 「revision 只解析一次并贯穿所有读取」确实能在分析期间源仓库前移时防止版本混用；
- 只读能力在类型层面隔离（只注入只读 Port）之后，Repository Analysis 全程不写源仓库。
- 「确定性」是可以被独立复算的：材料选材不调用 Provider 也能用 git 独立复现并核对。

后续调整
- M1 完成时 Material Selection 只做到 bounded representative sampling：能采样到工程结构与配置，
  但业务实现代码（controller / service 等）覆盖不足，部分大文件因单文件上限被跳过。
  该重访条件随后被真实仓库验证触发，Material Selection 已在 M2 被
  Repository Map + Scout + 定向读取取代（见下一条目）。
  当时的记录与重访条件：docs/validation/m1-repository-analysis-smoke-test.md §7。
- M1 完成时将 Workspace mutation Adapter 留待 M3 的真实调用方；M3 已将准备能力单独实现，
  步骤代码修改 Adapter 与授权作用域继续留给 M4（见 §8.7 与 M3 完成记录）。
```

---

### M2 — Product Direction Discovery

**Completed**

```text
2026-10-03
```

**Outcome**

```text
形成 Product Discovery 的第三段可信输入：由「已确认的用户」与「已理解的仓库」
共同支撑的产品方向候选。

Confirmed UserProfile @ revision
        +
RepositoryProfile @ analyzedRevision
        ↓
3–5 个 Evidence 可追溯的 ProductDirection 候选
        ↓
Select / Reject / Supersede 生命周期

M2 复盘                docs/retrospectives/m2-product-direction.md
Product Direction 端到端 docs/validation/m2-product-direction-e2e-smoke.md
```

**Lessons Learned**

```text
被真实数据推翻的设计
- Repository Analysis 的「一次性确定性代表采样」在真实仓库上系统性错过业务实现
  （方向引用的 16 个文件全是配置与构建元数据）。改为「完整 Map（只看元数据）
  + LLM Scout 指认 + 定向读取」之后，业务实现才稳定进入依据。
- 「把全部源码候选一次交给模型」与仓库大小线性相关，64 KiB 上限在 4/5 的真实仓库上
  fail-closed。改为按真实目录结构分层下降之后，其中 3 个恢复可用。
- 整条链路上唯一把仓库内容送出去的地方是最终分析器，而此前没有任何一层排除凭据文件。
  补上「一条政策、两个执行点」（读取前路径排除 + 交给模型前内容净化）。

被验证的假设
- 引用是调用内的闭集短名（RF-* / RR-*），由 Application 校验并换回真实描述符，
  模型输出的路径永远不会进入读取调用。
- 预算围绕不确定的模型调用写成确定的：守卫在调用之前判定，契约违反后的重试
  也占用真实额度，聚合日志记的是实际调用次数而不是逻辑阶段数。
- 失败不留部分状态：方向发现的整批原子性、baseline 不成立时在调用模型之前失败，
  都在真实 smoke 上核对过（失败前后相关表行数不变）。

代价与流程调整
- Repository Analysis 吃掉了 M2 的绝大部分工程量，且核心架构可用之后仍做了若干轮
  边界打磨（预算标定、有界重试），其中一部分可以更早停止。
- M3 起改用「一个里程碑 → 一个相对完整的垂直切片 → 一次里程碑级审查 → 一次聚焦 smoke
  → 复盘」的推进方式，优先级为主产品链路与面试可解释性高于边界完备性。
  详见 docs/retrospectives/m2-product-direction.md §E 与 §H。

接受的边界
- 极端庞大或极宽的仓库（当前实例 langflow）可能在 Scout 预算内无法完成导航，
  失败关闭并返回 409 是接受的产品边界，不是实现缺陷。
```

---

### M3 — Evolution Planning & Working Copy

**Completed**

```text
2026-10-05
```

**Outcome**

```text
Selected ProductDirection
        +
Base SoftwareAsset
        +
Base RepositoryProfile
        ↓
EvolutionPlan（PROPOSED）
        ↓
WorkingCopy（独立 clone / detached checkout）
        ↓
Plan ACTIVE
WorkingCopy READY
Steps PENDING_CONFIRMATION

sourceRevision = currentRevision = lastVerifiedRevision = analyzedRevision
Steps.baselineRevision = null；没有步骤授权、代码修改或 Verification 执行。
```

M3 Deliverables 与 Acceptance Criteria 全部满足，审查问题已修正。最终代码 `912d506`
全量 verify 通过（1127 tests、0 failure / error / skipped）；同一代码完成真实 Provider
与真实 Git WorkingCopy 的 focused smoke，源资产不变。此处引用既有验证，不表示文档收尾重新运行。

- 验证记录：[M3 Evolution Planning & Working Copy focused smoke](validation/m3-evolution-planning-working-copy-smoke.md)。
- 复盘：[M3 Evolution Planning & Working Copy retrospective](retrospectives/m3-evolution-planning-working-copy.md)。

**Lessons Learned**

- Planning 保持 AI Proposes → Domain Decides；Parser 解析结构、Resolver 还原既有依据、Domain
  接受候选，各自责任明确。Proposal 仅作候选输入，正式 Plan / Step 直接拥有领域内容。
- Direction 的历史发现基线与 Plan 的规划基线不同：R1 可以发现方向，同一 Candidate Asset
  的 R2 可以用于后续规划和准备；身份、归属、授权与 revision 校验继续保留。
- Working Copy provisioning 是独立能力；WorkspaceReadPort、WorkingCopyProvisioningPort 与
  未来 WorkspaceMutationPort 互不继承，环境准备不产生步骤写能力。
- 数据库生命周期状态由事务保证原子性；Git / 文件系统副作用靠失败补偿。补偿尽力删除本次
  候选，失败保留原异常，残留目录不具有 WorkingCopy 的领域权威性。
- Plan ACTIVE 表示环境已可用于后续演化，仍须逐 Step 获得用户确认。
- 真实 Provider 产出可用规划；自然语言步骤粒度与职责重叠仍需人工判断，单次输出不证明普遍质量。
- 「较大垂直切片 → 里程碑审查 → 聚焦修正 → smoke → 复盘」完成了主链路；M4 应保留这一节奏，
  并在实现前明确候选 / 正式状态及解析 / 引用 / 接受边界，避免依赖事后 walkthrough 才发现问题。

**Freeze**

M3 STATUS: FROZEN。验收、审查修正、focused smoke 与文档同步均已完成。
后续不进行 M3 优化或 cleanup，除非出现具体阻塞 M4 的正确性问题；M4 仍为 TODO。

---

### M4 — First Verified Evolution Step

**Completed / Frozen：2026-10-10。**

真实直接 Provider 执行器 `9efee445` 在固定 A=`47ca448` 的隔离 WorkingCopy 中，完成
Python 文件名角色提示与三个实际新增测试，生成 B=`6dfdc121`；实际 BUILD / TEST / DIFF_CHECK
通过，Candidate 1346 tests 无失败，Step SUCCEEDED，lastVerifiedRevision 由 A 推进到 B。
原资产没有被 Evolution 修改。Plan 保持 ACTIVE，没有开始下一 Step 或 M5。

M4 维护分支以 merge `bf949db` 进入 main，保留八份维护提交与报告历史；Candidate B
的业务代码未合入 main。新增显式 `m4-local` 配置提交 `1830e67`，复用已成功的 SSE true、
connect / idle 90s、total 300s、98,304 tokens、32 MiB 响应、64 KiB 事件预算，以及
Verification 900s / 1200s、8 MiB 输出、160 XML。原默认配置保留，未取得同等真实 M4 证明。
当前所有 AI 阶段共用 Provider 预算；这不是最优预算或任意仓库可靠性声明。

交付聚焦回归 111 项通过，合并与配置后的 Wrapper `verify` 1376 项通过，零失败 / 错误 / 跳过。
独立新 SQLite 的 V1–V12 迁移、仅本机监听、新画像创建与授权资产登记均通过；夹具真实 Maven
测试通过，源码不变，没有调用 Provider 或再执行真实 Smoke。历史数据库 / Verified B 原件保留。
根目录 28 份忽略且无引用日志带 hash 清单归档到仓库外，九个历史证据目录原地保留。

业务 walkthrough、完整 Plan、关键 diff 与失败链见 [M4 真实案例](validation/m4-real-provider-verified-evolution-smoke.md)；
版本、验证与外部索引见 [交付关闭记录](validation/m4-delivery-closeout.md)；配置和 API 见
[本地使用](M4_LOCAL_USAGE.md)。历史 UNKNOWN 不改写；Java/Maven 有限技术验证、Windows
宿主文件系统风险与未实现的失败恢复继续是交付限制。

**M4 STATUS: DONE / FROZEN。M5 STATUS: DEFERRED。后端功能开发暂停。**
本关闭任务只整合与配置，没有新增业务功能、Prompt 优化、领域变化、验证器放宽或前端开发。

---

### Task B — Minimal Web Experience

**Completed: 2026-10-10。** 既有 Vue 3 / TypeScript / Vite 基础上交付需求聊天与六维画像、
授权仓库分析、候选方向决定、演化规划 / WorkingCopy / Step 确认，以及真实检查点和验证结果视图。
按本任务授权改用 pnpm 锁文件，新增开发交互测试，不改变 M0–M4 后端、领域或 Prompt。

验收采用合成 API 前端回归、类型检查、生产构建及新数据库的本机 Proxy 联通；没有新付费
Provider Smoke 或代码演化，因此不声称通过 Web 完成了第二次真实 Verified B。
长请求、刷新和断线只查询实际状态，不自动重复 execute；缺失的历史 / diff / 请求任务 API
如实保留为交互限制，见 [Task B 验证](validation/minimal-web-experience.md) 与
[前端使用](../frontend/README.md)。

**Task B: DONE。M0–M4 后端继续 FROZEN，M5 继续 DEFERRED。停止新增前后端功能；**
下一阶段仅在另行授权后进行新仓库产品体验、代码复盘与实习准备，不以本记录启动新功能。

---

### M1 交付后聚焦修复 — Sufficiency / Conversation Flow

用户于 2026-10-10 单独授权修复真实 Web 体验中发现的 M1 问题；这是 M4 冻结后的有限例外。
六区内容与用户依据共同参与充分性判断；普通对话轮满足最低条件仍保持探索，用户显式请求
审阅才评估并推进 REVIEWING。确认当前版本、Evidence 语义与失败原子性保持不变。
nextQuestion 统一加入本页聊天并去重，不构造未保存的历史。M2–M4 实现继续冻结，M5 暂缓。
验证及限制见 [M1 聚焦修复](validation/user-discovery-sufficiency-conversation-fix.md)。

后续真实手动体验暴露“充分即无下一问”的残留契约。另行授权的 Conversation Continuation &
Review 修复将最低充分性与下一问分开：普通对话仍两次模型调用；用户主动 Continue / Reopen
后使用仅限当前探索版本的只读提问。审阅展示六区和真实依据，不新增状态或持久化对话。
验证见 [连续探索与画像审阅修复](validation/user-discovery-conversation-continuation-review-fix.md)。
M2–M4 实现保持冻结，M5 继续暂缓。

以下为记录格式，供后续 Milestone 使用：

### Mx — Milestone Name

**Completed**

```text
YYYY-MM-DD
```

**Outcome**

```text
实际完成了什么。
哪些 Acceptance Criteria 得到验证。
最终交付状态是什么。
```

**Lessons Learned**

```text
开发过程中发现了哪些原设计问题。
哪些假设得到验证或被推翻。
后续 Roadmap 因此需要做什么调整。
```

如果 Milestone 的实际结果改变：

```text
Product Scope
Architecture
Domain Model
```

应同时同步更新对应文档，而不是只在 Roadmap 中记录。

### Task C — 用户交互一致性与演示包（用户授权收尾）

C1 仅修复 M1 同版本充分性复用、并发保存和探索/审阅展示；C2 准备三个许可清晰的
Java/Maven BUILD_READY 演示基线；C3 准备本地 Bundle、Catalog 与 README。
不新增演化能力，不调用付费 Provider，不自动发布/push；M4 保持冻结，M5 仍暂缓。

**Completed: 2026-10-11。** C1 已完成同版本评估复用、原子保存与会话/审阅分离，
完整后端 1401 项及前端 34 项通过；C2 三个固定许可来源的独立基线构建通过；C3 三份
Bundle 均还原到新目录，HEAD/工作树与 manifest 一致，离线 Maven 测试通过。
三份仅为 BUILD_READY，尚未由真实 Provider 演化；GitHub Release 附件待用户确认发布。
见 [C1](validation/task-c1-user-discovery-consistency.md)、
[C2](validation/task-c2-demo-build-readiness.md)、[C3](validation/task-c3-demo-bundle-delivery.md)
与 [Demo Catalog](demos/README.md)。本任务后等待手动验收，不继续扩展功能或开始 M5。

### Task D — 规划授权阻塞与资产交互（用户授权修复）

**DONE（2026-10-11）。** 完整后端 1423 项及前端 44 项测试通过，类型检查和构建通过。
保留 Evolution Base 的读取、使用授权与许可证门槛；仅补齐原 SoftwareAsset 的显式授权
更新、同路径并发登记保护和 Evidence 展示。资产授权有独立并发版本，旧分析与已选方向
引用保持不变；Planning 提交原子核对授权依据，不自动升级权限或重新消费 M1–M2。
M0–M4 继续 FROZEN，M5 继续 DEFERRED；本任务不调用真实 Provider 或修改 Git / Verification。
根因、API 契约、确定性回归和现有 GS REST 恢复步骤见
[Task D 验证记录](validation/task-d-planning-authorization-ux.md)。

### Task E — Final E2E UX Polish 与 WorkingCopy 换盘（用户授权修复）

**DONE（2026-10-11）。** 后端最终 `mvnw.cmd verify` 1436 项通过，前端 56 项、
类型检查及构建通过。新增许可证声明的最小输入检查与 SPDX/混合声明控件、按精确资产 ID
补充授权并返回原规划、客户端等待计时及 GET 状态展示。
保留已有 Workspace Root 环境变量，并提供显式旧副本根目录允许列表与只读配置展示；
不移动旧副本，不改写历史授权、Plan、Step 或已验证 revision。
Git Adapter 仅调整存储范围及错误检查，Candidate、Mutation 和 Verification 领域语义不变。
没有付费 Provider、真实 Smoke 或新演化；M0–M4 继续 FROZEN，M5 继续 DEFERRED。
实现边界、测试与手动验收见 [Task E 验证记录](validation/task-e-final-e2e-ux-polish.md)。

### Task F — Project Workspace & History Recovery（用户授权闭环）

**DONE（2026-10-11）。** Application 导航上下文、只读历史投影、资源目录与 Vue 项目
工作台已交付。V15 仅新增表与索引；旧 Plan 可沿保存引用直接查询，首次继续才认领项目。
复用确认画像不复制或修改；项目切换不重新调用 AI，不创建副本或执行步骤。关联更新有
独立 CAS 版本，慢轮询不会覆写另一个项目，未决执行标记跨项目保留。

最终 Wrapper verify 1450 项通过，前端 71 项、类型检查与构建通过。现有 GS REST 原库
只读核对 1/4 成功及 Verified B c822cc3；一致副本上的 API、导航认领、后端重启及 Vite
Proxy 回读通过，没有新的 Provider / 演化 / Maven 验证请求。用户原库尚未迁移；首次启动
新版本才应用 V15，升级及手动验收见 [Task F 验证](validation/task-f-project-workspace-history-recovery.md)。

当前画像与历史绑定版本分开显示；未持久化聊天与 CodingProposal 不能恢复。
Task F 当时保留的全局单 SELECTED 规则已由下述 F-Fix 显式演进；授权、revision、
WorkingCopy 与 Verification 守卫继续有效。

### Task F-Fix — 项目方向隔离与重新规划

**DONE（2026-10-11）。** 稳定 flowId 将 Direction 选择和 Plan 废弃限定在同一演化流程。V16 仅从明确引用建立
旧记录归属，未知归属只读；不恢复任何 SUPERSEDED 终态。新规划轮次保存
previousContextId，不替换原 planId。查看历史、切换项目和建立轮次不会调用 AI，
只有用户明确点击规划才使用当前选定的资产和分析快照。决策见 ADR-0008。

追加的项目删除/恢复采用 V17 导航软删除，保留业务对象和磁盘代码。默认列表隐藏，
已删除列表可显式恢复；同一 flowId 的规划轮次独立保留。CAS 拒绝过期请求，READY 或
未决执行禁止删除。恢复不改变 Direction、Plan 或验证状态。决策见 ADR-0009。

F-Fix 首次完整 verify 1457 项 PASS；追加最终 verify 1468 项 PASS，前端 80 项、类型检查
与构建 PASS。升级前备份及手动验收见
[软删除与恢复记录](validation/task-f-fix-project-deletion-recovery.md)。没有操作用户数据库，
没有付费 Provider / 真实 Step 请求，也没有推送远端。
**M0–M4 继续 FROZEN，M5 继续 DEFERRED；本任务结束后等待用户手动验收，不扩展功能。**

### Final Fix — 偶发规划拒绝（用户授权修复）

**DONE（2026-10-11）。** 保存的历史失败定位到 Provider 返回后的 CurrentState 原文成员校验；
具体被改写条目与刷新后成功原因无法从旧日志恢复。严格事实与 Evidence 规则保持不变，
仅增加固定失败阶段/原因、Context 版本预检和规划请求/回读边界，没有自动付费重试。
最终 Maven verify 1474 项、前端 85 项、类型检查和构建 PASS。
见 [定位与验证记录](validation/final-planning-rejection-fix.md)。没有数据库迁移或真实 Provider /
Evolution Step 调用。结束业务扩展；数据库归档由用户决定，当时 Task G 尚未实施。
代码已在 Pre-UI Closeout 快进整合至 main，仅增加文档与交接；
见 [关闭记录](validation/pre-ui-final-closeout.md) 和 [UI 技术交接](UI_TASK_G_HANDOFF.md)。

### Task G — UI / UX Polish

**DONE / ACCEPTED / FROZEN（2026-10-12，当前发行版 UI）。** G1 设计基础和项目工作台、
G2 五工作流页面及后续视觉 / 长内容细化已交付；最终实现为 `dd3d24e`。
G2 包含 G1 `508859e`，本地 main 已从 Pre-UI `40538b6` 快进整合全部五个实现提交。
最终前端 10 文件 / 123 项测试、typecheck、build 与 diff 检查 PASS；关闭工作仅补录文档。

用户接受当前 Graphite + Indigo 与六页结构，少量视觉不足延后。合成响应和历史截图提供 UI
交互 / 响应式证据，不代替真实后端 / Provider E2E 或部署接受。六张参考 PNG 保持未跟踪。
M1–M4 业务边界不变，M5 继续 DEFERRED，M6 继续 TODO；不自动发起下一轮 UI、E2E 或发布。
实现、组件、证据范围、限制与复盘见 [Task G 最终关闭记录](validation/task-g-final-frontend-closeout.md)；
之前的 G1/G2 提交与报告保留历史意义。本地整合未 push。
