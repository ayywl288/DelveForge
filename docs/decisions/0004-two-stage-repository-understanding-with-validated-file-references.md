# ADR-0004: Two-Stage Repository Understanding with Validated File References

Status: Accepted

Date: 2026-09-29

> 本 ADR 记录 Repository Analysis 从「一次性确定性代表采样」演进为
> 「Repository Map → LLM Scout → 校验引用 → 定向读取」这一长期方向的决策。
>
> **本 ADR 记录的是决策，不是实现进度。** 它划分的各个阶段会分批落地，权威状态以代码与
> `ROADMAP.md` 为准，这里只在下面标注一次当前进度，避免读者把它当成现状描述。
>
> 当前进度：
>
> ```text
> 已实现    Repository Map、确定性分类与候选路由
> 已实现    Scout 的契约、严格解析与引用校验（产出 RepositoryInspectionPlan）
> 未实现    定向读取、Foundation / 定向源码预算、聚焦轮转、材料合并
> 未实现    AnalyzeRepositoryUseCase 接入——现有分析与选材行为尚未改变
> ```

## Context

### M1 建立了什么，以及当时为什么合理

M1 的 Repository Analysis 用一次有界、确定、可复现的采样，从已提交的 commit tree 里
选出 40 个以内的文件，把它们的内容交给模型，得到 Repository Profile：

```text
listEntries（整棵树的 blob 元数据）
        ↓  排除 + 按用途分类 + 类别轮转 + 三个预算
readFile（只看选中文件的内容）
        ↓
一次 AI 调用
        ↓
RepositoryProfile @ analyzedRevision
```

这是在 M1 的目标下作出的合理取舍。M1 需要回答的是：

> 系统在某个确定的软件状态上理解到了什么，以及这些理解各自有什么依据。

它要求的是**可信**（固定 revision、依据可核对、只读），而不是**理解得深**。
在没有真实使用证据的情况下引入相关性排序或语义检索，等于先造一套自己的质量标准
（见 `docs/retrospectives/m1-repository-analysis.md` §8）。

### 什么证据触发了重访

M2 的真实链路验证触发了 M1 明确写下的重访条件（条件 1）：

```text
docs/validation/m2-product-direction-discovery-smoke-test-round1.md §10
M1 Material Selection revisit condition: TRIGGERED
```

证据是具体的：Direction Discovery 最终引用的 16 个 Repository 文件**全部**是配置、
脚本、文档与构建元数据，`controller/`、`service/`、`entity/`、`mapper/` 下的业务实现
一个都没有进入过任何方向的依据；22 条 `reusableCapability` 依据里 8 条 Java 源码
全部来自 `config/` 与入口类。后果是 5 个候选方向中有 2 个的「用户问题」实际主语是
仓库自身的工程产物，而不是用户。

也就是说：**Profile 足以描述这个仓库的工程形态，不足以描述它实现过什么业务。**
而 Product Direction 与 Evolution Planning 需要的恰恰是后者。

### 真正的问题不是「排序不够聪明」

把当前采样的问题描述为「类别内按路径升序，所以排到的是 config 包」并不准确。
即使换一个更聪明的排序常量，仍然没有回答「哪些业务实现值得看」——那需要一份对
Repository 的结构性认识，而当前阶段根本**没有**这份认识可供排序使用：

```text
当前：在一无所知的情况下，靠路径与扩展名硬选一批文件，再让模型从这批文件里总结
```

选材与理解被压在同一个阶段里：采样必须同时承担「控制上下文规模」与「找到重要实现」
两个目标，而它只有路径这一个信息来源。

## Options Considered

### Option A — 继续调整当前 material selection 的参数或排序

Pros:

- 改动最小，不引入新阶段
- 复用现有全部实现与测试

Cons:

- 仍然是「在一无所知的情况下硬选」，只是换一个排序常量
- M1 复盘 §8 已经明确拒绝过这条路：它会沿着
  `package sampling → relevance ranking → chunking → AST` 一路滑下去，
  每一步都需要自己的质量标准，却没有真实证据支撑
- 无法回答「为什么选中这些业务实现」，只把问题往后推

### Option B — 去掉采样，把整棵树的全部内容交给模型

Pros:

- 概念上最简单：模型看到的就是全部
- 不需要任何相关性判断

Cons:

- 真实仓库（黑马点评：139 个 tracked blob）尚可，稍大的仓库直接不可行
- 上下文预算与成本无界，且失败模式是硬失败（超出 Provider 上限）
- 与 M1 明确建立的「有界」原则冲突，且没有任何取舍余地

### Option C — 引入 Embedding / Vector DB 做语义检索

Pros:

- 能按语义相近度召回文件，不依赖路径

Cons:

- 需要先把文件切成 chunk 并建立索引 → 引出切分策略、嵌入模型、索引更新、
  相似度阈值等一整套自己的质量标准
- 当前**没有查询意图**可用于检索：Repository Analysis 在 Product Direction 之前发生，
  此时还没有「想找什么」。对整棵树做一次无查询的向量化并不能回答「哪些是业务实现」
- 属于 M1 复盘明确列为「需要独立验证方式」的机制

### Option D — 两阶段：Repository Map → LLM Scout → 校验引用 → 定向读取（当前选择）

```text
已提交的完整 tree（只读元数据，不读内容）
        ↓
Repository Map        每个 blob 一个描述符：路径 / 大小 / 语言 / material kind / role hints
        ↓
LLM Scout             只看描述符，指出「哪些文件值得实际读」（输出 RF-* 引用）
        ↓
Application 校验      每条引用必须命中本次 Map 中的描述符，否则拒绝
        ↓
定向 readFile         对选中路径在同一个 analyzedRevision 上真实读取
        ↓
RepositoryAnalysisExtraction（不变）
        ↓
RepositoryProfile
```

Pros:

- 把「控制上下文规模」与「找到重要实现」拆成两个各司其职的阶段：
  Map 负责**完整**（全树可见，不因预算而截断），Scout 负责**取舍**
- Scout 的输入是描述符而不是内容，因此「让模型看一眼整棵树」的代价是有界的
- 定向读取让被选中的文件可以用完整内容进入分析，不再受「先到先得」的预算挤压
- 每个新阶段都保持 M1 已经建立的语义：固定 revision、只读、可追溯
- 可以被真实证据驱动地逐步替换：Map 与 Scout 都能单独验证

Cons:

- 引入一个新的 AI 调用阶段（Scout），也就引入一次额外的成本与一次新的失败点
- 多了一层「Scout 选错文件」的风险，需要读取阶段的取舍兜底
- Map 的描述符质量（分类与角色提示）直接决定 Scout 能选出什么

### Option E — 在 D 之上再引入 AST / 符号图 / 调用图

Pros:

- 结构性信息最强，能回答「这个能力在哪实现、被谁调用」

Cons:

- 需要按语言实现解析器，成本与维护面远超前两者
- 当前 MVP 只有 Java 一种真实验证过的语言，为它单独建一套分析器属于
  speculative investment
- 上述信息在真实需求出现前无法验证其必要性

## Decision

Repository Analysis 的长期方向确定为 **Option D**：

```text
complete committed tree
        ↓
Repository Map
        ↓
deterministic candidate routing
        ↓
LLM Scout over bounded file descriptors
        ↓
Application validates invocation-local file references
        ↓
targeted reads at the same analyzedRevision
        ↓
existing RepositoryAnalysisExtraction
```

分阶段实现，各阶段之间的边界就是下面这条链路的分段：

```text
1. Repository Map（完整已提交 tree 的描述符集合）+ 确定性分类 + 候选路由
2. Scout：契约、严格解析、引用校验 → RepositoryInspectionPlan
3. 定向读取（同一 analyzedRevision 上按计划读取）
4. AnalyzeRepositoryUseCase 接入与预算编排
```

**第 1、2 段已经落地，第 3、4 段还没有。** 本 ADR 记录的是这条链路整体的方向与理由，
不把「哪一段实现到哪一步」当成决策的一部分——那些会变，决策不会。

### 决策要点

**1. Foundation Material 与 targeted source discovery 分离。**

当前阶段被要求同时承担两个互相冲突的目标。分离之后：

```text
Foundation Material    工程元数据 / 配置 / 文档 / 脚本 / 部署 / 数据模型
                       ——回答「这是什么工程」，规模小、可稳定取全

Targeted source        业务实现源码
                       ——回答「它实现过什么」，需要先被指出来再去读
```

**2. `RF-*` 引用是 invocation-local 的，必须由 Application 校验后才允许读取。**

这与 `DirectionDiscoveryInputs` 里 `U-E1` / `R1-E2` 的设计同源，理由也相同：

- 模型需要指认「就是这几个文件」，但复述路径既冗长又可能与真实路径不一致；
- 引用是闭集指针，未知引用可以直接判定为失败，而不是「尽力匹配」；
- 引用只在一次 Map / 一次分析内有效，**不是 Domain 身份，也不得持久化**。

稳定技术身份始终是：**已解析的 repository state（`analyzedRevision`） + 提交树中的相对路径**。
引用只是它在一次调用内的短名。

应用侧在读取之前必须完成校验：引用命中本次 Map 的描述符 → 取出该描述符的路径 →
用该路径与同一个 revision 调 `readFile`。**模型输出的路径永不直接进入读取调用。**

**3. Scout 输出只是 inspection hint，永远不是 Repository fact。**

Scout 说的是「这个文件看起来值得读」，不是「这个文件实现了订单服务」。
后者只有在文件内容真的被读进来、并经过既有的严格解析与来源校验之后才成立。
把 Scout 的判断当成结论，等于让一个只见过路径的模型替整个分析定调。

**4. 第一版刻意不引入 Embedding / RAG / Vector DB / AST / 代码切分 / 符号图 /
分层 Scout / 新的 AI 框架。**

理由与 M1 §8 一致：这些机制各自需要一套质量标准与验证方式，而当前还没有真实证据
说明哪一种是必要的。先建立最小可行的两阶段结构，再按真实失败模式决定下一步。
**没有查询意图**这一点尤其关键：Repository Analysis 发生在 Product Direction 之前，
此时不存在可用于语义检索的目标。

**5. Map 不读文件内容。**

Map 只消费 commit tree 的元数据（路径、blob 大小）。读取内容会让「看一眼整棵树」
这件事重新变成有界采样，也就把要解决的问题原样带回来。

## Rationale

选择 D 而不是 A / B / C 的共同理由是：**当前失败的原因不是参数没调好，而是这个阶段
缺少它需要的信息**。

- 相对 A：换排序常量不能凭空造出「哪些是业务实现」的认识；那需要先看一眼整棵树。
- 相对 B：无界地把内容交给模型，会把「有界」这一已经成立的性质丢掉，且失败不可控。
- 相对 C：语义检索需要一个查询意图，而 Repository Analysis 阶段还没有它；
  在没有意图的情况下引入向量化，只是把「硬选」换成「按某种相似度硬选」，
  却额外背上索引、切分、模型与阈值四套需要自行验证的机制。

选择 D 而不是 E，是因为 AST / 符号图解决的是「实现位置与依赖关系」这一类问题，
而当前暴露的是更前置的问题：**业务实现根本没有进入分析**。在文件都还没被读进来之前
建立符号图，属于针对假设需求的投入。

`RF-*` 之所以必须是 invocation-local 并强制校验，是因为它承担的角色与
`EvidenceReference` 完全相同：让模型**指认**而不是**复述**。复述无法与真实路径可靠比对，
而闭集指针一旦越界就是可判定的失败。

Scout 输出被限定为 inspection hint，是为了不让一次只基于路径的判断获得领域权威：
`AI Proposes, Domain Decides` 在这里的具体含义是——
**Scout 决定读什么，Domain 决定这些内容说明了什么。**

## Consequences

### Positive

- Repository Map 让**整棵已提交树**对后续阶段可见，不再因为预算而被截断
- 分类与路由是确定、纯函数式的：同一棵树得到同一个 Map，可复现、可测试
- 「读什么」与「得出什么结论」被拆开，各自可以单独验证
- 引用校验把「模型指了一个不存在的文件」变成可判定的失败，与 M1 §5.2 的教训一致
- 定向读取使被选中的文件可以完整进入分析，不再被「先到先得」的预算挤掉

### Negative

- 多一次 AI 调用（Scout），因此多一次成本与一个失败点
- Repository Map 会完整列出 commit tree 的描述符；超大 monorepo 下这个集合本身
  也会变得很大（见 Revisit Conditions 第 1 条）
- 描述符的分类与角色提示是启发式，会直接影响 Scout 的选择质量

### Risks

- **Scout 选偏。** 只看路径与角色提示，Scout 可能选中并不重要的文件。
  缓解：读取阶段保留自己的取舍（单文件大小、总量预算），不让 Scout 直接决定读多少。
- **描述符成为新的盲区。** 如果分类把真实业务文件判成 `GENERATED_VENDOR` 或
  `OTHER`，Scout 就再也看不到它们——这与 M1 的层级筛选是同一类错误，
  只是发生在更细的粒度上。缓解：分类必须允许 `UNKNOWN`，并且默认**不做排除性判断**。
- **引用校验被绕过。** 一旦有人让模型输出的路径直接进入 `readFile`，
  「模型只能指认已知文件」这一性质就消失了。缓解：读取只能经由 Map 的描述符。

## Revisit Conditions

```text
1. Scout catalog 过大
     整棵树的描述符规模让 Scout 的输入本身成为上下文或成本问题
     → 重新考虑 hierarchical scout（先按目录/模块聚合，再逐层下钻），
       而不是先把 Map 截断——截断会把 M1 的问题原样带回来。

2. 许多重要被选中的文件超过单文件读取上限
     定向读取仍然读不进来（M1 已知局限中「大文件被完全跳过」仍然成立）
     → 重新考虑大文件读取策略：chunk / 符号提取 / AST 摘要。
       此时才有真实证据说明「完整文件」这一形式不适用。

3. 出现带具体目标导向的检索需求
     例如「针对某一条 ProductDirection 或某个查询找出相关实现」
     → 此时才存在查询意图，重新考虑 hybrid / embedding retrieval。
       在此之前引入向量检索缺少必要的输入。

4. 需要 implementation-level 的依赖与位置信息
     Evolution Planning 需要知道某能力的实现位置、依赖关系或可改造点
     → 重新考虑 symbol / call graph / AST。

5. Repository Map 的启发式分类在多个真实仓库上反复误判
     → 先修正分类规则；只有在规则本身无法表达时，才考虑引入更强的静态分析。
```

每次重访都应当从**真实的失败模式**出发，而不是从「当前机制看起来不够先进」出发。

## References

```text
AGENTS.md
docs/ARCHITECTURE.md
docs/DOMAIN_MODEL.md
docs/ROADMAP.md
docs/retrospectives/m1-repository-analysis.md            §8 Why We Stop Here / §9 Revisit Conditions
docs/validation/m1-repository-analysis-smoke-test.md
docs/validation/m2-product-direction-discovery-smoke-test-round1.md   §10 Revisit condition: TRIGGERED
docs/decisions/0001-separate-workspace-read-and-mutation-capabilities.md
WorkspaceReadPort
RepositoryAnalysisMaterialCollector
RepositoryAnalysisMaterialPolicy
DirectionDiscoveryInputs（U-E1 / R1-E2 的 invocation-local 引用与校验，同源设计）
```
