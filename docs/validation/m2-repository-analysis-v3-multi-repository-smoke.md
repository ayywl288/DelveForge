# M2 — Repository Analysis V3 多仓库 Smoke 验证

**Status:** Validation Record / **Not Source of Truth**
**Last Updated:** 2026-10-03
**Validated Revision:** `main` @ `7dcc8fa`（V3 分层 Scout + 凭据边界均已合入）
**本轮性质:** 真实仓库端到端验证（5 个真实本地 Git Repository + 真实 Provider）
**依据:** ADR-0004 / ADR-0005 / ADR-0006、`docs/validation/m2-repository-analysis-v3-code-walkthrough-probe.md`、
`docs/validation/m2-final-multi-repository-smoke-test.md`、`docs/validation/m2-hierarchical-scout-design-reconnaissance.md`

> 本文档记录一次真实运行**实际观察到了什么**，不新增规则、不替代任何权威文档。
> 领域语义以 `DOMAIN_MODEL.md` 为准，架构以 `ARCHITECTURE.md` / `AGENTS.md` 为准。
>
> **本轮没有修改任何生产代码、测试、Prompt、预算、分类规则或配置。**
> 唯一的代码是 build 目录（`target/`，未提交）下的只读观测工具，见 §1.2。

---

## 1. 验证范围与环境

### 1.1 仓库与 revision

五个仓库沿用此前多仓验证的同一批本地快照，**HEAD 与工作树均未改动**：

| 逻辑名 | 目录 | analyzedRevision | 工作树 |
|---|---|---|---|
| hm-dianping | `java-comment-main` | `8a5fa2b607ede3666ab0df1c96edf851aa4293e5` | clean |
| mall | `mall-master` | `3910bf80a9723b165e707f25649d6befb517ee53` | clean |
| memos | `memos-main` | `aea105e081c97dcd45eea25adcf2b69d89c8e4ef` | clean |
| awesome-llm-apps | `awesome-llm-apps-main` | `b7b5dd3bfc11b855786d3d6749cbb6199d204025` | clean |
| langflow | `langflow-main` | `4c291b766c859222550d01cc21d8bc43475d7356` | clean |

五个 revision 与 `m2-hierarchical-scout-design-reconnaissance.md` §4 记录的 HEAD **逐字相同**，
本轮没有静默更换 revision。

### 1.2 观测方法

真实应用 + 真实 Provider，中间插一个只读记录代理：

```text
java -jar delveforge-app.jar
     --delveforge.persistence.database-file=<临时库>
     --delveforge.ai.deepseek.base-url=http://127.0.0.1:8799   ← 指向记录代理
         ↓
记录代理（target/ 下的一次性脚本）
     · 原样转发到真实 Provider
     · 按顺序记录**请求体与响应体**
     · 不记录任何请求头 → Authorization 从未进入任何文件
     · 请求体为 chunked 编码，代理按 chunk 读取
```

`base-url` 是既有的环境相关配置项，因此**不需要改代码或配置**就能插桩。

另有三个 build 目录下的只读工具：

```text
Recon.java           用生产类（RepositoryMapBuilder / RepositoryScoutExtraction /
                     RepositoryCandidateLane / DeterministicRepositorySecretPolicy）
                     复算每个仓库的 Map 统计、flat catalog 字节数与路径排除集合
analyze_trace.py     按 system prompt 把每次调用分类为 Region / File / Final
analyze_repo.py      通道拆分、轮转合并重建、Evidence 校验
```

工具只读元数据：不读文件内容、不调用 Provider、不写任何仓库。

### 1.3 未做的事（与任务书一致）

```text
未提高 catalog 上限
未截断终态组
未采样任意文件
未绕过凭据边界
未回退到已退休的 M1 选材收集器
未在测试期间修改任何生产代码
```

---

## 2. Repository Map 统计（确定性、可复算）

用生产类复算，与 Provider 无关，因此同一 revision 下逐字节可复现：

| 仓库 | Map 文件 | FOUNDATION | SCOUT_SOURCE | NONE | flat File Catalog | 是否超限 |
|---|---|---|---|---|---|---|
| hm-dianping | 139 | 43 | 84 | 12 | 14,837 B | 否 |
| mall | 717 | 221 | 491 | 5 | 99,181 B | 是（1.51×） |
| memos | 1,317 | 153 | 880 | 284 | 141,079 B | 是（2.15×） |
| awesome-llm-apps | 1,975 | 1,062 | 854 | 59 | 179,351 B | 是（2.74×） |
| langflow | 10,375 | 3,194 | 4,572 | 2,609 | 889,253 B | 是（13.6×） |

**五个数值与上一轮侦察完全一致**（`84 / 491 / 880 / 854 / 4,572` 与
`14,837 / 99,181 / 141,079 / 179,351 / 889,253`）——分类与序列化是确定性的，
跨轮次可核对。

FOUNDATION 按 materialKind 分布（说明「工程形态」这一侧的构成）：

```text
awesome-llm-apps   BUILD_METADATA 213 · DOCUMENTATION 300 · OTHER 350 · CONFIGURATION 92
                   SCRIPT_AUTOMATION 68 · DEPLOYMENT 28 · DATA_SCHEMA 6 · SOURCE_CODE 5
langflow           OTHER 1723 · SCRIPT_AUTOMATION 518 · CONFIGURATION 405 · DEPLOYMENT 265
                   DOCUMENTATION 234 · BUILD_METADATA 40 · SOURCE_CODE 9
```

---

## 3. 运行结果总览

| 仓库 | HTTP | 路由 | Region 调用 | File Scout 调用 | Scout 合计 | 终态组 | 耗时 |
|---|---|---|---|---|---|---|---|
| hm-dianping | **201** | FLAT | **0** | 1 | 1 | —（flat 无分支） | 44.5 s |
| mall | **201** | HIERARCHICAL | 1 | 6 | 7 | 6 | 113 s |
| memos（第 1 次） | **502** | — | 3 | 4（中止） | 7 | — | 91 s |
| memos（第 2 次） | **201** | HIERARCHICAL | 3 | 12 | 15 | 12 | 181 s |
| awesome-llm-apps | **201** | HIERARCHICAL | 1 | 6 | 7 | 6 | 159 s |
| langflow | **409** | — | **12（用尽）** | 0 | 12 | 0 | ~2 min（未精确计时） |
| canary（合成夹具） | **201** | FLAT | 0 | 1 | 1 | — | <10 s |

聚合日志（`RepositoryUnderstanding` 唯一一行）：

```text
hm-dianping  scoutPath=FLAT  foundationSelectedCount=12 targetedSourceSelectedCount=18 materialCount=30 tooLargeCount=0 totalBudgetExceededCount=0 secretExcludedCount=0  sanitizedSpanCount=6
mall         scoutPath=HIER foundationSelectedCount=12 targetedSourceSelectedCount=18 materialCount=29 tooLargeCount=1 totalBudgetExceededCount=0 secretExcludedCount=0  sanitizedSpanCount=2
memos        scoutPath=HIER foundationSelectedCount=12 targetedSourceSelectedCount=18 materialCount=30 tooLargeCount=0 totalBudgetExceededCount=0 secretExcludedCount=0  sanitizedSpanCount=25
awesome      scoutPath=HIER foundationSelectedCount=12 targetedSourceSelectedCount=18 materialCount=29 tooLargeCount=1 totalBudgetExceededCount=0 secretExcludedCount=35 sanitizedSpanCount=30
canary       scoutPath=FLAT foundationSelectedCount=2  targetedSourceSelectedCount=1  materialCount=3  tooLargeCount=0 totalBudgetExceededCount=0 secretExcludedCount=2  sanitizedSpanCount=2
```

`tooLargeCount=1`（mall / awesome）= 规划期按 blob metadata 判定放得下的文件，
在执行期按**真实内容**复核时超限而被剔除——§6.2 展开。

---

## 4. Phase A — hm-dianping 的 flat 路径

**架构形状与预期一致，且是从运行行为验证的、不是假设的：**

```text
RepositoryMap（139 文件 / FOUNDATION 43 / SCOUT_SOURCE 84）
      ↓ flat File Catalog = 14,837 B <= 65,536 B
      ↓ 恰好 1 次 File Scout           ← 代理记录中只有 1 次 file 类调用
      ↓ 0 次 Region Scout              ← 代理记录中 0 次 region 类调用
      ↓ ReadPlanner
      ↓ 读取 30 个文件（12 Foundation + 18 targeted）
      ↓ 凭据净化（6 处替换）
      ↓ 1 次 Final Analyzer
RepositoryProfile @ 8a5fa2b6…
```

**与上一轮同仓库的结果对照**：上一轮（V2 / M1 采样）同一 revision 得到 32.7 s、
evidence 29 条；本轮 44.5 s、evidence 30 条、techStack 14 / modules 12 /
capabilities 19 / reusableAssets 12 / limitations 11 / risks 9。

模型判定的 purpose 仍然识别为「基于 Spring Boot 的本地生活点评后端 + 高并发重构」，
`modules` 以业务模块为主，**不是 config / build / docs 堆砌**——这一点与 ADR-0004 要解决的问题直接对应。

**flat 路径没有回归**：小仓库不多付任何 Region 调用，分层机制对它完全透明。

---

## 5. Phase B — 四个超限仓库

### 5.1 mall（1 Region + 6 File）

```text
flat catalog 99,181 B > 65,536 B
      ↓ Region Scout ×1（root 层 7 个 Region，见 §6.2）
      ↓ 选中 6 个 → 6 个终态组（全部在第一层就装得下，max depth = 1）
      ↓ 6 次分支本地 File Scout
      ↓ 保序轮转合并 → 266 个有序候选
      ↓ ReadPlanner 取前 18 个可用的（0 个因尺寸被跳过，恰好用满 maxFiles=18）
      ↓ 读取 29 个文件（11 Foundation + 18 targeted）
RepositoryProfile @ 3910bf80…
```

### 5.2 memos（3 Region + 12 File）

```text
flat catalog 141,079 B > 65,536 B
      ↓ Region Scout ×3，沿 web 分支下钻到第 3 层（max depth = 3）
      ↓ 12 个终态组
      ↓ 12 次分支本地 File Scout
      ↓ 合并 → 391 个有序候选
      ↓ ReadPlanner 扫描到第 26 个候选才选满 18（8 个因尺寸/剩余总量被跳过）
      ↓ 读取 30 个文件（12 Foundation + 18 targeted）
RepositoryProfile @ aea105e0…
```

### 5.3 awesome-llm-apps（1 Region + 6 File）

```text
flat catalog 179,351 B > 65,536 B
      ↓ Region Scout ×1（root 层 10 个 Region，选中 6）
      ↓ 6 个终态组（max depth = 1）
      ↓ 合并 → 329 个有序候选 → ReadPlanner 取前 18（0 个被跳过）
      ↓ 读取 29 个文件（11 Foundation + 18 targeted）
      ↓ 凭据边界排除 35 个路径（见 §8.1）
RepositoryProfile @ b7b5dd3b…
```

### 5.4 langflow（**失败：Region Scout 预算用尽**）

```text
flat catalog 889,253 B > 65,536 B
      ↓ 分层 Scout 启动，Region Scout 连续调用 12 次
      ↓ 第 13 次被守卫拒绝 → RepositoryNotAnalyzableException → 409
      ↓ 一次 File Scout 都没有发生，没有终态组，没有 RepositoryProfile
```

**这是本轮最重要的结果**，详见 §10.1。

---

## 6. Scout ↔ LLM 交互记录

> 完整逐调用记录见随本文档提交的 trace 产物：
>
> ```text
> docs/validation/artifacts/hm-dianping-scout-trace.json
> docs/validation/artifacts/mall-scout-trace.json
> docs/validation/artifacts/memos-scout-trace.json
> docs/validation/artifacts/awesome-llm-apps-scout-trace.json
> docs/validation/artifacts/langflow-scout-trace.json
> docs/validation/artifacts/canary-synthetic-scout-trace.json
> ```
>
> 每个产物含：调用序号、阶段（region / file / final）、**逐字的 SYSTEM prompt**、
> **逐字的 USER 载荷**、载荷 UTF-8 字节数、原始模型返回。
> Final Analyzer 的载荷含仓库源码，因此**不写入产物**（只记路径与每份大小）。
> 同一阶段的 SYSTEM prompt 文本相同，产物里存一次、其余用 `systemPromptRef` 引用。

### 6.1 如何区分三个阶段

三个阶段靠 system prompt 的固定措辞区分，`analyze_trace.py` 据此分类：

```text
"你是 DelveForge 的 Repository 区域侦察组件。" → Region Scout
"你是 DelveForge 的 Repository 侦察组件。"     → File Scout
"你是 DelveForge 的软件资产理解组件。"          → Final Analyzer
```

### 6.2 最完整的 Region Scout 交互：mall

**调用**：seq 3，root 层，载荷 **1,870 B**。

**USER 载荷（逐字，唯一切去 scope 以便阅读）**：

```json
{"analyzedRevision":"3910bf80a9723b165e707f25649d6befb517ee53","regionCatalog":[
 {"reference":"RR-<scope>-1","pathPrefix":"mall-admin",   "directSourceFiles":0,"descendantSourceFiles":147,
  "childRegionsWithSource":1,"languages":["JAVA"],
  "roleHints":["API_ENTRY","APPLICATION_SERVICE","DOMAIN_MODEL","PERSISTENCE","UNKNOWN"]},
 {"reference":"RR-<scope>-2","pathPrefix":"mall-common",  "descendantSourceFiles":14, …},
 {"reference":"RR-<scope>-3","pathPrefix":"mall-demo",    "descendantSourceFiles":8,  …},
 {"reference":"RR-<scope>-4","pathPrefix":"mall-mbg",     "descendantSourceFiles":230,…},
 {"reference":"RR-<scope>-5","pathPrefix":"mall-portal",  "descendantSourceFiles":74, …},
 {"reference":"RR-<scope>-6","pathPrefix":"mall-search",  "descendantSourceFiles":8,  …},
 {"reference":"RR-<scope>-7","pathPrefix":"mall-security","descendantSourceFiles":10, …}]}
```

**模型原始返回**：

```json
{"regionRefs":["RR-<scope>-1","RR-<scope>-5","RR-<scope>-4",
               "RR-<scope>-6","RR-<scope>-2","RR-<scope>-7"]}
```

**Application 解析后的结果**（`RepositoryRegionSelection`，顺序 = 分支优先级）：

```text
1 mall-admin      147 个源码
2 mall-portal      74
3 mall-mbg        230
4 mall-search       8
5 mall-common      14
6 mall-security    10
```

**被有意剪掉的分支**：`RR-<scope>-3` = `mall-demo`（8 个源码文件）。
选中的 6 个与 `region.max-selected-regions=6` 恰好相等——**上限生效了**，
第 7 个候选没有被考虑。

**可以回答任务书要求的那句话**：LLM 收到的正是上面这份 Region Catalog（7 条），
它选出的正是这 6 个 `RR` 引用，Application 随后把它们解析成了这 6 个目录——
`mall-demo` 是唯一被剪掉的，且是模型自己的取舍，不是系统截断。

**后续 6 次 File Scout 的调用顺序与上面完全一致**（seq 4..9），
证明「Region Scout 的返回顺序 = 分支遍历顺序」。

### 6.3 langflow 的导航树与停点

12 次 Region 调用、0 次 File Scout。按前缀重建的实际遍历（缩进 = 深度）：

```text
seq42 d1  root            3 个 Region（.agents, docs, src）        → 选 src
seq43 d2  src             6 个 Region（backend, bundles, frontend,
                                     langflow-stepflow, lfx, …）   → 选 4
seq44 d3  src/backend     2 个（base, langflow）                   → 选 2
seq45 d4  …/base/langflow 1 个                                     → 选 1
seq46 d5  …/langflow/*   25 个                                     → 选 6
seq47 d3  src/lfx/src     1 个                                     → 选 1
seq48 d4  src/lfx/src/lfx 1 个                                     → 选 1
seq49 d5  src/lfx/src/lfx/* 30 个                                  → 选 6
seq50 d3  src/bundles/*  24 个                                     → 选 6
seq51 d3  src/frontend    2 个                                     → 选 2
seq52 d4  src/frontend/src/* 19 个                                 → 选 6
seq53 d5  src/frontend/src/components/* 6 个                       → 选 4
───────────── 第 13 次 Region Scout 被拒绝 ─────────────
```

- **最大深度 5 轮**（`region.max-rounds-per-branch = 8` 未触及）
- **每层宽度 4–6**（`region.max-selected-regions = 6` 多次触及上限）
- **载荷 347 – 6,656 B**，`region.max-catalog-bytes = 65,536` **远未被触及**
- 12 次 = `region.max-scout-calls = 12`，**绑定约束是调用次数上限，不是字节上限**

### 6.4 最有说明力的一次 File Scout：awesome-llm-apps 分支 0

seq 35，载荷 **65,514 B** —— 距 `scout.max-catalog-bytes = 65,536` **只差 22 字节**。

```text
catalog 299 个描述符 → 65,514 B <= 65,536 B → 判定为终态组，停止下钻
```

这正是 ADR-0005 反复强调的那条：**停止条件是实际序列化字节，不是深度也不是文件数**。
同一个仓库里另一个分支（seq 38）只有 64 个描述符 / 12,125 B；
若这一支再多 23 字节，它会继续下钻一层，而它的兄弟不会——**同一个仓库里不同分支的深度由字节决定**。

模型返回的 `focusAreas` 示例（label 是中文，此处保留原文以证明边界）：

```json
{"focusAreas":[
 {"label":"…多智能体…旅行规划系统",
  "fileRefs":["RF-32","RF-46","RF-34","RF-49","RF-40","RF-30"]},
 {"label":"…新闻与播客…平台",
  "fileRefs":["RF-134","RF-164","RF-171","RF-172","RF-163","RF-228"]},
 {"label":"…垂直…团队",
  "fileRefs":["RF-15","RF-13","RF-17","RF-10","RF-19","RF-22"]}, …]}
```

`RF-32` → `advanced_ai_agents/multi_agent_apps/agent_teams/ai_travel_planner_agent_team/backend/agents/team.py`
（引用换回真实描述符由 Application 完成，模型从不给出路径）。

### 6.5 调用内引用重绑：同一个 `RF-1` 在六个分支里是六个文件

mall 六次分支本地 File Scout，**每次的目录里都有 `RF-1`**：

```text
seq 4  （mall-admin）   RF-1 → mall-admin/src/main/java/com/macro/mall/bo/AdminUserDetails.java
seq 5  （mall-portal）  RF-1 → mall-portal/src/main/java/com/macro/mall/portal/component/CancelOrderReceiver.java
seq 6  （mall-mbg）     RF-1 → mall-mbg/src/main/java/com/macro/mall/CommentGenerator.java
seq 7  （mall-search）  RF-1 → mall-search/src/main/java/com/macro/mall/search/controller/EsProductController.java
seq 8  （mall-common）  RF-1 → mall-common/src/main/java/com/macro/mall/common/api/CommonPage.java
seq 9  （mall-security）RF-1 → mall-security/src/main/java/com/macro/mall/security/annotation/CacheException.java
```

**同一个字符串 `RF-1`，六次解析出六个不同的文件，每次都成功。**
隔离来自「每次调用绑定自己那份目录」（`catalog → gateway → parse → resolve` 的同步闭包），
**不是**来自编号互不相同——与 `RR-*` 必须嵌入调用作用域形成对照（Region 会逐层重建目录）。

---

## 7. 保序轮转合并

### 7.1 mall 的具体例子（6 分支）

```text
branch[0] mall-admin   catalog 147 → File Scout 产出 117
branch[1] mall-portal  catalog  74 → 产出 74
branch[2] mall-mbg     catalog 230 → 产出 43
branch[3] mall-search  catalog   8 → 产出  8
branch[4] mall-common  catalog  14 → 产出 14
branch[5] mall-security catalog 10 → 产出 10

合并（前两轮，逐字路径）：
第 1 轮  mall-admin/…/service/impl/PmsProductServiceImpl.java
         mall-portal/…/portal/controller/OmsPortalOrderController.java
         mall-mbg/…/Generator.java
         mall-search/…/search/controller/EsProductController.java
         mall-common/…/common/api/CommonResult.java
         mall-security/…/security/util/JwtTokenUtil.java
第 2 轮  mall-admin/…/service/impl/PmsProductCategoryServiceImpl.java
         mall-portal/…/portal/service/OmsPortalOrderService.java
         mall-mbg/…/CommentGenerator.java
         mall-search/…/search/service/impl/EsProductServiceImpl.java
         mall-common/…/common/api/CommonPage.java
         mall-security/…/security/component/JwtAuthenticationTokenFilter.java
```

ReadPlanner 恰好选中前 18 个 = **正好 3 轮 × 6 分支**，一个不多一个不少。
这正是轮转要的效果：`mall-mbg`（230 个候选）没有因为自己大就把预算吃光，
`mall-search`（8 个候选）的前三个文件同样进入了材料。

**顺序的三种含义在本例中可分辨**：

| 层次 | 本例中的体现 | 来源 |
|---|---|---|
| 分支之间 | admin → portal → mbg → search → common → security | **Region Scout 的返回顺序**（模型取舍） |
| 分支之内 | 各自 `focusAreas` 里 `fileRefs` 的顺序 | 该分支 File Scout 的优先级 |
| 直属组与子分支之间 | mall 各分支 `directSourceFiles=0`，本轮**没有出现直属组** | 结构约定（直属在前） |

**合并不是一份全局 AI 排序**：它是「6 份局部优先级」被 Application 按轮次交织出来的结果。

### 7.2 对三个成功仓库的机械校验

用「分支产出 → 保序轮转」重建的合并顺序与运行期实际送进 Final Analyzer 的定向源码顺序比较：

| 仓库 | 合并候选 | 被采纳的定向源码 | 观察顺序是否 = 合并顺序的子序列 |
|---|---|---|---|
| mall | 266 | 18 | ✅ 是 |
| memos | 391 | 18 | ✅ 是 |
| awesome-llm-apps | 329 | 18 | ✅ 是 |

即：**规划器的采纳顺序确实沿着合并顺序推进**，没有重排（ADR-0005 要求的那一条成立）。

> 注：hm-dianping 走 flat，定向源码的队列是**多个查看区域**（不是一条流），
> 因此它的采纳顺序是「区域轮转」的结果，与「单分支合并顺序」不可比——这是两条路径
> 输入形状不同带来的必然差异，不是异常。

---

## 8. FOUNDATION 与定向源码

### 8.1 两条通道的实际贡献

| 仓库 | Foundation 选中/实读 | Foundation 内容字节 | 定向源码选中/实读 | 定向内容字节 | 合计 |
|---|---|---|---|---|---|
| hm-dianping | 12 / 12 | 33,063 | 18 / 18 | 98,794 | 30 文件 / 131,857 B |
| mall | 12 / **11** | 33,797 | 18 / 18 | 122,979 | 29 文件 / 156,776 B |
| memos | 12 / 12 | 39,595 | 18 / 18 | 161,531 | 30 文件 / 201,126 B |
| awesome-llm-apps | 12 / **11** | 21,108 | 18 / 18 | 155,683 | 29 文件 / 176,791 B |

Foundation 通道按 materialKind 轮转，实际选中的种类（hm-dianping）：

```text
SOURCE_CODE 2（配置类）· BUILD_METADATA 1 · CONFIGURATION 2 · DOCUMENTATION 2
SCRIPT_AUTOMATION 2 · DEPLOYMENT 1 · OTHER 2
```

**两条通道都贡献了有用材料**：hm-dianping 的 30 条 Evidence 里 18 条指向定向源码、
12 条指向 Foundation（`pom.xml`、`docs/*.md`、`jmeter/*.py`、`rocketmq/docker-compose.yml`、`.gitignore`）。
定向源码提供「实现了什么」，Foundation 提供「这是什么工程」——模型在结论里同时用了两者。

### 8.2 规划期与执行期的尺寸判定差异（真实发生）

```text
mall    planned = 12 + 18 = 30，materialCount = 29，tooLargeCount = 1
awesome planned = 12 + 18 = 30，materialCount = 29，tooLargeCount = 1
```

规划依据的是 `git ls-tree -l` 的 blob 字节数；执行期按**真实内容**的 UTF-8 字节数复核。
两次各有一个文件在复核时被判超限（`max-file-bytes`）而被剔除。

**这是设计内的行为**（`RepositoryReadExecutor` 的类说明写明了「blob metadata 不一定等于实际内容」），
但本轮给出了真实证据：**1/30 的入选文件会因 metadata 与内容不一致而被撤下**。
影响是「少读一个文件」，不是失败。

### 8.3 摘取率

```text
mall               266 个合并候选 → 采纳 18 → 6.8%
memos              391                 18 → 4.6%
awesome-llm-apps   329                 18 → 5.5%
```

分层把「交给模型的候选」缩小了，但**分成两段来看**：
Scout 阶段只把 4,572（langflow）/ 854（awesome）压到「每支装得下」，
真正的强取舍发生在 ReadPlanner 的 `maxFiles=18` 上——**材料预算才是决定「模型最终看到什么」的那一道**。

---

## 9. 凭据边界验证

### 9.1 真实仓库：只记数量

> 按任务书要求，真实仓库**只记录计数**，不复制任何凭据取值或源码片段。

| 仓库 | 路径排除（执行点 ①） | 内容替换处数（执行点 ②，`sanitizedSpanCount`） |
|---|---|---|
| hm-dianping | 0 | 6 |
| mall | 0 | 2 |
| memos | 0 | **25** |
| awesome-llm-apps | **35** | **30** |
| langflow | 3（未走到读取） | — |

**awesome-llm-apps 的 35 个路径排除值得单独说**：逐条核对后，**全部是 `.env.example`**。
langflow 的 3 个同样全部是 `.env.example`。见 §11.3（finding）。

memos 的 25 处、awesome 的 30 处内容替换发生在被读取的文件内部——
这是执行点 ② 在真实仓库上确实起了作用的直接证据（规则命中并被替换，但取值未离开本机）。

### 9.2 合成夹具：金丝雀验证（与真实仓库证据严格分开）

在 `target/` 下建一个**只含合成值**的本地 Git Repository 并完整跑一次分析：

```text
仓库内容（全部为合成值，无任何真实凭据）
  application.yml            password: CANARY-CONFIG-0001          → 应「读进来但被替换」
  src/main/java/…/App.java   apiKey = "CANARY-SOURCE-0001"         → 应「读进来但被替换」
  zz/.env.local              DB_PASSWORD=CANARY-ENV-0001           → 应「根本不读」
  certs/server.key           PEM 块 CANARY-PEM-0001                → 应「根本不读」
  docs/notes.md              普通文档                               → 对照组
```

**运行结果**（`scoutPath=FLAT`，`secretExcludedCount=2`，`sanitizedSpanCount=2`，`materialCount=3`）：

```text
进入材料的路径        application.yml · docs/notes.md · src/main/java/com/acme/App.java
未进入材料的路径      zz/.env.local · certs/server.key        ← 连读都没有读（materialCount=3 可证）

Final Analyzer 请求里：
  CANARY-CONFIG-0001   不存在
  CANARY-SOURCE-0001   不存在
  CANARY-ENV-0001      不存在
  CANARY-PEM-0001      不存在
  [redacted-credential] 出现 2 次
```

**模型实际看到的两个片段**（结构保留）：

```text
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/app
    username: app
    password: [redacted-credential]

public class App {
    private String apiKey = [redacted-credential];
    public static void main(String[] args) { }
}
```

三条结论同时成立：

```text
高危路径             → 从未被读取
支持形态的行内凭据   → 读进来，但被替换
原始金丝雀           → 未出现在 Final Analyzer 的请求里
结构（键名、缩进、周边内容）→ 保留，材料仍然有用
```

---

## 10. Final Analyzer 与 Evidence 校验

### 10.1 Final Analyzer 请求规模（不记录源码）

| 仓库 | 模型可见文件数 | Foundation / targeted | 请求体字节 | 净化替换处数 |
|---|---|---|---|---|
| hm-dianping | 30 | 12 / 18 | 138,276 B | 6 |
| mall | 29 | 11 / 18 | 163,842 B | 2 |
| memos | 30 | 12 / 18 | 210,517 B | 25 |
| awesome-llm-apps | 29 | 11 / 18 | 187,763 B | 30 |
| canary | 3 | 2 / 1 | 520 B | 2 |

各仓库被采纳的定向源码路径清单见 `docs/validation/artifacts/*-scout-trace.json` 的
`final` 调用条目（`material[].path`，只含路径与每份字节数）。
**Final Analyzer 的完整请求体不写入产物**——它含仓库源码，按任务书要求只记形状与规模。

### 10.2 RepositoryProfile 摘要

| 仓库 | techStack | modules | capabilities | reusableAssets | limitations | risks | evidence |
|---|---|---|---|---|---|---|---|
| hm-dianping | 14 | 12 | 19 | 12 | 11 | 9 | 30 |
| mall | 18 | 8 | 9 | 12 | 6 | 6 | 31 |
| memos | 23 | 15 | 21 | 13 | 9 | 10 | 31 |
| awesome-llm-apps | 22 | 6 | 10 | 10 | 5 | 6 | 16 |

### 10.3 Evidence 校验：全部通过

对四次成功分析逐条核对 `Evidence.sourceRef ∈ 本次模型可见文件路径`：

| 仓库 | evidence 条数 | 是否全部落在模型可见集合内 | 指向定向源码 | 指向 Foundation |
|---|---|---|---|---|
| hm-dianping | 30 | ✅ **是** | 18 | 12 |
| mall | 31 | ✅ **是** | 18 | 13 |
| memos | 31 | ✅ **是** | 18 | 13 |
| awesome-llm-apps | 16 | ✅ **是** | 12 | 4 |

**没有任何一条依据指向**：不存在的文件、Scout 选中但未被读的文件、被凭据政策排除的文件、
因预算被舍弃的文件。

一个值得记录的观察：hm-dianping 的 30 条 evidence **恰好覆盖全部 30 个模型可见文件**；
awesome-llm-apps 的 16 条只覆盖 29 个可见文件中的一部分——
两者的区别是模型自己的取舍，不是系统的约束。

---

## 11. 与上一轮失败的对照

上一轮四个仓库全部在 `requireCatalogWithinLimit` 处 409，一个 Profile 都没有产出。

| 仓库 | 上一轮结果 | 上一轮 flat catalog | 本轮路由 | 本轮 Scout 形状 | 本轮最终结果 |
|---|---|---|---|---|---|
| mall | ❌ 409 | 99,181 B | HIERARCHICAL | 1 Region + 6 File | ✅ **201，29 文件，31 evidence** |
| memos | ❌ 409 | 141,079 B | HIERARCHICAL | 3 Region + 12 File | ✅ **201（第 2 次尝试），30 文件，31 evidence** |
| awesome-llm-apps | ❌ 409 | 179,351 B | HIERARCHICAL | 1 Region + 6 File | ✅ **201，29 文件，16 evidence** |
| langflow | ❌ 409 | 889,253 B | HIERARCHICAL | 12 Region（用尽预算） | ❌ **409（换了一个原因）** |

```text
上一轮：4/5 在「flat catalog 超限」处失败 —— 一个 Profile 都没有
本轮： 3/5 成功产出 Profile，且这 3 个恰好就是上一轮失败的其中 3 个；
       langflow 仍然不可分析，但失败点从「flat 目录超限」移到了「Region 调用数用尽」
```

**V3 解决了什么、没解决什么**：对 1.5×–2.7× 的超限幅度，分层 Scout 一轮就能把每个分支压进预算；
对 13.6× 的 langflow，分层方向正确但**当前守卫值不允许它走完**。

---

## 12. 发现（Findings）

### 12.1 `IMPORTANT` — langflow 超出 Region Scout 调用预算，仍不可分析

**现象**：`4c291b76` 的 langflow 在 12 次 Region Scout 之后被
`region.max-scout-calls = 12` 拒绝，`RepositoryNotAnalyzableException` → 409。
0 次 File Scout，0 个终态组，无 Profile。耗时约 150 s。

**证据**：trace 产物中恰好 12 条 `kind=region`、0 条 `kind=file`；
导航树深度 5（`max-rounds-per-branch = 8` **未触及**），
每层宽度 4–6（`max-selected-regions = 6` 多次触及），
载荷 347–6,656 B（`region.max-catalog-bytes = 65,536` **远未触及**）。

**含义**：绑定约束是**调用次数**，不是字节、不是深度。
ADR-0005 §导航守卫 已经写明这些数值是「暂定守卫，必须在实现时用真实数据校准」，
本轮的 12 次即为那条校准请求的真实数据。
ADR-0005 的 Revisit Condition 1（「导航守卫在真实仓库上被证明过紧或过松」）**已被触发**。

**本轮不做任何调整**：提高上限属于「静默放宽」，任务书明确禁止。

### 12.2 `IMPORTANT` — 单次模型不合规即中止整次分析，且无重试

**现象**：memos 第一次分析在**第 4 次**分支本地 File Scout 处 502。

**根因（离线核对代理记录得出）**：该次模型返回了 **7 个 `focusAreas`**，
而 `AiRepositoryScoutProposal.MAX_FOCUS_AREAS = 6`。所有 `fileRefs` 都合法、
无重复、格式正确——**唯一的违规是数量多了 1**。
`RepositoryScoutProposalParser` 按契约拒绝 → `AiGatewayException` → 502。

**第二次尝试**：同一资产、同一 revision，1 秒不差地走完全程，**201 成功**（3 Region + 12 File，30 文件）。

**含义**：失败是**概率性**的，来源是模型没有严格遵守「3–6 个区域」的约定；
系统的处理是正确的（失败关闭、不产出半成品、不静默丢弃那一个区域），
但**代价是整次分析作废**，而重来一次通常就能成功。
`langflow` 与 `memos` 的两次失败都属这一类「一次不合规 = 一次全废」的模式。

**这不是缺陷，是一个可评估的取舍**：ADR-0004 明确写了「不存在『丢掉不合法的区域、保留其余部分』这种降级」，
理由是部分成功会让调用方误判。本轮只是给出了这种取舍在真实仓库上的**发生率**（5 个仓库 2 次）。

### 12.3 `OBSERVATION` — `.env.example` 被路径政策整份排除

**现象**：awesome-llm-apps 排除 35 个路径、langflow 排除 3 个，**逐条核对后全部是 `.env.example`**。

**根因**：`DeterministicRepositorySecretPolicy.excludes` 的 `.env` 规则包含
`name.startsWith(".env.")`，因此 `.env.example`（以及 `.env.sample` / `.env.template` 一类）
被当作凭据载体整份排除。

**含义**：`.env.example` 是**模板文件**，按惯例不含真实取值，反而记录了该应用需要哪些环境变量——
正是理解工程形态的有用材料。当前它被静默排除。这不构成泄漏（方向是过度保护），
但是**真实的分析质量损失**，且属于「规则比声明的更宽」：
`docs/validation/m2-repository-secret-boundary-reconnaissance.md` 里 `.env.local` 一类是设计意图，
`.env.example` 是同一规则的溢出效应。

**量级**：单个仓库最多 35 个文件（awesome-llm-apps 的 Foundation 候选 1,062 个中的 3.3%）。

### 12.4 `OBSERVATION` — 规划期与执行期的尺寸判定有约 1/30 的不一致

见 §8.2：mall 与 awesome-llm-apps 各有一个入选文件在按真实内容复核时被剔除。
这是设计内的行为（执行期复核本来就为这个而存在），但本轮给出了真实发生率。

### 12.5 `OBSERVATION` — 分层"成功"的仓库里，真正的取舍发生在 ReadPlanner

见 §8.3：合并候选 266–391，最终采纳 18（4.6%–6.8%）。
分层解决了「交出去的目录装不下」，但**模型最终能看到什么，由材料预算决定**。
这不影响本轮结论，只是在读「分层 Scout 把问题解决了」时需要补一句：
它解决的是**可达性**，不是**覆盖面**。

---

## 13. 最终结果表

| Repository | Revision | Map files | FOUNDATION | SCOUT_SOURCE | Flat catalog bytes | Route | Region calls | File Scout calls | Total Scout calls | Terminal groups | Foundation read | Targeted read | Final Analyzer files | Result |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| hm-dianping | `8a5fa2b6` | 139 | 43 | 84 | 14,837 | flat | 0 | 1 | 1 | — | 12 | 18 | 30 | **PASS** |
| mall | `3910bf80` | 717 | 221 | 491 | 99,181 | hierarchical | 1 | 6 | 7 | 6 | 11 | 18 | 29 | **PASS** |
| memos | `aea105e0` | 1,317 | 153 | 880 | 141,079 | hierarchical | 3 | 12 | 15 | 12 | 12 | 18 | 30 | **PASS**（第 2 次尝试） |
| awesome-llm-apps | `b7b5dd3b` | 1,975 | 1,062 | 854 | 179,351 | hierarchical | 1 | 6 | 7 | 6 | 11 | 18 | 29 | **PASS** |
| langflow | `4c291b76` | 10,375 | 3,194 | 4,572 | 889,253 | hierarchical | **12（用尽）** | 0 | 12 | 0 | — | — | — | **409**（Region 预算用尽） |
| canary（合成） | `70a36c4c` | 5 | 4 | 1 | 228 | flat | 0 | 1 | 1 | — | 2 | 1 | 3 | **PASS** |

### 13.1 逐条回答任务书的问题

**1. V3 是否解决了原来的 >64 KiB 问题？**
部分解决。上一轮 4/5 在 flat catalog 处失败；本轮其中 **3 个成功**（mall / memos / awesome-llm-apps），
覆盖 1.51×–2.74× 的超限幅度。**langflow（13.6×）仍然不可分析**，
失败点从「flat 目录超限」移到了「Region Scout 调用数用尽」——方向正确，但当前守卫值不够。→ §12.1

**2. hm-dianping 的 flat 路径是否回归？**
没有。零 Region 调用、恰好一次 File Scout、30 个文件、30 条 evidence 全部可追溯；
与上一轮同一 revision 的结果同量级（evidence 29 → 30）。

**3. 是否有分支出现病态的深度/宽度？**
深度方面没有：成功的三个仓库最大深度 1–3 轮，远低于 8 的上限。
宽度方面**langflow 触碰了上限**（`max-selected-regions=6` 多次被用满），
并最终因宽度 × 深度的组合耗尽 12 次调用预算。→ §6.3

**4. 保序轮转是否按设计工作？**
是。mall 的 18 个采纳文件恰好是「3 轮 × 6 分支」的完整轮转；
三个成功仓库的采纳顺序都是合并顺序的子序列（机械校验通过）。
`mall-mbg`（230 候选）没有吃光预算，`mall-search`（8 候选）的前几个文件同样进入材料。

**5. Foundation 与定向源码是否都贡献了有用材料？**
是。证据中两条通道都出现（hm-dianping 18:12、mall 18:13、memos 18:13、awesome 12:4），
Foundation 提供工程形态，定向源码提供业务实现。

**6. 凭据边界是否实质影响了有用分析？**
两个方面：
- **实质影响了**：awesome-llm-apps 有 35 个文件因路径政策未进材料，但它们**全部是 `.env.example`**——
  排除它们没有安全收益，却有分析质量损失。→ §12.3
- **没有影响的是**：内容替换发生在被读取的文件内部（memos 25 处、awesome 30 处），
  结构保留、材料仍可用；合成夹具证明原始值从未进入 Final Analyzer 请求。→ §9

**7. Evidence 是否保持可追溯？**
是。四次成功分析共 108 条 evidence，**逐条**落在本次模型可见的文件集合内，
没有一条指向不存在、未读取、被排除或因预算舍弃的文件。

**8. 是否有其它具体遗留问题？**
- 单次模型不合规（7 个 focusAreas）作废整次分析，无重试 → §12.2（IMPORTANT）
- langflow 的 Region 预算 → §12.1（IMPORTANT）
- `.env.example` 过度排除 → §12.3（OBSERVATION）
- blob metadata 与真实内容约 1/30 不一致 → §12.4（OBSERVATION）
- 分层解决可达性、材料预算决定覆盖面 → §12.5（OBSERVATION）

**没有 BLOCKER。** 三个已合入的机制（分层 Scout、分支本地 File Scout、凭据边界）在真实仓库上
均按设计工作；两个 IMPORTANT 都指向**尚未校准的守卫值**与**模型合规的风险敞口**，
而不是实现错误。

---

## 14. 复现步骤

```text
1. 构建：./mvnw -q -DskipTests package

2. 启动记录代理（只读观测，不记录请求头）
   python recording_proxy.py 8799 calls.jsonl

3. 启动应用，把 base-url 指向代理、数据库指向临时文件
   java -jar delveforge-app.jar \
        --delveforge.persistence.database-file=<tmp>/smoke.db \
        --delveforge.ai.deepseek.base-url=http://127.0.0.1:8799

4. 每个仓库：
   POST /api/software-assets            {"location":…,"readPermissionAllowed":true,
                                         "licenseInfo":null,"usageAuthorization":"UNCLEAR"}
   POST /api/software-assets/{id}/analysis
   GET  /api/repository-profiles/{id}   回读快照

5. Map 统计（不调用 Provider）：
   java -cp "<modules>/target/classes;<app 依赖>" Recon.java <outDir> <name>=<absPath> …

6. 轨迹整理：
   python analyze_trace.py  calls.jsonl <startSeq> <endSeq> <out.json> <label>
   python analyze_repo.py   <trace.json> map-index-<label>.tsv resp-<label>.json
```

`target/smoke-v3/` 下的工具与原始记录**未提交**；提交的是
`docs/validation/artifacts/` 下六个已脱敏的 trace 产物。

---

## 15. 未改动确认

```text
生产代码 / 测试 / application.yml / Prompt / 预算 / 分类规则   0 处改动
5 个测试仓库 HEAD 与工作树                                    未变
真实凭据取值 / 凭据路径片段 / 宿主绝对路径在本文档与产物中        均无
DEEPSEEK_API_KEY                                              未写入任何文件（代理不记录请求头）
Final Analyzer 的完整请求体（含仓库源码）                       未提交
```

提交的 trace 产物只含：Scout 的 system prompt 与**元数据载荷**、模型返回、
以及 Final Analyzer 的**路径清单与每份字节数**。
