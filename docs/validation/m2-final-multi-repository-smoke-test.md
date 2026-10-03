# M2 Final 多仓库真实链路验证记录

**Status:** Validation Record / **Not Source of Truth**
**Last Updated:** 2026-10-02
**Validated Revision:** `main` @ Task 9（`m2-final-multi-repository-smoke` 分支起点）
**本轮性质:** 5 仓库单仓泛化（Phase A）+ 多仓 Product Direction Discovery（Phase B）

> 本文档记录一次真实环境端到端验证**实际观察到了什么**，不新增规则、不替代任何权威文档。
> 领域语义以 `DOMAIN_MODEL.md` 为准，架构以 `ARCHITECTURE.md` / `AGENTS.md` 为准，
> 里程碑以 `ROADMAP.md` 为准，长期方向以 ADR-0004 为准。
>
> **本轮没有修改任何生产代码、Prompt、预算、分类规则、测试或配置。** 唯一的代码是
> build 目录下的两个**只读观测工具**（HTTP 驱动脚本与独立重放探针），见 §17。

---

## 1. Validation Scope

本轮要回答：

```text
1. Repository Understanding V2 能否泛化到不同语言、规模和仓库形态？
2. Product Direction Discovery 在同时输入多个 RepositoryProfile 时，
   是否真正利用多个软件资产，而不是退化成只使用一个？
3. 是否出现足以重新打开 Repository Understanding V2 的真实证据？
```

**实际结果：问题 1 得到明确否定答案（4/5 仓库根本无法分析），问题 2 因此无法执行。**

第 2 节起如实记录。

---

## 2. Environment

```text
Backend    : java -jar delveforge-app-0.1.0-SNAPSHOT.jar（真实启动，端口 8099）
数据库     : 本次新建的独立临时 SQLite（target/smoke-m2-final/smoke.db），
             不依赖任何既有数据；Flyway 全部 migration 在本次运行中重建
Repository : 本地 5 个 Git 仓库，见 §3
Provider   : DeepSeek（真实调用）；凭据由环境变量提供，本文档不含任何凭据
Workspace  : 真实 Git CLI（GitWorkspaceAdapter 只读）
观测中继   : 本地 127.0.0.1 观测转发（原样转发 + 落盘；不 mock、不改写输出、
             不记录 Authorization 头），用于取得生产日志与 HTTP 响应都不暴露的 Scout 明细
```

真实链路（与 Round 2 相同）：

```text
real HTTP API → real AnalyzeRepositoryUseCase → real RepositoryUnderstanding
  ├── real RepositoryMapBuilder   ← real Git（读提交树）
  ├── real RepositoryScoutExtraction ← 真实 DeepSeek
  ├── real RepositoryReadPlanner
  └── real RepositoryReadExecutor ← real Git 读取
→ real RepositoryAnalysisExtraction ← 真实 DeepSeek
→ real RepositoryProfileRepository → real SQLite
```

本次运行期间 5 个被分析仓库**均未被改动**（见 §5 与 §17）。

---

## 3. Repository Baselines

测试仓库根目录：`E:\develop\local_repository`。逐个记录运行前的基线：

| 逻辑名 | 本地目录 | 语言 / 形态 | HEAD（运行前后一致） | 已跟踪文件 | 总字节 | 工作树 |
|---|---|---|---|---|---|---|
| hm-dianping | `java-comment-main` | Java，已知回归基线 | `8a5fa2b607ed…` | 139 | 704 KB | clean |
| mall | `mall-master` | Java，大型多模块电商 | `3910bf80a972…` | 717 | 16.7 MB | clean |
| langflow | `langflow-main` | Python，大型 AI / workflow 产品 | `4c291b766c85…` | 10 375 | 221.9 MB | clean |
| memos | `memos-main` | Go，真实 note-taking 产品 | `aea105e081c9…` | 1 317 | 12.2 MB | clean |
| awesome-llm-apps | `awesome-llm-apps-main` | Python / mixed，示例集合仓库 | `b7b5dd3bfc11…` | 1 975 | 90.0 MB | clean |

```text
五个仓库均为单次 "smoke baseline" 提交、工作树 clean。
目录名与实际内容不一致的两处已核实：
  java-comment-main  内容确为 hm-dianping（存在 com.hmdp / HmDianPingApplication）
  xxx-main / xxx-master 为远程拉取后的目录名，提交为本地 baseline，不要求等于 upstream SHA
```

---

## 4. Phase A — Repository Understanding Results

### 4.1 总览：只有 1/5 完成了分析

| 仓库 | HTTP | 结果 |
|---|---|---|
| hm-dianping | 201 | **成功**，32.7s，产出 RepositoryProfile |
| mall | 409 | `SCOUT_CATALOG_TOO_LARGE`，0.5s，**未调用 AI** |
| langflow | 409 | 同上，0.7s |
| memos | 409 | 同上，0.4s |
| awesome-llm-apps | 409 | 同上，0.5s |

**四个失败发生在调用 Scout 之前**，位于 `RepositoryUnderstanding.requireCatalogWithinLimit`
（`RepositoryUnderstanding.java:184`），异常 `RepositoryNotAnalyzableException`，
接口层映射为 409。这是**设计上的 fail-closed**（不截断、不采样、不降级），不是崩溃。

### 4.2 A1 — Pipeline Correctness

| 仓库 | SoftwareAsset 注册 | Analysis | analyzedRevision == 起始 HEAD | GET 回读一致 | HEAD 未变 | 工作树未改 | PIPELINE |
|---|---|---|---|---|---|---|---|
| hm-dianping | ✅ 201 | ✅ 201 | ✅ | ✅ | ✅ | ✅ | **PASS** |
| mall | ✅ 201 | ❌ 409 | — | — | ✅ | ✅ | **FAIL** |
| langflow | ✅ 201 | ❌ 409 | — | — | ✅ | ✅ | **FAIL** |
| memos | ✅ 201 | ❌ 409 | — | — | ✅ | ✅ | **FAIL** |
| awesome-llm-apps | ✅ 201 | ❌ 409 | — | — | ✅ | ✅ | **FAIL** |

失败原因**不是**模型质量，而是分析前置条件在真实规模上不成立（§4.3）。按任务书要求，
记为 implementation finding（F1），而不是用模型质量问题掩盖。

### 4.3 A2 — Repository Map / Scout Scalability（关键数据）

`catalog_bytes` 与 lane 计数**不在生产日志、也不在任何 HTTP 响应里**（F3）。
下表由**只读独立重放**（用生产 `RepositoryMapBuilder` + `RepositoryScoutInputs` +
`RepositoryScoutExtraction.catalogPayloadBytes`，见 §17）得到：

| 仓库 | 总文件 | FOUNDATION | SCOUT_SOURCE | NONE | **catalog 字节** | 上限 | 超限倍数 |
|---|---|---|---|---|---|---|---|
| hm-dianping | 139 | 43 | 84 | 12 | 14 837 | 65 536 | 0.23× |
| **mall** | 717 | 221 | 491 | 5 | **99 181** | 65 536 | **1.51×** |
| **memos** | 1 317 | 153 | 880 | 284 | **141 079** | 65 536 | **2.15×** |
| **awesome-llm-apps** | 1 975 | 1 062 | 854 | 59 | **179 351** | 65 536 | **2.74×** |
| **langflow** | 10 375 | 3 194 | 4 572 | 2 609 | **889 253** | 65 536 | **13.6×** |

Scout 调用情况：

```text
hm-dianping   Scout 成功；6 个聚焦区域；81 条引用（74 唯一 / 7 重复）；0 非法；0 越界
              catalog 14 837 字节 = 上限的 22.6%（与 Round 2 完全一致，确定性可复现）
其余 4 个      未发生 Scout 调用（在 requireCatalogWithinLimit 处失败）
```

**catalog 大小的增长规律**（由上面 5 个数据点得出，用于判断这不是个例）：

```text
catalog 字节 / SCOUT_SOURCE 候选 ≈ 176（hm-dianping）／202（mall）／160（memos）
                                  /210（awesome）／194（langflow）
→ 约 160–210 字节/条。
→ 65 536 字节上限 ≈ 约 330 个源码候选。
mall（491 个源码候选）已超出，而它是失败集里最小的仓库。
```

**这直接推翻了 Round 2 §12 的推断。** Round 2 依据 hm-dianping 占用 22.6% 推论
「仓库再大 4 倍也不会触发」。该推论假设 catalog 与「总文件数」同比例增长；实际它随
**源码候选数**增长，而 mall 的源码候选已是 hm-dianping 的 5.8 倍。

### 4.4 A3 — Material Selection / Targeted Reading

**对于 4 个失败的仓库：Targeted 通道完全未使用**（Scout 从未运行，分析从未开始）。
它们的 Foundation 规划可用只读重放得到，但那只是一次「假如能跑」的推演，**没有产生任何
RepositoryProfile**，因此不能用于回答「是否读到了核心实现」。

Foundation 规划（重放，均触顶 `maxFiles=12`）与逐条跳过诊断：

| 仓库 | Foundation 选中 | 字节 | 跳过诊断 |
|---|---|---|---|
| hm-dianping | 12 | 33 036 | `SELECTED_BUT_TOO_LARGE` ×1：`src/main/resources/db/hmdp.sql` 151 948 B (DATA_SCHEMA) |
| mall | 12 | 56 810 | ×4：`document/sql/mall.sql` 407 690 B；`document/axure/mall-app.rp` 3 274 767 B；`document/axure/mall-flow.rp` 116 825 B；`document/mind/app.emmx` 32 782 B |
| langflow | 12 | 62 159 | ×1：`Makefile` 53 218 B (BUILD_METADATA) |
| memos | 12 | 39 595 | ×1：`go.sum` 34 698 B (BUILD_METADATA) |
| awesome-llm-apps | 12 | 21 629 | 无 |

**这条数据同时补上了 Round 2 §5.4 记录的观测缺口**：规划期的逐条 `SELECTED_BUT_TOO_LARGE`
诊断在生产日志与 HTTP 响应里都不可观测，只有通过独立重放才能看到。

**观察（对深路径仓库）：** Foundation 的类别内选择由**相对路径字典序**决定
（Round 2 边界 2 的同一机制）。在 langflow 上，它选中的两个 SOURCE_CODE 是
`src/frontend/src/modals/knowledgeBaseUploadModal/.../StepConfiguration.tsx` 这类
深路径前端文件——它们只是因为路径靠前而被选中，不代表「核心实现」。
该现象在 4 个失败仓库上**没有实际后果**（分析根本没有运行），仅作为观察记录。

### 4.5 A4 — RepositoryProfile Semantic Quality（仅 hm-dianping）

```text
repositoryProfileId : d8eb3ac3-39e1-4b37-b63b-799f07520dc0
analyzedRevision    : 8a5fa2b607ede3666ab0df1c96edf851aa4293e5
耗时                : 32.7 s
结构                : techStack 14 / modules 12 / capabilities 13 /
                      reusableAssets 10 / limitations 10 / risks 8 / evidence 29
```

- **purpose**：识别为「基于 Spring Boot 的本地生活点评后端 + 高并发重构（三级缓存 /
  Redis Lua 预扣 / RocketMQ 事务消息 / Redisson 锁）」——**正确的产品身份**，
  不是泛化的「Spring Boot 示例」。
- **modules（12）**：以业务模块为主（多级缓存服务、秒杀订单服务、用户服务、缓存工具、
  实体映射、Lua 脚本），而非 config / build / docs 堆砌。
- **capabilities（13）**：12 条是业务能力（三级缓存读链路、穿透/击穿/雪崩防护、
  缓存失效广播、Redis 验证码登录、BitMap 签到、秒杀下单、事务消息削峰、下单幂等、
  秒杀对账），仅最后 1 条是工程验证能力。
- **reusableAssets（10）**：6 项指向具体实现（`MultiLevelCacheServiceImpl`、
  `CaffeineConfig`、`CacheClientUtils`、三个 `*.lua`），另 2 项是对账脚本、
  1 项是 RocketMQ 编排、1 项是压测数据。没有「Spring Boot 技术栈可复用」这类空话。

**结论：hm-dianping 的 Profile 质量与 Round 2 一致，PASS。**
其余 4 个仓库**无 Profile 可评**（NOT EVALUATED）。

### 4.6 A5 — Evidence Quality（仅 hm-dianping）

```text
evidence 总数      29
sourceRef         全部落在本次实际读取的 30 个材料内（0 条指向未读文件）
sourceType        全部 REPOSITORY
业务实现依据       controller 6 / service-impl 3 / service-iface 1 / lua 3 /
                   entity 3 / util 1  → 17/29 是实现级
配置与入口         2（CaffeineConfig、HmDianPingApplication）
构建元数据         1（pom.xml）
脚本 / 文档 / 数据 / 部署 / 其他  9
```

抽查最容易造假的陈述，均能回对已读文件内容（例如「L1 容量 10000 / voucher 5000、
`expireAfterWrite=8s`」出自 `CaffeineConfig`；「v2 未接入生产调用链」出自
`seckill_compensate_v2.lua` 自身声明）。**未发现指向未读文件的依据，也未发现对已读内容的
实质性歪曲。**

**一条安全向事实（本轮新发现，见 F4）：** `risks` 第 1 条与 `evidence` 第 23、24 条
把被分析仓库脚本中的**明文数据库 / Redis 口令**读进了 Profile。该口令属于被分析仓库的敏感
内容，本文档**不复制其值**。这是模型正确引用了已读文件（`jmeter/check_seckill.py` 中确有
硬编码口令），但「Repository Analysis 会把源仓库里的凭据持久化到 RepositoryProfile」
本身是一个需要单独判断的事实。

---

## 5. Cross-Repository Comparison

| Repository | Language / Shape | Total Blobs | FOUNDATION | SCOUT_SOURCE | NONE | Catalog Bytes | Focus Areas | Unique RF | Foundation Files | Targeted Files | Targeted Bytes | Core Product Impl Coverage | Capabilities Quality | Reusable Assets Quality | Evidence Quality | Pipeline | Semantic Result |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| hm-dianping | Java / 单体 + 中间件 | 139 | 43 | 84 | 12 | 14 837 | 6 | 74 | 12 | 18 | 78 974 | 是（6 controller / 5 service / 3 lua / 3 entity） | 高 | 高（6/10 具体实现） | 高（17/29 实现级） | PASS | **PASS** |
| mall | Java / 大型多模块 | 717 | 221 | 491 | 5 | 99 181 | — 未运行 — | — | 12（重放） | — | — | 无法评估（未产出 Profile） | 无法评估 | 无法评估 | 无法评估 | **FAIL** | **FAIL SIGNAL** |
| langflow | Python / 大型 AI 产品 | 10 375 | 3 194 | 4 572 | 2 609 | 889 253 | — | — | 12（重放） | — | — | 无法评估 | 无法评估 | 无法评估 | 无法评估 | **FAIL** | **FAIL SIGNAL** |
| memos | Go / 真实产品 | 1 317 | 153 | 880 | 284 | 141 079 | — | — | 12（重放） | — | — | 无法评估 | 无法评估 | 无法评估 | 无法评估 | **FAIL** | **FAIL SIGNAL** |
| awesome-llm-apps | Python / 集合仓库 | 1 975 | 1 062 | 854 | 59 | 179 351 | — | — | 12（重放） | — | — | 无法评估 | 无法评估 | 无法评估 | 无法评估 | **FAIL** | **FAIL SIGNAL** |

「Semantic Result」只对产出过 Profile 的仓库评定；其余为 FAIL SIGNAL（未产出 Profile），
不是「差」，而是「不可评估」。不计算人工总分。

---

## 6. Language Generalization

**无法回答**——本轮不足以判断语言结构偏置。

```text
Java    hm-dianping  PASS（1 个）
        mall        未运行（catalog 超限，与语言无关）
Python  langflow    未运行（catalog 超限）
Go      memos       未运行（catalog 超限）
```

任务书 §10 定义的强 revisit 信号是「Java 正常而 Python + Go 退化成 README/config 理解」。
本轮**没有出现该形态**：4 个失败仓库**根本没有产出一份 Profile**，失败原因与语言分类无关
（mall 是 Java，同样失败）。因此不能记为 `LANGUAGE STRUCTURAL BIAS SUSPECTED`；
正确记法是 **NOT EVALUATED**。

---

## 7. Special Repository Shape Observation

**无法回答**——`awesome-llm-apps` 未产出 Profile。

无法判断系统是否将其识别为「collection / examples 仓库」还是误判为统一产品，
因为分析在 Scout 之前就失败了（catalog 179 351 字节，超限 2.74×）。
不记为 `REPOSITORY SHAPE SEMANTIC MISINTERPRETATION`（无证据），记为 **NOT EVALUATED**。

---

## 8. Repository Understanding V2 Revisit Decision

逐条对照任务书 §21 的五个 revisit 类别：

| # | 类别 | 判定 | 依据 |
|---|---|---|---|
| A | Language bias | **不成立 / 无法评估** | 仅 1 个仓库产出 Profile；4 个失败与语言无关 |
| B | **Scout catalog scale** | **TRIGGERED** | 4/5 真实仓库超限（mall 1.51×、memos 2.15×、awesome 2.74×、langflow 13.6×）；上限实际约等于 330 个源码候选 |
| C | Large-file problem | **部分可见，非本轮阻塞主因** | hm-dianping `db/hmdp.sql` 151 948 B、mall `mall.sql` 407 690 B 等因 `maxFileBytes` 被跳过；但本轮真正的阻塞是 B，不是 C |
| D | Scout relevance | **不成立（就已观测到的）** | hm-dianping 的 Scout 全部落在业务实现上；其余 4 个未运行 Scout，无证据 |
| E | Repository shape semantics | **无法评估** | `awesome-llm-apps` 未产出 Profile |

**结论：B 被真实证据触发。** 这是本轮唯一被强触发的类别，且它的后果不是「结果稍差」，
而是「对大多数真实规模仓库，Repository Analysis 根本无法开始」。

---

## 9. Phase B — Multi-Repository Product Direction Discovery

**BLOCKED。**

任务书 §13 规定 Phase B 使用 `mall RepositoryProfile` + `langflow RepositoryProfile`，
且「如果其中一个完全失败，则记录 BLOCKED，不要用其它仓库偷偷替代」。

```text
mall RepositoryProfile        不存在（分析 409）
langflow RepositoryProfile    不存在（分析 409）
→ Phase B 无法执行。未使用任何其它仓库替代。
```

本轮的 Phase B 相关字段（§10–§12）全部为 **NOT EVALUATED**，因为**没有执行过任何
Product Direction Discovery**（包括没有用 hm-dianping 单仓替代跑一次——那不是本轮问题）。

---

## 10. Multi-Asset Utilization

**NOT EVALUATED**（Phase B 未执行，§9）。

---

## 11. Personalization and Evidence Semantics

**NOT EVALUATED**（Phase B 未执行）。

---

## 12. Cross-Direction Diversity

**NOT EVALUATED**（Phase B 未执行）。

---

## 13. M2 Final Grades

```text
Repository Understanding V2 泛化能力        FAIL SIGNAL
    5 个真实仓库中 4 个无法分析；失败为 fail-closed，非崩溃，但后果是
    「多数真实规模仓库无法进入 Product Direction Discovery」。

RepositoryProfile 质量（仅 hm-dianping）     PASS
    正确的产品身份、业务能力为主、8/10 可复用资产为具体实现、19/29 依据为实现级。

Pipeline correctness（已执行的仓库）         PASS
    hm-dianping 全链路正确；5 个测试仓库均未被改动。

Phase B 多仓 Product Direction Discovery     NOT EVALUATED
    被 §9 的阻塞挡住，未执行。

语言泛化                                     NOT EVALUATED

仓库形态语义（awesome-llm-apps）              NOT EVALUATED
```

**本轮不构成对产品假设的任何证明或否定。** 它证明的是一个**能力边界**：
Repository Understanding V2 在真实规模仓库上的可达性。

---

## 14. Findings

按任务书 §27 的格式（Finding / Evidence / Affected layer / Severity / Revisit）：

### F1 — Scout catalog 上限在真实规模仓库上 fail-closed（本轮主因）

```text
Finding      5 个真实仓库中 4 个因 SCOUT_CATALOG_TOO_LARGE 在调用 AI 之前失败，
             拿不到任何 RepositoryProfile。
Evidence     app.log 4 条 RepositoryNotAnalyzableException，栈顶 requireCatalogWithinLimit
             (RepositoryUnderstanding.java:184)；catalog 字节见 §4.3（99 181 / 141 079 /
             179 351 / 889 253，上限 65 536）。
Affected     Application（RepositoryUnderstanding 前置条件）；下游 RepositoryProfile →
             Product Direction Discovery 整条链路。
Severity     高：不是质量下降，而是功能对多数真实仓库不可用。
Revisit      是（§8 类别 B）。
```

### F2 — Round 2 的规模外推不成立

```text
Finding      Round 2 §12 依据「hm-dianping 占用 22.6%」推论「再大 4 倍也不会触发」。
             该推论被真实证据推翻。
Evidence     catalog ≈ 160–210 字节/源码候选 → 上限 ≈330 个源码候选；
             mall（491 个源码候选，仅 717 个文件）即超限 1.51×。
Affected     ADR-0004 的 revisit 判断方法（外推基准应为「源码候选数」而非「总文件数」）。
Severity     中：影响的是判断，不是代码。
Revisit      否（属于对既有判断的更正）。
```

### F3 — catalog 大小在日志与 HTTP 响应中都不可观测

```text
Finding      触发失败时，具体字节数只存在于异常 message 里，而应用按设计只记异常类型、
             接口层只返回通用冲突文案。因此「超了多少」必须靠独立重放才能得到。
Evidence     ApiExceptionHandler 日志只含异常类名；409 响应为通用文案。
             Round 2 §5.4 已记录同类的规划期诊断不可观测问题；本轮再次出现。
Affected     可观测性（Application / App 日志边界）。
Severity     低-中：不妨碍判定「是否触发」，妨碍判定「超了多少、离得很远还是接近」。
Revisit      否（记为后续可选改进）。
```

### F4 — Repository Analysis 会把源仓库中的凭据持久化进 RepositoryProfile

```text
Finding      hm-dianping 的 Profile 将源仓库脚本中的明文数据库 / Redis 口令
             读入 risks 与 evidence 并持久化。
Evidence     risks 第 1 条、evidence 第 23、24 条（指向 jmeter/check_seckill.py 等已读文件）。
             该口令来自被分析仓库自身，本文档不复制其值。
Affected     RepositoryProfile 持久化内容；潜在影响 API 响应与后续 LLM 调用。
Severity     中-高（安全向，需单独判断）。
Revisit      否（不属于 Repository Understanding 机制本身，需单独决策）。
```

### F5 — 深路径仓库的 Foundation 选择受字典序主导（本轮无实际后果）

```text
Finding      Foundation 通道的类别内选择由相对路径字典序决定，在深路径仓库上会选中
             任意深层文件（如 langflow 的 StepConfiguration.tsx）而非核心实现。
Evidence     §4.4 重放列表。
Affected     若这些仓库能运行，Material Selection 质量会受影响。
Severity     低（本轮 4 个失败仓库未运行分析，无实际后果）。
Revisit      否（Round 2 边界 2 的同一机制，本轮仅新增观察）。
```

---

## 15. User Judgment Required

以下不属于事实性评价，必须由用户决定（任务书 §28）：

```text
USER JUDGMENT REQUIRED
  1. 是否接受「Repository Understanding V2 在真实规模仓库上不可用」这一现状进入 M2 收尾；
     还是先重新打开 Repository Understanding V2（对应 §8 类别 B）。
  2. 若重新打开，目标是什么形态：仅放宽 catalog 上限，还是需要能承接更大目录的机制。
     —— 本轮不预设任何实现方向（RAG / embedding / 分层 Scout 等均未被证据触发）。
  3. F4（源仓库凭据被写入 RepositoryProfile）是否需要单独处理。
```

> **后续结果（2026-10-03）：** 这三条都已不再是未决问题——
> 1、2 → 重新打开，方向定为**分层 Scout**（ADR-0005，已由 Task 10A-6 实现并接入生产链路）；
> 3 → 仓库源码凭据边界（ADR-0006，已由 Task 10B-2 实现）。
> 本文档保留为那次 Smoke 的原始记录，不再维护其结论的当前状态。

---

## 16. Stop / Revisit Decision

```text
REVISIT TRIGGERED
```

**触发了什么：** `SCOUT_CATALOG_TOO_LARGE` 使 Repository Analysis 对多数真实规模仓库
无法开始（ADR-0004 Revisit Condition 1 的实质形态）。

**哪些 Repository 复现：** mall / langflow / memos / awesome-llm-apps（4/5）；
hm-dianping 未复现。

**影响了什么输出：** 4 个仓库完全没有 RepositoryProfile；Phase B（多仓 Product
Direction Discovery）因此无法执行；语言泛化与仓库形态语义两个问题被迫 NOT EVALUATED。

**应该重新评估哪一层：** Scout 目录载荷的**规模上限机制**（Application 层
`RepositoryUnderstanding` 的前置条件 + 目录构造）。本轮**不进一步指定**实现方式。

**为什么不选 STOP：** Round 2 的 STOP 建立在「catalog 占用 22.6%、再大 4 倍也不会触发」
之上；本轮用 4 个真实仓库推翻了该前提。

**为什么不选 MIXED：** 这不是「结果尚可但有已知局限」，而是「多数目标仓库无法分析」。

---

## 17. Reproduction Notes

### 运行方式

```text
1. 构建：./mvnw -pl backend/delveforge-app -am -DskipTests package
2. 启动（新建数据库、指向观测中继）：
   java -jar backend/delveforge-app/target/delveforge-app-0.1.0-SNAPSHOT.jar \
     --server.port=8099 \
     --delveforge.persistence.database-file=backend/delveforge-app/target/smoke-m2-final/smoke.db \
     --delveforge.ai.deepseek.base-url=http://127.0.0.1:8899
3. 逐个仓库：POST /api/software-assets → POST /api/software-assets/{id}/analysis
```

### 本轮使用的两个只读观测工具（均在 build 目录，不提交、不改产品代码）

```text
target/smoke-m2-final/relay.py    本地观测中继：原样转发 + 落盘，不记录 Authorization
target/smoke-m2-final/drive.py    真实 HTTP 驱动：register / analyze，落盘原始响应
target/smoke-m2-final/Probe.java  只读独立重放：用生产 RepositoryMapBuilder /
                                  RepositoryScoutInputs / RepositoryScoutExtraction
                                  .catalogPayloadBytes / RepositoryReadPlanner，
                                  在真实 Map 与真实预算上复算 lane 计数、catalog 字节、
                                  Foundation 规划与逐条跳过诊断。不调用模型、不写任何存储。
```

原始产物（build 目录，不提交）：

```text
target/smoke-m2-final/app.log                 生产日志
target/smoke-m2-final/responses/              register / analysis 的原始 HTTP 响应
target/smoke-m2-final/capture/                两次真实 Provider 调用的请求/响应原文
target/smoke-m2-final/probe.txt               只读重放输出
target/smoke-m2-final/hm-profile.txt          hm-dianping Profile 摘录
target/smoke-m2-final/hm-material.txt         hm-dianping 实际材料清单
```

### 未改动确认

```text
5 个测试仓库：运行前后 HEAD 与 git status --porcelain 均一致（见 §3）
DelveForge   ：本轮只新增本文档；无生产代码 / 测试 / 迁移 / 配置改动
```

### 不可复现性

真实模型调用使具体输出不可复现；**可复现**的是结构性质——lane 计数、catalog 字节、
`analyzedRevision == HEAD`、失败发生在 Scout 之前、逐条跳过诊断。其中 lane 计数与 catalog
字节只依赖确定性组件（Map + 序列化），因此对同一 revision 可逐字节复现
（hm-dianping 的 14 837 与 Round 2 完全一致即为证）。

---

## 附：与其它验证文档的关系

```text
Round 1   V1 选材的实际后果（docs/validation/m2-product-direction-discovery-smoke-test-round1.md）
Round 2   V2 在单个中小仓库上的表现与 STOP 判定
          （docs/validation/m2-product-direction-discovery-smoke-test-round2.md）
本轮       V2 在 5 个真实仓库上的可达性——推翻 Round 2 的规模外推，触发 Revisit
```

三份应并列阅读。本轮**不修改**前两份。
