# ADR-0005: Hierarchical Repository Scout for Oversized Source Catalogs

Status: Accepted

Date: 2026-10-02

> 本 ADR 记录当 flat File Catalog 超出 `maxScoutCatalogBytes` 时，Repository Scout
> 如何按真实目录结构分层下降的决策。
>
> **本 ADR 记录的是决策，不是实现进度。** 当前**尚未实现**；权威状态以代码与
> `ROADMAP.md` 为准。
>
> 当前进度：
>
> ```text
> 已实现    ADR-0004 的四个阶段（Map / Scout / 定向读取 / UseCase 接入）
> 未实现    本 ADR 描述的分层 Scout
> ```
>
> 触发本决策的证据：
>
> ```text
> docs/validation/m2-final-multi-repository-smoke-test.md
>   5 个真实仓库中 4 个因 SCOUT_CATALOG_TOO_LARGE fail-closed
>   → ADR-0004 Revisit Condition 1 的实质形态
> docs/validation/m2-hierarchical-scout-design-reconnaissance.md
>   结构分析：分层可达，但在这些仓库上只有「窄选」可行
> ```

## Context

### 当前失败边界

ADR-0004 的链路在真实仓库上建立了 Repository Map，然后一次性把 **全部** SCOUT_SOURCE
候选序列化成一个 flat File Catalog 交给 File Scout：

```text
RepositoryMap（成功，含 10,375 文件的仓库）
      ↓  取全部 SCOUT_SOURCE
flat File Catalog          ← 这里超限
      ↓
requireCatalogWithinLimit  ← 失败关闭（不截断、不采样、不降级）
```

实测（同一 revision、确定性可复算）：

```text
hm-dianping        84 候选 →  14,837 B   PASS
mall              491 候选 →  99,181 B   FAIL (1.51×)
memos             880 候选 → 141,079 B   FAIL (2.15×)
awesome-llm-apps  854 候选 → 179,351 B   FAIL (2.74×)
langflow        4,572 候选 → 889,253 B   FAIL (13.6×)
maxScoutCatalogBytes = 65,536
```

**失败点唯一地落在 LLM 面向的 flat catalog，而不是 Repository Map 的构建。**
5 个仓库（含 221 MB 的 langflow）全部成功建立 Map。

### 侦察给出的结构事实

```text
在当前描述符写法下，File Catalog ≈ 219 字节/文件
→ 65,536 B 大约对应 299 个源文件（观测到的容量估计，不是硬性文件数限制）

根层 region 目录很小：1–11 个 region → 335–2,119 B
四个失败仓库都能在 1–5 轮下降内把候选压到上限内：
  mall 1 轮、awesome-llm-apps 1 轮、memos 3 轮、langflow 5 轮
```

**并且：在这些仓库上，按体积排序的结构模拟显示，浅层保留 ≥3 个分支都会超限**——
可达的缩减是「窄选」，不是「保留若干分支即覆盖产品」。
（该模拟是按后代文件数排序的**结构边界**，不是对 Region Scout 实际选择的预测。）

### 真正的问题

```text
不是「Map 无法承载真实仓库」，也不是「需要语义检索」，
而是「一次交给模型的 flat catalog 与仓库大小线性相关」。
```

因此在 Map 之上增加**一层按真实目录结构的分层导航**，把交给 File Scout 的候选集合
先压到预算之内。不引入 RAG / embedding / AST / chunking：它们各自需要一套新的质量标准，
而当前证据指向的是目录结构的规模问题，不是语义相关性问题。

## Decision

保留现有 Repository Map、Foundation lane、File Scout、`RF-*` 校验、Read Planner 与
最终的 Repository Analysis。

**当且仅当** flat SCOUT_SOURCE File Catalog 超出 `maxScoutCatalogBytes` 时，启用分层 Scout：

```text
1. 从既有 Repository Map 构造有界的 Region Catalog（目录前缀视图，无文件内容）。
2. Region Scout 返回有序的 invocation-local `RR-*` 引用。
3. Application 校验并解析这些引用（命中本轮 Region Catalog，否则失败）。
4. 每个被选中的 Region **独立**探索：
     - 若该分支的局部 File Catalog 已在既有字节预算内 → 对该分支运行现有 File Scout；
     - 否则在该分支上递归一次 Region Scout。
5. 因此可能有多个终态分支各自调用 File Scout。
6. 保序：Region Scout 的顺序 = 分支优先级；File Scout 的顺序 = 分支内优先级。
7. 合并各分支的 File Scout 结果：**保序 round-robin**
     - 先取每条分支的第一个文件（按分支优先级），再取每条分支的第二个，依次类推。
8. 去重后，把有序候选交给**现有全局** RepositoryReadPlanner。
9. 现有的 `maxFiles` / `maxFileBytes` / `maxTotalBytes` 仍是最终读材料的预算。
```

**flat File Catalog 已在预算内的小仓库，完全旁路 Region Scout，现有 File Scout 路径不变。**

### 两个必须分开的预算

```text
导航预算（Navigation budget）
    约束 Region / File Scout 的探索范围与 LLM 调用增长
    ——本 ADR 新增，是技术/配置守卫，不是 Domain invariant

材料预算（Material budget）
    约束最终读源码的规模
    ——既有 maxFiles / maxFileBytes / maxTotalBytes，本 ADR 不改动
```

**递归的停止条件必须是「实际序列化后的 catalog 字节数满足配置上限」，而不是固定目录深度。**

### 导航守卫（MVP 默认值，技术/配置守卫，非 Domain invariant）

| 配置项 | 建议默认 | 证据 |
|---|---|---|
| 单次 Region Scout 可选 region 数上限 | **6** | 侦察中根层 frontier 为 1–11；有用的选择是窄的（观测到的达标下降多为 1 个分支）。6 是防止单轮选择无界膨胀的守卫，不替代字节停止条件 |
| 沿单条分支的最大 Region Scout 轮数 | **8** | 观测到的需求是 ≤5 轮（langflow）；8 留出余量。这是**守卫上限，不是目标值** |
| 单次分析的总 Region Scout 调用数上限 | **12** | 侦察只测了**单支**下降（≤5 轮），**没有**测多分支下的调用数；12 是「若干分支 × 若干轮」的**暂定守卫**，必须在实现时用真实数据校准 |
| 终态分支（File Scout 调用）数上限 | **6** | 与「单轮可选 region 数」同源；终端分支数不应超过一次选择所能产生的规模 |

以上数值是**建议的 MVP 默认值**，用于让探索有界；证据不充分处已显式标注为暂定。
不因为「看起来合理」就当成已确定的领域语义。

### 失败语义（一律 fail-closed）

```text
Region Catalog 自身超出其预算          → 失败，不继续探索该层
递归到叶子仍装不下（不可约的过大叶子）  → 失败，不截断、不采样
导航预算耗尽（轮数 / 调用数）           → 失败，不返回部分结果
非法 / 重复 / 跨调用的 `RR-*` 引用      → 失败（与 ADR-0004 的 RF 校验同一原则）
```

**明确禁止**：随机采样降级、静默截断、自动提高 catalog 上限。

### 保持不变的组件

```text
RepositoryMap / RepositoryMapBuilder   不变（Region Catalog 是它之上的视图）
Foundation lane                        不变（仍独立于 Region Scout 选取）
RepositoryScoutInputs / RepositoryScoutExtraction   契约不变（仍消费 bounded 描述符集合）
`RF-*` 校验 / RepositoryInspectionPlan              不变
RepositoryReadPlanner                  预算语义不变；见下
RepositoryAnalysisExtraction           不变
```

**唯一需要确认的集成点：** Read Planner 的输入目前是 `RepositoryInspectionPlan`。
分层合并产出的是一个**有序、去重后的候选描述符集合**。若现有输入形状能表达它（例如作为
有序区域），则无契约变更；否则需要一处最小泛化，让 Planner 接受有序描述符集合。
**Planner 的规划与预算逻辑本身不变。** 该点应在实现时确认，不在本 ADR 内定案。

## Rationale

**为什么是分层而不是语义检索。** 触发本决策的问题是「flat catalog 与仓库大小线性相关」，
是规模问题，不是相关性问题。Reconnaissance 显示分层能在 1–5 轮内把候选压进既有预算，
且不引入新的质量标准。

**为什么 `RR-*` 与 `RF-*` 同为 invocation-local。** 与 ADR-0004 第 2 条同源：引用是
调用内的闭集指针，越界即可判定失败；跨调用复用引用会让「这条引用属于哪一层」变得不可判定，
因此**跨调用引用一律拒绝**。

**为什么分支独立而不是全局一次选择。** 一个 Region 的后代集合能否装进预算，只取决于该分支
自身；把它与别的分支绑在一起决定，会让「某个分支可读」依赖于「另一个分支多大」。
独立探索让每个分支的终止条件局部化。

**为什么合并用保序 round-robin。** 各分支的 File Scout 顺序表达的是**分支内**优先级；
直接拼接会让第一条分支吃满全部预算。Round-robin 让每条分支的第一优先级文件先进入候选，
把「分支间公平」与「分支内保序」同时保住。最终取舍仍由 Read Planner 的材料预算决定。

**为什么导航预算与材料预算分开。** 前者约束的是 LLM 调用的增长（成本与失败点），
后者约束的是读源码的规模。把两者混为一谈会导致「为了少调一次模型而多给文件份额」这类
无依据的耦合。

## Consequences

### Positive

- 使 Repository Analysis 在真实规模仓库上重新可用（当前 4/5 不可用）
- 复用既有全部组件与语义：Map、File Scout 契约、`RF-*` 校验、Read Planner、材料预算
- 分层导航是确定性的：同一棵树得到同一份 Region Catalog
- 停止条件与预算同源（字节），不引入新的质量阈值

### Negative

- 引入一类新的 AI 调用（Region Scout），因此增加成本与新的失败点
- 导航预算需要真实数据校准；本 ADR 给的默认值部分为暂定
- 对超大仓库，可达的缩减可能是「窄选」——保留下来的材料未必能覆盖产品的全部模块
  （侦察在 mall 上已观察到：无法在一次 File Scout 内保留全部模块）

### Risks

- **Region Scout 选偏。** 只按目录结构选择，可能选中与产品无关的大分支
  （侦察中按体积下降会走向前端图标资源）。缓解：Region Scout 可看到语言与 roleHints
  等确定性提示；最终材料仍受 Read Planner 约束。
- **超限从 File Catalog 平移到 Region Catalog。** 侦察显示单个 region 可能有数百个子节点。
  缓解：Region Catalog 也需要自己的字节守卫与失败语义。
- **导航预算爆炸。** 每轮可选多个 region、每个都要递归，调用数可能快速增长。
  缓解：总调用数守卫；超限即失败。

## Revisit Conditions

```text
1. 导航守卫（轮数 / 调用数 / 每轮 region 数）在真实仓库上被证明过紧或过松
     → 用真实运行数据校准，而不是按感觉调整。

2. 分层在多个真实仓库上稳定产生「窄选导致材料不足以解释产品」
     → 重新考虑材料预算（maxFiles / maxTotalBytes）或另一层聚合方式；
       不引入采样或截断。

3. Region Catalog 在真实仓库上频繁超出自身预算
     → 重新考虑 Region 描述的粒度或分层起点，而不是先截断。

4. 出现真正的查询意图（例如针对某条 ProductDirection 找实现）
     → 此时才重新考虑 ADR-0004 Revisit Condition 3 的语义检索方向。
```

## References

```text
AGENTS.md
docs/ARCHITECTURE.md
docs/validation/m2-final-multi-repository-smoke-test.md              （触发证据：4/5 fail-closed）
docs/validation/m2-hierarchical-scout-design-reconnaissance.md       （结构分析与守卫取值依据）
docs/decisions/0004-two-stage-repository-understanding-with-validated-file-references.md
    Revisit Condition 1（本 ADR 的直接来源）
RepositoryMap / RepositoryMapBuilder
RepositoryScoutInputs / RepositoryScoutExtraction（catalogPayloadBytes）
RepositoryInspectionPlan / RepositoryReadPlanner
DirectionDiscoveryInputs（U-E1 / R1-E2：invocation-local 引用与校验，同源设计）
```
