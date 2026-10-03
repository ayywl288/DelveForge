# M2 — Repository Analysis V3 生产实现代码走查报告

**Status:** Validation Record / **Not Source of Truth**
**Last Updated:** 2026-10-03
**性质:** 只读侦察。**未修改生产代码 / 测试 / 配置 / 文档 / Git 历史。**
**范围:** 从 `POST /api/software-assets/{id}/analysis` 到持久化 `RepositoryProfile` 的完整 V3 链路。

> 本文档以**当前生产代码**为准（`main` @ `9c8f014`），ADR 只用于解释意图。
> 代码是事实来源；当本文与代码冲突时，以代码为准，并把冲突记在 §21。
>
> 目的：让没有逐个跟过 Task 10A-3 ~ 10B-2 的读者，能够按图索骥地读完这套实现。
> 因此本文优先给**流程图、表格与短代码路径**，不整段抄源码。

---

## 0. 阅读顺序建议

```text
先读 §1（全景）→ §2（revision）→ §3（Map）→ §4（两条通道）
再按路径二选一：§5（小仓库）或 §6（大仓库）
然后合流：§7 → §8（规划）→ §9/§11（凭据边界两个点）→ §10（读取）→ §12（最终分析）
收尾：§13（Evidence）→ §14（Profile 与持久化）→ §15（失败分类）
工程视角：§16（装配）→ §17（在用 vs 保留）→ §18（演进史）→ §19（完整例子）→ §20（包地图）
```

一句话版本：

```text
一次分析 = 固定一个 revision
         → 看清整棵已提交树（只有元数据）
         → 决定「读哪些文件」（Foundation 确定性地选，源码交给模型指认）
         → 真的把文件读进来
         → 抹掉凭据
         → 交给模型得出结论
         → 校验依据指向真实读过的文件
         → 建领域对象，写一次库
```

---

## 1. 从真实入口走一遍

### 1.1 请求

```text
POST /api/software-assets/{id}/analysis      请求体：无
→ 201 RepositoryProfile
```

端点**不接收请求体**：分析针对的 revision 与材料全部由服务端决定。客户端即使发送
`analyzedRevision` / `evidence` 也不会被读取。

### 1.2 分层与职责

| # | 类 · 方法 | 层 | 输入 | 输出 | 主要职责 | 关键不变量 / 失败条件 |
|---|---|---|---|---|---|---|
| 1 | `SoftwareAssetController.analyze` | API | `PathVariable id` | `RepositoryProfileResponse` | 只做形状映射，不判断可读性、不碰 Workspace、不接触 AI | 无业务判断（RULE-ARCH-005） |
| 2 | `AnalyzeRepositoryUseCase.analyze` | Application 编排 | `SoftwareAssetId` | `RepositoryProfile` | 串起「前置条件 → 理解 → 提取 → 建领域对象 → 保存」 | 保存是**最后一步且只发生一次** |
| 3 | `SoftwareAsset.requireAnalysisAllowed` | Domain | — | — | 读取权限判定（INV-A01） | `readPermissionAllowed == false` → `SoftwareAssetNotReadableException` |
| 4 | `WorkspaceReadPort.isReadableRepository` | Infrastructure 能力（Application 接口） | `WorkspaceRef` | `boolean` | 判断位置是不是可读的本地 git worktree | 否 → 编排层抛 `RepositoryNotAnalyzableException` |
| 5 | `WorkspaceReadPort.headRevision` | 同上 | `WorkspaceRef` | `String` commit id | **唯一一次**解析 HEAD | 空仓库 / 无法解析 → `WorkspaceException` |
| 6 | `RepositoryUnderstanding.understand` | Application 策略 | `WorkspaceRef` + `analyzedRevision` | `List<RepositorySourceFile>`（**已过凭据边界**） | 建 Map → 选 Scout 路径 → 规划 → 读取 → 净化 | 见 §15；失败一律不产出半成品 |
| 7 | `RepositoryAnalysisExtraction.extract` | Application 策略 | 材料 | `RepositoryAnalysisProposal` | 调模型、严格解析、**校验依据指向送出的文件** | 任一不合法 → `AiGatewayException` |
| 8 | `RepositoryProfile.create` | Domain | 11 个参数 | `RepositoryProfile` | 判定内容是否构成合法领域状态 | `purpose` / `analyzedRevision` 非空白；各段元素非空白 |
| 9 | `RepositoryProfileRepository.save` | Port → Infrastructure | `RepositoryProfile` | — | 一次事务写入 header + 段 + 依据 | id 已存在 → `RepositoryProfileAlreadyExistsException` |
| 10 | `RepositoryProfileResponse.from` | API | 领域对象 | DTO | 映射为响应（`sourceRef` 是仓库内相对路径） | 不暴露宿主机绝对路径 |

### 1.3 编排代码的形状

`AnalyzeRepositoryUseCase.analyze`（唯一生产入口）的顺序固定：

```java
asset = softwareAssetRepository.findById(id)   // ① 找不到 → 404
asset.requireAnalysisAllowed();                // ② INV-A01 → 409
ref = new WorkspaceRef(asset.location());
if (!workspace.isReadableRepository(ref)) …    // ③ 不是可读 repo → 409
analyzedRevision = workspace.headRevision(ref); // ④ 只解析一次
material  = repositoryUnderstanding.understand(ref, analyzedRevision); // ⑤ 读回「模型可见」材料
proposal  = analysisExtraction.extract(material);                      // ⑥ AI + 校验
profile   = RepositoryProfile.create(新 uuid, assetId, analyzedRevision, …); // ⑦ Domain 判定
repositoryProfileRepository.save(profile);     // ⑧ 唯一一次写入
return profile;
```

**读这八行就能读懂这次 Task 的边界**：理解仓库的那一段整体被推到了
`RepositoryUnderstanding`，本类只保留「资产前提 + revision 只解析一次 + 最后写一次」。

---

## 2. 先讲清 `analyzedRevision`

### 2.1 它在哪里被解析

| 问题 | 答案 | 证据 |
|---|---|---|
| 哪里解析 HEAD | `AnalyzeRepositoryUseCase.analyze` 第 ④ 步 | `workspace.headRevision(workspaceRef)` |
| 解析几次 | **恰好一次** | 测试 `resolvesHeadOnceAndPinsEveryOperationToIt` 断言 `headRevisionCalls() == 1` |
| 谁实现它 | `GitWorkspaceAdapter.headRevision` → `git rev-parse --verify HEAD` | Infrastructure |
| 用什么类型承载 | 普通 `String`（完整 commit id） | 领域刻意不约束其格式（`RepositoryProfile.analyzedRevision` javadoc） |
| 之后谁接收它 | Map 构建、两条 Scout 路径、读取规划、每一次 `readFile`、`RepositoryProfile.analyzedRevision` | 见下 |

### 2.2 它一路被传递到哪些操作

```text
analyzedRevision
  ├─ RepositoryMapBuilder.build(ref, revision)     → listEntries(ref, revision, "", 整棵树)
  ├─ RepositoryScoutInputs.analyzedRevision()      → 进入 File Catalog 载荷
  ├─ 分支本地 File Scout 的 RepositoryMap.of(revision, …)
  ├─ RepositoryRegionCatalog.of(revision, …)       → 进入 Region 载荷
  ├─ RepositoryReadPlanner / RepositoryReadPlan    → 计划的 analyzedRevision
  ├─ RepositoryReadExecutor.readLane(…, revision, …) → readFile(ref, revision, path)
  └─ RepositoryProfile.create(…, analyzedRevision, …)
```

### 2.3 为什么后续阶段不得再解析 HEAD

`WorkspaceReadPort` 的契约明确要求 `revision` 是**已解析的完整 commit id**，
`GitWorkspaceAdapter.requireCommitId` 用 `git rev-parse --verify --quiet <rev>^{commit}`
把三种情况拒掉：

```text
HEAD / 分支名         → 解析结果与输入不一致 → IllegalArgumentException
缩写 id（如 a1b2c3）  → 同上
不存在 / 非 commit    → IllegalArgumentException
```

因此**「再解析一次 HEAD」在类型与实现两层都不可行**：能传进来的只有固定 commit id。

### 2.4 这条不变量防的是什么 bug

```text
一次 analyzedRevision
   → RepositoryMap（描述符大小来自该 commit）
   → 每次 readFile（内容来自该 commit）
   → Evidence.sourceRef（指向该 commit 中真实存在的路径）
   → RepositoryProfile.analyzedRevision（声称描述的就是该 commit）
```

若中途重新解析 HEAD，源仓库在分析期间产生新提交（或切换分支）时，会出现：

```text
描述符来自 commit A（大小、路径集合都是 A 的）
内容来自 commit B（路径可能已不存在，或内容已不同）
Profile 声称 analyzedRevision = A
────────────────────────────────────────
结果：一份自称描述 A、实际混了 B 的内容的快照 —— 它的可追溯性是假的
```

`AnalyzeRepositoryUseCaseTest.keepsUsingTheResolvedRevisionWhenHeadMovesDuringAnalysis`
把这条钉死：测试让工作区在分析中途把 HEAD 前移到 `REVISION_B`，断言最终
`profile.analyzedRevision() == REVISION_A`，且 `readRevisions()` 全部等于 `REVISION_A`。

**测试如何证明 revision 一致性**：`RecordingWorkspace`（测试替身）记录
`headRevisionCalls()`、`listedRevisions()`、`readRevisions()`，用例断言这三个集合，
因此「只解析一次」与「每次读取都带同一个 revision」都是可断言的**行为事实**，
而不是注释里的约定。

---

## 3. Repository Map —— 完整的源码视图

### 3.1 组件

| 类型 | 一句话 |
|---|---|
| `RepositoryMapBuilder` | 把一次 `listEntries` 的结果变成描述符集合 |
| `RepositoryMap` | 某个 revision 上**全部**已提交文件的描述符 + `RF-*` 索引 |
| `RepositoryMapEntry` | 一个文件的确定性描述（无内容） |
| `RepositoryPathClassifier` | 纯函数：路径 → materialKind / language / roleHints |
| `RepositoryCandidateLane` | 描述符将来进入哪一组（FOUNDATION / SCOUT_SOURCE / NONE） |

### 3.2 树的枚举

```text
WorkspaceReadPort.listEntries(ref, revision, "", Integer.MAX_VALUE)
        ↓  git ls-tree -r -t -l -z --full-tree <commit>
   [路径, 是否目录, blob 字节数]
        ↓  丢掉目录项
   按相对路径升序（MapBuilder **自己再排一次**，不依赖 Adapter 的输出顺序）
        ↓  按位置分配 RF-1 … RF-n
   逐项 RepositoryPathClassifier.classify(path)
        ↓
RepositoryMap.of(revision, descriptors)
```

`RepositoryMap.of` 强制两条不变量：**引用必须恰好是 `RF-1…RF-n` 且与位置一致**，
**相对路径不得重复**。因此「引用能不能解析」是一个可判定问题，不是「尽力匹配」。

### 3.3 每个描述符有什么

```text
reference      本次 Map 内的短名 RF-i
relativePath   提交树内的相对路径 ← 稳定的技术身份（与 revision 一起）
sizeInBytes    blob 字节数（规划阶段据此取舍）
language       按扩展名识别（RepositoryLanguage，UNKNOWN 也是合法取值）
materialKind   这是什么材料（RepositoryMaterialKind）
roleHints      源码可能扮演的结构角色（0..n 个；UNKNOWN 只能单独出现）
```

### 3.4 **刻意不读**什么

`RepositoryMapBuilder` 只调用 `listEntries`，**从不调用 `readFile`**。
这不是优化：一旦为了分类去读内容，「让整棵树可见」就会退化成另一次有界采样，
Map 也就不再完整——而 M1 的系统性盲区正是这么来的（见 §18）。
因此描述符里**没有内容字段**，`sizeInBytes` 只来自 blob metadata。

### 3.5 分类的三条自我约束

`RepositoryPathClassifier` 是纯函数，且刻意遵守：

```text
分类不排除任何文件   判错的文件仍在 Map 里，只是进入不同候选组
拿不准用 UNKNOWN     不为「看起来整齐」塞进语义不符的类别
不按大小分支         没有内容时「多大算大」只能靠拍常量，M1 已记录过代价
```

值得一提的一处**刻意不对称**：`build` / `target` / `dist` 这类目录名，
**只有在源码树之外**才算生成物。因为 `src/main/java/com/acme/build/BuildService.java`
是完全正常的业务代码——只看目录名会把它整体排除，那正是 M1 的老错误。

### 3.6 两条逻辑候选通道

`RepositoryMap.entriesIn(lane)` 是**纯派生视图**（每次按 `RepositoryCandidateLane.of` 现算），
不构成第二份状态。路由规则（`RepositoryCandidateLane.of`）：

```text
GENERATED_VENDOR / TEST_CODE            → NONE
非 SOURCE_CODE 的其它材料                → FOUNDATION
SOURCE_CODE 且 roleHints 恰好 == [CONFIG_BOOTSTRAP] → FOUNDATION
其余 SOURCE_CODE                         → SCOUT_SOURCE
```

#### FOUNDATION：回答「这是什么工程」

| materialKind | 具体例子 |
|---|---|
| `BUILD_METADATA` | `pom.xml`、`build.gradle`、`package.json`、`go.mod`、`Makefile` |
| `CONFIGURATION` | `.yml/.yaml/.properties/.xml/.json/.env/.ini/.toml`、`.gitignore`、`.editorconfig` |
| `DEPLOYMENT` | `Dockerfile`、`docker-compose.yml`、`k8s/`、`helm/`、`.github/`、`.tf` |
| `DATA_SCHEMA` | `.sql`、`.ddl` |
| `DOCUMENTATION` | `.md/.rst/.adoc/.txt`、`README*` |
| `SCRIPT_AUTOMATION` | `.sh/.bash/.bat/.ps1` |
| `OTHER` | 无扩展名、未覆盖类型（数据样本等） |
| 外加一类源码 | 恰好只带 `CONFIG_BOOTSTRAP` 的配置类 |

#### SCOUT_SOURCE：回答「它实现过什么」

非生成的**业务源码**：`SOURCE_CODE` 且不带（或不仅是）配置类提示。
典型即 `controller/ service/ mapper/ config/` 下的 `.java`。

#### NONE

`GENERATED_VENDOR`（`node_modules/`、`target/`、锁文件、`.min.js`）与
`TEST_CODE`（`test/`、`*Test.java`、`*.test.ts`、`test_*.py`）。
把测试放这里是**当前选择而非结论**，代码注释明说「真实验证仓库里测试没有稀释候选集」。

#### 为什么两条通道不走同一套选取机制

```text
FOUNDATION   规模小、可稳定取全、价值在「覆盖工程形态的各侧面」
             → 确定性选取即可，**不需要模型**
SCOUT_SOURCE 数量最多（真实仓库里可能几千个）、内容最需要取舍
             → 必须先「指认」再「定向读取」，**需要模型**
```

它们要回答的问题不同、材料性质不同、规模差一个数量级，因此**预算、队列与轮转规则都不同**
（见 §8）。把它们塞进同一条队列，会让一方的规模决定另一方的规模。

---

## 4. 两条通道的分与合

```text
                        RepositoryMap（完整已提交树，只有元数据）
                                 │
        ┌────────────────────────┴────────────────────────┐
        │                                                 │
   FOUNDATION                                       SCOUT_SOURCE
        │                                                 │
  按 materialKind 分组                            是否需要分层？
  类别轮转（确定性）                              ├─ 否 → File Scout（§5）
        │                                          └─ 是 → Region 导航 + 分支 File Scout（§6）
        │                                                 │
        │                                          有序、去重的候选流
        │                                                 │
        └───────────────► RepositoryReadPlanner ◄──────────┘
                          两条通道各自轮转
                                 │
                        RepositoryReadPlan
                                 │
                        RepositoryReadExecutor
                                 │
                    材料 = Foundation 材料 ++ 定向源码材料   ← **合流点**
```

### 4.1 FOUNDATION 通道

| 问题 | 答案 |
|---|---|
| 谁选 | `RepositoryReadPlanner.foundationLanes`，**完全确定性** |
| 有 LLM 吗 | **没有** |
| 排序 / 轮转 | 按 `RepositoryMaterialKind` 枚举顺序分组，组内按相对路径升序，然后**类别轮转** |
| 预算 | `foundation.{max-files, max-file-bytes, max-total-bytes}` = 12 / 32 KiB / 96 KiB |
| 凭据政策参与在哪 | 候选进入队列之前：`foundationLanes` 对每个描述符调 `excludedFromMaterial`（§9） |
| 何时真正读内容 | 不在这一层——规划只看 metadata；真正读在 `RepositoryReadExecutor`（§10） |

**类别轮转的意义**：任何一类都不能凭数量占满预算。一个「文档很多、源码很少」的仓库，
不该只分析出文档。

### 4.2 SCOUT_SOURCE 通道

| 问题 | 答案 |
|---|---|
| 为什么用 LLM | 候选可能上千个，「读哪些」是一个按**语义相关性**判断的问题；用确定性的路径排序回答它，在真实仓库上已经失败过（§18） |
| 确定性排序历史上为什么不够 | 目录靠前的文件先到先得，业务实现被配置/入口类挤掉——M1 复盘 §5.8 与真实 smoke 都记录过 |
| flat vs hierarchical 由什么决定 | `scoutExtraction.catalogPayloadBytes(inputs) > maxScoutCatalogBytes` |
| 候选最终如何变成有序定向源码 | flat → `RepositoryInspectionPlan`；hierarchical → `RepositoryFileCandidates.orderedFiles()` |

### 4.3 合流点（本报告最需要记住的一处）

**两条通道的候选在规划阶段合流，材料在读取阶段合流：**

```text
规划阶段   RepositoryReadPlanner.plan(map, …)
             foundation = roundRobin(foundationLanes, foundationBudget)
             targeted   = roundRobin(targetedLanes,   targetedSourceBudget)
             → RepositoryReadPlan(analyzedRevision, foundation.selected, targeted.selected, skipped)

读取阶段   RepositoryReadExecutor.execute(plan, ref)
             material = foundation.material() ++ targeted.material()
```

即：**基础材料在前，定向源码在后**（`RepositoryReadPlan.entries()` 的派生视图同序）。
两条通道的预算**互不占用**：Foundation 读满 12 个不会让定向源码少读一个。

---

## 5. 小仓库路径：flat File Scout

### 5.1 判定

`RepositoryUnderstanding.understand`：

```java
RepositoryScoutInputs inputs = RepositoryScoutInputs.of(map);      // 取全部 SCOUT_SOURCE
boolean oversized = scoutExtraction.catalogPayloadBytes(inputs) > maxScoutCatalogBytes;
```

- `catalogPayloadBytes` → `FileCatalogPayload.payloadBytes` → `render(...).getBytes(UTF_8).length`
- **量的是用户消息**（描述符清单那一段），系统指令是固定文本、不随仓库变化
- `<= 65536` → flat；`> 65536` → hierarchical
- 边界行为有测试：`acceptsACatalogExactlyAtTheLimit`（恰好等于上限仍走 flat）

### 5.2 数据流

```text
SCOUT_SOURCE 描述符
      ↓ FileCatalogPayload.render  {"analyzedRevision": …, "fileCatalog":[ {reference,path,sizeBytes,language,materialKind,roleHints} ]}
  AiGateway.generate(AiRequest[SYSTEM, USER])
      ↓ 原始模型文本
RepositoryScoutProposalParser.parse        → AiRepositoryScoutProposal（仍带未校验引用）
      ↓ RepositoryScoutProposalResolver.resolve(proposal, inputs)
RepositoryInspectionPlan                   → 已换回真实描述符
      ↓ RepositoryReadPlanner.plan(map, inspectionPlan)
RepositoryReadPlan
```

### 5.3 模型看到什么 / 没看到什么

| 看到 | 看不到 |
|---|---|
| 编号（`RF-7`）、相对路径、字节数、语言、材料类别、结构角色提示 | **任何文件内容** |
| | 宿主机绝对路径 |

提示词把边界写死：`你不是在给这个仓库下结论 …… 你看到的只是文件名与路径，看不到代码，
因此不要假装读过它们。`

### 5.4 `RF-*` 的语义

- 形如 `RF-<position>`，由**位置**分配（`RepositoryFileReference.of(i+1)`），不是内容派生
- **调用内有效**：换一个 revision、换一张 Map，`RF-1` 指向别的文件
- 模型只能**指认**，不能给出路径 —— 解析器用正则 `RF-[1-9][0-9]*` 拒绝路径与前置零
- 解析器与解析器分工：解析器管**形状**（数量 3–6 个区域、label 非空、`fileRefs` 非空不重复），
  解析器（resolver）管**引用是否存在**（需要本次输入）

### 5.5 为什么 AI 输出只是「查看提议」

`RepositoryInspectionPlan` 回答的是 `where should we inspect?`，**不回答**
`what does it actually prove?`。`label` 是模型的**查看意图**，不是仓库能力——
把它当成 `capabilities` 或依据，等于让一个只见过路径的模型替整次分析定调。

### 5.6 非法引用怎么失败

| 情况 | 抛出点 | 结果 |
|---|---|---|
| 编号在本次 Map 里不存在 | `RepositoryScoutProposalResolver.requireScoutSourceEntry` | `AiGatewayException`（502） |
| 编号存在但不是本次提供的源码候选 | 同上 | `AiGatewayException` |
| 同一区域内重复引用 | 解析器 + 解析器各拦一次 | `AiGatewayException` |
| 跨区域重复引用 | **允许**（同一个文件可以从几个角度看） | 交给规划器去重 |
| 字段缺失 / 类型不对 / 数量越界 | `RepositoryScoutProposalParser` | `AiGatewayException` |

**失败是整次的**：不存在「丢掉那个区域、保留其余部分」的降级——部分成功会让调用方
以为「模型只指出了这几处」。

---

## 6. 大仓库路径：Hierarchical Scout

### 6.0 全景

```text
RepositoryMap
      ↓ RepositoryRegionNavigator.navigate(map)        Region Navigator
RepositoryRegionNavigation（有序终态文件组 + Region 调用数）
      ↓ RepositoryBranchScoutRunner.run(navigation)    分支 File Scout 执行器
RepositoryFileCandidates（有序、去重的候选文件）
      ↓ RepositoryTargetedSourceCandidates.of(...)
RepositoryReadPlanner.plan(map, candidates)
```

### 6.1 Region 的表示

| 类型 | 职责 |
|---|---|
| `RepositoryRegionTree` | `RepositoryMap` 之上按**目录前缀**聚合出的确定性视图（只取 SCOUT_SOURCE） |
| `RepositoryRegion` | 一个前缀的导航元数据 |
| `RepositoryRegionCatalog` | **一次** Region Scout 调用可见的兄弟 Region + `RR-*` 编号 |
| `RepositoryRegionReference` | 引用值 `RR-<scope>-<position>` |
| `RepositoryRegionSelection` | 已校验的选中区域（顺序 = 分支优先级） |

**Region 描述符有什么**：

```text
pathPrefix                src/main/java/com/acme/controller
directSourceFileCount     直接位于该目录下的源码候选数
descendantSourceFileCount 该前缀下全部源码候选数
childRegionCount          含源码的直接子目录数
languages                 后代出现过的语言（去重、排序）
roleHints                 后代出现过的结构角色提示（去重、排序）
```

**刻意没有什么**：没有 purpose / capability / importance / confidence / score ——
那些需要读过内容才能成立，而 Region 阶段看不到内容。也没有文件清单：一个 Region
只暴露「有多大、是什么形状」，不暴露里面有哪些文件。

**`RR-*` 的调用身份**：引用形如 `RR-3f1a9c02…7f-1`，`scope` 是**每次构造 Catalog 时新生成的**
完整 UUID（32 位十六进制），**刻意不由内容派生**：

```text
内容摘要做不到调用身份：相同输入的不同调用会算出同一个摘要，
上一次调用留下的响应仍会被这一次接受；而且有限长度的摘要还会碰撞。
调用身份必须是每次调用新生成的，不是算出来的。
```

截短也不行：32 位只有约 43 亿种取值，两万多次调用按生日悖论就会碰撞一次。
**没有作用域**的话，A 调用的 `RR-1` 交给 B 解析会**静默成功**并返回 B 的第 1 个区域——
那就退化成「当前目录成员校验」，而不是「跨调用引用一律拒绝」。

### 6.2 递归导航算法

`RepositoryRegionNavigator` 内部类 `Navigation.descend(prefix, entries, roundsUsed)`：

```text
一个节点（该前缀下的全部源码候选，含直属与各子目录后代）

  catalogBytes(entries) <= maxFileCatalogBytes ?
  ├─ 是 → 该节点整体成为一个终态组，返回
  └─ 否
     ├─ tree.childRegions(prefix) 为空 ?
     │    → RegionHierarchyNotReducibleException      （结构上无法再分）
     ├─ 直属文件本身超限 ?
     │    → RegionHierarchyNotReducibleException      （它们没有更细的结构可分）
     ├─ 轮数 / Region 调用数 / 总 Scout 数 任一超限 ?
     │    → 相应预算异常（**发生在调用之前**，这一次不触达模型）
     ├─ scoutCalls++
     ├─ 直属文件单独成组（若非空）——它们不属于任何子 Region，任何 Region Scout 都看不到它们
     └─ Region Scout 选子分支 → 对每个被选中的区域递归 descend(subtree, roundsUsed + 1)
```

**三个关键设计点**：

1. **直属文件单独成组**。Region 由目录前缀定义，直接放在这一层的源码候选不对应任何子目录，
   任何一次 Region Scout 都看不到它们。若不为它们单开一组，它们会在分解中**静默消失**。
   仓库根目录同理；`RepositoryRegionTree.rootDirectSourceFileCount` 把这个数量显式暴露出来。
2. **被选中的分支不丢文件**：每个非直属文件恰好属于一个子 Region，直属文件又被单独收走，
   因此沿一条被选中分支走下去不会有源码候选悄悄消失。会让文件**不进入结果**的只有一件事：
   Region Scout 没选中它所属的分支——那是**取舍**，不是丢失。
3. **停止条件是字节，不是深度**：目录深浅与文件大小无关，同样 3 层，有的装得下有的装不下。

### 6.3 五道守卫

| 守卫 | 保护什么 | 在哪里判定 | 在 Gateway 之前？ | 失败异常 |
|---|---|---|---|---|
| Region Catalog 字节上限 | 一次 Region 调用的载荷规模 | **`RepositoryRegionScoutExtraction.scout` 内**：先序列化一次、量这一份、超限即失败，否则原样发出**同一份** | ✅ 是（守卫在调用边界内） | `RepositoryRegionCatalogTooLargeException` |
| 单次可选 Region 数 | 单轮选择无界膨胀 | `RepositoryRegionProposalParser`（上限随配置传入） | ❌ 在模型返回之后 | `AiGatewayException`（模型没守约定） |
| 沿单分支轮数 | 单条分支无限下钻 | `Navigation.descend` | ✅ 是 | `RegionNavigationBudgetExceededException` |
| 单次分析 Region 调用数 | Region Scout 总量 | `Navigation.descend`；`RepositoryBranchScoutRunner.run` 入口再核对一次 | ✅ 是 | `RegionNavigationBudgetExceededException` |
| 总 Scout 调用数（Region + File） | 整次分析一共能问模型多少次 | `Navigation.descend` **与** `RepositoryBranchScoutRunner.run` 循环内 | ✅ 是（两处都在调用之前） | `ScoutCallBudgetExceededException` |

**为什么「Region 上限」与「Region+File 总数」是独立的两道**：

```text
Region Scout 调用 ≤ 12        （单通道上限）
Region + File Scout   ≤ 18    （整次分析总数）
```

`15 + 3 = 18` 不超总数，但已经违反 `Region ≤ 12`——因此总数守卫**不能替代**通道守卫。
`RepositoryBranchScoutRunner` 在入口显式核对输入导航声明的 Region 调用数，就是为了堵这个洞
（它不信任一个公开类型的调用方自报的数字）。

**为什么总数守卫必须在导航阶段就生效**：Region 调用与 File 调用合并计数，
分层下降多问几次，留给终态分支的额度就该少几次。等到分支阶段才发现超限，
**收不回已经付出的 Region 调用**。

配置来源（`application.yml` → `RepositoryAnalysisProperties`）：

```text
scout.max-catalog-bytes          65536   ← 同时是 flat 分支门槛与区域字节门槛（见 §16）
region.max-catalog-bytes         65536   ← 目录描述符载荷（另一件事）
region.max-selected-regions          6
region.max-rounds-per-branch         8
region.max-scout-calls              12
scout-calls.max-total               18
```

### 6.4 终态分支的 File Scout

每个终态组独立跑一次**全新的** File Scout 调用（`RepositoryBranchScoutRunner.branchOrder`）：

```text
终态组（本组源码文件，原描述符）
      ↓ 按组内位置重新编号 RF-1…RF-n，并记下 reference → 原描述符 的映射
RepositoryMap.of(revision, localCatalog)
      ↓ RepositoryScoutInputs.of(...) → **同一个 RepositoryScoutExtraction**
RepositoryInspectionPlan
      ↓ 用本组映射把引用换回**原描述符**
组内优先级顺序（同一文件出现在多个区域只保留首次出现）
```

**契约一字未改**：仍然是「描述符清单 → 模型 → 严格解析 → 引用校验」，
复用同一个类，不另写解析器或提示词。

**微妙之处：`RF-*` 本身不携带调用身份**

```text
分支 A 的第一个文件 → RF-1
分支 B 的第一个文件 → RF-1      ← 同一个字符串，在两个分支里都解析成功
```

隔离**不是**来自编号互不相同，而是来自「每次调用绑定自己那份目录」：
`catalog → gateway → parse → resolve against the same catalog` 是完全同步的闭包，
两次调用之间没有任何结果或目录被传递。这一点与 Region 的 `RR-*` **刻意不同**——
Region 会一次调用建一份、逐层重建，所以必须把作用域写进值里。

### 6.5 保序轮转合并

```text
A: A1 A2 A3
B: B1 B2
C: C1 C2 C3
        ↓ 第 r 轮按分支顺序取各分支第 r 个，取完的跳过
A1 B1 C1  A2 B2 C2  A3 C3
```

**三种顺序含义必须分开，不能把整个列表说成「一份模型给出的优先级」**：

| 顺序层次 | 来源 |
|---|---|
| 被 Region Scout 选中的**兄弟分支之间** | 模型的取舍顺序（真正的分支优先级） |
| 一条分支**内部** | 该分支 File Scout 表达的优先级 |
| **直属文件组与子分支之间** | 结构约定（直属在前）——直属文件**从未被任何一次 Region Scout 排序过** |

`RepositoryFileCandidates` 的类说明明确写下这条：本类型只承诺「确定、可复现的顺序」，
**不宣称整个列表是模型给出的优先级**。

**为什么必须轮转，而不是拼接**：

```text
直接拼接 A++B++C            → A 吃满全部预算，B、C 一个都进不来
保序轮转                     → 每条分支的第一优先级文件先进入候选
```

轮转同时保住了「分支间公平」与「分支内保序」。最终取舍仍由 Read Planner 的材料预算决定。

---

## 7. 两条路径的汇合

```text
flat:         RepositoryInspectionPlan ─────────┐
                                                ├─► RepositoryReadPlanner
hierarchical: RepositoryFileCandidates ─────────┘   （同一份实现、同一套预算与跳过原因）
```

`RepositoryUnderstanding.hierarchicalReadPlan`：

```java
RepositoryRegionNavigation navigation = regionNavigator.navigate(map);
RepositoryFileCandidates candidates = branchScoutRunner.run(navigation);
return readPlanner.plan(map, RepositoryTargetedSourceCandidates.of(
        candidates.analyzedRevision(), candidates.orderedFiles()));
```

### 7.1 为什么引入 `RepositoryTargetedSourceCandidates`

`RepositoryInspectionPlan` 描述的是**模型给出的查看意图**：每个区域带一个 `label`，
表达「这一组文件打算用来看什么」。分层合并出的候选流**没有这样的标签**——
它的顺序来自「区域优先级 × 分支内优先级」的保序轮转，是 Application 自己算出来的。

```text
把候选流塞进一个带 label 的查看区域 = 替模型宣称一个它从未表达过的分组
```

因此用一个**不冒充查看计划**的输入类型，把「谁产生的」留在类型上：

| | `RepositoryInspectionPlan` | `RepositoryTargetedSourceCandidates` |
|---|---|---|
| 产生者 | 模型（经解析与引用校验） | Application（保序轮转合并） |
| 形状 | 多个区域，每区一条队列 | 一条有序候选流 |
| 语义 | 查看意图 + 分组 | 考虑顺序 |
| 共同点 | 都折成「定向源码的候选队列」 → **共用同一份预算与同一段轮转逻辑** | 同左 |

`RepositoryReadPlanner` 的两个公共入口只负责「把输入折成候选队列」，
其余（两条通道、预算、跳过原因、诊断去重）完全共用。**规划策略语义一字未改**。

---

## 8. RepositoryReadPlanner

### 8.1 结构

```text
RepositoryReadPlanner(foundationBudget, targetedSourceBudget, secretPolicy)
        │
        ├─ foundationLanes(map)                  → Lanes（按 materialKind 分组 + 被排除的）
        └─ targetedSourceLanes(map, plan)        → Lanes（按聚焦区域分组 + 被排除的）
             或 resolveLane(map, candidates)     → Lanes（一条队列 + 被排除的）
        │
        ├─ roundRobin(foundation.lanes(), foundationBudget)       → LaneResult
        └─ roundRobin(targeted.lanes(),   targetedSourceBudget)   → LaneResult
        │
RepositoryReadPlan.of(revision, foundation.selected, targeted.selected, skipped + 排除诊断)
```

### 8.2 三份预算

| 预算 | 何时检查 | 检查依据 | 超大候选的结局 | 谁在竞争剩余额度 |
|---|---|---|---|---|
| `maxFiles` | **规划时** | 已选文件数 | — | 轮转：每条通道内按顺序，达到上限即停整条通道 |
| `maxFileBytes` | **规划时**（blob metadata）**与执行时**（真实内容 UTF-8 字节） | `sizeInBytes` / 实际内容长度 | 记 `SELECTED_BUT_TOO_LARGE`，跳过并继续找下一个 | 不占额度 |
| `maxTotalBytes` | 同上两处 | 累计已选字节 | 记 `EXCEEDS_REMAINING_TOTAL_BYTES`，跳过并继续 | 先到先得：早被选中的吃掉额度 |

**执行阶段不重判 `maxFiles`**：数量上限在规划时已定死，执行只会让数量变少。

**顺序如何影响谁拿到剩余预算**：轮转按通道与队列顺序推进，因此「谁排在前面」
在总量紧张时直接决定谁被选中。这正是 `targetedSourceLanes` **不做任何重排**的原因——
Scout 的顺序就是优先级。

### 8.3 轮转的行为

```text
每一轮从每条通道各取一个候选
候选放不下   → 跳过它、继续在本通道里找下一个能放下的（**不停止整轮**）
已经处理过   → 直接跳过，不再计一次文件与字节，也不再留一条诊断
连一个都选不出来 → 结束
达到 maxFiles    → 结束
```

与 M1 的一处刻意不同：M1 遇到放不下的文件时**直接停止整轮收集**，
这里改为跳过并继续考察更小的候选——同一通道里后面的候选没有理由因为前面一个太大而失去机会。

**「已处理」既包括选中的，也包括已记为跳过的**：跳过是**文件的属性，不是引用的属性**。
否则一个被三个区域引用的大文件会留下三条一模一样的诊断，把「跳过了几个文件」说成三倍。

### 8.4 跨通道去重

同一路径**不可能**同时出现在两条通道里（路由互斥），但 `RepositoryReadPlan.of` 仍然强制两条不变量：
**同一个引用不得出现两次，同一个相对路径也不得出现两次**。

路径这条不能省：ADR-0004 把「已解析 revision + 提交树相对路径」定义为稳定技术身份，
两个**不同编号**指向同一路径时物理上仍是同一个文件——那同样会让它被读两次。

---

## 9. 凭据边界 —— 读取之前的路径判定（执行点 ①）

### 9.1 位置

在 `RepositoryReadPlanner` 内部，**两条通道的候选进入轮转之前**：

```text
foundationLanes  → 对每个 FOUNDATION 描述符调 excludedFromMaterial(entry, excluded)
resolvedLane     → 对每条定向源码候选调 excludedFromMaterial(fromMap, excluded)
                     ↓
if (secretPolicy.excludes(entry.relativePath())) { excluded.add(entry); return true; }
   → 命中者**不进入候选队列**，因此不会被考虑、不会被读、不占用名额
```

### 9.2 排除的路径类别（`DeterministicRepositorySecretPolicy.excludes`）

```text
精确文件名（小写）   .env  .npmrc  .pypirc  .netrc
.env 前缀/后缀       .env.local / production.env
扩展名（按末段整段） .pem .key .p12 .pfx .jks .keystore
私钥文件名           id_rsa / id_dsa / id_ecdsa / id_ed25519 及其变体（id_rsa_old、id_rsa.bak）
                     但 **放行公钥**：id_rsa.pub 不是凭据
.aws/credentials     按**路径分段**判定，不是按子串
```

两条刻意避免误伤的设计：**只取末段文件名**（`docs/about.env.md` 不排除）、
**不按子串匹配**（`myenv`、`env`、`.envrc`、`notes.keyword`、`my.aws/credentials-backup` 都不排除）。

### 9.3 为什么必须在 `maxFiles` 名额之前

代码注释把两条理由写在方法上：

```text
1. 没进入候选队列的候选不会被读 → 被排除的文件不可能出现在材料里，
   也就不可能出现在送往模型的请求里。这是本次分析里**唯一能整份排除**的地方。
2. 它不占用 maxFiles 名额 —— 一个被挡下的 .env 不该让后面那个安全的候选失去机会。
   否则「仓库里多了一个凭据文件」会变成「分析少看了一个正常文件」。
```

### 9.4 诊断

- 跳过原因：`RepositoryReadSkipReason.EXCLUDED_BY_SECRET_POLICY`
- **按文件去重**：Scout 允许同一文件出现在多个区域，排除判定按区域各跑一遍；
  不去重的话同一个凭据文件被三个区域提到就会留三条一模一样的记录。
  用 `LinkedHashSet` 保首次出现顺序。
- 它记录的是**路径**（描述符），这一层从来没有读过它的内容。
- 日志只记**数量**（`secretExcludedCount`），不记路径——路径属于用户数据。

### 9.5 与内容净化的区别

| | 执行点 ①（路径排除） | 执行点 ②（内容净化） |
|---|---|---|
| 时机 | 读取**之前**（规划器） | 读取**之后**、交给模型之前（理解阶段末尾） |
| 能做什么 | **整份不读** | 只能改内容 |
| 为什么必须存在 | 二进制凭据（`.p12` / `.jks`）在文本层无从识别，只有路径能识别 | `application.yml` 是最可能的凭据位置，**同时**是最有价值的分析材料，不能整份丢掉 |

**任缺一处都会留下明显的洞**：只有 ① 会漏掉源码与配置里的凭据，只有 ② 会漏掉内容层无从识别的整份凭据文件。

---

## 10. RepositoryReadExecutor —— 真正读取内容

### 10.1 这是「元数据 → 真实内容」的分界线

```text
截至这里为止的一切        Map 构建、两条 Scout、Region 导航、规划
                          全部只使用**描述符**（路径 / 大小 / 语言 / 角色提示）
                          —— 没有任何一个字节的仓库内容进入 Application 内存

从这一行开始              workspace.readFile(ref, analyzedRevision, entry.relativePath())
                          —— 真实文件内容第一次进入 Application
```

### 10.2 路径

```text
RepositoryReadPlan + WorkspaceRef
      ↓ 逐条（顺序 = 规划定下的优先级）
WorkspaceReadPort.readFile(ref, revision, relativePath)     ← Application 接口
      ↓ GitWorkspaceAdapter：git -C <repo> cat-file blob <commit>:<path>
      ↓ UTF-8 解码 stdout
String content
      ↓ 执行期尺寸复核
RepositorySourceFile(relativePath, content)
      ↓
RepositoryReadResult(material, skipped)
```

**能力边界**：`GitWorkspaceAdapter` 每次调用新起一个 `git` 进程，
**没有任何缓存或 memoization**；`cat-file` 直接输出对象内容，不经过 checkout 或过滤器，
因此拿到的就是该 commit 中保存的那份内容。

### 10.3 固定 revision 的使用

`readFile` 把 `commitId` 写进**对象规格** `commit:path`——git 自己保证读的就是那个 blob。
前缀两步（`requireReadableRepository` + `requireCommitId`）拒绝 `HEAD` / 分支名 / 缩写 id。

### 10.4 执行期尺寸复核

规划依据的是 blob metadata 里的字节数，**它不一定等于实际读到的内容的字节数**。
因此执行阶段独立地按**真实内容**复核同一条通道的 `maxFileBytes` 与 `maxTotalBytes`：

```text
实际内容 > maxFileBytes            → 剔除，记 SELECTED_BUT_TOO_LARGE，继续处理后面的文件
acceptedBytes + 实际 > maxTotalBytes → 剔除，记 EXCEEDS_REMAINING_TOTAL_BYTES，继续
```

**剔除而不截断**：半个文件会让模型基于缺失的部分下结论，而系统并不知道缺了什么。

**两条通道各自记账**：一条通道剩余的总量不会被另一条用掉。

### 10.5 字节记账

`utf8Length(content) = content.getBytes(StandardCharsets.UTF_8).length`，用 `long` 累计
（注释说明：两条通道的预算都是 `int`，加上一个 `int` 长度不会溢出，因此比较不会因溢出而放行超预算文件）。

### 10.6 读取失败不是「跳过」

尺寸问题是**可以被安全剔除**的候选；读取失败不是——它意味着环境层面出了问题
（一个在确定 revision 上确实存在的文件读不到）。因此读取失败直接抛 `WorkspaceException`
让整次分析失败，而不是悄悄少读几个文件还当作分析成功。

### 10.7 输出与诊断

```text
RepositoryReadResult
  material = foundation.material() ++ targeted.material()   ← 合流点，基础材料在前
  skipped  = 规划期跳过 ++ 执行期剔除（两个来源都保留）
```

---

## 11. 凭据边界 —— 读取之后的内容净化（执行点 ②）

### 11.1 位置与形状

`RepositoryUnderstanding.understand` 在读取之后、返回之前：

```java
RepositoryReadResult read = readExecutor.execute(readPlan, workspaceRef);
SanitizedRepositoryMaterial modelVisible = modelVisibleMaterial(read.material(), analyzedRevision);
…
return modelVisible.material();     // 本方法返回的**已经是模型可见材料**
```

> 为什么放在这里而不是更靠下游：本方法返回的材料就是这次分析**唯一**的材料来源，
> 因此「本方法返回的已经是模型可见材料」是一条关于**单个组件**的性质，可以直接测试，
> 而不必逐个调用方去确认它记得过边界。

### 11.2 规则族（`DeterministicRepositorySecretPolicy.CONTENT_RULES`，按序应用）

| # | 规则族 | 替换形态 | 保留 |
|---|---|---|---|
| 1 | PEM 私钥块（含 OPENSSH / RSA / EC / ENCRYPTED） | 整块 → marker | 周边内容 |
| 2 | 已知令牌前缀：`AKIA/ASIA`、`ghp_/gho_/…`、`github_pat_`、`sk-`、`xox[baprs]-`、`AIza`、`ya29.`、JWT `eyJ…` | 整个字面量 → marker | 键名 |
| 3 | `Authorization: Bearer/Basic <cred>` | 仅凭据 → marker | 头名与方案名 |
| 4 | URI userinfo `scheme://user:pass@host` | 仅口令 → marker | 用户名与主机 |
| 5 | 凭据位置的赋值（含引号内整体、含转义） | 仅取值 → marker | 键名与分隔符 |

第 5 条的边界都写死在正则里：分隔符只认单个赋值符（不认 `==` / `===` / `!=` / `=>` / `::`）、
两侧不吃换行（否则 `password:` 后的下一行键名会被当成取值）、引号内支持 `\"` / `''` / `\'` 转义。

### 11.3 替换标记与结构保留

```text
REDACTION_MARKER = "[redacted-credential]"
```

键名、缩进、**行数**与周边内容都保留，只把值换掉——模型仍然看得出「这里配了一个口令」，
而看不到口令本身，分析价值的损失因此很小。私钥块这类没有「键名」可保留的整块凭据，整块替换。
**相对路径原样保留**：它是 Evidence 定位文件的依据，改了就不可追溯。

### 11.4 为什么净化发生在读预算校验**之后**

顺序是 `读取 → 尺寸复核 → 净化`。先复核再净化，是因为：

```text
预算约束的是「读了多大的真实内容」——这是工作量与成本
净化是对已读内容的后处理，不改变读过多少
```

反过来（先净化再复核）会让「材料预算」变成「净化后文本的预算」，
而净化后的长度可能**变化**（替换可能变长或变短），预算语义就不再是「读进来多少」。

### 11.5 代价也必须有界（复审期间引入）

材料里的单个文件可以达到 64 KiB 量级，因此每条规则都不能：

```text
逐字符递归   可选项放进重复（(?:A|B)*）会按字符数消耗调用栈，
             4000 字符就足以抛 StackOverflowError；那是 Error，
             会绕过 RepositorySecretBoundaryException 的失败关闭约定，变成「未知故障」。
             引号内因此用占有量词 *+，匹配是迭代的。
代价变平方   在多个起点重复扫同一段长文本。URI 的方案名上界 {0,31} 就是为避免这一条
             （不设上界时，一段没有 :// 的长文本在 60 KB 上实测约 12 秒）。
```

对应两个测试：`replacesLongValuesCompletelyWithinTheFileBudget`（40 000 字符三种取值形态，
金丝雀放在**末尾**）与 `keepsConnectionStringScanningLinearOnLongTextWithoutSeparator`（5 秒代价守卫）。

### 11.6 残留风险 —— 必须区分两句话

```text
✅ 已声明支持的形态会被净化
❌ 不是「所有可能的凭据都保证安全」
```

规则是**枚举 + 形态判断**：命中什么就保护什么。**未知令牌格式、自研格式、
被拼接 / 编码 / 分片构造的凭据都可能不命中**。这写在 ADR-0006 的「不保证」一节，
代码注释也重复了一次：「不要把本包读成『已经确保没有凭据』」。

另外 `DeterministicRepositorySecretPolicy` **不判断真假**：处于凭据位置的值一律替换，
包括 `"your-password-here"`、`${DB_PASSWORD}`、`changeme`。理由是判定「这个值是不是真的」
不可能可靠，统一替换让结果只依赖**位置与形态**，因此可以写出确定的测试。

---

## 12. 最终 Repository Analyzer

### 12.1 数据流

```text
RepositoryUnderstanding → List<RepositorySourceFile>（已净化）
      ↓ RepositoryAnalysisExtraction.extract(files)
        requireFiles(files)                        非空校验
        buildRequest(files)                        SYSTEM + USER(describeFiles)
      ↓ AiGateway.generate
DeepSeek /chat/completions（response_format: json_object）
      ↓ 原始文本
RepositoryAnalysisProposalParser.parse              字段必须全部出现
      ↓
requireEvidenceRefersToSentFiles(proposal, files)   ← 依据校验（§13）
      ↓
RepositoryAnalysisProposal
      ↓ AnalyzeRepositoryUseCase → RepositoryProfile.create
```

### 12.2 最终模型收到什么

```json
{ "repositoryFiles": [ { "path": "src/main/.../FooService.java", "content": "<净化后的正文>" }, … ] }
```

| 发送 | 不发送 |
|---|---|
| 每条材料的**相对路径 + 净化后内容** | 宿主机绝对路径 |
| | **`analyzedRevision`**（代码注释说明：模型需要的是内容，位置由相对路径表达） |
| | Foundation / 定向源码的通道区分 |

### 12.3 与两次 Scout 请求的对比

| | Region Scout | File Scout | 最终 Analyzer |
|---|---|---|---|
| 载荷 | `{analyzedRevision, regionCatalog:[{reference,pathPrefix,directSourceFiles,descendantSourceFiles,childRegionsWithSource,languages,roleHints}]}` | `{analyzedRevision, fileCatalog:[{reference,path,sizeBytes,language,materialKind,roleHints}]}` | `{repositoryFiles:[{path,content}]}` |
| 含文件内容 | ❌ | ❌ | ✅（**唯一**一处） |
| 用途 | 选目录 | 选文件 | 得结论 |
| 系统提示词的边界声明 | 目录规模与语言提示，看不到代码 | 文件名与路径，看不到代码 | 根据给出的文件内容提出结论 |

**这是整条链路上唯一发送仓库内容的一次调用**——这一点是理解凭据边界为何必须存在的关键。

### 12.4 AI proposes → Application validates → Domain decides

```text
AI proposes        RepositoryAnalysisProposal（中间数据，不是领域对象）
Application 校验   字段结构（解析器）+ 依据是否指向真实读过的文件（extraction）
Domain decides     RepositoryProfile.create 判定内容是否构成合法状态（RULE-DOM-002）
```

`RepositoryAnalysisProposal` 的类说明明确：在被 Aggregate 接受之前，它不构成任何合法领域状态，
也不携带领域身份、revision 或生命周期。

**字段缺失 = 不合法**（与 User Profile 的增量提议相反）：一次 Repository 分析没有上一版可以合并，
把缺失当成「该区为空」等于把模型的沉默记成一条它从未做过的结论
（「没有风险」与「没有回答风险」是两件事）。因此解析器要求**所有字段必须出现**，
某区确实没内容时应当给出**空数组**。

---

## 13. Evidence 校验

### 13.1 `sourceRef` 里是什么

模型给出的一个**仓库内相对路径**，即本次交给它的材料里出现过的 `path`。
领域侧的 `Evidence.sourceRef` 只要求非空非空白（`Evidence` 的 compact constructor），
**格式与含义由 Application 决定**——`RepositoryEvidenceProposal` 的 javadoc 写明：
本实现不为它设计更细的定位方式（无行号、无片段、无符号）。

### 13.2 校验依据的是哪一份集合 —— 精确回答

```java
private static void requireEvidenceRefersToSentFiles(proposal, files) {
    Set<String> sentPaths = files.map(relativePath) …
    for (evidence : proposal.evidence())
        if (!sentPaths.contains(evidence.sourceRef()))
            throw new AiGatewayException("AI 提出的依据指向了本次没有提供的文件: …");
}
```

**依据是「本次真正交给模型的那份材料列表」** —— 不是整张 `RepositoryMap`，
不是「Scout 选中的文件」，就是 `describeFiles` 实际序列化出去的那些文件。

**匹配是精确字符串相等，不做任何归一化**：`./pom.xml` ≠ `pom.xml`，
不同分隔符也算不同路径（注释说明：本层没有关于「什么算同一个路径」的可靠依据可依赖）。

### 13.3 模型引用了不存在 / 未读的文件会怎样

```text
只要有一条依据的路径不在本次材料中 → **整次分析被拒绝**（AiGatewayException → 502）
不是丢掉那一条、保留其余
```

理由：无法定位的依据说明模型这次没有按材料作答，其结论整体都不可信；
保留其余部分等于把一份来源已经不可靠的分析当成可用的分析。

### 13.4 为什么在早期 smoke 之后引入

提示词**已经要求**模型只引用材料中的路径（「不要引用没有在材料里出现过的路径」），
但那只是**要求**：模型完全可以给出一个看起来合理、却从未被读取过的路径。
而这正是 M1 smoke 暴露的问题类型——方向最终引用的文件全部是配置与构建元数据，
业务实现一个都没进来。因此这条限制必须由代码兜住（代码注释直接引用了
`DOMAIN_MODEL.md §3.6`：「依据不应由无法定位依据的模型输出凭空产生」）。

### 13.5 它提供什么可追溯性，不证明什么

```text
提供  每条 Evidence → 本次真实读过的某个文件（该 revision 上）
不证明 「这条 claim 确实成立」
不证明 「这个文件足以支撑这条结论」
不证明 「这个文件是重要的」
```

「指向真实文件」是**可定位性**的保证，不是**正确性**的保证。模型仍然可能：
引用了正确的文件但得出错误结论；引用了次要文件却声称它是核心实现。

---

## 14. RepositoryProfile 的创建与持久化

### 14.1 Domain 侧

```java
RepositoryProfile.create(
    RepositoryProfileId id,      // ← Application 生成：UUID.randomUUID()
    SoftwareAssetId assetId,
    String analyzedRevision,
    String purpose,
    List<String> techStack, List<String> modules, List<String> capabilities,
    List<String> reusableAssets, List<String> limitations, List<String> risks,
    List<Evidence> evidence)
```

| 字段 | 约束（`RepositoryProfile`） |
|---|---|
| `id` / `assetId` | 不得为 `null` |
| `analyzedRevision` | 非 `null` 非空白；**格式不受领域约束**（Git commit / 快照 id 都可以） |
| `purpose` | 非 `null` 非空白 |
| 六个段 | 列表本身不得为 `null`，**可以为空**；元素不得为空白 |
| `evidence` | 不得为 `null`、不得含 `null`；**可以为空** |

所有列表都经 `List.copyOf` 固化：不可变，不与调用方共享状态。**没有 setter，没有 status，没有 revision**——
`RepositoryProfile` 是**不可变的写一次快照**。同一个 Repository 在新 revision 上重新分析会得到
一份**新的 Profile 与新的 id**（INV-D04）。

`Evidence` 由 Application 构造：

```java
new Evidence(EvidenceSourceType.REPOSITORY, item.sourceRef(), item.claim(), null, false)
```

`sourceType` 固定 `REPOSITORY`、`confirmed` 固定 `false`、`confidence` 固定 `null`
——**可信程度与确认状态不由模型给出**（领域尚未规定 confidence 的数值口径，不在这里臆造一个刻度）。

### 14.2 持久化

```text
RepositoryProfileRepository.save(profile)
      ↓ SqliteRepositoryProfileRepository（@Transactional）
        1. if (selectById(profileId) != null) → RepositoryProfileAlreadyExistsException   ← 写一次语义
        2. insert header（repository_profile）
        3. insert 段项（repository_profile_section_item：profileId, section, position, value）
        4. insert 依据（repository_profile_evidence：…, source_type, source_ref, claim, confidence, confirmed）
```

| 问题 | 答案 |
|---|---|
| 何时保存 | 整条链路的**最后一步**，且只调用一次 |
| 为什么不会有半成品 Profile | 保存是最后一个语句；它之前的任何失败都以异常结束，此时**尚未写入**。写本身是单事务：要么整份，要么什么都没有 |
| 持久化了什么 | `id / assetId / analyzedRevision / purpose` + 六个段 + 依据 |
| **没有**持久化什么 | 仓库原文、净化后的材料、Scout 计划、Region 目录、`RF-*` / `RR-*` 引用、跳过诊断、替换计数。这些都不进入 Domain，也没有对应的表 |
| 表结构 | `V4__repository_profile.sql`；三张表均**没有 revision / status 列**（`analyzed_revision` 描述的是「分析的是哪个软件状态」，不是「这份 Profile 自己的第几版」） |

### 14.3 HTTP 响应路径

```text
AnalyzeRepositoryUseCase.analyze 返回 RepositoryProfile
      ↓ SoftwareAssetController.analyze
RepositoryProfileResponse.from(profile)     ← 映射放在资源自己的类上（查询端点也用同一份）
      ↓ @ResponseStatus(CREATED)
201 { id, assetId, analyzedRevision, purpose, techStack[], modules[], capabilities[],
      reusableAssets[], limitations[], risks[], evidence[{sourceType, sourceRef, claim, confidence, confirmed}] }
```

`sourceRef` 是**仓库内相对路径**——接口不暴露宿主机文件系统布局。

查询端点 `GET /api/repository-profiles/{id}` 走 `GetRepositoryProfileUseCase` → 同一个响应类，
只读、没有状态机、没有重新分析入口。

---

## 15. 失败分类

### 15.1 三类语义（先分清）

```text
400 请求不合法          IllegalArgumentException（含领域拒绝）—— 调用方改请求可以避免
409 当前无法分析        资产存在、请求可理解，但当前状态不允许 —— 要改变的是资产/仓库状态
502 外部 AI 能力失败     AiGatewayException（含模型没按约定作答）
500 本地能力失败 / 兜底  WorkspaceException、未映射异常
```

### 15.2 明细表

> 「Provider 已调用」指的是 AI Gateway 已经发出过请求；「内容已读」指的是
> `readFile` 已经返回过仓库原文；「Profile 可已写」在所有行都是 **否**（保存是最后一步）。

| # | 阶段 | 抛出点 | 异常 | HTTP | Provider 已调用 | 内容已读 |
|---|---|---|---|---|---|---|
| 1 | 资产 | `AnalyzeRepositoryUseCase` 找不到资产 | `SoftwareAssetNotFoundException` | 404 | 否 | 否 |
| 2 | 资产 | `SoftwareAsset.requireAnalysisAllowed` | `SoftwareAssetNotReadableException` | 409 | 否 | 否 |
| 3 | 位置 | 编排层 `isReadableRepository == false` | `RepositoryNotAnalyzableException` | 409 | 否 | 否 |
| 4 | revision | `GitWorkspaceAdapter.headRevision`（空仓库等） | `WorkspaceException` | 500 | 否 | 否 |
| 5 | Map | `listEntries` / 路径 / revision 校验 | `WorkspaceException` / `IllegalArgumentException` | 500 / 400 | 否 | 否 |
| 6 | Map | `requireSourceCandidates`（无 SCOUT_SOURCE） | `RepositoryNotAnalyzableException` | 409 | 否 | 否 |
| 7 | flat 判定 | 目录超限 | **不失败**：改走分层（决策点） | — | 否 | 否 |
| 8 | flat Scout | Gateway 调用失败（含缺 Key、超时、非 2xx） | `AiGatewayException` | 502 | **是（元数据）** | 否 |
| 9 | flat Scout | 解析失败 / 引用非法 / 区域内重复 | `AiGatewayException` | 502 | **是（元数据）** | 否 |
| 10 | Region | Catalog 超限（`RepositoryRegionScoutExtraction.scout` 内，Gateway 之前） | `RepositoryRegionCatalogTooLargeException` → 编排层转 409 | 409 | 否（**该次**未发出；此前可能已有其它 Region 调用） | 否 |
| 11 | Region | 轮数 / Region 调用数超限 | `RegionNavigationBudgetExceededException` | 409 | 否（超限那次未发出） | 否 |
| 12 | Region | Region 解析失败 / 引用非法 / 重复 | `AiGatewayException` | 502 | **是（元数据）** | 否 |
| 13 | 总数 | Scout 调用总数超限（导航或分支阶段） | `ScoutCallBudgetExceededException` | 409 | 否（超限那次未发出） | 否 |
| 14 | Region | 结构不可再分（无子目录 / 直属文件本身超限） | `RegionHierarchyNotReducibleException` | 409 | 可能已有 Region 调用 | 否 |
| 15 | 分支 | 分支本地 File Scout 失败 | `AiGatewayException` | 502 | **是（元数据）** | 否 |
| 16 | 规划 | 读取计划为空 | `RepositoryNotAnalyzableException` | 409 | 是（Scout 已发生） | 否 |
| 17 | 读取 | `readFile` 失败（该 revision 上不该缺的文件） | `WorkspaceException` | 500 | 是 | **部分（失败前已读的文件）** |
| 18 | 读取 | 执行期复核后材料全被剔除 | `RepositoryNotAnalyzableException` | 409 | 是 | **是** |
| 19 | 凭据边界 | 净化失败（**当前无生产触发点**，见下） | `RepositoryNotAnalyzableException(SECRET_BOUNDARY_FAILED)` | 409 | 是 | **是** |
| 20 | 最终分析 | Gateway 失败 | `AiGatewayException` | 502 | **是（含内容）** | 是 |
| 21 | 最终分析 | 解析失败（字段缺失 / 类型不对） | `AiGatewayException` | 502 | **是（含内容）** | 是 |
| 22 | 依据校验 | `sourceRef` 指向本次未提供的文件 | `AiGatewayException` | 502 | **是（含内容）** | 是 |
| 23 | Domain | `RepositoryProfile.create` 拒绝（如 purpose 空白） | `IllegalArgumentException` | 400 | 是 | 是 |
| 24 | 持久化 | id 已存在 | `RepositoryProfileAlreadyExistsException` | **500**（未映射 → 兜底） | 是 | 是 |

### 15.3 两类 AI 调用必须分清

```text
元数据型调用（Region Scout / File Scout）
    载荷 = 描述符（目录规模 / 路径 / 大小 / 语言 / 角色提示）
    内容 = 无
    失败后果 = 502，但**没有仓库内容离开本机**
    次数 = 0 ~ 18（受 scout-calls.max-total 约束）

内容型调用（最终 Analyzer）
    载荷 = 相对路径 + **净化后的文件正文**
    内容 = 有（**整条链路唯一一处**）
    只有前面全部成功（且材料已净化）才会发生
```

这个区分是 §9 / §11 两个执行点存在的全部理由：**必须保证内容型调用的载荷里没有命中规则的凭据**。

### 15.4 值得一提的是

- **9 / 12 / 15 这三行的失败发生在元数据型调用之后**：一次分析可能已经付过若干次 Scout 调用，
  才在解析或引用校验上失败。这是设计上的取舍（失败关闭优先于节省调用）。
- **第 19 行当前是不可达的**：`DeterministicRepositorySecretPolicy` 的两个方法是对字符串的纯函数，
  今天不存在让它无法完成输入的情形。`RepositorySecretBoundaryException` 是**契约的一部分**
  （类 javadoc 明说「当前实现不会以它失败，但契约必须允许」），生产上唯一能触发它的方式
  是注入一个会失败的策略——`RepositoryAnalysisSecretBoundaryFailureApiTest` 正是这么做的
  （`@Primary failingRepositorySecretPolicy` → 断言 409）。
  因此**路径判定失败的真实语义**是：它发生在 Scout 调用**之后**（凭据路径判定在规划器里，
  规划在 Scout 之后），所以「不调用最终分析」成立，但「此前可能已发生仅含元数据的 Scout 调用」。
- **第 24 行落到兜底 500**：`RepositoryProfileAlreadyExistsException` 没有出现在
  `ApiExceptionHandler` 的任何 `@ExceptionHandler` 里。用随机 UUID 作 id 时实际不可达，
  但这条映射缺口是真实的。

---

## 16. 生产 Spring 装配

### 16.1 Bean 图

```text
RepositoryAnalysisUseCaseConfiguration（Composition Root，@Configuration(proxyBeanMethods=false)）
│
├─ RepositoryMapBuilder(WorkspaceReadPort)
├─ RepositoryScoutExtraction(AiGateway, ObjectMapper)            ← File Scout（flat）
│     内部自己 new FileCatalogPayload / Parser / Resolver
├─ RepositoryRegionScoutExtraction(AiGateway, ObjectMapper, properties.region().toLimits())
├─ RepositoryRegionNavigator(
│       regionScout, new FileCatalogPayload(objectMapper),      ← **同一个类的另一个实例**
│       properties.scout().maxCatalogBytes(),
│       properties.region().toRecursionBudget(),
│       properties.scoutCalls().toBudget())
├─ RepositoryBranchScoutRunner(
│       repositoryScoutExtraction,                              ← **复用同一个 File Scout 实例**
│       properties.region().toRecursionBudget(),
│       properties.scoutCalls().toBudget())
├─ RepositorySecretPolicy → DeterministicRepositorySecretPolicy  ← 规则写死在代码里
├─ RepositoryReadPlanner(properties.foundation().toBudget(),
│                        properties.targetedSource().toBudget(),
│                        repositorySecretPolicy)                 ← 执行点 ①
├─ RepositoryReadExecutor(WorkspaceReadPort, foundationBudget, targetedSourceBudget)
├─ RepositoryUnderstanding(mapBuilder, fileScout, navigator, branchRunner,
│                          readPlanner, readExecutor, secretPolicy,        ← 执行点 ②
│                          properties.scout().maxCatalogBytes())
├─ RepositoryAnalysisExtraction(AiGateway, ObjectMapper)         ← 最终 Analyzer
├─ GetRepositoryProfileUseCase(RepositoryProfileRepository)
└─ AnalyzeRepositoryUseCase(SoftwareAssetRepository, WorkspaceReadPort,
                            understanding, analysisExtraction, profileRepository)
```

### 16.2 有哪些限制被**刻意绑在一起**，哪些是独立的

| 绑定 / 独立 | 说明 |
|---|---|
| **同一个值**：`scout.max-catalog-bytes` | 既用于「flat 目录放不放得下」（`RepositoryUnderstanding`），也用于「一个分支的目录放不放得下」（`RepositoryRegionNavigator`）。若取成两个值，分层可能交出一个**超过 flat 上限却在分支上限之内**的目录，「交给模型的文件目录不会超过这个上限」这条保证就断了。`RepositoryAnalysisWiringTest.usesTheSameCatalogByteLimitForBothScoutPaths` 把这条钉死 |
| **同一个渲染入口**：`FileCatalogPayload` | 导航器一个实例、File Scout 内部一个实例——**同一个类的两个无状态实例，不是两份实现**。因此停止条件量到的字节与真正发出的载荷必然一致 |
| **同一个实例**：`RepositorySecretPolicy` | 两个执行点共用，保证「一条政策」而不是两套规则 |
| **同一个实例**：`RepositoryScoutExtraction` | flat 路径与所有分支本地 File Scout 复用，不另写解析器或提示词 |
| 独立：`region.max-catalog-bytes` | 量的是**目录描述符**载荷，与文件目录载荷是两件事，因此是独立配置键 |
| 独立：两条通道的材料预算 | Foundation 与定向源码各一份，互不占用 |
| 独立：轮数 vs 总调用数 | 轮数管深度，总调用数管「深度 × 宽度」；总调用数小于单分支轮数上限是合法组合 |
| 独立：Region 通道上限 vs 总数上限 | 见 §6.3 |
| 独立：导航预算 vs 材料预算 | 前者约束探索范围（进而约束 AI 调用增长），后者约束读源码规模 |

### 16.3 配置键与当前默认值

| YAML 键 | 值 | Java 属性 |
|---|---|---|
| `delveforge.ai.deepseek.base-url` | `https://api.deepseek.com` | `DeepSeekProperties.baseUrl` |
| `delveforge.ai.deepseek.model` | `deepseek-flash` | `model` |
| `delveforge.ai.deepseek.timeout` | `60s` | `timeout` |
| `delveforge.ai.deepseek.api-key` | `${DEEPSEEK_API_KEY:}`（缺失时为空串） | `apiKey`（**刻意不校验**，缺失时启动仍成功，调用时才失败） |
| `delveforge.repository-analysis.scout.max-catalog-bytes` | `65536` | `scout.maxCatalogBytes` |
| `…scout-calls.max-total` | `18` | `scoutCalls.maxTotal` |
| `…region.max-catalog-bytes` | `65536` | `region.maxCatalogBytes` |
| `…region.max-selected-regions` | `6` | `region.maxSelectedRegions` |
| `…region.max-rounds-per-branch` | `8` | `region.maxRoundsPerBranch` |
| `…region.max-scout-calls` | `12` | `region.maxScoutCalls` |
| `…foundation.max-files` / `max-file-bytes` / `max-total-bytes` | `12` / `32768` / `98304` | `foundation.*` → `RepositoryMaterialBudget` |
| `…targeted-source.max-files` / `max-file-bytes` / `max-total-bytes` | `18` / `65536` / `163840` | `targetedSource.*` → `RepositoryMaterialBudget` |

**默认值只在 `application.yml`，代码里不兜底**：配置缺失时启动即失败，
而不是静默使用一个意料之外的上限。所有数值在属性记录里校验（`> 0`；`maxTotalBytes >= maxFileBytes`）。

---

## 17. 生产在用 vs 历史保留

### 17.1 保留但已不在生产链路上的类

| 类 | 原来为什么存在 | 为什么不再是生产 | 还有谁引用它 |
|---|---|---|---|
| `RepositoryAnalysisMaterialCollector` | M1 的确定性代表采样：`listEntries` → 分类 → 类别轮转 → 有界选取 → `readFile` | ADR-0004 的两阶段取代了它：Map + Scout + 定向读取 | **main 源码零引用**；两个单元测试直接测它；`RepositoryAnalysisWiringTest` 断言它**不是 Bean** |
| `RepositoryAnalysisMaterialPolicy` | 上者的预算值对象（`mvpDefault()` = 40 / 20 000 / 200 000） | 同上 | 只有 `MaterialCollector` 引用它 |
| `RepositoryAnalysisMaterialCategory` | 上者的路径分类枚举（`of(path)`） | 被 `RepositoryPathClassifier` + `RepositoryMaterialKind` 取代 | 只有 `MaterialCollector` 引用它 |

**没有任何 Bean 装配它们**，也**不存在「新路径失败就退回旧路径」的降级**
（`RepositoryAnalysisUseCaseConfiguration` 的类 javadoc 明确写了这一条）。
它们保留在代码库里，是因为 M1 复盘与对照仍会引用。

**删除它们需要连带处理**：`RepositoryAnalysisMaterialCollectorTest`、
`RepositoryAnalysisMaterialCategoryTest` 随类一起删；
`RepositoryAnalysisWiringTest.doesNotWireTheOldMaterialSelectionPolicy` 的**断言要改而不是删**
（它是有价值的守卫：防止有人把旧路径接回装配）。

### 17.2 判断依据

```text
main 源码中对 MaterialCollector 的非注释引用数 = 0
RepositoryAnalysisUseCase / RepositoryUnderstanding / RepositoryReadPlanner /
RepositoryReadExecutor 的 import 里都没有这三个类
除这三个类之外，repositoryanalysis 包的 67 个 main 类全部可从已装配的 Bean 到达
```

---

## 18. 历史演进

```text
① M1：确定性代表采样
     listEntries → 排除 → 分类 → 类别轮转 → 三个预算（40 / 20 KB / 200 KB）→ 一次 AI 调用
     要回答的是「系统在某个确定的软件状态上理解到了什么」——要求可信，不要求理解得深

        ↓ 真实链路验证暴露的问题（Round 1 §10）
② 问题：方向最终引用的 16 个文件**全部**是配置、脚本、文档与构建元数据；
     controller/ service/ entity/ mapper/ 下的业务实现一个都没进入过任何方向的依据。
     原因：确定性路径排序 + 先到先得的预算，让「排在前面的工程元数据」挤掉了业务实现。
     触发 ADR-0004（M1 写下的重访条件 1，见 Round 1 §10 TRIGGERED）

        ↓
③ ADR-0004：Repository Map + File Scout + RF 校验 + 定向读取
     Map 让**整棵树可见**（不再靠筛选制造盲区）
     Scout 让模型**指认**「读哪些」而不是替它排序
     引用校验把「模型指了一个不存在的文件」变成可判定的失败
     定向读取让被选中的文件可以完整进入分析，不再被先到先得挤掉

        ↓ 真实多仓 smoke（4/5 仓库 fail-closed）
④ 问题：一次性把**全部** SCOUT_SOURCE 序列化成 flat File Catalog，
     与仓库大小线性相关：mall 1.51×、memos 2.15×、awesome 2.74×、langflow 13.6×（上限 64 KiB）
     失败点**唯一地落在 LLM 面向的 flat catalog**，而不是 Map 的构建（5 个仓库全部成功建 Map）
     触发 ADR-0005（ADR-0004 Revisit Condition 1 的实质形态）

        ↓
⑤ ADR-0005：分层 Region Scout
     先在目录层缩小范围（Region Catalog 只有规模与语言提示，没有文件清单，更没有内容）
     递归到每个分支的 File Catalog 能装下为止
     终态分支各自跑一次**现有的** File Scout
     保序轮转合并

        ↓
⑥ 生产接入（Task 10A-6）
     flat 放得下时**完全旁路**分层（小仓库不多付一次 Region 调用）
     唯一一处最小输入泛化：RepositoryTargetedSourceCandidates
     守卫失败的对外语义统一成「当前无法分析」（409），而不是「未知服务端故障」

        ↓ 凭据边界侦察与实现（Task 10B-1 / 10B-2，ADR-0006）
⑦ 问题：源代码与配置会被原样送给外部模型
     结论：**一条政策，两个执行点** —— 读取前按路径整份排除 + 交给模型前内容净化
     规则写死在代码里（可配置就等于可以被静默放宽）
     失败关闭：没有「净化没跑完就按原文继续」这条路
```

**每一步都是被前一步的真实失败推出来的**，不是一开始就设计好的：
分层 Scout 在 M1 与 ADR-0004 里都不存在；凭据边界在 ADR-0004 与 ADR-0005 里也都没有出现。
把它们读成「一开始就规划好的分层架构」会误解这套代码里每一道守卫存在的理由。

---

## 19. 完整走查示例

虚构一个小仓库（**不声称模型会怎么选**——模型相关处已标注）：

```text
pom.xml
application.yml
.env
docker-compose.yml
docs/README.md
src/main/java/com/acme/controller/OrderController.java
src/main/java/com/acme/service/OrderService.java
src/main/java/com/acme/mapper/OrderMapper.java
src/main/java/com/acme/config/AppConfig.java
src/test/java/com/acme/service/OrderServiceTest.java
```

### 19.1 Map 与路由（确定性，可复算）

| 路径 | materialKind | roleHints | lane |
|---|---|---|---|
| `pom.xml` | BUILD_METADATA | — | FOUNDATION |
| `application.yml` | CONFIGURATION | — | FOUNDATION |
| `.env` | CONFIGURATION | — | FOUNDATION（**随后被路径排除**） |
| `docker-compose.yml` | DEPLOYMENT | — | FOUNDATION |
| `docs/README.md` | DOCUMENTATION | — | FOUNDATION |
| `…/controller/OrderController.java` | SOURCE_CODE | API_ENTRY | SCOUT_SOURCE |
| `…/service/OrderService.java` | SOURCE_CODE | APPLICATION_SERVICE | SCOUT_SOURCE |
| `…/mapper/OrderMapper.java` | SOURCE_CODE | PERSISTENCE | SCOUT_SOURCE |
| `…/config/AppConfig.java` | SOURCE_CODE | **CONFIG_BOOTSTRAP（恰好一个）** | **FOUNDATION** |
| `…/service/OrderServiceTest.java` | TEST_CODE | —（测试不给角色提示） | NONE |

### 19.2 flat 还是分层

```text
SCOUT_SOURCE 只有 3 个文件 → File Catalog 几百字节 ≪ 65536
→ flat 路径，**完全不进入分层**（一次 Scout 调用，无 Region Scout）
```

### 19.3 FOUNDATION 通道（无模型参与）

```text
按 materialKind 枚举顺序分组：BUILD_METADATA → CONFIGURATION → DOCUMENTATION → DEPLOYMENT → SOURCE_CODE
      ↓ 组内按路径升序
      ↓ 类别轮转，在预算（12 个 / 32 KiB / 96 KiB）内选取
.env 在进入队列之前被 excludedFromMaterial 拦下 → 不进队列、不占名额、不会被读
      ↓
选出 ≈ 5 个：pom.xml、application.yml、docs/README.md、docker-compose.yml、AppConfig.java
```

### 19.4 SCOUT_SOURCE 通道（模型参与，标注为模型相关）

```text
发给模型：3 个描述符（编号 / 路径 / 大小 / 语言 / 材料类别 / 角色提示）——**没有内容**
模型返回（示例，**模型相关**）：{ focusAreas: [ {label:"订单入口", fileRefs:["RF-1"]},
                                            {label:"订单业务逻辑", fileRefs:["RF-2","RF-3"]} ] }
解析 + 引用校验 → RepositoryInspectionPlan（2 个区域，条目已换回真实描述符）
```

### 19.5 规划

```text
Foundation 候选  → 类别轮转 → 全部 5 个装得下（假设都小于 32 KiB）
定向源码候选    → 区域轮转 → OrderController / OrderService / OrderMapper
去重             → 无重复
RepositoryReadPlan(foundation=5, targeted=3, skipped=[.env → EXCLUDED_BY_SECRET_POLICY])
```

### 19.6 读取与净化

```text
readFile × 8（5 + 3），全部带同一个 analyzedRevision
      ↓ 执行期按真实内容复核尺寸
      ↓ 凭据边界执行点 ②：sanitize
           application.yml 的 password: <值> → password: [redacted-credential]（键名与缩进保留）
           其它文件未命中规则 → 原样
      ↓
modelVisibleMaterial（8 个文件，其中一个的内容被替换过）
```

### 19.7 最终分析

```text
发给模型：8 个 {path, content} —— 这是唯一一处发送仓库内容的调用
模型返回（**模型相关**）：
  { purpose: "…", techStack: ["Java","Spring Boot"], modules: ["order"],
    capabilities: ["订单创建与查询"], reusableAssets: [...], limitations: [...], risks: [...],
    evidence: [ {claim:"…", sourceRef:"src/main/java/com/acme/service/OrderService.java"}, … ] }
裁判：解析器检查字段齐全 → requireEvidenceRefersToSentFiles 检查每条 sourceRef 在 8 个路径里
```

### 19.8 Domain 与持久化

```text
RepositoryProfile.create(uuid, assetId, revision, purpose, 六个段, Evidence[sourceType=REPOSITORY, confirmed=false, confidence=null])
      ↓ save（单事务，写一次）
      ↓ 201 RepositoryProfileResponse
```

**整个过程里没有任何一次调用把 `.env` 的内容发出去**——它连读都没被读过。

---

## 20. 包地图（为后续读源码准备）

> 依赖方向：`map → scout → region → readplan → secret → extraction → workflow`（前向依赖，无环）。

```text
map/
Question: 「这次提交里有什么？」
Read:
  1. RepositoryMap            完整描述符集合 + RF-* 索引；of() 强制「引用=位置」
  2. RepositoryMapEntry       一个描述符有什么、没有什么
  3. RepositoryCandidateLane  FOUNDATION / SCOUT_SOURCE / NONE 的路由规则
  4. RepositoryMaterialKind   材料分类的取值
  5. RepositoryRoleHint       结构角色提示的取值（UNKNOWN 的语义）
  6. RepositoryPathClassifier 路径 → 分类（纯函数；注意 build/target 的上下文规则）
  7. RepositoryMapBuilder     唯一入口：listEntries → 排序 → 编号 → 分类
```

```text
scout/                                   （flat File Scout）
Question: 「接下来读哪些文件？」
Read:
  1. RepositoryScoutInputs    输入：全部 SCOUT_SOURCE 描述符（不含内容）
  2. FileCatalogPayload       唯一的序列化入口（度量与发送共用它）
  3. RepositoryScoutExtraction  调用 + 度量 + 组装 AiRequest
  4. AiRepositoryScoutProposal / AiRepositoryScoutFocusArea  不可信提议（含 MIN/MAX_FOCUS_AREAS）
  5. RepositoryScoutProposalParser   形状校验（数量、格式、区域内不重复）
  6. RepositoryScoutProposalResolver 引用校验（越界 / 非源码候选）
  7. RepositoryInspectionPlan / RepositoryInspectionArea  可信结果
```

```text
region/                                  （hierarchical Scout）
Question: 「这一层该往哪几个目录看？」
Read:
  1. RepositoryRegion          Region 描述符有什么、没有什么
  2. RepositoryRegionTree      按目录前缀聚合（只有 SCOUT_SOURCE 参与）
  3. RepositoryRegionReference RR-<scope>-<position> 与「作用域=调用身份」
  4. RepositoryRegionCatalog   一次调用可见的 Region + 编号
  5. RepositoryRegionProposalParser / Resolver  同上，粒度是目录
  6. RepositoryRegionScoutExtraction  调用边界内的字节守卫
  7. RegionNavigationLimits / RegionRecursionBudget / ScoutCallBudget  三种预算的含义
  8. RepositoryRegionNavigator 递归下降（终态组 / 直属文件 / 不可再分）
  9. RepositoryTerminalFileGroup / RepositoryRegionNavigation  中间结果
 10. RepositoryBranchScoutRunner 逐组 File Scout + 保序轮转合并
 11. RepositoryFileCandidates  最终有序候选（顺序的三层含义）
```

```text
readplan/
Question: 「在预算内到底读哪些文件？」
Read:
  1. RepositoryMaterialBudget maxFiles / maxFileBytes / maxTotalBytes 的约束
  2. RepositoryReadPlanner    两条通道 + 类别轮转 + 聚焦区域轮转 + 凭据路径排除
  3. RepositoryTargetedSourceCandidates  分层候选流（为什么不是 InspectionPlan）
  4. RepositoryReadPlan       两条通道互斥、路径不得重复
  5. RepositoryReadSkipReason / SkippedReadCandidate  跳过原因的三种取值
  6. RepositoryReadExecutor   唯一一处 readFile + 执行期尺寸复核
  7. RepositoryReadResult     结果与执行期诊断（两条通道各自记账）
```

```text
secret/
Question: 「哪些东西不能出这个进程？」
Read:
  1. RepositorySecretPolicy   一条政策、两个执行点的契约
  2. DeterministicRepositorySecretPolicy  路径规则 + 内容规则（代价有界的写法）
  3. SanitizedRepositoryMaterial  「模型可见材料」这一事实的类型载体
  4. RepositorySecretBoundaryException  失败出口（当前无生产触发点）
```

```text
extraction/
Question: 「这些内容说明了什么？」
Read:
  1. RepositorySourceFile     材料的形状（相对路径 + 内容）
  2. RepositoryAnalysisExtraction  唯一的内容型 AI 调用 + 依据校验
  3. RepositoryAnalysisProposalParser  字段必须全部出现
  4. RepositoryAnalysisProposal / RepositoryEvidenceProposal  中间数据，不是领域对象
```

```text
workflow/                                 （编排）
Question: 「这一整件事按什么顺序发生？」
Read:
  1. AnalyzeRepositoryUseCase  八步编排；revision 只解析一次；保存是最后一步
  2. RepositoryUnderstanding   理解阶段的总控；两条路径；两个执行点的位置
  3. RepositoryNotAnalyzableException  409 的统一语义与它覆盖的情形
  4. （历史保留）RepositoryAnalysisMaterialCollector / Policy / Category  见 §17
```

---

## 21. 本次侦察发现的文档 / 代码不一致（**只记录，不修改**）

以下都不是生产行为缺陷，而是**读源码时可能被误导**的地方。按对本报告读者的影响排序。

| # | 位置 | 现状 | 与代码的冲突 | 影响 |
|---|---|---|---|---|
| F1 | `map/package-info.java` 「它在整条链路里的位置」 | 「本包是 ADR-0004 两阶段设计的第一阶段。当前它**尚未接入** `AnalyzeRepositoryUseCase`：现有材料收集与选材策略没有任何变化。」 | 早已接入：`RepositoryMapBuilder` 由装配提供、由 `RepositoryUnderstanding` 调用 | **高**：读者会以为 Map 还没上生产 |
| F2 | `scout/package-info.java` 同上段落 | 「本包是 ADR-0004 两阶段设计的第二阶段。它**尚未接入** `AnalyzeRepositoryUseCase`……」（同一段还写「不做分层 Scout —— 当前清单规模（真实验证仓库约 84 条）不需要」） | 已接入；分层 Scout 不仅存在，而且已是 oversized 仓库的**唯一**路径 | **高** |
| F3 | `scout/RepositoryScoutInputs` javadoc | 「分层 Scout、独立预算与聚焦轮转都属于后续阶段。」 | 三者都已落地（ADR-0005 + Read Planner） | 中 |
| F4 | `scout/RepositoryInspectionPlan` javadoc | 「『按计划去读文件』『基础材料与定向源码各占多少预算』『重复引用怎么合并』都属于后续阶段，**当前不存在**。」 | 三者都已存在（`RepositoryReadPlanner` / `RepositoryReadExecutor`） | 中 |
| F5 | `region/RegionNavigationLimits` javadoc | 「ADR-0005 还包含轮数、总调用数与终态分支数三个守卫，它们属于**后续的编排步骤**……本类型不提前发明它们。」 | 轮数与总调用数已实现，分别落在 `RegionRecursionBudget` / `ScoutCallBudget` | 中 |
| F6 | `workflow/package-info.java` | 「`RepositoryAnalysisMaterialPolicy` 描述的是**当前** Application 层的 MVP 选材策略」 | 该策略已退出生产链路（§17）；真正在用的是 `RepositoryReadPlanner` | **高**：正是本项目最容易被误学成「当前架构」的一处 |
| F7 | ADR-0006 / `RepositoryUnderstanding` 的失败语义描述 | 凭据边界失败 → 409，失败关闭 | **一致**，但需注意：生产上没有任何路径会抛 `RepositorySecretBoundaryException`（`DeterministicRepositorySecretPolicy` 是纯函数）。`SECRET_BOUNDARY_FAILED` 目前只在注入失败策略的测试里可达 | 低（契约性预防，类 javadoc 已如实说明） |
| F8 | `ARCHITECTURE.md` §8.2 的时序图 | `RA->>AI: Analyze Repository` 只有一次交互 | V3 实际有 1~18 次元数据型调用 + 1 次内容型调用。§8.2 是高层次协作图，未提 Scout / 分层 | 低（若读者把它当成调用次数的事实来源则会误判成本） |
| F9 | `ApiExceptionHandler` | — | `RepositoryProfileAlreadyExistsException` 没有映射 → 落到兜底 500。随机 UUID 下不可达，但缺口真实 | 低 |

**F1 / F2 / F6 是同一类问题**：这些 javadoc 写于对应阶段「刚建成、还没接上线」的时刻，
之后接入发生（ADR-0004 → 10A-6），但注释没有跟着改。它们不会影响运行，
但会让「按注释学架构」的读者学到一个**已经不成立的中间状态**。

**F7 / F9 属于「契约 vs 现状」的不同侧面**：F7 是契约预留了失败出口而现状用不到（如实说明，不算缺陷）；
F9 是现状会出现而映射表没有覆盖（是缺口）。

---

## 附：本报告的事实来源

```text
代码（main @ 9c8f014）
  application/repositoryanalysis/{asset,extraction,map,profile,readplan,region,scout,secret,workflow}
  app/api/softwareasset、app/api/repositoryprofile、app/error、app/config
  infrastructure/workspace（GitWorkspaceAdapter）、infrastructure/ai（DeepSeekAiGatewayAdapter）
  infrastructure/persistence/repositoryprofile + db/migration/V4__repository_profile.sql
  domain/{repositoryprofile,evidence,asset}

测试（用于确认行为而非补充设计）
  AnalyzeRepositoryUseCaseTest（revision 只解析一次 / HEAD 前移 / 金丝雀 / 回显）
  RepositoryUnderstandingTest（flat 旁路 / 边界值 / 守卫 / 失败关闭）
  RepositoryReadPlannerSecretPolicyTest、DeterministicRepositorySecretPolicyTest
  RepositoryAnalysisApiIntegrationTest、RepositoryAnalysisSecretBoundaryFailureApiTest
  RepositoryAnalysisWiringTest
  RepositoryBranchScoutRunnerTest、RepositoryRegionNavigatorTest

文档（仅用于解释意图，不作为事实来源）
  ADR-0001 … ADR-0006、ARCHITECTURE.md、DOMAIN_MODEL.md、ROADMAP.md、AGENTS.md
```

```text
未改动确认
  git diff 仅包含本文件
  0 处生产代码 / 测试 / 配置（application.yml）/ 权威文档改动
  0 次 Provider 调用（全程只读源码）
```
