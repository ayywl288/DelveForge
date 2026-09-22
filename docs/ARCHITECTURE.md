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
- **AI / Agent Integration:** 业务模块只能依赖内部 AI Gateway 抽象；首个真实 Adapter 直接调用 DeepSeek 的 OpenAI 兼容 HTTP API，**不引入 Spring AI / Spring AI Alibaba**（[ADR-0003](decisions/0003-first-ai-adapter-uses-deepseek-http-api.md)）。
  - Adapter 使用 `RestClient`（`spring-web`）与 Jackson；两者本来就通过 `delveforge-app` 的 `spring-boot-starter-web` 存在于本项目，只是显式声明到真正使用它们的模块。
  - 不引入 AI Framework 的依据是当前需求与框架能力不匹配：只需要一次 `messages → JSON completion`，而 Tool Calling、多模型路由、Streaming 与 Agent 能力都在当前范围之外（AGENTS.md §8.3）。
  - **该选择被 AI Gateway Port 隔离**：更换 Provider 或改用 AI Framework 都只影响 Infrastructure 内的 Adapter。
  - 重新评估的条件见 ADR-0003：出现 Tool Calling / 多 Provider / Agent 需求，或升级到 Spring Boot 4.x 时。
  - 无论走哪条路线，业务模块都不得直接依赖具体 Provider SDK、Provider 专有 API 类型或 Spring AI Alibaba 实现类型。
- **Architecture Style:** Modular Monolith。
- **Database:** SQLite。
- **Persistence Access:** MyBatis-Plus。
- **Schema Migration:** Flyway。
- **Initial LLM Provider:** DeepSeek。
- **Initial Model:** Provider 为 DeepSeek API；默认 model id 为 `deepseek-flash`（配置默认值见 `delveforge-app` 的 `application.yml`）。具体 Model ID 与 Provider 参数必须配置化。
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
├── userdiscovery                 按职责分子包，见下
│   ├── profile                   创建 / 读取 / 结构化更新
│   ├── exploration               一轮输入 → AI 建议 → Profile 更新
│   ├── sufficiency               信息是否足够进入 Review
│   ├── review                    确认 / 继续探索 / 重新开启探索
│   ├── workflow                  组合成面向产品的一轮 User Discovery
│   └── shared                    跨子包协作单元（Application 内部）
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

### 6.2 Cross-Cutting Infrastructure

配置 / 错误处理 / 日志的约定如下。

**Configuration**

```
配置类命名     {Concern}Properties，使用 @ConfigurationProperties record
归属           由消费该配置的 Module 自己声明并通过 @EnableConfigurationProperties 启用
默认值         application.yml，不在代码中兜底
环境相关值     API Key / 数据库路径 / LLM Endpoint 与 Model / Workspace Root
               一律通过配置提供，不得硬编码进业务代码
```

技术配置按边界归属：SQLite / Provider 等专有配置留在 Infrastructure。
Domain / Application 不出现任何技术配置类型。

**Error Handling**

```
异常来源                                  HTTP       ApiErrorCode
IllegalArgumentException                 400        INVALID_REQUEST
UserProfileNotFoundException             404        NOT_FOUND
UserProfileStateException                409        CONFLICT
AiGatewayException                       502        EXTERNAL_CAPABILITY_UNAVAILABLE
WorkspaceException                       500        INTERNAL_ERROR
Spring MVC 协议层异常                      由框架决定   INVALID_REQUEST / INTERNAL_ERROR
  （405 / 415 / 400 请求体无法解析等）
其余未预期异常                             500        INTERNAL_ERROR
```

`UserProfileNotFoundException` 表示调用方的请求形态合法、但目标当前不存在，
与 `IllegalArgumentException`（请求本身不合法）区分开。

`UserProfileStateException` 是领域自己的状态冲突类型（例如对已经进入 REVIEWING 的
Profile 再次执行状态转移、修改 CONFIRMED 的 Profile）。**不要映射通用的
`IllegalStateException`**：它可能来自 JDK 或第三方库，一律当成 409 会把真正的
服务端故障伪装成可重试的客户端冲突。其它业务模块出现同类语义时各自提供自己的
异常类型，而不是共用一个通用基类。

映射集中在一处（`delveforge-app` 的 error 包），Controller 不承担异常分类与错误构造。

协议层异常的状态码与响应头由框架决定，映射层只替换响应体。
自行重建响应会丢失 `Allow`、`Accept` 等协议头。

响应体不携带异常 message、堆栈、原因链或框架生成的细节文本——
这些内容可能包含请求内容、用户数据或第三方 SDK 的原始返回。

**Logging**

```
日志格式      operation=<操作> path=<资源标识> result=<结果>
              exception=<异常类型链 + 堆栈位置>
默认级别      INFO；不使用 DEBUG 作为默认值
记录内容      异常类型链与堆栈位置（类名 / 方法名 / 文件名 / 行号）
不记录内容    异常 message 与原因链文本 —— 任何日志级别
固定级别的包  org.springframework.web / org.apache.tomcat / org.apache.coyote
禁止记录      API Key / Token / Credential、完整源码、完整 Prompt 或模型响应、成批用户数据
```

日志级别不是例外：`AGENTS.md` §8.8 禁止记录凭据的规则不区分级别，
因此不存在「DEBUG 下可以记录原始异常文本」的例外。

```
不记录异常文本的原因
    异常 message 与原因链可能是调用方输入、用户数据或第三方 SDK 的原始返回，
    无法在记录前可靠判定其中是否含凭据。
    异常类型链与堆栈位置是代码标识，不承载运行时数据，足以定位失败点。

不使用 DEBUG 作为默认级别的原因
    Mapper 位于 com.ayywl.delveforge.infrastructure.persistence，
    该包打开 DEBUG 会让 MyBatis 打印 SQL 与绑定参数值，其中可能包含用户数据。

固定框架包级别的原因
    这些包的 DEBUG 输出发生在应用代码之外，无法通过 @RestControllerAdvice 拦截：
      org.springframework.web     ExceptionHandlerExceptionResolver 输出
                                  "Resolved [<异常.toString()>]"
      org.apache.tomcat          DEBUG 下输出请求头，可能包含 Authorization
      org.apache.coyote
```

上述固定是最低保障，不是完整清单；引入新的技术组件时应确认其 DEBUG 输出内容。

本节规则的原因、代价与重新评估条件见 [ADR-0002](decisions/0002-structural-only-exception-logging.md)。

M0 不引入 ELK / OpenTelemetry / Tracing 等超出范围的观测能力。

### 6.3 Frontend / Backend 连接方式

**当前开发阶段采用的方式：**

```
Frontend 调用     使用相对路径 /api/...
转发              Vite dev server 将 /api 代理到本地 Backend
代理目标          .env.development 的 BACKEND_DEV_URL
Backend           不为开发环境开放 CORS
```

目的是避免 Frontend 源码绑定具体 Backend 地址：

```
BACKEND_DEV_URL 刻意不加 VITE_ 前缀
→ Vite 只把 VITE_ 前缀的变量注入浏览器端产物
→ 该地址只用于 dev server 代理配置，不进入前端代码
```

**正式运行形态尚未决定。** 以下问题都是开放的，随 Desktop Shell 一并确定：

```
Frontend 产物如何托管
API 地址在运行期如何解析
是否需要同源
与 Desktop Shell 如何集成
```

当前实现不排除任何一种运行期形态。候选方式包括但不限于：前端产物由 Backend 托管、
本地 web server、custom protocol、IPC、运行期注入 API base。

Desktop Shell 选型时必须重新评估这套连接模型。

除业务端点（见 6.5）外，Backend 另提供一个不承载业务语义的连通性端点：

```
GET /api/system/connectivity
→ { service, status, timestamp }
```

该端点不探测数据库或 Provider 等下游依赖，避免把连通性检查变成对下游可用性的隐式承诺。

### 6.4 Build / Test 验证入口

```
Backend    ./mvnw verify      编译 + 模块依赖校验 + 全部测试 + 打包
Frontend   npm ci && npm run build   类型检查 + 生产构建
```

两条命令链相互独立，都能在全新环境中重复执行，且不依赖真实 LLM、
开发数据库或用户本地数据。

测试按 Module 划分职责：

```
delveforge-domain           领域规则与状态语义，纯 JUnit
delveforge-application      Port 契约与 Use Case 编排，在 Port 边界使用 fake，不引入 Spring
delveforge-infrastructure   真实 SQLite / Flyway / MyBatis-Plus 集成
delveforge-app              上下文装配 / HTTP 端点 / 错误映射 / 配置绑定
```

需要数据库的测试使用 `target/test-databases/<uuid>/` 下的临时 SQLite 文件。

当前未引入 CI/CD、Coverage Gate、Failsafe 阶段拆分与 E2E 平台；
Frontend 也未引入 lint 与 test。引入时机见仓库根 `README.md`
与 `frontend/README.md`。

### 6.5 Backend API Contract（当前范围）

下表是当前已经实现的业务端点。它随对应业务能力一起扩展，
不为尚未实现的领域能力预先定义接口。

| Method  | Path                             | 说明                                                       | 成功 | 失败 |
| ------- | -------------------------------- | ---------------------------------------------------------- | ---- | ---- |
| `POST`  | `/api/user-profiles`             | 创建一个空的 `EXPLORING` User Profile                      | 201  | —    |
| `GET`   | `/api/user-profiles/{id}`        | 返回当前（最新）revision 的 Profile                        | 200  | 404  |
| `PATCH` | `/api/user-profiles/{id}`        | 结构化 partial update，返回更新后的 Profile                | 200  | 400 / 404 |
| `POST`  | `/api/user-profiles/{id}/explore`| 提交一轮用户自然语言输入，由 AI 提出建议后更新，返回更新后的 Profile | 200  | 400 / 404 / 502 |
| `POST`  | `/api/user-profiles/{id}/sufficiency-assessment` | 评估当前 Profile 信息是否足够进入 Review 阶段 | 200 | 404 / 502 |
| `POST`  | `/api/user-profiles/{id}/confirm` | 用户确认 Profile（`REVIEWING → CONFIRMED`），请求体须携带所依据的 revision | 200 | 400 / 404 / 409 |
| `POST`  | `/api/user-profiles/{id}/continue-discovery` | 用户选择继续探索（`REVIEWING → EXPLORING`） | 200 | 404 / 409 |
| `POST`  | `/api/user-profiles/{id}/reopen-discovery` | 用户重新开启探索（`CONFIRMED → EXPLORING`） | 200 | 404 / 409 |
| `POST`  | `/api/user-profiles/{id}/discovery-turn` | 一轮 User Discovery：提取 → 更新 → 评估 → 必要时进入 Review | 200 | 400 / 404 / 409 / 502 |
| `POST`  | `/api/software-assets`           | 登记一个 Software Asset（只登记元数据，不访问文件系统）     | 201  | 400  |
| `GET`   | `/api/software-assets/{id}`      | 读取已登记的资产元数据                                     | 200  | 404  |
| `POST`  | `/api/software-assets/{id}/analysis` | 分析该资产的 Repository，形成一份新的 Repository Profile | 201 | 404 / 409 / 502 |
| `GET`   | `/api/repository-profiles/{id}`  | 读取一份已保存的分析快照                                   | 200  | 404  |

**explore 语义**

```text
请求体    {"input": "<本轮用户输入的原文>"}
```

系统把「当前 Profile + 本轮输入」交给 AI Gateway，得到结构化 Proposal，再交给
Aggregate 决定是否接受。AI 只提出建议：

- 模型不能指定 Evidence 的来源：来源固定为本轮用户输入，`confirmed` 固定为 `false`；
- Proposal 里不合法的内容会被 Aggregate 拒绝，整个请求失败且不写入任何内容；
- `revision` 是否推进仍由 Domain 判断。

AI 调用失败或返回内容无法解析时返回 502，与其它外部能力失败一致。

**sufficiency-assessment 语义**

```text
请求体    无
响应体    {"sufficient": …, "missingAreas": [...], "nextQuestion": …, "profileStatus": "…"}
```

系统把「当前 Profile」交给 AI Gateway，得到一份充分性建议，再决定是否推进状态：

```text
insufficient   Profile 保持 EXPLORING，不写入、不推进 revision
sufficient     Application 调用 Domain 的状态转移，由 Domain 决定是否允许 EXPLORING → REVIEWING
```

模型输出里没有状态字段：AI 只表达「够不够、缺什么、下一问是什么」，
`profileStatus` 始终由 Domain 决定。模型输出自相矛盾（例如声明足够却列出缺失信息）
会被解析层拒绝，按 502 处理且不改变任何状态。

评估结果当前不持久化，每次调用都会重新评估。

**discovery-turn 语义**

面向产品的主要入口：一次请求完成一整轮 User Discovery。

```text
请求体    {"input": "<本轮用户输入的原文>"}
响应体    {"profile": {…}, "sufficient": …, "missingAreas": […], "nextQuestion": …}
```

```text
本轮用户输入
     ↓
提取      当前 Profile + 输入 → AI 提出建议 → 更新候选 Profile
     ↓
评估      对「本轮已经更新过的候选 Profile」判断信息是否足够
     ↓
insufficient  保存更新后的 Profile，返回 missingAreas + nextQuestion，仍为 EXPLORING
sufficient    执行 EXPLORING → REVIEWING，保存 Profile，返回 REVIEWING
```

- **`discovery-turn` 只能从 `EXPLORING` 开始**：`REVIEWING` 与 `CONFIRMED` 下调用直接 409，
  不调用 AI、不改动内容。这道校验由 Domain 提供
  （`UserProfile.requireExplorationAllowed()`）。若没有它，同一轮输入会因为模型恰好判断
  「足够」或「不足」而走向不同结局——「足够」时状态转移被拒、「不足」时内容被照常写入，
  等于让 AI 决定领域状态是否被接受。
- **一轮只保存一次**：整轮作用在同一个候选 Profile 上，只在最后写入。它不是把
  `explore` 与 `sufficiency-assessment` 串起来——那样会在探索结束后先落一次库，
  随后的评估失败就会留下「本轮只完成一半」的持久化状态。
- **评估针对更新后的候选 Profile**：用户这一轮说的信息必须先进入 Profile 再判断够不够。
- **失败原子性**：提取的 AI 调用/解析失败、内容更新被 Aggregate 拒绝、评估的 AI 调用/解析
  失败、`beginReview` 被拒绝——四个失败点都不会产生持久化副作用。
- **循环跨 HTTP 请求**：一轮返回 `nextQuestion`，调用方据此向用户提问，拿到回答后再发起
  下一轮。服务端不做 `while`、不等待用户、不保存对话记录。
- `sufficient` 为 true 时 `profile.status` 一定是 `REVIEWING`——由 Domain 决定，
  不是接口层写死的。

`explore` 与 `sufficiency-assessment` 保留为更细的步骤入口；`discovery-turn` 是它们的组合。
`explore` 保持原有行为：它只做提取与更新，`REVIEWING` 下仍可调用（状态保持不变）；
`REVIEWING` 起点限制只加在「整轮」入口 `discovery-turn` 上。

**Review / Confirm 语义**

Review 阶段复用已有的 `GET`：它已经返回完整的内容、`status` 与 `revision`，
不为「Review」另建一套读取模型。用户在这个界面上可以做三件事：

```text
PATCH  {id}                修正内容      状态保持 REVIEWING；实际变化才推进 revision
POST   {id}/continue-discovery  继续探索  REVIEWING → EXPLORING
POST   {id}/reopen-discovery    重新探索  CONFIRMED → EXPLORING
POST   {id}/confirm             确认      REVIEWING → CONFIRMED
       请求体 {"revision": <用户查看时所依据的 revision>}
```

- 三个动作都是**人类决策**，只能由调用方的显式请求触发；AI 流程（`explore`、
  `sufficiency-assessment`）没有通往 `CONFIRMED` 的路径。
- **确认必须绑定用户实际查看的版本。** 确认的意义是「用户同意了这一版内容」，
  而不是「确认服务端当前碰巧是什么版本」。请求体缺少 `revision` 是请求形状错误（400）；
  `revision` 与当前 revision 不一致说明内容在用户查看之后又变化过，此时确认不再代表
  用户的真实决定，返回 409 且不写入任何内容。继续探索与重新探索不表达对内容的认可，
  因此不需要绑定 revision。
- 状态转换本身不推进 `revision`（§6.1 的 Revision 触发规则只覆盖内容与 Evidence）。
  因此确认结果是「`CONFIRMED` @ 确认时的 `revision`」：该 revision 的内容快照已由
  Persistence 保留，确认之后内容修改被拒绝，这个组合构成 Product Direction Discovery
  可以稳定引用的基线。
- 非法起始状态一律 409 `CONFLICT`（例如在 `EXPLORING` 直接确认、在 `CONFIRMED`
  再次确认），不会落到 500。
- `UserProfileConfirmed`（`DOMAIN_MODEL.md` §9.1）是这一事实的领域表达。当前不引入
  事件机制，该语义由「状态被持久化 + revision 被保留」承载；事件如何传播仍属于
  §14.10 的未决问题。

**PATCH 语义**

```text
字段缺失或为 null   本次不更新该区，保持原值
字段存在（含 []）   该值是该区更新后的完整内容，整区替换——不是追加 / 合并
additionalEvidence  逐条新增的 Evidence，不是替换整个集合
```

`revision` 由 Domain 决定：只有实际内容发生变化才推进，接口不计算也不保证递增次数。
`status` 当前只读，状态转换尚未实现。

本接口不提供历史 revision 查询，`GET` 始终返回当前 revision。
历史内容快照由 Persistence 保留，等出现真实消费者时再决定以何种形式暴露。

**响应体**

```json
{
  "id": "...",
  "status": "EXPLORING",
  "revision": 1,
  "interests": [],
  "behaviors": [],
  "painPoints": [],
  "technicalCapabilities": [],
  "projectGoals": [],
  "constraints": [],
  "evidence": [
    {
      "sourceType": "USER_INPUT",
      "sourceRef": "...",
      "claim": "...",
      "confidence": null,
      "confirmed": true
    }
  ]
}
```

`status` 与 `sourceType` 使用 Domain 的取值，接口层不引入同义词
（RULE-DOM-001）。未知取值会被反序列化拒绝，并映射为 400 `INVALID_REQUEST`。

DTO 定义在 `delveforge-app` 的 `api` 包，不暴露 Persistence 数据对象。

**Software Asset 注册语义**

```text
POST /api/software-assets
{
  "location": "...",
  "readPermissionAllowed": true,       必须显式给出
  "licenseInfo": "MIT",                可选，null 表示未知
  "usageAuthorization": "UNCLEAR"      必须显式给出，且只能用取值名称
}
```

- **`readPermissionAllowed` 必须显式给出。** 缺省它不会被当成 `false`——那等于用缺失的
  输入制造出一个「不允许读取」的授权事实（RULE-DOM-004）。字段缺失或为 `null` 一律 400，
  且不写入资产。
- **枚举只能用取值名称。** Jackson 默认允许用数字表示枚举（按序号），那会把
  `"usageAuthorization": 0` 静默解释成第一个取值，凭空产生一个授权事实。
  `spring.jackson.deserialization.fail-on-numbers-for-enums` 关闭了这种强转。
- 资产的标识、类型与来源都由服务端决定：请求体里给出这些字段不会生效。

**Repository Analysis 语义**

```text
POST /api/software-assets/{id}/analysis     请求体：无
响应体：RepositoryProfile（见下）
```

分析针对的东西全部由服务端决定，客户端不能指定：

```text
analyzedRevision   服务端解析一次 HEAD，之后所有读取固定在这个 commit 上
repository material 按 Application 层选材策略从该 revision 的已提交内容中读取
Evidence           来自真实读到的文件，sourceRef 是材料中的相对路径
```

- **请求体不参与分析。** 客户端即使发送 `analyzedRevision`、`evidence` 或材料字段也不会
  被读取：它们不是这个端点的输入，服务端不会把客户端提供的值当作分析结果。
- **登记不校验位置。** `location` 只要求非空，任何取值都能登记成功；位置是否真的可用
  在分析时才见分晓，那时返回 409 而不是 400——登记请求与分析请求本身都没有写错。
- **每次调用产生一份新的快照**，不覆盖已有快照（`DOMAIN_MODEL.md` §10.4）。
  需要重新分析时再调用一次该端点，旧快照仍然可用。
- **失败不留下快照**：资产校验、仓库可读性、revision 解析、材料读取、AI 提取、
  领域创建任一失败都以异常结束，此时没有任何写入。
- **409 表示资产当前不能被分析**，与请求写法无关，调用方改请求也不会成功：

  ```text
  资产自身的 readPermission 不允许读取（INV-A01）
  资产位置当前不是可读取的本地 Git Repository
  该 Repository 在选材策略下没有可分析的材料
  ```

  三者都与「请求不合法」（400）区分开。Workspace 自身的操作失败（例如环境里找不到
  `git`）仍按本地能力失败处理，返回 500。
- AI 调用失败或模型输出无法解析时返回 502，不写入任何内容。
- 分析是只读的：它只通过 `WorkspaceReadPort` 读取，不修改源 Repository 的代码或 Git 状态。

**RepositoryProfile 响应体**

```json
{
  "id": "...",
  "assetId": "...",
  "analyzedRevision": "abc123...",
  "purpose": "...",
  "techStack": [],
  "modules": [],
  "capabilities": [],
  "reusableAssets": [],
  "limitations": [],
  "risks": [],
  "evidence": [
    { "sourceType": "REPOSITORY", "sourceRef": "pom.xml", "claim": "...",
      "confidence": null, "confirmed": false }
  ]
}
```

`analyzedRevision` 与内容属于同一个快照，一起返回；`evidence` 的 `sourceRef` 是
Repository 内相对于根目录的路径，接口不暴露宿主机文件系统布局。
`confirmed` 固定为 `false`、`confidence` 为 `null`：模型得出的结论属于系统推断，
其可信程度的口径尚未由领域模型规定。

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
- Workspace 的只读能力与代码修改能力在 Application 层拆分为两个 Port，使上述限制在类型层面成立：只读流程的依赖中不存在修改能力，而不是仅靠调用约定保证。取舍、已知缺口与重新评估条件见 [ADR-0001](decisions/0001-separate-workspace-read-and-mutation-capabilities.md)。
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

---

## 9. Architecture Decisions

重大且长期的架构决策记录在 `docs/decisions/`，本节只维护索引。

| ADR | Decision | Status |
| --- | --- | --- |
| [0001](decisions/0001-separate-workspace-read-and-mutation-capabilities.md) | Workspace 读写能力在 Application 边界拆分 | Accepted |
| [0002](decisions/0002-structural-only-exception-logging.md) | 异常日志只记录结构信息，防止运行时数据泄漏 | Accepted |
| [0003](decisions/0003-first-ai-adapter-uses-deepseek-http-api.md) | 首个 AI Adapter 直接调用 DeepSeek HTTP API，不引入 Spring AI | Accepted |

以下事项经过评审后**决定不建立 ADR**，结论保留在本文件或 `docs/ROADMAP.md` 中：

```text
AI Gateway 边界形态         「边界该不该存在、长什么样」已被 AGENTS.md RULE-DOM-003、
                            DOMAIN_MODEL 12.12 与 AiGateway javadoc 覆盖；
                            「首个真实 Adapter 走哪条技术路线」由 ADR-0003 记录

Frontend / Backend 连接方式  开发期实现细节，见 6.3
```

已延后、尚未形成决策的事项见 `docs/ROADMAP.md` §8.7。
