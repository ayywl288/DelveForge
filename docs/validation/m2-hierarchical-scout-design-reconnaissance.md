# M2 Task 10A-1 — Hierarchical Scout 实现前侦察记录

**Status:** Validation Record / **Not Source of Truth**
**Last Updated:** 2026-10-02
**性质:** 实现前侦察。**未修改生产代码 / Prompt / 预算 / 配置；0 次 Provider 调用；0 次 fake Provider 调用。**
**依据:** `docs/validation/m2-final-multi-repository-smoke-test.md`（4/5 仓库 SCOUT_CATALOG_TOO_LARGE）

> 本文档记录对「在 flat File Catalog 之前增加 Region-level 分层导航」这一**待验证假设**的
> 确定性结构分析。它不新增规则、不替代权威文档、不提出实现。
> 领域语义以 `DOMAIN_MODEL.md` 为准，架构以 `ARCHITECTURE.md` / `AGENTS.md` 为准，
> 长程方向以 ADR-0004 为准。

---

## 1. Scope

回答一个问题：

> 在 oversized flat File Catalog 前增加 Region-level hierarchical navigation，
> 是否真的能在有限轮次内把待暴露给 File Scout 的 SCOUT_SOURCE 集合缩小到
> `maxScoutCatalogBytes` 可承载的规模？

**方法限定：不使用 LLM、不模拟 LLM 的选择。** 只对真实提交树做确定性结构分析，
用**生产**类（`RepositoryMapBuilder` / `RepositoryScoutInputs` / `RepositoryScoutExtraction`
的 `catalogPayloadBytes`）复算字节数。

---

## 2. Why This Reconnaissance Exists

最终多仓 Smoke 观察到 4/5 仓库在 `requireCatalogWithinLimit` 处 fail-closed：

```text
hm-dianping       SCOUT_SOURCE   84    File Catalog  14,837 B   PASS
mall              SCOUT_SOURCE  491    File Catalog  99,181 B   FAIL (1.51×)
memos             SCOUT_SOURCE  880    File Catalog 141,079 B   FAIL (2.15×)
awesome-llm-apps  SCOUT_SOURCE  854    File Catalog 179,351 B   FAIL (2.74×)
langflow          SCOUT_SOURCE 4,572   File Catalog 889,253 B   FAIL (13.6×)
maxScoutCatalogBytes = 65,536
```

四个仓库的 Repository Map **都成功建立**。失败点在「把全部源码候选一次性序列化成
一个 flat File Catalog」这一步。因此待验证的问题是「LLM 面向的 flat catalog 是否可分层缩小」，
而不是「Map 能否建立」。

---

## 3. Current Failure Boundary

`RepositoryUnderstanding.understand()` 的顺序（`RepositoryUnderstanding.java`）：

```text
135  RepositoryMap map = mapBuilder.build(workspaceRef, analyzedRevision);   ← 这一步全部成功
136  requireSourceCandidates(map, analyzedRevision);
138  RepositoryScoutInputs scoutInputs = RepositoryScoutInputs.of(map);       ← 取全部 SCOUT_SOURCE
139  requireCatalogWithinLimit(scoutInputs, analyzedRevision);                ← 在这里失败
141  scoutExtraction.scout(scoutInputs);                                     ← 从未到达
```

`catalogPayloadBytes` 量的是**用户消息**，即 `{"analyzedRevision":…,"fileCatalog":[…]}` 的
UTF-8 字节数；每条描述符含 `reference / path / sizeBytes / language / materialKind / roleHints`。
引用 `RF-*` 是**每次调用重新编号**的（`RepositoryMap.of` 强制 `RF-1…RF-n`）。

---

## 4. Repository Baselines

侦察前逐个核对（未修改任何仓库）：

| 逻辑名 | 目录 | HEAD | 工作树 | SCOUT_SOURCE | File Catalog | 超限 |
|---|---|---|---|---|---|---|
| mall | `mall-master` | `3910bf80a972…` | clean | 491 | 99,181 B | 1.51× |
| langflow | `langflow-main` | `4c291b766c85…` | clean | 4,572 | 889,253 B | 13.6× |
| memos | `memos-main` | `aea105e081c9…` | clean | 880 | 141,079 B | 2.15× |
| awesome-llm-apps | `awesome-llm-apps-main` | `b7b5dd3bfc11…` | clean | 854 | 179,351 B | 2.74× |
| hm-dianping | `java-comment-main` | `8a5fa2b607ed…` | clean | 84 | 14,837 B | 0.23× |

**本工具复算出的 File Catalog 字节数与前一轮 Smoke 逐字节一致**——说明它复用的是同一套
生产分类与序列化逻辑，而不是另写了一个近似模型。

---

## 5. Proposed Region Model（分析用，非实现）

侦察中使用的 Region 只是 **`RepositoryMap` 上的一个路径前缀视图**，不引入任何领域概念：

```text
pathPrefix              目录前缀（真实提交路径）
directSourceFiles       直接位于该目录的 SCOUT_SOURCE 数
descendantSourceFiles   该前缀下全部 SCOUT_SOURCE 数
childRegionsWithSource  有源码的直接子目录数
languages / roleHints   后代语言与结构角色提示的枚举集合
```

Region Catalog 原型序列化：

```json
{"analyzedRevision":"…","regionCatalog":[
  {"reference":"RR-1","pathPrefix":"…","directSourceFiles":N,
   "descendantSourceFiles":M,"childRegionsWithSource":K,
   "languages":["JAVA"],"roleHints":["API_ENTRY"]}]}
```

**描述符中不含** purpose / capability / importance / confidence / score / rationale，
也不含任何文件内容。

结构常数（由数据得出）：

```text
File Catalog 字节 / 源文件 ≈ 219（65,514 / 299；65,410 / 299）
→ 65,536 B 上限 ≈ 约 299 个源文件。这是整个分析最关键的一条约束。
```

「分层」能做到的上限就是：**把一次交给 File Scout 的候选压到约 ≤300 个文件。**

---

## 6. mall Path-Tree Analysis

```text
源文件 491；最大路径深度 10；中位深度 8；全部带源前缀 95 个
根层 region：7 个（7 个 Maven 模块），根 Region Catalog 1,639 B
最宽单 region：mall-portal/src/main/java/com/macro/mall/portal（7 个子 region）
最长单子链：7 层（mall-*/src/main/java/com/macro/mall/…）
flat 目录：1 个 —— mall-mbg/src/main/java/com/macro/mall/model（152 个直接文件）
```

根层 7 个模块都是「深单链 + 末端分叉」，因此 depth 1–7 的 frontier 恒为 7 个 region，
每层 Region Catalog ≈1.6–1.8 KB，**远低于上限**。

单支下降：`root → mall-mbg`（1 轮）即得 230 个文件 → File Catalog **45,324 B，可容纳**。
但保留多个模块不行：

```text
depth 1   n=1  230 files  45,324 B  ✅
depth 1   n=3  451 files  90,914 B  ❌
depth 1   n=6  483 files  97,519 B  ❌
```

**结论：mall 可分层缩小（1 轮），但代价是只能保留 1 个模块。**

---

## 7. langflow Path-Tree Analysis

```text
源文件 4,572；最大深度 13；中位深度 6；全部带源前缀 1,432 个
根层 region：3 个；根 Region Catalog 739 B
最宽单 region：src/frontend/src/icons —— 202 个子 region
最长单子链：2 层
flat 目录：1 个 —— src/backend/base/langflow/alembic/versions（119 个直接文件）
```

逐层 frontier（全量展开的上界）：

| depth | regions | Region Catalog | 说明 |
|---|---|---|---|
| 1 | 3 | 739 B | 根 |
| 2 | 11 | 2,296 B | |
| 3 | 41 | 7,918 B | |
| 4 | 65 | 12,876 B | |
| **5** | **412** | **84,699 B** | **全量展开时 Region Catalog 自身超过 64 KiB** |
| **6** | **530** | **112,559 B** | 同上 |
| 7 | 169 | 37,753 B | |

单支下降（模拟）：

```text
round 0  root               children=3    regionCat=739 B    descendants=4,572  fileCat=888,349  ❌
round 1  src                children=6    regionCat=1,359 B  descendants=3,706  fileCat=697,434  ❌
round 2  src/frontend       children=2    regionCat=542 B    descendants=1,763  fileCat=342,763  ❌
round 3  src/frontend/src   children=19   regionCat=3,915 B  descendants=1,749  fileCat=340,573  ❌
round 4  …/src/icons        children=202  regionCat=42,171 B descendants=404    fileCat=68,463   ❌
round 5  …/icons/IBM        children=3    regionCat=664 B    descendants=4      fileCat=759      ✅
```

**三条必须记录的观察：**

1. **langflow 需要 5 轮**才降到上限内，远多于其它仓库。
2. **最大单 region（`src/frontend/src/icons`，404 个文件）自身就 68,463 B，仍超上限**——
   即「取最大的一个分支」在这一层也不够，必须再切一层到单个图标目录（4 个文件）。
3. **size-greedy 下降会走向前端图标资源，而不是产品核心**（round 1–4 全部落在 `src/frontend`）。
   这**不是**对 Region Scout 行为的预测（Region Scout 会按语义而非体积选），但它说明：
   在 langflow 上「能让 catalog 装下」的那条结构路径，与「能解释产品做什么」的材料几乎没有交集。

保留多个分支在 langflow 上**任何浅层都不行**：

```text
depth 1 n=3 → 4,572 files → 888,349 B ❌     depth 3 n=3 → 612,450 B ❌
depth 2 n=1 → 343,882 B ❌                    depth 5 n=3 → 164,450 B ❌
depth 5 n=1 → 299 files → 65,410 B ✅（勉强）  depth 5 n=6 → 274,826 B ❌
```

---

## 8. memos Path-Tree Analysis

```text
源文件 880；最大深度 6；中位深度 3；全部带源前缀 118 个
根层 region：11 个；根 Region Catalog 1,957 B
最宽单 region：web/src/components（24 个子 region）
最长单子链：0
flat 目录：无（≥100 直接文件的目录：0）
```

单支下降：

```text
round 0  root                 children=11  regionCat=1,957 B  descendants=880  fileCat=140,952  ❌
round 1  web                  children=1   regionCat=312 B    descendants=465  fileCat=78,727   ❌
round 2  web/src              children=9   regionCat=1,758 B  descendants=460  fileCat=78,007   ❌
round 3  web/src/components   children=24  regionCat=4,807 B  descendants=272  fileCat=47,782   ✅
```

**memos 可分层缩小（3 轮 → 272 个文件）。** 但同样是「只能窄选」：

```text
depth 1 n=1 → 78,727 B ❌    n=3 → 117,392 B ❌
depth 3 n=1 → 55,374 B ✅    n=3 →  76,195 B ❌    n=6 → 91,017 B ❌
depth 5 n=6 → 11,451 B ✅（但只剩 64 个文件）
```

memos 的结构相对健康（无 flat、无长单链、深度浅），是四个里最容易被分层处理的。

---

## 9. awesome-llm-apps Path-Tree Analysis

```text
源文件 854；最大深度 10；中位深度 4；全部带源前缀 428 个
根层 region：10 个；根 Region Catalog 2,119 B
最宽单 region：rag_tutorials（24 个子 region）
最长单子链：1
flat 目录：无
```

单支下降：

```text
round 0  root                  children=10  regionCat=2,119 B  descendants=854  fileCat=178,851  ❌
round 1  advanced_ai_agents    children=3   regionCat=813 B    descendants=299  fileCat=65,514   ✅
```

**1 轮即可，但只差 22 字节。** 299 个文件 = 65,514 B，上限 65,536 B。
这是本轮最脆的一个数据点：**任何描述符变长（更长的路径、更多 roleHint、更多语言）都会把它翻到超限。**

```text
depth 1 n=1 → 65,514 B ✅（余量 22 B）    n=3 → 147,085 B ❌
depth 2 n=1 → 55,899 B ✅                 n=3 →  87,835 B ❌
```

这条库本身是「多个互相独立的小应用」的集合形态，根层 10 个覆盖不同主题的目录
（`advanced_ai_agents` / `rag_tutorials` / …）。分层在这里**天然贴合仓库形态**，
但**保留多个主题就会立刻超限**——与「集合型仓库的价值恰在于跨主题的样品」直接冲突。

---

## 10. hm-dianping Regression Observation

```text
SCOUT_SOURCE 84；File Catalog 14,837 B < 65,536 B
单支下降 round 0：root children=1 → descendants=84 → fileCat=14,800 B ✅
```

**现有 flat File Catalog 已经可用，不需要进入分层。** 因此 §17 的回归要求成立：
分层导航必须能在「flat catalog 已在上限内」时**完全旁路**，交给现有 File Scout，
路径不变（无 Provider 调用差异、无行为变化）。

---

## 11. Region Catalog Scalability

**Q5 的答案是：会，但只在特定条件下。**

```text
根层 frontier：       1–11 个 region → Region Catalog 335–2,119 B（全部远低于上限）
单个 region 最宽子节点：langflow src/frontend/src/icons = 202 个 → 42,171 B（我的原型描述符）
```

- 只要每轮只展示**已选 region 的直接子节点**，Region Catalog 都在 42 KB 以内。
- 但 **langflow 的 202 子节点**已经占掉上限的 64%（我的描述符含 languages+roleHints 数组，
  偏保守）。若描述符更丰富，或某个目录有数百个直接子目录，**Region Catalog 会自己超过 64 KiB**。
- 全量展开（一次性列出某一层的**所有** region）在 langflow depth 5/6 确实超限
  （84,699 / 112,559 B）——但那是「不选择、只展开」，不是分层设计的工作方式。

**结论：分层不会自动把 `SCOUT_CATALOG_TOO_LARGE` 从 File Catalog 平移到 Region Catalog，
但它确实把同一类风险引入了 Region Catalog**，需要一个等价的上限与失败语义。

---

## 12. Hierarchy Depth / Round Analysis

```text
路径深度（SCOUT_SOURCE）：  最大 6–13；中位 3–8
达到 ≤64 KiB 所需的下降轮数（单支、结构模拟）：
    hm-dianping  0 轮（已满足）
    mall         1 轮（depth 2）
    awesome      1 轮（depth 2）
    memos        3 轮（depth 3）
    langflow     5 轮（depth 5）
```

- 中位路径深度 3–8，说明「有意义的分叉」大多出现在 depth 2–5。
- 超过约 depth 5 之后，region 的 descendant 数普遍降到个位数，再下降只剩语义收益、没有体积收益。
- **observed requirement ≤ 5 轮**；考虑到还有更大的仓库未测，建议的 `maxRegionScoutRounds` 证据范围为
  **6–8**（作为守卫上限，不是目标值）。**本 Task 不实现、不写死。**

---

## 13. Non-Reducible Shapes

逐类核对（§12 要求的四种形状）：

| 形状 | 是否materially出现 | 证据 | 是否导致不可约 |
|---|---|---|---|
| **Flat directory** | 是，2 处 | mall `mall-mbg/…/model` = 152 直接文件；langflow `…/alembic/versions` = 119 | **否**（152 文件 = 单个 region 内可容纳，只是无法再细分） |
| **Extremely broad sibling frontier** | 是，1 处显著 | langflow `src/frontend/src/icons` = 202 个同级子 region | **否，但接近**：该 frontier 的 Region Catalog 42 KB；该 region 自身 404 文件仍超 File Catalog 上限 |
| **Deep single-child chain** | 是，1 处显著 | mall 最长单子链 **7 层**（`mall-*/src/main/java/com/macro/mall/`） | **否**（末端会分叉），但意味着最多 7 轮**零缩减** |
| **Generated/vendor-like 集中** | 是 | mall `mall-mbg`（MyBatis 生成代码，230 文件集中在 `model`+`mapper`）；langflow `icons` | **部分**：mall 的生成代码恰好是最大模块，挤占了「保留业务模块」的空间 |

**没有任何一个仓库是「不可约」的**——四个都在 1–5 轮内到达可容纳的集合。
但 langflow 的「可约」是**语义上有害的**（见 §7），这是形状之外的问题。

---

## 14. Existing File Scout Compatibility

侦察过程本身就是一次兼容性验证：工具用**未修改的**生产调用链

```text
List<RepositoryMapEntry>（后代文件集，按 RF-1…RF-n 重新编号）
        ↓  RepositoryMap.of(revision, entries)      （未修改）
        ↓  RepositoryScoutInputs.of(map)            （未修改）
        ↓  RepositoryScoutExtraction.catalogPayloadBytes(inputs)   （未修改）
```

并成功复算出与前一轮完全一致的字节数。

**结论：现有 File Scout 契约可以保持不变。** 分层导航只需要向它交付一个
**bounded `List<RepositoryMapEntry>`**；`RF-*` 编号本来就是按调用重新分配的，
所以「后代子集形成新的一次调用」与现有语义天然一致。

**唯一需要注意的隐含契约**：分层产出的子集必须是**该 revision 上 SCOUT_SOURCE 的真子集**，
且引用校验（`findInMap`）仍以「子集对应的那张 Map」为准——否则模型引用会在两张 Map 之间错位。
这不是契约变更，而是调用方式的约定。

**未发现不可避免的契约变更。**

---

## 15. Bounded Failure Cases

未来实现需要显式处理的边界（命名仅为示意，**本 Task 不实现**）：

```text
REGION_CATALOG_TOO_LARGE
    单轮 frontier 的 Region Catalog 超过其上限。
    证据：langflow 单 region 有 202 个子 region（42 KB）；全量展开 depth 5 = 84 KB。
    需要：与 File Catalog 同等的「先量再调」守卫；以及「某个目录子节点过多时怎么办」的抉择。

HIERARCHY_NOT_REDUCIBLE
    下降到叶子仍然装不下（例如一个 flat 目录内含 >300 个文件）。
    本轮 4 个仓库均未复现（mall 最大 flat = 152，langflow = 119），但形状是真实存在的。

MAX_REGION_SCOUT_ROUNDS_EXCEEDED
    轮次耗尽仍未达到可容纳集合。
    证据：langflow 需要 5 轮，是四个里最多的。

（附带）SEMANTICALLY_DEGENERATE_REDUCTION
    结构上达到了上限，但保留下来的材料不能解释产品（langflow 的图标路径）。
    这不是一个「失败」，因此没有自然的失败语义——但它是设计必须面对的结果。
```

---

## 16. Findings

### Q1 — 当前失败的本质

```text
是 LLM-facing flat File Catalog 的可扩展性，不是 Repository Map 的可扩展性。
```

证据：5 个仓库（含 10,375 文件 / 221 MB 的 langflow）全部成功建立 Map；
失败点唯一地落在 `requireCatalogWithinLimit`（`RepositoryUnderstanding.java:139`），
而 Map 构建在其之前（`:135`）已经成功。

### Q2 — 每个失败仓库的真实目录结构是否支持分层缩减

```text
mall              支持（1 轮 → 230 文件 → 45 KB）
memos             支持（3 轮 → 272 文件 → 48 KB）
awesome-llm-apps  支持（1 轮 → 299 文件 → 65.5 KB，余量 22 B）
langflow          结构上支持（5 轮），但保留下来的材料语义上无关产品（§7）
```

### Q3 — 大约在什么深度进入 64 KiB 兼容区间

```text
observed: depth 2（mall/awesome）、depth 3（memos）、depth 5（langflow）
对应下降轮数: 1 / 3 / 5；hm-dianping 无需下降
Hard constraint: 单个 File Scout 调用最多容纳 ≈299 个源文件
```

### Q4 — 是否存在不可约或难以约的形状

```text
不存在「不可约」。存在 4 类形状（flat / broad / deep-chain / generated-concentration），
但都不致命；唯一严重的是 langflow 的「可约但语义退化」。
```

### Q5 — Region Catalog 自身会不会超过 64 KiB

```text
会（在特定条件下）：单个 region 的子节点数可达 202（langflow icons），原型描述符下 42 KB；
全量展开某一层可达 84–112 KB。
→ 分层把同类风险引入了 Region Catalog，需要等价守卫。
```

### Q6 — 现有 File Scout 能否保持不变

```text
能。侦察全程使用未修改的生产序列化链，并逐字节复现了前一轮的字节数。
分层只需交付 bounded List<RepositoryMapEntry>。
```

### Q7 — 未来的有界失败情形

见 §15：`REGION_CATALOG_TOO_LARGE` / `HIERARCHY_NOT_REDUCIBLE` /
`MAX_REGION_SCOUT_ROUNDS_EXCEEDED`，以及没有自然失败语义的
`SEMANTICALLY_DEGENERATE_REDUCTION`。

### Q8 — 证据是否支持继续该设计

```text
SUPPORTED WITH REQUIRED DESIGN CHANGES
```

支持的部分：四个失败仓库**都能**被分层压到现有上限内（1–5 轮），
且 File Scout 契约无需改动。

**必须带上的设计约束（否则会做错）：**

```text
1. 停止条件必须是「字节预算满足」，不能是固定深度或固定轮数。
2. 不能假设「每轮保留少量 region 就能覆盖产品」：在全部四个仓库上，
   保留 ≥3 个分支在浅层一律超限；只有窄选（约 1 个分支 / ≤300 文件）可行。
3. Region 描述符必须保持精简，或对 frontier 宽度设限——
   否则超限只是从 File Catalog 平移到 Region Catalog。
4. 需要显式的有界失败类别（§15）。
5. 必须接受一种结果：结构上达标 ≠ 语义上有用（langflow）。
   设计要能面对它，而不是假装它不会发生。
```

---

## 17. Recommendation

```text
建议：按 SUPPORTED WITH REQUIRED DESIGN CHANGES 继续，但先解决 §16 Q8 列出的五条约束，
      尤其是第 2 条（窄选是常态，不是退化）与第 5 条（语义退化不是失败）。

maxRegionScoutRounds        observed ≤5；建议证据范围 6–8（守卫上限，非目标值）
每轮 region selection 数量   evidence 显示 3–6 对这四个仓库都过宽；
                            建议范围 1–3，并把「保留多少」交给字节预算而不是固定值
                            （本 Task 不实现、不写死任何数值）
```

**注意：以上是 observed / recommended 的区分，不是配置决定。** 本轮不修改 `application.yml`。

---

## 18. Reproduction Notes

```text
工具（build 目录，未提交、不属任何生产模块）：
  target/smoke-m2-final/Recon.java
    - 用生产 RepositoryMapBuilder 建立真实 Map
    - 取 SCOUT_SOURCE，按 '/' 前缀聚合出确定性路径树
    - 用生产 RepositoryScoutExtraction.catalogPayloadBytes 复算 File Catalog 字节
      （子集按 RF-1…RF-n 重新编号后重建 RepositoryMap）
    - 用临时确定性序列化器量 Region Catalog 字节
    - 不读文件内容、不调用 Provider、不写任何存储、不改任何仓库

复算命令（classpath 由 mvn dependency:build-classpath 生成）：
  java -cp "<modules' target/classes>;<cp.txt>" \
       backend/delveforge-app/target/smoke-m2-final/Recon.java \
       "mall=…/mall-master" "langflow=…/langflow-main" \
       "memos=…/memos-main" "awesome-llm-apps=…/awesome-llm-apps-main" \
       "hm-dianping=…/java-comment-main"

原始输出：target/smoke-m2-final/recon.txt（不提交）
```

确定性：路径树、前缀计数、Region Catalog 字节、File Catalog 字节全部只依赖
commit tree 与序列化，**对同一 revision 可逐字节复现**（File Catalog 字节与前一轮
Smoke 完全一致即为证）。唯一不确定的是「Region Scout 会选哪些 region」——本轮**没有**
对这一点做任何预测。

```text
未改动确认
  0 次 Provider 调用（无 LLM、无 fake）
  0 处生产代码 / Prompt / 预算 / 配置改动
  5 个测试仓库 HEAD 与工作树均未变
```
