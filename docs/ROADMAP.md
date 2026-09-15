# ROADMAP.md

> 本文档回答：DelveForge 准备按照什么顺序发展，以及当前阶段最重要的开发目标是什么。

**Status:** Active  
**Last Updated:** 2026-09-15

---

## 1. Current Goal

当前最重要目标：

> 完成 DelveForge 的 MVP 核心闭环，使用户能够从自身兴趣、行为、痛点和技术目标出发，结合至少一个本地 Git Repository，发现并选择 Product Direction，生成 Evolution Plan，并在隔离的 Working Copy 中完成至少一个经过用户确认且成功验证的 Evolution Step。

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
Preconditions
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
- [ ] 建立统一配置、错误处理与日志基础设施。
- [ ] 建立自动化测试基础设施。
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
- [x] 确定 Initial LLM Provider：DeepSeek，并以 DeepSeek V4.1 Flash 作为第一版默认模型。
- [ ] 对需要长期保留的重要架构决策建立 ADR。

**Acceptance Criteria**

- [ ] 项目能够在全新开发环境中成功构建。
- [ ] 应用能够正常启动。
- [ ] 自动化测试框架可以运行。
- [ ] Maven Module 依赖关系符合既定架构边界。
- [ ] Domain Module 不依赖 Application、Infrastructure 或 App。
- [ ] Application Module 不依赖 Infrastructure 或 App。
- [ ] 业务模块不直接依赖具体 LLM Provider SDK。
- [ ] 业务模块不直接执行 Git / Shell / Filesystem 操作。
- [ ] Frontend 与 Backend 可以在本地开发环境中独立启动并完成基本通信。
- [ ] 新开发者或 Coding Agent 可以依据现有文档理解基本模块边界和构建方式。

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

- [ ] 支持创建和持续更新 User Profile。
- [ ] 支持通过用户交互收集兴趣、行为、痛点、技术能力、项目目标和约束。
- [ ] 支持 User Profile revision。
- [ ] 支持 Profile Sufficiency Assessment，判断当前用户信息是否已经足以支持具有个人相关性的 Product Direction Discovery。
- [ ] 当信息不足时，能够识别重要缺失信息并继续探索。
- [ ] 当信息足够时，使 User Profile 从 `EXPLORING` 进入 `REVIEWING`。
- [ ] 支持用户 Review / Correct / Confirm Profile。
- [ ] 保存关键 Profile 判断对应的 Evidence。

#### Repository Analysis

- [ ] 支持用户指定本地 Git Repository。
- [ ] 注册对应 Software Asset。
- [ ] 确定 Repository 当前 analyzedRevision。
- [ ] 通过 Workspace 只读访问 Repository。
- [ ] 生成结构化 Repository Profile。
- [ ] 提取 purpose、techStack、modules、capabilities、reusableAssets、limitations 和 risks。
- [ ] 保存关键分析结果对应的 Evidence。
- [ ] 保证 Repository Analysis 不修改源 Repository。

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
并进入 REVIEWING。

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

- [ ] Profile Sufficiency 不依赖固定对话轮数，而取决于当前信息是否足以支持具有个人相关性的项目方向发现。
- [ ] 信息不足时系统不会过早结束 User Discovery。
- [ ] 信息足够后系统不会为了增加对话轮数继续进行无明显价值的探索。
- [ ] User Profile 必须经过用户确认后才能作为 Product Direction Discovery 的正式输入。
- [ ] User Profile 后续修改不会覆盖历史上已经被引用的 revision 语义。
- [ ] Repository Profile 可以追溯到确定的 Software Asset 和 analyzedRevision。
- [ ] Repository Analysis 全程不产生源代码修改。

**Out of Scope**

- 为 Profile Sufficiency 建立复杂评分模型。
- 自动搜索 GitHub Repository。
- Product Direction 选择。
- Working Copy。
- Coding Execution。

---

### M2 — Product Direction Discovery

**Goal**

当 Product Discovery 所需输入已经准备完成后，系统主动基于 Confirmed User Profile 与 Repository Profile 发现具有个人相关性和可实施性的候选 Product Direction，而不要求用户额外判断“什么时候应该开始推荐”。

**Deliverables**

- [ ] 建立 Product Direction Discovery Use Case。
- [ ] 支持判断 Product Direction Discovery 所需输入是否已经准备完成。
- [ ] 当存在 Confirmed User Profile 与至少一个可用 Repository Profile 时，由系统进入 Product Direction Discovery。
- [ ] AI Gateway 能够产生结构化 Direction Proposal。
- [ ] ProductDirectionDiscoveryService 对 Proposal 进行领域校验和转换。
- [ ] 每次生成 3–5 个具有明显差异的 Product Direction。
- [ ] 每个 Direction 说明：
  - [ ] Problem
  - [ ] Target Product
  - [ ] User Fit
  - [ ] Candidate Software Asset
  - [ ] Differentiation
  - [ ] Technical Value
  - [ ] Estimated Complexity
  - [ ] Risks
  - [ ] Evidence
- [ ] 支持用户查看候选 Direction。
- [ ] 支持用户明确 Select / Reject Direction。
- [ ] 保留 Product Direction 对 User Profile revision 和 Repository Profile 的追溯。

**Acceptance Criteria**

```text
Given

存在：

Confirmed User Profile @ Revision
+
One or More Repository Profiles

并且这些输入已经满足 Product Direction Discovery 的前置条件。

When

系统识别到 Product Discovery Inputs 已经准备完成。

Then

系统主动进入 Product Direction Discovery，
无需用户额外发起“生成 Product Direction”的请求。

系统生成 3–5 个具有明显差异的候选方向，
且每个方向能够解释：

为什么适合当前用户
+
利用了哪些已有软件能力
+
与原项目有什么差异
+
大致需要付出什么成本和风险

随后系统向用户展示这些 Candidate Product Directions，
由用户决定选择、拒绝或继续考虑。
```

并且：

- [ ] Product Direction Discovery 不得在 User Profile 尚未 `CONFIRMED` 时开始。
- [ ] Product Direction Discovery 至少需要一个可用 Repository Profile。
- [ ] 所有新 Direction 初始状态均为 `CANDIDATE`。
- [ ] 系统可以主动生成和推荐 Product Direction，但不能自动将某个 Direction 标记为 `SELECTED`。
- [ ] 只有用户明确操作才能完成 `CANDIDATE → SELECTED`。
- [ ] 关键推荐理由具有可追溯 Evidence。

**Out of Scope**

- Evolution Plan。
- Working Copy。
- 自动选择 Product Direction。
- 自动修改代码。

---

### M3 — Evolution Planning & Working Copy

**Goal**

把用户已经选择的 Product Direction 转化为可执行的增量演化计划，并准备安全的代码演化环境。

**Deliverables**

#### Evolution Planning

- [ ] 支持确定 Base Software Asset。
- [ ] 实现最小 AssetUsagePolicy。
- [ ] 生成 Current State。
- [ ] 生成 Target State。
- [ ] 分析二者之间的 Gap。
- [ ] 识别 Reusable Capabilities。
- [ ] 识别 Required Changes。
- [ ] 生成按顺序组织的 Evolution Steps。
- [ ] 每个 Step 具有：
  - [ ] Goal
  - [ ] Scope
  - [ ] Planned Changes
  - [ ] Preconditions
  - [ ] Verification Criteria

#### Working Copy

- [ ] 根据 Base Software Asset 创建隔离 Working Copy。
- [ ] 保证 sourceRevision 与 Base Repository Profile 的 analyzedRevision 一致。
- [ ] 初始化 currentRevision。
- [ ] 初始化 lastVerifiedRevision。
- [ ] 将 Working Copy 与 Evolution Plan 建立绑定。
- [ ] 实现 PlanActivationPolicy。
- [ ] 支持 Evolution Plan 从 `PROPOSED → ACTIVE`。

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

- [ ] Working Copy 与原 Software Asset 相互隔离。
- [ ] Working Copy 的 sourceRevision 可以稳定追溯。
- [ ] `sourceRevision == analyzedRevision`。
- [ ] 创建 Working Copy 不得修改原 Software Asset。
- [ ] Plan ACTIVE 不代表其中所有 Step 已获得执行授权。

**Out of Scope**

- 自动执行所有 Evolution Steps。
- 无人值守长期 Coding Agent。
- 复杂 Recovery Strategy。

---

### M4 — First Verified Evolution Step

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

- [ ] 支持用户确认一个 Evolution Step。
- [ ] 实现 `PENDING_CONFIRMATION → READY`。
- [ ] 实现 StepExecutionPolicy。
- [ ] Step 首次执行前记录 baselineRevision。
- [ ] AI / Coding Capability 根据 Step Scope 产生代码修改方案。
- [ ] Workspace 在 Working Copy 中应用修改。
- [ ] 形成 Execution Result。
- [ ] 记录 affectedPaths / changes / warnings / errors。
- [ ] Execution 完成后形成 Candidate State。
- [ ] 支持运行 Build / Test / Diff 等 Verification。
- [ ] 形成 Verification Result。
- [ ] Verification Passed 后将 Step 标记为 `SUCCEEDED`。
- [ ] 成功后更新 Working Copy.lastVerifiedRevision。
- [ ] 保证后续 Step 只能建立在 Verified Revision 上。

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

- [ ] 未经用户确认不得修改代码。
- [ ] Execution Complete 不等同于 Step Success。
- [ ] Verification Passed 才允许推进 lastVerifiedRevision。
- [ ] 原 Software Asset 始终未被修改。

**Out of Scope**

本 Milestone 首先保证正常成功路径可运行。

完整 Failure / Repair / Retry / Recovery 流程进入下一 Milestone。

---

### M5 — Failure Safety & Recovery

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
| M0 | Project Foundation | IN_PROGRESS |
| M1 | Discovery Inputs | TODO |
| M2 | Product Direction Discovery | TODO |
| M3 | Evolution Planning & Working Copy | TODO |
| M4 | First Verified Evolution Step | TODO |
| M5 | Failure Safety & Recovery | TODO |
| M6 | MVP End-to-End Validation | TODO |

统一状态：

```text
TODO
IN_PROGRESS
BLOCKED
DONE
```

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
Pre-Implementation
→ M0 — Project Foundation
```

### Current

- [x] 完成并确认 ROADMAP.md 的 MVP Milestone 与主要技术决策。
- [x] 明确 MVP 第一阶段用户交互方式与最终产品形态。
- [x] 明确 Java / Python 在 MVP 中的职责边界。
- [x] 确定 Java 21 + Maven + Maven Wrapper 技术基线。
- [x] 确定 com.ayywl.delveforge Java Root Package。
- [x] 确定 Monorepo Repository Structure。
- [x] 确定 Backend 四模块 Maven Multi-Module Structure。
- [x] 确定 Feature-first Code Organization。
- [x] 确定 Frontend 技术栈：Vue 3 + TypeScript + Vite。
- [x] 确定 MVP Persistence 技术方案。
- [x] 确定 MVP Working Copy 默认技术方案。
- [x] 确定 Initial LLM Provider 与第一版默认模型。
- [x] 建立初始工程目录与 Maven Module。
- [ ] 建立 Application / Domain / Infrastructure 基础结构。
- [x] 建立 AI Gateway 最小抽象。
- [x] 建立 Workspace Gateway 最小抽象。
- [x] 建立基础 Persistence 能力。
- [ ] 建立 Frontend 基础工程。
- [ ] 建立统一配置、错误处理与日志基础设施。
- [ ] 建立 Build 与 Test 基础流程。

### Next

M0 完成后优先进入：

```text
M1 — Discovery Inputs
```

近期首先实现：

- [ ] User Profile 基础领域模型与状态转换。
- [ ] User Profile revision 与确认流程。
- [ ] 最小 User Discovery Use Case。
- [ ] Software Asset 注册。
- [ ] Local Repository 只读 Workspace 能力。
- [ ] Repository Profile 生成链路。

当前不提前将 M2 之后的所有实现细节拆分为 Task。

具体 Task 应在对应 Milestone 即将开始时，根据当时已有代码状态进一步拆分。

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

初始默认模型选择：

```
DeepSeek V4.1 Flash
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

- DeepSeek V4.1 Flash 具有较低的使用成本和较强的 Agent / Tool Use 能力。
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

例如：

```text
com.ayywl.delveforge.domain
├── user
├── asset
├── direction
└── evolution
com.ayywl.delveforge.application
├── userdiscovery
├── repositoryanalysis
├── opportunitydiscovery
├── evolution
└── port
    ├── ai
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

| Decision                                    | Current State | Target  |
| ------------------------------------------- | ------------- | ------- |
| AI Gateway 第一版具体 Adapter Design        | OPEN          | M0      |
| Workspace Gateway 第一版具体 Adapter Design | OPEN          | M0      |
| Initial Database Schema                     | OPEN          | M0 / M1 |
| Frontend / Backend Development API Contract | OPEN          | M0 / M1 |

这些事项属于接近具体实现时才能合理确定的设计，不阻止 Initial Project Structure 与 `AGENTS.md` 的建立。

Desktop Shell Technology 已明确延后，不属于 M0 Blocker。

如果其中任何未决事项实际阻止当前 Milestone 继续推进，再将其提升为正式 Blocker。

---

## 9. Completed Milestones

当前暂无 Completed Milestone。

完成 Milestone 后保留历史记录，不从 Roadmap 删除。

记录格式：

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