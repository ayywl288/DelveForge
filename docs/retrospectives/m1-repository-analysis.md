# M1 Repository Analysis 阶段复盘

**Status:** 阶段记录，非 Source of Truth
**Last Updated:** 2026-09-22

> 本文档记录 M1 中 Repository Analysis 这一半的交付过程、设计决策与工程教训。
>
> **它不是规范性文档。** 领域规则以 `DOMAIN_MODEL.md` 为准，架构规则以 `ARCHITECTURE.md`
> 与 `AGENTS.md` 为准，里程碑与优先级以 `ROADMAP.md` 为准，长期决策以 `docs/decisions/` 为准。
> 本文档不新增规则，也不替代上述任何文档。若其中内容与它们冲突，以它们为准。
>
> 本文档的目标是：**将来重新进入这个阶段时，能快速理解代码为什么长成现在这样**，
> 以及哪些结论是文档决定的、哪些只是当时的实现选择、哪些是真实环境验证出来的。

---

## 1. Stage Goal

Repository Analysis 在 M1 中负责产出 Product Discovery 的第二个可信输入：

```text
SoftwareAsset
        ↓
Repository Analysis
        ↓
RepositoryProfile @ analyzedRevision
```

它与 User Discovery 产出的

```text
Confirmed UserProfile @ revision
```

共同构成 M2 — Product Direction Discovery 的输入。

M1 **不**回答「这个仓库应该演化成什么产品」——那是 M2。M1 只回答：

> 系统在某个确定的软件状态上，理解到了什么，以及这些理解各自有什么依据。

这一点决定了后面大部分设计取舍：分析必须是只读的、必须固定在一个 revision 上、
必须能追溯到具体文件，而**不**需要对「哪段代码更重要」下判断。

---

## 2. 形成的链路

| Task | 主题 | 最终产出 |
| --- | --- | --- |
| 1 | Software Asset 领域基座 | `SoftwareAsset` Aggregate、`SoftwareAssetId`、类型 / 来源 / 读取权限 / 使用授权 |
| 2 | Software Asset 登记与持久化 | `SoftwareAssetRepository`、Register / Get Use Case、`V3__software_asset.sql` |
| 3 | 只读 Workspace Adapter | `GitWorkspaceAdapter`（`WorkspaceReadPort` 的首个真实实现）、读取按显式 revision 固定 |
| 4 | Repository Profile 领域基座 | `RepositoryProfile` Aggregate、`RepositoryProfileId` |
| 5 | Repository Profile 持久化 | `RepositoryProfileRepository`、快照只写一次、`V4__repository_profile.sql` |
| 6 | Repository Analysis AI 提议 | `RepositoryAnalysisExtraction`、严格解析、Evidence 来源校验 |
| 7 | Analyze Repository Workflow | `AnalyzeRepositoryUseCase`、材料收集与选材策略 |
| 8 | Repository Analysis API | 四个 HTTP 端点、错误语义映射（404 / 409 / 502） |
| — | Material Selection Smoke Fix | 选材不再按层级筛选，改为按用途类别轮转 |

最终链路：

```text
POST /api/software-assets                     注册 Software Asset（只登记元数据）
        ↓
POST /api/software-assets/{id}/analysis
        ├─ 资产读取权限（Domain）
        ├─ 仓库可读性（Workspace）
        ├─ 解析一次 HEAD → analyzedRevision
        ├─ 按固定 revision 列目录 + 读文件 → 材料
        ├─ AI 提议 → 严格解析 → 来源校验
        ├─ RepositoryProfile.create（Domain）
        └─ save（最后一步）
        ↓
GET /api/repository-profiles/{id}             回读持久化快照
```

---

## 3. 关键设计决策

### 3.1 Software Asset 是独立 Aggregate，Repository Profile 不是它的子对象

`DOMAIN_MODEL.md` §11.4 明确 Software Asset 不拥有 Repository Profile，两者通过
`assetId` 建立跨 Aggregate 关系。实现严格照此处理：`RepositoryProfile` 只持有
`SoftwareAssetId`，Software Asset 里没有任何 Profile 集合。

一条资产可以产生多个 Profile（都是独立快照），但这不是"资产的历史版本"，
而是"这个资产的多个分析结果"。

### 3.2 Repository Profile 是 Snapshot，不复制 User Profile 的生命周期

User Profile 有 `status`（EXPLORING / REVIEWING / CONFIRMED）与 `revision`，
因为它是一份**持续被修改**的用户画像，且 Product Direction 要引用"哪一版"。

Repository Profile 被刻意做成另一种东西：

```text
不可变            全部字段 final，内容集合创建时固化
没有 status       §6 没有为它定义状态机
没有 revision     它自己就是某个 analyzedRevision 的一次快照
没有修改入口      只有 create，没有 update
新状态 → 新 Profile
```

所以"同一 asset 在不同 revision 上的分析"天然是两个 Profile，不需要额外规则保证
"旧快照不被改写"——**它是类型上做不到**。

### 3.3 一次分析必须固定在一个已解析的 revision 上

这是本阶段最重要的正确性约束，也是最容易写错的地方（见 §5.1）。

语义最终定为：

```text
resolve HEAD once  →  concrete commit id
                   →  every later read uses that revision
```

它同时决定了 Profile 能够声称「我描述的是 abc123」而不是「我描述的是分析时的某样东西」。

### 3.4 AI proposes, Domain decides

AI 输出是**不可信输入**，不是领域状态：

```text
原始模型输出 → 严格解析 → Application 校验 → Domain 决定是否接受
```

具体体现：

- Proposal 本身是 Application 层的中间数据，不携带领域身份；
- 结构不合规（不是恰好一个 json 对象、字段缺失、类型错误）一律失败，不"尽力解析"；
- 模型可以指出依据在哪个文件，但不能自己决定证据多可信（`confidence` / `confirmed`
  固定为 `null` / `false`，由系统而非模型给出口径）。

### 3.5 只读能力在类型层面隔离，而不是靠约定

`AnalyzeRepositoryUseCase` 只持有 `WorkspaceReadPort`。它在编译期就拿不到
`WorkspaceMutationPort`，因此"Repository Analysis 不修改源仓库"不依赖开发纪律
（ADR-0001 的动机在此第一次有了真实调用方）。

### 3.6 Deterministic Bounded Material Sampling

M1 没有引入 RAG、embedding、AST、代码索引或多阶段摘要，而是先做**确定、有界、可复现**
的采样：

```text
不按目录层级筛选（层级会系统性藏起深目录里的主源码树）
按用途分类别（构建元数据 / 源码 / 配置 / 文档 / 脚本 / 其他）
类别之间轮转，类别内按路径升序
三个预算：maxFiles / maxFileBytes / maxTotalBytes
超限文件在读取之前按 blob 大小跳过
```

理由是：M1 需要的是"有代表性且可核对"，不是"最相关的代码"。相关性判断需要一套自己的
质量标准和验证方式，把它塞进一个排序常量只会把问题往后推（见 §8、§9）。

这是一个 **Application 层实现选择**，不是领域规则：`DOMAIN_MODEL.md` 只要求分析针对
确定状态并形成可追溯结论，没有规定读多少、读哪些。

---

## 4. 逐 Task 的决策与经验

### Task 1 — Software Asset 领域基座

只表达 MVP 需要的东西：身份、类型（本地 Git Repository）、来源（用户指定）、位置、
读取权限、许可证信息、使用授权；**不**预留未来类型的占位取值。

`readPermission` 与 `usageAuthorization` 分开表达，并把 INV-A01 做成领域行为
（`requireAnalysisAllowed()`），而不是留给调用方各自判断。

**被修正的建模错误**：`usageAuthorization` 最初用 `boolean`，于是「已明确不允许」与
「尚未确认」被压成同一个 `false`——领域信息被丢弃。审查后改为三态
（`ALLOWED` / `DENIED` / `UNCLEAR`）。

`readPermission` 则**保持** `boolean`：文档只定义了 allowed / denied 两种结果，
没有 unclear。这条不对称是刻意的，并且写进了代码注释，避免后人"顺手对齐"。

### Task 2 — Software Asset 登记与持久化

登记**只登记元数据**：不判断 location 是否存在、不判断它是不是 Git Repository。
依据是 `DOMAIN_MODEL.md` §8.3 把"必须是可访问的本地 Git Repository"列为
**Analyze Repository 的前提**，而不是登记的前提。因此登记不需要 Workspace 能力。

读取权限与使用授权必须由输入显式给出，接口与本层都不给默认值——给默认值等于替用户
制造一个资产授权事实（RULE-DOM-004）。

刻意**不做**：location 唯一约束、去重、路径规范化、资产历史版本。文档没有定义这些语义。

**被修正的持久化缺陷**：MyBatis-Plus 默认跳过 null 字段，于是"已知许可证 → 未知"的
覆盖保存不会清空旧值，读回的内容与调用方刚保存的 Aggregate 不一致。
修法是给该列单独开放更新（`FieldStrategy.ALWAYS`），不动全局策略。

值得记住的对比：**这个缺陷只在"保存当前状态、允许覆盖"的语义下才是缺陷**。
Repository Profile 是只写一次的快照，不存在这个问题（见 Task 5）。

### Task 3 — 只读 Workspace Adapter

用 `git` 命令行的只读 plumbing（`rev-parse` / `ls-tree` / `cat-file`）实现，
暂不引入 JGit。工具选择本身是当时的实现前提（依赖运行环境有 `git`），
不是文档规定的架构要求。

读取语义：**只读 commit tree，不读工作区磁盘内容**。未提交修改、untracked、
被忽略的文件都不进入分析——否则 `RepositoryProfile.analyzedRevision` 会声称一个
它其实没有描述的版本。

**被修正的正确性缺陷（Critical）**：最初 `listEntries` / `readFile` 内部各自解析
`HEAD`。于是"报告 revision A 之后仓库又产生了提交 B"时，读取会变成 B——
一次分析混用了两个版本，而 Profile 只记录了 A。修法是让读取显式接受一个**已解析的
完整 commit id**，并拒绝 `HEAD` / 分支名 / 缩写 id。

**测试环境教训**：git 在 Windows 上把对象文件标记为只读，测试仓库留在 `target/` 下会让
**下一次 `mvn clean` 失败**。测试必须自己收尾（删除前去只读属性），而且清理逻辑只应
存在一处。

### Task 4 — Repository Profile 领域基座

按 §3.2 做成不可变快照。当时**没有**实现 `reconstitute`——Persistence 是下一个 Task
的事，提前给没有调用方的入口属于投机设计。

两个**实现选择**（不是文档规则，已在代码注释中标注）：`purpose` 必填（一个没有用途
描述的快照回答不了它描述的是什么）；`analyzedRevision` 只要求非空白、不规定格式
（文档明确它可以是 commit、快照标识或其他机制）。

### Task 5 — Repository Profile 持久化

存储语义体现 Snapshot 边界：**同一标识只写入一次**，已存在则拒绝，
`RepositoryProfileAlreadyExistsException`。这与 `SoftwareAssetRepository` 的按标识覆盖
刻意不同——一个是可能变化的元数据，一个是不可改写的分析快照。

用**项目自有异常**而不是 `IllegalStateException`：这是业务冲突，接口层需要能只把这一类
映射成对应协议错误。（`UserProfileRepository` 仍是旧写法，属既有遗留项，当时没有顺手改。）

`reconstitute` 与 `create` 当前的校验完全相同，因为 Profile 没有"创建之后才成立"的状态。
**如果将来出现只在创建时成立的约束，要避免它污染 restore 的语义**——恢复一份已保存的
快照不应该比当初创建它更难。

### Task 6 — Repository Analysis AI 提议

严格解析的具体含义（与 User Profile 的提议刻意不同）：

```text
User Profile 提议是增量   字段缺失 = 本次不建议修改该区（存在上一版可合并）
Repository 分析是一次结论 字段缺失 = 模型没有回答，必须失败
```

把"模型没回答"当成"该区为空"，等于把模型的沉默记成一条它从未做过的结论
（「没有风险」≠「没有回答风险」）。

Evidence 的 `sourceRef` 由模型给出——Repository 分析的依据必须能指回具体的代码或配置。
但模型不能决定依据有多可信（`confidence` / `confirmed` 不由模型给）。

**被修正的缺口**：prompt 里写了"只能引用材料中的文件"，但**模型仍然可能给出一个不存在
的路径**。修复方式是在 Application 层做精确校验：任一条 `sourceRef` 不在本次实际发送的
文件集合中就拒绝整次分析（不是丢掉那一条）。见 §5.2。

附带的一次整理：共享的 JSON 读取契约从 User Discovery 的包移到了 AI 边界所在的包，
因为第二个业务流程开始使用它。

### Task 7 — Analyze Repository Workflow

把此前各自独立的能力第一次串成完整流程。四条固定语义：

```text
revision 只解析一次，之后所有读取带着它
材料的路径来自 listEntries、内容来自对同一路径的 readFile（不构造、不猜测路径）
save 是最后一步，且只发生一次
Use Case 只持有 WorkspaceReadPort
```

**被修正的问题（1）——预算没有约束真实工作量**：最初的取舍是"先完整读取文件，
再判断它是否超限"。于是 `maxFileBytes` 只限制了**进入材料的内容量**，没有限制
**实际读取量**：1000 个超限文件会被逐个读完，单个巨大文件会先耗尽内存再被判定"不该读"。
修复方式是让列目录就带上 blob 大小（`git ls-tree -l`），在 `readFile` 之前取舍。
见 §5.3。

**被修正的问题（2）——关键语义缺少真实环境验证**：revision 在分析期间移动、
失败不写入数据库这两件事当时只在替身测试里验证过。补了真实 Git + SQLite 的集成验证，
包括"解析 revision 之后、材料读取之前推进真实 HEAD"和"失败时直接数三张表确认无新增行"。
见 §5.4。

### Task 8 — Repository Analysis API

Controller 保持轻量：解析请求、映射输入、映射输出。它不判断资产是否可读、
不访问 Workspace、不接触 AI、不决定 `analyzedRevision`。

分析端点**不接收请求体**：`analyzedRevision`、Evidence 与材料都由服务端决定。
客户端即使发送这些字段也不会被读取——不是靠校验挡住，而是这个端点没有接收它们的入口。

错误语义按"谁的问题"划分：

```text
404  资产 / 快照不存在
409  资产当前不能被分析（读取权限不允许 / 位置不是可读仓库 / 没有可分析材料）
502  AI 调用或输出解析失败
400  请求写法错误（未知枚举取值、必填字段缺失、非法 json）
500  Workspace 自身的操作失败（环境问题）
```

**被修正的问题（1）——缺失的输入被静默变成领域事实**：注册端点用原始 `boolean` 接收
读取权限，"字段缺失"于是变成 `false`；枚举又会被 Jackson 按**序号**接受
（`"usageAuthorization": 0` 变成第一个取值）。两者都等于用错误的输入制造出授权事实。
修法：用包装类型 + 拒绝 null，并关闭枚举的数字反序列化。

**被修正的问题（2）——状态问题被报成请求问题**：资产的 location 不可用时，
可读性检查内部抛参数异常，接口层于是返回 400——但调用方的请求本身没有任何问题
（它只是拿着一个已登记资产的 location 来问）。修法：可读性检查对"位置不可用"返回
`false`（它回答的是"能不能读"），由上层翻译成 409；读取操作仍然要求可解析的位置。

### Material Selection Smoke Fix

在两轮真实仓库验证之间完成，只改 Application 层的选材策略：

```text
不再按目录层级筛选
按用途分类别，类别之间轮转
```

三个预算、超限文件的读取前跳过、固定 revision 读取、Evidence 语义全部不变。
动机与数据见 §6。

---

## 5. Review-Driven Lessons

以下几条是本阶段最值得长期保留的（每一条都来自一次真实的审查发现，不是假想）。

### 5.1 会移动的引用不是稳定快照

`HEAD` 是会移动的符号引用。用它当分析基线，等于把"分析的对象"交给一个会变化的东西。

```text
错误：读 HEAD 报告给调用方，之后每次读取再各自解析 HEAD
正确：解析一次 → 具体 commit id → 之后所有读取都带着它
```

修复后仍然保留了一条回归测试：解析 A 之后提交 B，带着 A 的读取必须返回 A 的内容。

### 5.2 Prompt 里的约束不是校验

模型被要求"只能引用材料中的文件"，并不意味着它一定做到。**关键输出必须由代码验证**，
而且校验要发生在能拿到事实的那一层（`sourceRef` 是否来自本次材料，只有材料收集层知道）。

同理，prompt 里写"必须给出全部字段"也不能替代解析层的字段校验。

### 5.3 预算必须约束真实工作量，而不只是最终产物

`maxFileBytes` 如果只在读完文件之后生效，它约束的只是"送进模型的内容量"，
资源消耗（内存、进程、时间）完全没有被限制住。

正确的顺序是：**先用便宜的元数据（blob 大小）取舍，再决定读不读**。

### 5.4 集成语义无法只用替身证明

"分析期间 revision 前移不会混用版本"、"失败不会写入半成品快照"这类语义，
最终需要真实 Git + 真实数据库来验证——替身的目录结构、失败时机、事务行为都是测试
自己安排的，因此**它证明不了真实组件的语义**。

### 5.5 「缺省」不是值

缺失的字段、为 null 的输入、格式错误的取值，都不应该被静默变成一个具体的领域事实：

```text
缺 readPermissionAllowed → 不能变成「不允许读取」
"usageAuthorization": 0  → 不能变成某个具体授权状态
没有可分析材料          → 不能变成「分析结论为空」
```

前两者会凭空制造授权事实，后者会让模型对着空材料编造结论。

### 5.6 用通用异常表达业务语义，会让协议层无法分类

`IllegalStateException` / `IllegalArgumentException` 可能来自 JDK 或第三方库。
把业务冲突（快照已存在、资产当前不可分析）混进这些类型，接口层就只能"要么全映射、
要么全不映射"，两种都会出错。项目自有异常类型是必要的，**而且要映射具体类型**。

### 5.7 文档的沉默不是决定

本阶段反复出现同一类错误：把"文档没有写"当成"文档已经决定了"。

```text
文档没说 usageAuthorization 只能两态  ≠  文档决定了它是 boolean
文档没说保存策略                      ≠  文档决定了覆盖保存
文档没说材料怎么选                    ≠  文档决定了按目录层级选
```

处理方式统一为：可以按当前理解实现，但**必须在代码注释里标明这是当前实现选择**，
并保留为"将来按证据重新评估"的问题，而不是把它写成领域规则。

### 5.8 系统性盲区只有真实数据才暴露

"按目录层级筛选"在替身测试里永远是正常的——因为测试自己决定目录结构。
只有在真实仓库上（源码按工程惯例位于第 7–8 层）它才表现为"整个主源码树不可见"。

**这类问题的共同点是：参数本身在测试中看起来完全合理，错的是它面对真实世界时的假设。**

---

## 6. Real Validation

完整记录见 `docs/validation/m1-repository-analysis-smoke-test.md`。这里只保留结论。

两轮验证都在同一个真实仓库、同一个 committed revision 上，使用真实 HTTP、真实 Git、
真实 Provider、真实 SQLite：

### 第一轮（修复前）

链路完全可用（注册 / 分析 / 持久化 / 回读一致 / 源仓库未被修改 / 未提交内容不进入分析），
**但材料里一个源文件都没有**：96 个 Java 文件全部因为位于第 7–8 层而被层级筛选排除，
浅层的文档与工具链按路径序占满了预算。Profile 因此描述的是"文档与工具链"，
而不是这个应用。

### 第二轮（修复后）

| 指标 | 修复前 | 修复后 |
| --- | ---: | ---: |
| Java source selected | 0 / 96 | **7 / 96** |
| Evidence 引用 Java source | 0 / 22 | **7 / 25** |

质量变化（模型真实输出，未做人工修正）：

- `purpose` 从"一次高并发重构与压测工作"变成明确识别**产品本身**（点评 / 秒杀系统后端）；
- `modules` 出现真实 Java 工程结构（应用入口、缓存 / MVC / MyBatis / 锁 / 消息 / 异常处理配置）；
- `reusableAssets` 出现代码级资产（可直接参考的配置类）；
- Evidence 能够直接引用 Java source，且逐条核对后确认是**内容级**证据
  （常量值、类名、字符串都与文件对应），不是"文件存在"式的弱证据。

同样必须记录的是**修复没有解决什么**：

- 选中的 Java 仍主要是 `config` 包，controller / service / entity 等业务实现没有覆盖；
- `MultiLevelCacheServiceImpl.java` 等文件受单文件预算限制被完全跳过；
- 当前 sampling 是 bounded representative sampling，**不等于** deep repository understanding。

---

## 7. Known Limitations

以下都是当前**明确接受**的限制，不是待修的缺陷。

### Repository representation

材料采样是：

```text
deterministic
bounded
category-balanced
```

但不保证：

```text
most relevant business implementation
```

类别内仍是路径序，因此"哪些业务代码更重要"没有被回答。

### Large files

超过单文件预算的文件**完全跳过**，不做 chunking、不截断。代价是它们的内容完全不进入分析
（真实仓库中包括多级缓存的实现类本身与数据库初始化脚本）。

### Context budgeting

当前按 **byte** 做粗边界，不是 provider-specific 的 token 预算。字节数只是内容规模的
保守上界，与真实 token 消耗不成比例。

### Huge repositories

当前会列出**完整的 commit tree metadata**。对超大型 monorepo 的 scalability 尚未验证。

### Re-analysis

同一 revision 主动分析两次仍然会：调用两次 Provider、创建两个 Snapshot。
这是当前**合法语义**（每次分析 = 一次新的分析结果，不覆盖旧的），不是 bug；
但它也意味着重复触发会重复消耗。

### 未实现但被文档提及的检查

`DOMAIN_MODEL.md` §8.3 的前提之一是"Software Asset 类型必须是当前系统支持分析的类型"。
当前 MVP 只有一种资产类型，这条前提**不可能失败**，因此没有写成一个永远为真的分支。
出现第二种资产类型时需要在 Use Case 中补上。

---

## 8. Why We Stop Here

真实的端到端验证已经证明：

```text
real HTTP          real Git            real SQLite       real Provider
fixed revision     immutable snapshot  Evidence traceability
source Repository safety              dirty Working Tree isolation
```

因此 M1 已经拥有足够可信的：

```text
RepositoryProfile @ analyzedRevision
```

当前"业务代码采样不足"是**已知的质量边界**，但没有证据表明必须在 M1 继续构建更复杂的
Repository Understanding：M1 需要的是"可追溯到确定 revision、依据可核对"，
而不是"对仓库理解得足够深"。

继续优化很容易沿着一条没有自然终点的路走下去：

```text
package sampling → relevance ranking → chunking → AST → repository retrieval
→ hierarchical analysis
```

每一步都能让结果"看起来更好一点"，但每一步都需要自己的质量标准与验证方式。
在还没有真实使用证据的情况下投入这些，违反 `ROADMAP.md` §2.1：

```text
Complete the Loop Before Expanding It
```

因此在此停止，并把重新评估的条件写清楚（下一节），而不是留下一个"以后优化"的模糊承诺。

---

## 9. Revisit Conditions

满足以下任一条时，重新打开 Repository material selection：

```text
1. M2 Product Direction 因为 RepositoryProfile 缺少关键实现事实而明显失真
     （方向建议停留在文档描述层面，无法指出可复用的具体实现或模块）

2. 在多个真实 Repository 上都采样不到业务主体
     （说明这不是个别仓库的目录结构问题，而是采样方式的问题）

3. Product Direction / Evolution Planning 明确需要 implementation-level facts
     （例如需要知道某能力的实现位置、依赖关系或可改造点）

4. 大型 Repository 让当前 sampling 明显失效
     （候选规模使现有预算下的采样不再具有代表性）

5. context budget 成为真实的 Provider failure 来源
     （而不只是"看起来会超"的担忧）
```

届时的重新评估应当从**真实需求与证据**出发，而不是从"当前排序不够聪明"出发。

---

## 10. Handoff to M2

M1 最终提供：

```text
Confirmed UserProfile @ revision
+
RepositoryProfile @ analyzedRevision
```

两者都有可追溯 Evidence，都可以按标识回读。

M2 可以把这两个输入用于 Product Direction Discovery。本文档不设计 M2 的 Task、
不设计方向发现算法、也不预设 RepositoryProfile 需要哪些新字段——那些应当在 M2 开始时，
根据当时的真实需要重新确认（包括：如果 M2 发现 Profile 缺少它真正需要的实现事实，
这正是 §9 的第 1 条重访条件）。
