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

## M3 refinement — 2026-10-05

M3 的环境准备使用独立 `WorkingCopyProvisioningPort`，不加入 `WorkspaceReadPort`
或 `WorkspaceMutationPort`。三个 Port 互不继承；Repository Analysis 继续只依赖读取 Port。
现有 `GitWorkspaceAdapter` 同时实现读取与准备 Port，不实现步骤代码修改 Port。

准备只接受源位置、完整 commit ID 与托管目录名称，返回技术位置、实际 revision 和清理凭据。
Adapter 使用配置的独立根目录、`clone --no-local --no-checkout --template=` 与 detached checkout，
不共享源仓库对象存储、不包含源仓库未提交内容。准备前后核对源 HEAD；偏离分析 revision 时拒绝。
清理只允许本次创建且持有匹配凭据的托管子目录，不采纳已有目录。

Git/文件系统与 SQLite 不形成一个事务：外部候选先准备，READY 元数据、绑定与 ACTIVE Plan
随后一次原子提交；提交失败时尽力删除候选。删除失败留下的目录不具有领域权威性。
这是 M3 的同步实现选择，不引入持久化 CREATING 或残留协调系统。

ACTIVE Plan 不构成 Step 授权。M4 仍须按上述 Revisit Conditions 评估绑定 Working Copy / Step
作用域的 mutation capability；M3 不开放步骤写能力。

## M4 Task 1 refinement — 2026-10-07

执行准备引入独立只读 `WorkingCopyInspectionPort`，核对托管位置、实际可信 revision、
干净状态、隔离及提案路径的技术可用性。它不继承读写 Port，不授予代码修改能力。
GitWorkspaceAdapter 在既有读取与准备之外实现此检查能力，仍不实现 WorkspaceMutationPort。
确认只持久化 READY；调用内理解与已校验提案不设置 baselineRevision，不开始 Execution。
mutation 的授权作用域与最终执行入口仍按上述 Revisit Conditions 留待 Task 2。

## M4 Task 2 refinement — 2026-10-07

真实 mutation 采用 Option E 的作用域绑定：`AuthorizedStepExecution` 由完整 EXECUTING
检查点与调用内 `ValidatedExecutionProposal` 建立，包含绑定副本、原资产位置、Step 身份和
固定基线。它不替代 mutation 前的 Application 资格重读或 Adapter 的实际托管 / revision 检查。
占位 writeFile 接口被完整补丁应用、实际范围检查和本地 Candidate 创建的窄操作替代。

`WorkspaceVerificationPort` 与其他 Port 独立且互不继承，只接受固定 Candidate 与有限验证意图。
Git / Maven 命令由 Infrastructure 基于仓库事实解析，不接收任意 shell。只读流程仍不持有写入或验证能力。
临时验证 checkout 是本次技术产物，不是新的领域 WorkingCopy 或新的软件信任状态。

业务状态与 Git 不组成跨技术事务，三个条件提交检查点保护半权威状态边界；M4 异常暂留
最后完整检查点并 fail closed，M5 再实现 canonical 失败 / 恢复流程。

## M5-B refinement — 2026-10-13

Repair 继续使用 Option E 的同一窄 mutation capability。`fromRepair` 只接受当前 EXECUTING
检查点、本次持久化授权身份与调用内校验提案；修改起点是失败 Candidate B，可信 baseline 仍为 A。
资产授权、绑定、隔离、实际 Git revision、提案范围及检查点 CAS 仍须独立核对。
用户的首次 Step Confirmation 不能构造 Repair 授权。重复 / 并发 Repair 先由原子授权检查点拒绝，
不重新调用 Provider；重启仅恢复事实，没有恢复或重放入口。Verification 重跑原 A..C 与全部标准。
Port 划分、模块依赖、WorkingCopy 策略不变；未增加通用命令、Attempt 或后台任务框架。

## M5-C refinement — 2026-10-13

User Rollback 使用独立 `WorkspaceRollbackPort`，不向读取或步骤补丁能力加入通用 Git 命令。
Option E 的 `AuthorizedStepRollback` 绑定本次授权检查点、失败 Candidate 与原 Step baseline。
操作只在托管独立克隆中执行；选择非强制 checkout、禁用 hooks、禁止覆盖 ignored 文件，
并在恢复前后核对实际内容。额外文件及用户修改不在授权清理范围，拒绝后不自动扩大权限。

SQLite / Git 仍无跨技术事务。授权先提交，精确恢复确认后提交 FAILED；异常只提交安全阻塞事实。
这个事实写入不授予任何外部能力，仍通过旧授权 / 结果 / WorkingCopy CAS，不因资产撤权而抹去
未知操作的诊断。成功检查点与所有既有执行流程继续使用完整资产资格守卫。
用户已裁定重启残留 ROLLING_BACK 只读保留、阻塞、不重放；所有权及停止判据留给后续 Recovery。
本 Slice 不增加恢复后台任务、Retry、自动 Execution Failure Rollback 或破坏性清理协议。

## References

```text
AGENTS.md
docs/ARCHITECTURE.md
docs/DOMAIN_MODEL.md
WorkspaceReadPort
WorkspaceMutationPort
WorkspaceRef
```

## M5-D refinement — 2026-10-14

System Rollback 继续使用独立 WorkspaceRollbackPort 和 Option E 的范围绑定。能力从已保存的
Execution Failure / ROLLING_BACK 检查点建立，不制造 User Rollback 授权；正常 User Rollback 入口仍须
独立授权。Application 不持有 shell 或 filesystem 能力，Infrastructure 仅恢复能证明归属的 Step 修改。
固定预期 tree 只是未发布补丁的技术证据，不成为 Candidate 或新的领域 Aggregate。
未知执行结果、未决重启和无法证明的文件现场继续 fail closed；不引入任务引擎或自动重放。

## M5-E refinement — 2026-10-15

FAILED Retry 的授权只通过现有 inspection 和原子 Domain / Persistence 转移返回 READY，不持有写
Port 或 AI Gateway。原 Step baseline 保持不变，新的 RetryAuthorization 在 Step 内归档上一轮失败
快照。该 Slice 延续 M5-B/C/D 的值对象历史实现，不引入独立 Attempt、Aggregate 或新执行架构。

显式执行准备与 Option E 的 AuthorizedStepExecution 绑定当前已持久化 Retry 身份；该轮后续
Repair / User Rollback 也核对父身份，防止同 baseline 请求发生跨轮次复用。每种动作自己的授权、
资产资格、真实内容守卫和检查点 CAS 仍独立执行。Git / SQLite 不形成跨技术事务，原 UNKNOWN
及重启未决边界不变；不引入启动补偿、自动 Retry、Recovery 或后台任务。

## M5 partial closeout — Recovery write capability deferred — 2026-10-16

状态：Accepted（用户明确裁定）；原有 capability 拆分决策与完整 Recovery 领域目标保留。

**问题与证据：** 后端 CAS、PID 消失、超时、重启和普通锁释放都不能证明旧进程失去副本写能力。
[F1](../validation/m5-f1-ownership-spike.md) 的真实 WMI 代创建 writer 在 Owner 退出、受管 Job 归零后
仍可写；[F1b](../validation/m5-f1b-isolation-spike.md) 的原受限进程在 KERNELBASE 初始化失败，
不能冒充已运行的独立身份。宿主对照中，同 SID ACL 不能撤销预开句柄，也不能只向可信宿主重新放行；
staging 原型的叶文件校验还遗漏父目录 junction。两轮实验虽经独立审查通过，技术 Gate 均为 NO-GO。

**决策：** 停止当前隔离候选研究，延期 Recovery 写能力，不将 Spike 接入生产 Port、UseCase、API、UI
或 SQLite；不推进 F2/F3，不以 feature flag、人工勾选或推测停止替代安全证明。M5-A～E 可作为部分能力
进入独立最终集成审查，但不能据此将 M5 标为 DONE 或声称已具备完整失败恢复能力。

**保持的边界：** 当前 RECOVERY_REQUIRED 与遗留 EXECUTING / VERIFYING / ROLLING_BACK UNKNOWN
保留原事实、只读查询并阻塞，不接管、不重放、不伪造 FAILED / Verified State / UNRECOVERABLE。
此延期不取消正常调用内已确认失败的 User / Automatic Rollback，也不改变已精确恢复 FAILED 的独立 Retry。
所有旧检查点不能追溯获得原型的所有权／停止保证；导航 restore 不构成业务 Recovery。

**取舍与后续条件：** 延期牺牲当前版本对长期阻塞现场的可恢复性，保留软件信任与历史含义。
拒绝直接采用局部 Job 计数、同 SID 撤权或未封闭的 staging 接收器。若未来重新提案，须先独立设计受支持的
执行身份／隔离部署边界，覆盖所有潜在写入口、broker、预开句柄、监督器崩溃、路径别名和接管时序，
以真实 OS 正反实验及独立审查证明旧写者不能继续写；再裁定集成，不默认选定账户、Sandbox 或 VM 技术。
Recovery 仍需新的独立用户授权，目标只能是原 Step baseline；无法证明安全时继续阻塞。

本决策只记录已授权的延期，不引入新状态机或架构技术。发布影响与后续 Gate 见
[M5 阶段性收尾](../validation/m5-partial-closeout.md)。
