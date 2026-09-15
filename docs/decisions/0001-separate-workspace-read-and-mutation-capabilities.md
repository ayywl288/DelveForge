# ADR-0001: Separate Workspace Read and Mutation Capabilities at the Application Boundary

Status: Accepted

Date: 2026-09-15

## Context

DelveForge 对 Workspace 提出了几条硬约束：

```text
RULE-ARCH-009   业务模块不得直接执行 Git / Shell / Build / Filesystem 操作，
                相关能力统一通过 Workspace 提供

RULE-ARCH-010   Repository Analysis 只能获得只读能力，
                不得获得代码修改能力

RULE-ARCH-011   MVP 中 Evolution Execution 是唯一允许请求写能力的业务流程

RULE-DOM-005    代码修改只能发生在 Evolution Plan 绑定的 Working Copy 内，
                不得修改原始 Software Asset
```

其中，Repository Analysis 修改原始 Software Asset 会直接破坏用户自己的代码库，
与产品原则 “Original Asset Must Remain Safe” 冲突。

M0 建立 Workspace 抽象时面对的问题不是“要不要提供写能力”，而是：

> 如果 Workspace 只有一个同时暴露读写能力的接口，
> 那么“Repository Analysis 不得写”只能依赖调用约定和 Code Review。
> 只要某个只读流程获得该接口，它在类型层面就已经拥有修改能力。

因此需要决定的是：

**是否通过 Application Boundary 的 capability 划分，
让不应该拥有修改能力的调用方在类型层面根本拿不到该能力。**

## Options Considered

### Option A — 单一 `WorkspaceGateway`，读写方法并列

Pros:

- 接口数量最少
- 概念表面上最简单

Cons:

- RULE-ARCH-010 没有类型层面的保障
- Repository Analysis 等只读流程仍然能够调用 mutation 方法
- 只能依赖调用约定与 Code Review 防止误用

### Option B — 单一接口，用 `read*` / `write*` 命名前缀区分

Pros:

- 保持单一接口
- 命名能够提示调用者不同操作性质

Cons:

- 与 Option A 在能力边界上实质等价
- 命名前缀不能阻止只读流程调用 mutation 方法
- 不构成可执行的安全约束

### Option C — 拆分为两个 Port，调用方只依赖自己需要的 capability

当前选择。

```text
WorkspaceReadPort
WorkspaceMutationPort
```

Pros:

- 只读流程可以只持有 read capability
- Repository Analysis 等调用方在类型层面无法通过该依赖执行 mutation
- 不需要额外引入运行时权限框架
- 一个 Infrastructure Adapter 仍可同时实现两个 Port，不要求人为拆成两个 Adapter

Cons:

- 一个 Workspace 边界被表达为多个 capability interface
- 同时需要读写能力的流程需要显式依赖多个 Port
- 增加少量接口与依赖组合的心智成本

### Option D — 拆分为两个 Port，并增加运行时授权检查

Pros:

- 同时具有 capability 隔离和运行时授权
- 可以进一步表达“本次 mutation 是否已经被允许”

Cons:

- M0 尚不存在真实的授权主体和 Evolution Execution 上下文
- 无法回答“这次调用代表哪一次已经确认的操作”
- 当前阶段引入权限体系属于投机设计

### Option E — Mutation capability 由已授权的 Evolution Step / Working Copy Context 提供

Pros:

- 能直接表达 mutation 与具体 Evolution Step / Working Copy 的绑定
- 更接近 RULE-DOM-005 的最终语义
- 获取写能力本身可以与授权上下文关联

Cons:

- 依赖 EvolutionPlan / WorkingCopy / EvolutionStep 等后续 Domain Model
- 这些对象及其真实授权语义要到 M3 / M4 才会参与执行
- M0 强行引入会产生没有真实业务语义的占位抽象

## Decision

Application 层将 Workspace 能力至少拆分为两个独立 Port：

```text
WorkspaceReadPort        只读能力
WorkspaceMutationPort    修改能力
```

两个接口互不继承。

只读流程只依赖 `WorkspaceReadPort`。

在 MVP 中，`WorkspaceMutationPort` 只应提供给确实承担 Evolution Execution
职责、需要修改受控 Workspace 的 Application flow。

当前 mutation 操作以 `WorkspaceRef` 指定其技术作用目标。

`WorkspaceRef` 只表示 Application / Workspace Boundary 上的一个 opaque workspace handle；
它不是 Domain authorization token，也不能证明该 Workspace 已经与某个经过用户确认的
Evolution Step 或 Working Copy 正确绑定。

因此，本 ADR 当前只解决：

> **调用方是否拥有 Workspace mutation capability。**

它尚未解决：

> **某次 mutation 是否已经获得正确的 Domain 授权，以及允许修改哪个 Working Copy。**

## Rationale

选择 Option C 而不是 A / B，是因为 Repository Analysis 的只读属性属于安全边界，
不应只依靠开发者纪律维持。

当只读流程只获得 `WorkspaceReadPort` 时，
mutation API 不存在于该流程的依赖中，
从而把一部分安全约束从文档约定提升为类型层面的能力隔离。

选择 Option C 而不是 D，是因为 M0 尚不存在可以参与授权判断的真实业务主体。
在缺少 Evolution Step / Working Copy execution context 时，
运行时权限检查没有可靠的业务对象可以验证。

选择 Option C 而不是 E，是因为 Option E 需要后续 Domain Model 与 Evolution Execution
真正进入实现后才能获得完整语义。

提前建立空的授权上下文或占位 Evolution Step 类型，
违反项目对 speculative abstraction 的限制。

因此 Option C 不是理论上的最终安全模型，
而是当前阶段能够基于真实需求实施的最强合理约束。

## Consequences

### Positive

- Repository Analysis 等只读流程可以在依赖层面完全不获得 mutation capability
- 误用 Workspace 写能力时更容易在设计和编译阶段暴露
- 不需要引入额外权限框架
- Infrastructure 不需要因为 capability 拆分而强制拆成多个 Adapter
- 当前已有测试能够至少锁定 Read / Mutation Port 相互独立这一基础结构，
  防止未来为了“简化接口”直接重新合并

### Negative

- 一个 Workspace Boundary 被表达为多个 Port，增加少量概念和依赖组合成本
- 同时需要读取与修改 Workspace 的 Application flow 需要显式依赖多个 capability
- 当前模型没有表达跨多个 Workspace 操作时的业务授权关系或原子性语义；
  如果未来出现这种需求，需要重新建模

### Risks

- **Mutation capability 的 Domain 作用域尚未在类型层面表达。**

  RULE-DOM-005 要求实际代码修改只能发生在当前 Evolution Plan 绑定的 Working Copy 内。

  当前 `WorkspaceRef` 只能指出技术操作目标，
  不能证明这个目标就是当前已授权的 Working Copy。

  因此这一部分目前仍需要 Application orchestration、Adapter contract
  与后续 Domain Model 共同保证。

- 当前 capability separation 只能回答“有没有写能力”，
  不能回答“为什么这一次写是合法的”。

- “Workspace mutation”不应演化成无限制的宿主文件系统访问能力；
  具体 Adapter 必须继续保持受控 Workspace Boundary。

## Revisit Conditions

```text
M3 / M4 实现真实 Evolution Execution 与 Workspace / Git mutation Adapter 时
  → 必须重新评估 mutation capability 是否需要绑定
    Authorized Evolution Step / Working Copy Context。

  → 如果单一 WorkspaceRef 无法表达 RULE-DOM-005 所需的授权作用域，
    应优先考虑向 Option E 演进，
    而不是继续向 WorkspaceMutationPort 堆叠与授权相关的方法。


出现跨多个 Workspace 的读取 / 修改流程时
  → 重新评估现有 capability 粒度以及授权、原子性和失败恢复语义。


Workspace Adapter 能力扩展到 Git / Filesystem / Build / Test 等多个技术领域时
  → 如果当前 Read / Mutation 二分产生明显耦合或接口膨胀，
    再根据真实 Use Case 重新划分 capability boundary。
```

## References

```text
AGENTS.md
docs/ARCHITECTURE.md
docs/DOMAIN_MODEL.md
WorkspaceReadPort
WorkspaceMutationPort
WorkspaceRef
```
