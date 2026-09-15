# ARCHITECTURE.md

> 本文档回答：系统准备如何实现，以及最重要的架构选择是什么。

**Status:** Stable
**Last Updated:** 2026-9-15

## 1. Architecture Goals

| Priority | Quality Attribute | Goal                                                         |
| -------- | ----------------- | ------------------------------------------------------------ |
| 1        | Modularity        | 用户探索、仓库分析、方向发现、演化执行能够独立演进           |
| 2        | Traceability      | Product Direction / Evolution Plan 能够追溯其依据的 User Profile、Repository Profile 和分析结果 |
| 3        | Safety            | Repository Analysis 对原始 Software Asset 保持只读；Evolution Execution 的代码修改必须限制在与当前 Evolution Plan 绑定的受控 Working Copy 内 |
| 4        | Verifiability     | 每个 Evolution Step 都能够产生明确的执行结果，并通过 build / test / diff 等方式验证 |
| 5        | Evolvability      | MVP 可以先采用简单架构，同时允许未来替换 LLM、Repository Analyzer 或 Coding Agent 实现 |
| 6        | Compliance        | 系统能够记录 Software Asset 的来源、许可证与使用授权信息，并在资产是否允许二次开发无法确认时给出风险提示或限制自动改造 |
| 7        | Transparency      | User Profile、Repository Profile 等关键中间产物对用户可见，并允许用户检查和纠正 |

---

## 2. Architecture Constraints

### 2.1 Project Constraints

- 当前主要由单人开发。
- MVP 优先完成完整闭环，而不是建设复杂基础设施。
- 当前 Software Asset 主要是本地 Git Repository。
- 系统需要读取用户提供的本地 Git Repository，并在隔离的 Working Copy 中执行允许的代码修改。
- 原始 Software Asset 不作为 Evolution Execution 的直接修改目标。
- MVP 不考虑多人协作。
- MVP 不考虑云端代码托管。
- MVP 不考虑长期自主运行的 Coding Agent。

### 2.2 Technology Constraints

- **Core Language / Runtime:** Java 21。MVP 核心业务、Application、Domain、AI Gateway、Workspace Gateway 与 Persistence 均以 Java 实现。
- **Optional Tool Language:** Python 不属于 MVP 必需 Runtime。未来只有在特定 Analyzer / Tool 明确依赖 Python 生态时，才允许作为独立 Tool Process 引入。
- **Application Framework:** Spring Boot 3.5.16。
- **Build Tool:** Maven + Maven Wrapper。
- **Root Java Package:** `com.ayywl.delveforge`。
- **Repository Strategy:** Monorepo。
- **Backend Module Structure:** Maven Multi-Module，初始拆分为 `delveforge-domain`、`delveforge-application`、`delveforge-infrastructure`、`delveforge-app`。
- **Code Organization:** Maven Module 负责主要 Architecture Layer Boundary，各 Module 内部优先按照 Feature / Domain Concept 组织代码。
- **AI / Agent Integration:** 优先基于 Spring AI 1.1.2 / Spring AI Alibaba 1.1.2.2 能力实现，但业务模块只能依赖内部 AI Gateway 抽象。
  - 版本线仍保持在 Spring Boot 3.5.x，不切换到 Spring Boot 4.x / Spring AI 2.x。原因是 Spring AI Alibaba 稳定版线只在 3.5.x 上提供，切换到 4.x 会使 AI Gateway 依赖 milestone 版本。
  - 后续 AI Gateway 的 Adapter 同样必须保持内部抽象边界：业务模块不得直接依赖具体 Provider SDK 或 Spring AI Alibaba 实现类型。
- **Architecture Style:** Modular Monolith。
- **Database:** SQLite。
- **Persistence Access:** MyBatis-Plus。
- **Schema Migration:** Flyway。
- **Initial LLM Provider:** DeepSeek。
- **Initial Model:** DeepSeek V4.1 Flash；具体 Model ID 与 Provider 参数必须配置化。
- **Working Copy:** 基于用户本地 Git Repository 创建独立 Local Git Clone，并从 Repository Profile 对应的 `analyzedRevision` 建立演化基线。
- **Frontend:** Vue 3 + TypeScript + Vite。
- **MVP User Interaction:** 开发与核心 MVP 验证阶段采用 localhost Web UI + Local Java Backend。
- **Final Product Form:** Local-first Desktop Application。
- **Desktop Shell:** Deferred。待 Core MVP 稳定后再选择具体技术，不作为当前 M0 Blocker。
- **Deployment:** MVP 优先本地运行。

---

## 3. System Context

描述系统外部世界。

```mermaid
flowchart LR
    User["User"]
    System["DelveForge"]
    Repo["Local Git Repository"]
    LLM["LLM Provider"]
    Tools["Build / Test / Git / Shell"]

    User --> System
    System --> Repo
    System --> LLM
    System --> Tools
```

---

## 4. Solution Strategy

```text
系统第一版采用单体应用组织核心业务能力，
内部按照用户理解、仓库理解、方向探索、演化规划和演化执行进行模块划分。

LLM 不直接承担系统状态管理，
结构化 User Profile、Repository Profile、Product Direction、Evolution Plan 和 Evolution Step 由系统维护。

所有 LLM 调用通过统一的 AI Gateway / Model Adapter 访问，
避免业务模块直接依赖具体模型供应商。

Repository Analysis
通过受控 Workspace 接口只读访问用户提供的本地 Repository，
不得修改原始 Software Asset。

进入 Evolution Execution 前，
系统基于 Evolution Plan 对应的 Base Software Asset 与确定 Revision
准备独立 Working Copy。

Evolution Execution
通过 Workspace 访问并修改该 Evolution Plan 对应的 Working Copy，
不得直接修改原始 Software Asset。

代码分析与修改分离：
分析阶段只读取原始 Repository；
执行阶段只有在用户确认具体 Evolution Step 后，
才允许在对应 Working Copy 中获得受限写能力。

每一个 Evolution Step 执行完成后，
必须进入 Verification 流程，
通过构建、测试、Diff 或其他验证方式确认结果。

未经 Verification 的 Candidate State
不得成为后续 Evolution Step 的可信执行基线。
```

---

## 5. High-Level Architecture

描述主要运行单元。

```mermaid
flowchart LR
    UI["Frontend UI<br/>Vue / Future Desktop Shell"]
    API["Interface / API"]
    APP["Application / Use Case Orchestration"]

    USER["User Discovery"]
    REPO["Repository Analysis"]
    DISCOVERY["Opportunity Discovery"]

    subgraph EVO["Evolution Module"]
        PLAN["Planning"]
        EXEC["Execution"]
        VERIFY["Verification"]

        EXEC --> VERIFY
    end

    LLM["AI Gateway"]
    WS["Workspace Gateway"]
    PERSIST["Persistence Adapter"]

    PROVIDER["External LLM"]
    LOCAL["Local Repository / Working Copy"]
    TOOLS["Git / Build / Test / Shell"]
    DB["SQLite"]

    UI --> API
    API --> APP

    APP --> USER
    APP --> REPO
    APP --> DISCOVERY
    APP --> PLAN
    APP --> EXEC

    USER --> LLM
    REPO --> LLM
    DISCOVERY --> LLM
    PLAN --> LLM
    EXEC --> LLM

    REPO --> WS
    EXEC --> WS
    VERIFY --> WS

    APP --> PERSIST

    LLM --> PROVIDER
    WS --> LOCAL
    WS --> TOOLS
    PERSIST --> DB
```

## 6. Module Design

| Module                | Responsibility                                | Does NOT             |
| --------------------- | --------------------------------------------- | -------------------- |
| User Discovery        | 对话探索用户并维护 User Profile               | 不负责推荐项目       |
| Repository Analysis   | 分析 Repository 并形成 Repository Profile     | 不修改代码           |
| Opportunity Discovery | 根据 User + Repository 发现 Product Direction | 不修改代码           |
| Evolution             | 生成 Evolution Plan、组织执行并验证           | 不直接访问模型供应商 |
| Workspace             | 文件、Git、Build、Test、Shell 等本地能力      | 不进行产品决策       |
| AI                    | 提供统一 LLM 访问能力                         | 不维护业务状态       |

### 6.1 Initial Project Structure

DelveForge 使用 Monorepo 管理 Backend、Frontend、Documentation 与未来 Desktop Integration。

```
DelveForge/
│
├── pom.xml
├── mvnw
├── mvnw.cmd
├── .mvn/
│
├── backend/
│   ├── delveforge-domain/
│   ├── delveforge-application/
│   ├── delveforge-infrastructure/
│   └── delveforge-app/
│
├── frontend/
├── docs/
├── AGENTS.md
└── README.md
```

Backend 使用四个 Maven Module。

```
delveforge-domain
    ↑
delveforge-application
    ↑
delveforge-infrastructure

delveforge-app
    ├── depends on delveforge-application
    └── depends on delveforge-infrastructure
```

职责如下：

| Maven Module                | Responsibility                                               |
| --------------------------- | ------------------------------------------------------------ |
| `delveforge-domain`         | Aggregate、Entity、Value Object、Domain Service、Domain Policy、Domain Event 与核心 Invariant |
| `delveforge-application`    | Use Case、Application Service、Orchestration，以及 AI / Workspace / Persistence Port |
| `delveforge-infrastructure` | DeepSeek、Persistence、Git、Filesystem、Shell、Build / Test 等外部能力 Adapter |
| `delveforge-app`            | Spring Boot Bootstrap、REST API、Configuration、Dependency Wiring 与 Interface Adapter |

Module Dependency Rules：

- `delveforge-domain` 不依赖其他 DelveForge Maven Module。
- `delveforge-application` 可以依赖 `delveforge-domain`。
- `delveforge-infrastructure` 可以依赖 `delveforge-application` 与 `delveforge-domain`。
- `delveforge-app` 负责组装 Application 与 Infrastructure。
- Domain / Application 不得通过间接方式访问具体 LLM Provider、Database、Git、Shell 或 Filesystem 实现。
- 不为每个 Architecture Module 建立独立 Maven Module。
- 不创建无明确业务语义的通用 `delveforge-common` Module。

Persistence 基础结构：

```
数据库位置     配置属性 delveforge.persistence.database-file
               （默认值与覆盖方式见 delveforge-app 的 application.yml）
DataSource     delveforge-infrastructure 的 SqliteDataSourceConfiguration
Mapper 位置    com.ayywl.delveforge.infrastructure.persistence 及其子包
Mapper 装配    由 delveforge-app 的 Composition Root 扫描
Migration      delveforge-infrastructure 的 src/main/resources/db/migration
               命名 V{版本}__{描述}.sql，已发布的迁移不可修改
```

SQLite、MyBatis-Plus 与 Flyway 只允许出现在 `delveforge-infrastructure` 内。
Domain / Application 不得出现这些技术类型的依赖。

具体业务表随 M1 及之后的对应领域对象以新增迁移的方式引入。
`delveforge-domain` / `delveforge-application` 不提供通用 Repository 抽象。

Maven Module 控制主要 Architecture Layer Boundary。

每个 Module 内部继续按照 Feature / Domain Concept 组织代码。

例如：

```
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

com.ayywl.delveforge.app
├── api
├── config
└── error
```

Architecture Module 与 Maven Module 不要求一一对应。

例如 User Discovery、Repository Analysis、Opportunity Discovery 与 Evolution 是业务 / 架构边界，但 MVP 中不分别建立独立 Maven Module。

---

## 7. Dependency Rules

本节描述代码模块之间允许存在的依赖方向，而不是系统运行时的调用顺序。

```
                    ┌──────────────> AI
                    │
User Discovery ─────┤

Repository Analysis ┼──────────────> AI
        │
        └──────────────────────────> Workspace (Read Only)

Opportunity Discovery ─────────────> AI

Evolution ──────────┬──────────────> AI
                    │
                    └──────────────> Workspace
```

### Rules

- 业务模块不得直接依赖具体 LLM Provider SDK，所有模型调用必须通过 AI 模块提供的抽象接口。
- 业务模块不得直接调用 Shell、Git、Build Tool 或直接操作本地文件系统，相关能力统一通过 Workspace 模块提供。
- Repository Analysis 只能依赖 Workspace 的只读能力，不得获得代码写入、删除或修改权限。
- Opportunity Discovery 不得直接修改 Repository，也不应依赖 Workspace 的写能力。
- Evolution Execution 是 MVP 中唯一允许请求 Workspace 写能力的业务流程。
- Workspace 的只读能力与代码修改能力在 Application 层拆分为两个 Port，使上述限制在类型层面成立：只读流程的依赖中不存在修改能力，而不是仅靠调用约定保证。
- Application / Agent Orchestrator 可以协调各业务模块，但业务模块不得反向依赖 Orchestrator。
- 跨模块协作应通过公开接口和明确的数据模型完成，不得通过直接读取或修改其他模块拥有的数据库表实现。
- 禁止循环依赖。

---

## 8. Runtime Flows

> Runtime Flow 为突出核心业务协作，省略 Interface / API 层的协议解析过程，请求均实际先经过对应 Interface Adapter 后进入 Application 层。

### 8.1 User Discovery

```mermaid
sequenceDiagram
    participant U as User
    participant O as Orchestrator
    participant UD as User Discovery
    participant AI as AI Gateway

    U->>O: Start Discovery / Send Message
    O->>UD: Process User Input

    loop Until profile information is sufficient
        UD->>AI: Analyze current conversation and profile draft
        AI-->>UD: Missing information / Next question

        UD-->>O: Next Question
        O-->>U: Ask Question

        U->>O: Answer
        O->>UD: Process Answer
    end

    UD->>AI: Generate Structured User Profile
    AI-->>UD: User Profile

    UD-->>O: Draft User Profile
    O-->>U: Show Profile for Review

    alt User requests correction
        U->>O: Correct Profile
        O->>UD: Update Profile
        UD->>AI: Refine Profile
        AI-->>UD: Updated User Profile
        UD-->>O: Updated Profile
        O-->>U: Show Updated Profile
    else User confirms
        U->>O: Confirm Profile
    end
```

### 8.2 Repository Analysis & Opportunity Discovery

> MVP 中 Repository Source 仅支持用户选择的本地 Git Repository。Repository Analysis 不负责 Repository 的发现与搜索；未来可在其上游增加 GitHub 等 Software Asset Discovery 能力，而无需改变 Repository Analysis 的核心职责。
>
> Opportunity Discovery 不要求用户额外发起“生成 Product Direction”的请求。当 Confirmed User Profile 与至少一个可用 Repository Profile 均已准备完成后，Application / Orchestrator 可以自动进入 Product Direction Discovery。
>
> 系统可以主动发现和生成 Product Direction，但 Product Direction 的最终选择仍然必须由用户完成。

```mermaid
sequenceDiagram
    participant U as User
    participant O as Orchestrator
    participant RA as Repository Analysis
    participant OD as Opportunity Discovery
    participant AI as AI Gateway
    participant WS as Workspace

    U->>O: Select Local Repository

    O->>RA: Analyze Repository
    RA->>WS: Read Repository Structure / Files
    WS-->>RA: Repository Content

    RA->>AI: Analyze Repository
    AI-->>RA: Structured Repository Profile

    RA-->>O: Repository Profile
    O-->>U: Show Repository Profile

    O->>O: Check Product Discovery Inputs

    alt User Profile not confirmed or Repository Profile unavailable
        O-->>O: Wait for required discovery inputs
    else Discovery inputs ready
        O->>OD: Generate Directions with Confirmed User Profile + Repository Profile
        OD->>AI: Discover Opportunities
        AI-->>OD: Direction Proposals
        OD-->>O: Candidate Product Directions
        O-->>U: Show Directions + Evidence
    end
```

### 8.3 Evolution

```mermaid
sequenceDiagram
    participant U as User
    participant O as Orchestrator
    participant EP as Evolution Planning
    participant EE as Evolution Execution
    participant V as Verification
    participant AI as AI Gateway
    participant WS as Workspace

    U->>O: Select Product Direction

    O->>EP: Generate Evolution Plan
    EP->>AI: Analyze Current State → Target State
    AI-->>EP: Evolution Plan / Steps
    EP-->>O: Evolution Plan

    O-->>U: Show Evolution Plan

    O->>WS: Prepare Working Copy from Base Asset @ analyzedRevision
    WS-->>O: Working Copy Ready

    O->>O: Validate Preconditions and Activate Evolution Plan

    U->>O: Confirm Evolution Step

    O->>EE: Execute Confirmed Step
    EE->>AI: Generate / reason about code changes
    AI-->>EE: Proposed Changes

    EE->>WS: Apply Code Change to Working Copy
    WS-->>EE: Change Result

    EE->>V: Verify Step
    V->>WS: Build / Test / Diff on Working Copy
    WS-->>V: Verification Result
    V-->>EE: Pass / Fail

    EE-->>O: Execution Result + Verification Result
    O-->>U: Show Result
```

该流程只展示 Evolution 的主要 Runtime Collaboration。

其中以下领域语义仍以 `DOMAIN_MODEL.md` 为准：

```text
Working Copy Preparation
Plan Activation
baselineRevision
Candidate State
lastVerifiedRevision
Verification Failure
Repair
Retry
Rollback
Recovery
UNRECOVERABLE
```

特别是：

```text
Generate Evolution Plan
        ↓
Prepare Working Copy
        ↓
Activate Evolution Plan
        ↓
Confirm Evolution Step
        ↓
Execute
        ↓
Verify
```

是进入实际代码修改的必要顺序。

Evolution Plan 被生成并不意味着系统已经获得代码修改权限。

只有：

```text
Evolution Plan = ACTIVE
+
Working Copy available
+
Evolution Step explicitly confirmed by user
```

时，Evolution Execution 才允许请求 Workspace 的代码修改能力。
