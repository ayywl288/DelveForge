# ADR-0003: First AI Adapter Calls the DeepSeek HTTP API Directly

Status: Accepted

Date: 2026-09-17

## Context

DelveForge 需要一个真实 AI Adapter，让 User Discovery 能够把「当前 User Profile + 本轮用户输入」
交给 LLM，得到结构化的 Profile Proposal。

`ROADMAP.md` §8.7 把「AI Framework 路线与版本基线」列为 **DEFERRED**，并明确要求：

> 首次实现真实 AI Adapter **之前**，必须评估 Spring AI Core 是否已足够、是否确实需要
> Spring AI Alibaba 的额外能力、以及当时的生态是否稳定。

`ARCHITECTURE.md` §2.2 记录的候选组合（Spring AI 1.1.2 / Spring AI Alibaba 1.1.2.2 +
Spring Boot 3.5.x）自述为**临时兼容性基线，且从未在项目中验证**——当时代码库中不存在任何
Spring AI 依赖，也没有真实 Adapter。

因此需要决定的是：

> 第一个真实 AI Adapter 走哪条技术路线。

已经确定、不因本 ADR 改变的前提：

```text
业务模块只依赖 application.port.ai.AiGateway（RULE-ARCH-008）
AiGateway.generate(AiRequest) → String，返回未经解析的原始内容
解析与校验由 Application 完成（RULE-DOM-003）
Provider 专有的 Endpoint / Model / 凭据属于配置（AGENTS.md §8.9）
```

## Options Considered

### Option A — Spring AI 1.1.x（`spring-ai-starter-model-deepseek`）

Pros:

- Spring 官方维护的 LLM 集成，自带 DeepSeek 原生 Client
- `ChatClient`、结构化输出转换、重试、可观测性开箱可用
- 将来接入 Tool Calling / Agent 能力时改动更小

Cons:

- 在本项目自己的 `AiGateway` 之下再叠一层框架；当前只需要一次 completion 调用
- 引入 `spring-ai` 核心与其传递依赖（含 web / reactor），需要在 Infrastructure 内隔离 auto-configuration
- **版本线受限**：Spring AI 2.0.x（当前 2.0.1）要求 Spring Boot 4.x / Spring Framework 7，
  而项目基线是 Spring Boot 3.5.16。选择 Spring AI 就等于选择 1.1.x，将来升级 Boot 4 时
  需要再迁移一次
- DeepSeek 只支持 `{"type":"json_object"}`，不支持 OpenAI 的严格 `json_schema`，
  响应结构与校验无论如何都要自己写，框架并不能免除这部分

### Option B — Spring AI Alibaba 1.1.2.x

Pros:

- 在 Option A 基础上提供 Agent / Graph / Multi-Agent / Voice 等能力

Cons:

- 本 Task 明确排除 Tool Calling、多模型路由与 Agent Framework，额外能力全部用不到
- 引入第二个 vendor 层与其发布节奏，且稳定线同样停在 Spring Boot 3.5.x
- 违反 AGENTS.md §5.1「不添加当前验收标准不需要的基础设施」

### Option C — 直接调用 DeepSeek 的 OpenAI 兼容 HTTP API

当前选择。

```text
DeepSeekAiGatewayAdapter
    ↓
POST {base-url}/chat/completions
    response_format: {"type": "json_object"}
    ↓
choices[0].message.content
```

实现使用 `spring-web` 的 `RestClient` + Jackson。

Pros:

- 与当前真实需求严格对应：一次 `messages → JSON completion`
- 不引入框架层；Provider 细节集中在 Adapter 内
- 两个新增依赖（`spring-web`、`jackson-databind`）本来就通过 `delveforge-app` 的
  `spring-boot-starter-web` 存在于本项目，不是新引入的库家族
- 不受 Spring AI 的版本线约束，不与 Spring Boot 4 升级绑在一起
- 请求构造与响应解析都可以用 `MockRestServiceServer` 在真实 HTTP 边界上测试

Cons:

- 超时、错误翻译、响应结构解析需要自己实现
- 将来需要 Tool Calling / Agent 能力时，这条路线需要重新评估

## Decision

DelveForge MVP 的第一个真实 AI Adapter 采用 **Option C**：直接调用 DeepSeek 的 OpenAI 兼容
HTTP API，不引入 Spring AI 或 Spring AI Alibaba。

该选择被 `AiGateway` Port 隔离：业务模块只看到 `AiGateway` 与 `AiRequest` / `AiMessage`，
不知道 Provider、Endpoint 或响应结构。更换 Provider 或改用 AI Framework 都只需要替换
Infrastructure 内的 Adapter。

## Rationale

选择 Option C 而不是 A / B，核心依据是**当前需求与框架能力不匹配**：

```text
当前需要        一次 messages → JSON completion
框架提供        ChatClient / Advisor / Tool / Memory / RAG / Observability
```

AGENTS.md §8.3 要求「除非当前需求证明必要，否则不引入通用框架」。本 Task 的 Out of Scope
已经明确排除 Tool Calling、多模型路由、Streaming 与 Agent 能力，因此框架带来的复杂度
在当下没有对应收益。

选择 Option C 而不是 A 的第二个依据是**版本风险**：Spring AI 2.x 要求 Spring Boot 4.x，
而项目基线是 3.5.16。现在引入 Spring AI，就是把 AI 依赖绑在 1.1.x 这条与 Boot 3.5 同时
到期的线上；而 `spring-web` 与 Jackson 属于长期稳定、与 AI 生态解耦的库。

第三个依据是**框架无法免除的工作**：DeepSeek 不支持严格 `json_schema`，结构化输出的
解析与校验必须由 DelveForge 自己完成——这本来就是 `AiGateway` 契约里划给 Application
的职责（RULE-DOM-003）。引入框架并不能减少这部分代码，只会增加一层需要隔离的抽象。

## Consequences

### Positive

- Infrastructure 内只有约一个类的 Provider 细节，没有需要隔离的 auto-configuration
- AI 依赖不绑定 AI 框架的发布节奏，也不与 Spring Boot 4 升级耦合
- `MockRestServiceServer` 可以在真实 HTTP 边界验证请求形状与响应解析，
  自动化测试完全不接触真实 LLM
- Port 边界保持不变，将来改用 Spring AI 时业务代码零改动

### Negative

- 超时、错误翻译与响应解析由项目自己维护
- 失去了框架提供的可观测性、重试与 Token 统计
- 未来接入 Tool Calling 时，要么扩展本 Adapter，要么重新评估引入框架

### Risks

- **没有真实 DeepSeek 端点上的联调验证。** 自动化测试全部使用 HTTP 替身，
  真实 Provider 的响应形状变化只有在实际调用时才会暴露。
- **`confidence` 不由模型提供。** 领域模型尚未规定其数值口径，本 Task 让 Adapter 与
  Application 都不臆造该值，Evidence 的 `confidence` 恒为空。
- 模型仍然可能给出无关或重复的建议；Domain 会拒绝不合法的内容，
  但「建议质量」本身没有自动化保障。

## Revisit Conditions

```text
出现 Tool Calling / Function Calling 需求时
  → 重新评估：扩展本 Adapter，还是引入 AI Framework

出现多模型路由、多 Provider 并存需求时
  → 重新评估 Provider 抽象与配置模型

需要 Agent / Graph / 工作流编排能力时
  → 重新评估 Spring AI Alibaba

升级到 Spring Boot 4.x 时
  → 重新评估 Spring AI 2.x 是否已经稳定，以及是否值得切换
    （此时原先的版本线阻碍已经消失）

DeepSeek 响应结构或鉴权方式发生破坏性变化时
  → 只需修改 Adapter；若变化频繁到需要额外抽象，再重新评估
```

## References

```text
docs/ARCHITECTURE.md §2.2、§9
docs/ROADMAP.md §8.7
AGENTS.md RULE-ARCH-008、§8.3、§9
application.port.ai.AiGateway
infrastructure.ai.DeepSeekAiGatewayAdapter
infrastructure.ai.DeepSeekAiGatewayConfiguration
```
