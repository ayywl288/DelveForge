# M2 Retrospective — Product Direction Discovery

**Status:** Retrospective / **Not Source of Truth**
**Covered period:** 2026-09-23 → 2026-10-03（`main` 共 62 个提交）
**依据:** `PRODUCT.md`、`DOMAIN_MODEL.md`、`ARCHITECTURE.md`、`ROADMAP.md`、`AGENTS.md`、
ADR-0004 / 0005 / 0006，以及 `docs/validation/` 下 M2 的全部 smoke / 标定记录
**连续性:** M1 的两份复盘（`m1-user-discovery.md`、`m1-repository-analysis.md`）与本文件是同一套写法；历史文档未被改写

> 本文回答的是：M2 本来要做什么、最终做成了什么、哪些判断被真实数据推翻过、
> 哪些代价本来可以避免。它不复制 Task 时间线，只保留对后续工程与面试讲解有用的部分。

---

## A. M2 的目标与最终结果

### A.1 目标

M2 在 `ROADMAP.md` 里的定义是 **Product Direction Discovery**：基于调用方显式给出的
Product Discovery 输入基线——**Confirmed User Profile 身份 + 确定的 `expectedRevision` +
一个或多个 Repository Profile 身份**——发现具有个人相关性和可实施性的候选 Product Direction。

它对应 `PRODUCT.md` 的 Scenario 3：系统分析「用户需求 / 技术能力 / 求职目标」与
「各可利用软件资产已有能力」之间的潜在连接，产出多个方向，每个方向说明需求来源、匹配点、
可复用资产、与原项目的差异、技术价值、复杂度与风险，最后由**用户**选择。

### A.2 最终的生产链路

```text
Confirmed UserProfile @ revision
        +
RepositoryProfile @ analyzedRevision
        ↓
Product Direction Discovery（Application 在调用模型之前校验基线）
        ↓
3–5 个 Evidence 可追溯的 ProductDirection 候选（初始 CANDIDATE）
        ↓
Select / Reject / Supersede 生命周期（只有用户能推进）
        ↓
持久化的生命周期状态
```

### A.3 已完成（明确结论）

```text
✅ 基线校验在执行之前：Profile 不存在 / revision 过期 / 未 CONFIRMED / Repository Profile 取不到
   —— 都在调用模型之前失败，不付模型调用代价，也不留下半批结果
✅ 每次发现产出 3–5 个彼此有差异的方向，全部初始 CANDIDATE
✅ 每个方向带可追溯依据：三个槽（userNeed / userFit / reusableCapability），
   每条依据的 origin 指向本轮真实的 Profile 与 revision
✅ Select / Reject / Supersede：全局同时只有一个 SELECTED；非法转换返回 409 且不改状态
✅ 整批写入的原子性：任何失败都不留下部分方向
✅ 领域模型不提供「系统自动选中」的入口——选择只能由用户发起
```

**M2 的交付物已在 `ROADMAP.md` 全部勾选**，并在 `docs/validation/m2-product-direction-e2e-smoke.md`
上完成了一次真实的三仓库端到端验证（同一个 Confirmed UserProfile × hm-dianping / mall / memos）。

---

## B. Repository Understanding 的演进

这一段占了 M2 的绝大部分工程量，而且**每一次转向都是被前一步的真实失败逼出来的**，
没有一步是「一开始就设计好的」。

### B.1 M1 确定性代表采样 → 完整 RepositoryMap + flat File Scout

| | |
|---|---|
| **观察到的失败** | M2 第一次真实链路验证（`m2-product-direction-discovery-smoke-test-round1.md` §10）发现：方向最终引用的 16 个 Repository 文件**全部**是配置、脚本、文档与构建元数据；`controller/`、`service/`、`entity/`、`mapper/` 下的业务实现一个都没进入过任何方向的依据 |
| **旧设计为什么不够** | M1 的一次性有界采样按路径确定性排序 + 先到先得的预算：排在前面的工程元数据先把名额吃掉了。清单**看不见**的东西，后面没有任何环节能补救——盲区被伪装成了「取舍」 |
| **新决策（ADR-0004）** | Repository Map 完整列出该 revision 的**全部**描述符（标记「这是什么材料 / 什么语言 / 可能是什么角色」），再用一次 LLM Scout 让模型**指认**该读哪些，最后定向读取被指认的文件 |
| **事后被什么验证** | V2 Round 2 真实 smoke：同一仓库同一 revision 下，业务实现（`service/impl`、`controller`）首次稳定进入依据；M1 的「大文件被跳过」局限被定向读取消化 |

**这一步真正解决的是「可见性」**，不是「读得更多」：Map 不因为后续上下文预算而截断，
预算是「读什么」的问题，不是「看得到什么」的问题。

### B.2 flat File Scout → 分层 Region Scout

| | |
|---|---|
| **观察到的失败** | M2 最终多仓 smoke（`m2-final-multi-repository-smoke-test.md`）：5 个真实仓库中 **4 个在调用 Scout 之前 fail-closed**——把全部源码候选一次性序列化成 flat File Catalog 时超限（mall 1.51×、memos 2.15×、awesome-llm-apps 2.74×、langflow 13.6×，上限 64 KiB）。失败点**唯一地**落在「交给模型的那份 flat catalog」，而不是 Map 的构建（5 个仓库全部成功建 Map） |
| **旧设计为什么不够** | 「一次交给模型的目录」与仓库大小线性相关。截断或采样会把 M1 的问题原样带回来（不可见 → 盲区） |
| **新决策（ADR-0005）** | 当且仅当 flat File Catalog 超限时，先在**目录层**缩小范围：Region Scout 只看目录前缀的规模与语言/角色提示，选出一批分支；每个分支独立判定自己的文件集合能否装进预算，不能就再下钻一层；到达终态的分支各自跑一次**现有的** File Scout，结果按保序轮转合并 |
| **事后被什么验证** | V3 多仓 smoke：mall / memos / awesome-llm-apps **全部 201 成功**（上一轮它们全是 409），round-robin 的采纳顺序可机械复现为「每支每轮各取一个」 |

**这一步解决的是「可到达性」，不是「覆盖面」**——见 D.4。

### B.3 分支本地 File Scout + 保序轮转

| | |
|---|---|
| **观察到的失败** | 分层之后，每个终态分支各自需要一次「读哪些文件」的判断；若把各分支候选直接拼接，第一条大分支会吃满全部材料预算 |
| **新决策** | 每个终态组重编号成一次独立调用（`RF-1…RF-n`），复用**同一个** File Scout 契约与解析器；组内保留模型给的优先级，组间按「第 r 轮取各分支第 r 个」合并 |
| **事后被验证的细节** | 保序轮转的顺序**确实**成为定向源码的考虑顺序（三个分层仓库的采纳顺序都是合并顺序的子序列）；`RF-1` 在六个分支里解析出六个不同文件，隔离来自「每次调用绑定自己那份目录」，而不是编号互不相同 |

### B.4 有界 ReadPlanner → 仓库源码凭据边界

| | |
|---|---|
| **观察到的失败** | 侦察（`m2-repository-secret-boundary-reconnaissance.md`）确认：整条链路里唯一把仓库内容送出去的地方是最终分析器，而**此前没有任何一层排除过凭据文件**——`.env` / 私钥 / keystore 只要被选中就会被原样读进内存并发出 |
| **新决策（ADR-0006）** | **一条政策、两个执行点**：读取之前按**路径**整份排除（二进制凭据只有路径能识别），交给模型之前按**内容**替换凭据取值（配置文件既是最可能的凭据位置，也是最有价值的分析材料，不能整份丢掉）。规则写死在代码里（可配置就等于可以被静默放宽）；失败一律关闭 |
| **事后被什么验证** | 合成金丝雀验证：高危路径**连读都没有读**；行内凭据读进来但被替换；四个金丝雀都没有出现在最终请求里；真实仓库上 memos 有 25 处、awesome-llm-apps 有 30 处内容替换确实发生 |

### B.5 稳定化与标定

| | |
|---|---|
| **观察到的失败** | ① 定向源码预算 18 个文件在 memos 上**取不满**（只取到 16）——总量上限先到，文件数有一部分只是理论值；② 模型偶发不守 Scout 输出契约（memos 的 File Scout 返回了 7 个 `focusAreas`，上限 6），一次不合规就作废整次分析；③ Go 的 `*_test.go` 没有被识别为测试代码，103 个测试文件混进了源码候选 |
| **新决策** | 预算调成 `24 / 229376`（配套调整，不是只改文件数）；Scout 契约违反允许**一次**有界重试，且重试占用**真实**调用额度、不放松任何校验；补上 Go 的测试命名惯例 |
| **事后被验证** | A/B 标定显示 18 跑满三轮（3 × 6 分支），24 多跑完整一轮（4 × 6），mall 的六个模块因此各 +1、hm-dianping 首次读到 `entity/` 领域模型；真实运行里重试已触发并自愈 2 次 |

### B.6 一句话概括这段演进

```text
M1  确定性代表采样                                   → 真实 smoke 暴露业务实现被系统性错过
V2  完整 Map + flat File Scout + 定向读取            → 真实 smoke 暴露大仓库在 flat catalog 处 fail-closed
V3  分层 Region Scout + 分支本地 File Scout + 轮转   → 真实 smoke 暴露凭据会随源码出站
V3+ 凭据边界（两个执行点）                            → 稳定化：预算标定、Scout 有界重试、Go 测试分类
```

---

## C. 最终 Repository Understanding 心智模型

### C.1 链路

```text
固定的 analyzedRevision（只在编排层解析一次 HEAD）
        ↓
完整的、只有元数据的 RepositoryMap（该 revision 上全部已提交文件）

RepositoryMap
├─ FOUNDATION     非源码材料（构建/配置/文档/脚本/部署/数据模型）+ 纯配置类源码
│                 → 确定性选材：按材料类别轮转，**不使用 LLM**
│
└─ SCOUT_SOURCE   非生成的业务源码
                  → flat File Scout（目录装得下时）
                     或
                  → Region Scout 分层下降 → 终态文件组
                     → 每组一次分支本地 File Scout
                     → 保序轮转合并

→ RepositoryReadPlanner   两条通道各自在预算内轮转；凭据路径判定在此处（执行点 ①）
→ 按固定 revision 真实读取
→ 执行期按**真实内容**复核尺寸（blob metadata 与内容长度可能不一致）
→ 凭据内容净化（执行点 ②）
→ Final Repository Analyzer
→ Evidence 校验（每条依据必须指向本次真正送出的文件）
→ RepositoryProfile
```

### C.2 三个 LLM 角色必须分清

| 角色 | 它收到什么 | 它回答什么 | 它**看不到**什么 |
|---|---|---|---|
| **Region Scout** | 某一层兄弟 Region 的描述符：目录前缀、直属/后代源码数、子目录数、出现过的语言与角色提示 | 「接下来往哪几个目录里看」，用 `RR-*` 引用表达顺序 | 文件清单、文件内容 |
| **File Scout** | 一份文件目录：编号、路径、字节数、语言、材料类别、角色提示 | 「接下来读哪些文件」，用 `RF-*` 引用按聚焦区域分组 | 文件内容 |
| **Final Repository Analyzer** | 相对路径 + **净化后**的文件正文 | 提出 `purpose / techStack / modules / capabilities / reusableAssets / limitations / risks` 与 Evidence | 宿主机路径、`analyzedRevision` |

高层请求形状（不含任何真实内容）：

```text
Region Scout   {"analyzedRevision": "...", "regionCatalog":  [{reference, pathPrefix,
                directSourceFiles, descendantSourceFiles, childRegionsWithSource,
                languages, roleHints}, …]}

File Scout     {"analyzedRevision": "...", "fileCatalog":    [{reference, path,
                sizeBytes, language, materialKind, roleHints}, …]}

Final Analyzer {"repositoryFiles": [{path, content}, …]}     ← 整条链路上唯一发送仓库内容的一次调用
```

### C.3 三条容易记错的性质

```text
1. 模型只能「指认」，不能给路径。RF-* / RR-* 是调用内的闭集短名，
   由 Application 换回 Map 上的真实描述符；越界即可判定失败。
2. 前两次调用只发元数据，第三次才发内容——凭据边界存在的全部理由就在这里。
3. 两条通道的预算互不占用：Foundation 读满不会让定向源码少读一个。
```

---

## D. 重要的工程经验

### D.1 可变的外部状态必须钉在不可变 revision 上

HEAD 只在编排层解析一次，之后建 Map、两次 Scout、规划、每一次 `readFile`、
以及最终的 `RepositoryProfile.analyzedRevision` 全部携带同一个 commit id。
`WorkspaceReadPort` 的契约明确拒绝 `HEAD` / 分支名 / 缩写 id，因此「中途重新解析」
在类型与实现两层都不可行。防的是：描述符来自 commit A、内容来自 commit B、
而 Profile 声称 `analyzedRevision = A` —— 一份看起来完整、实际无法追溯的快照。

### D.2 AI 输出是不可信提议，不是领域状态

三个 LLM 角色产出的都是**提议**：Scout 给的是「去哪里看」，不是对仓库的结论；
Analyzer 给的是待判定的字段与依据。是否成为合法领域状态由 Domain 判定（RULE-DOM-003）。
具体体现：

```text
· Scout 的 label 只是查看意图，绝不进入 capabilities / reusableAssets
· 引用经 Application 校验后才换回真实描述符，模型输出的路径永不进入读取调用
· 最终分析器提出的 sourceRef 必须命中「本次真正送出的文件」，否则整次分析被拒
· confirmed 固定 false、confidence 固定 null —— 可信程度不由模型给
```

### D.3 「完整的元数据可见」不等于「读全部源码」

Map 是完整的（该 revision 上每个文件都有描述符），但它**没有内容入口**。
这不是为了省事：一旦为了分类去读内容，「看一眼整棵树」就退化成另一次有界采样，
M1 的盲区会原样回来。**先看见，再取舍**——这两件事必须分开。

### D.4 分层解决的是「可到达性」，不是「最终覆盖面」

V3 把 4,572 个候选压到「每支装得下」，但真正决定模型最终看到什么的，
是 ReadPlanner 的 `maxFiles`：三仓库实测合并候选 266–391，最终采纳 18–24（约 5%–7%）。
所以「分层 Scout 把问题解决了」这句话要补一半：它解决的是**能开始**，不是**看得全**。

### D.5 预算必须围绕不确定的模型调用写成确定的

- Scout 的调用次数、Region 的轮数与宽度、材料的两条通道，全部是可配置的确定上限；
- 守卫一律在**调用之前**判定，超限的那一次不会发出去；
- 契约违反后的重试也要申请许可——重试是**真实的模型调用**，
  因此聚合日志记的是 `regionScoutAttempts / fileScoutAttempts`，而不是「逻辑阶段数」。
  「看起来问了 7 次、实际问了 9 次」这件事必须可读。

### D.6 模型给出的引用必须在 Application 侧解析

`RF-*` / `RR-*` 是调用内的短名。解析器只管形状，解析器（Resolver）管引用是否存在。
两条硬性质：**编号不携带调用身份**（两个分支的 `RF-1` 都是合法的，各自解析各自的文件），
而 Region 会逐层重建目录，因此 `RR-*` 必须把调用作用域写进值里——
否则 A 调用的引用交给 B 解析会**静默成功**。

### D.7 不变量：失败不留部分状态

Repository Analysis 的保存是整条链路的最后一步且只发生一次；方向发现整批原子写入。
真实 smoke 里验证过：四次 basis 不成立的调用前后，方向相关表的行数完全不变。

### D.8 安全边界必须存在**在**把仓库源码送出去之前

不是「事后过滤」：读取前按路径排除（二进制凭据只能靠路径识别），
交给模型前按内容替换（配置既是最可能的凭据位置，也是最不能整份丢掉的材料）。
任缺一处都有明显的洞。

### D.9 真实 smoke 是架构发现工具，但也会变成边际收益陷阱

M1 与 M2 的两次重大转向**都**来自真实仓库 smoke，而不是单元测试或代码评审——
这是这套流程最大的价值。但同一种手段在核心架构已经可用之后继续使用，
边际收益会迅速下降（见 E）。

---

## E. 哪些地方走弯了 / 代价过高

这一节如实记录，区分「有用的发现」与「边际收益递减的打磨」。

### E.1 Repository Analysis 吃掉了 M2 的大部分时间，且不是计划内的

M2 的目标是 Product Direction。实际的时间分布是：

```text
09-24 → 09-28   Product Direction 领域 → 持久化 → AI 边界 → 发现服务 → API    14 个提交
09-29 → 10-01   Repository Understanding V2（Map / Scout / ReadPlan）+ 生命周期  16 个提交
10-02           分层 Scout 侦察 → ADR-0005 → 实现 → ADR-0006 → 凭据边界          25 个提交
10-03            收尾：文档清理、代码走查、V3 多仓 smoke、稳定化标定、PD 端到端    7 个提交
```

10-02 单日 25 个提交——**那一天既是效率最高的，也是最像「被真实失败追着跑」的**。
ADR-0005 与 ADR-0006 在同一天内侦察、决策、实现、修复完毕，说明架构本身是对的、
推理并不困难；难的是**发现它需要存在**（需要真实仓库）与**把它打磨到可交付**。

### E.2 核心架构可用之后，还在为边界情况做了好几轮

可用的分界线很清楚：**V3 多仓 smoke 3/5 成功** 之后，Repository Understanding 已经
足够支撑 M2 的任何后续工作。之后的稳定化仍然做了：

- 定向源码预算的 A/B 标定（8 次真实 Provider 运行）；
- Scout 契约违反的有界重试（含额度记账的整套改造）；
- 计划中的配置 C（30 个文件）与 langflow 的进一步守卫标定——
  最后被明确叫停，理由是「A/B 已经足以判断，边际收益递减」。**叫停是对的**，
  但更早叫停会更好。

### E.3 验证与评审的粒度变得过细

M2 的 63 个提交里 **14 个 fix** 对 21 个 feat（另有 docs 24、refactor 2、test 1、chore 1）。
修缺陷的提交占比并不离谱，但其中相当一部分不是「发现了未知缺陷」，
而是**同一处规则被反复逼近**。最典型的是凭据内容规则：

```text
第 1 轮：带转义引号的取值只净化了前半段
第 2 轮：赋值规则破坏 `==` 比较、且会吞掉下一行
第 3 轮：新增的引号匹配导致 4000 字符就 StackOverflowError（那是 Error，绕过失败关闭）
另有 1 处我自己探针查出、评审没提的平方代价（URI 方案名无上界，60 KB 上约 12 秒）
```

四轮里前三轮都值得修，但它们是**同一条正则的三个不同边界**——
如果一开始就把「规则必须在 64 KiB 输入上线性、且不得逐字符递归」写成验收条件，
后两轮可以合并。教训：**给规则类改动先写清代价与失败语义，比逐轮修边界便宜。**

### E.4 大仓库支持一度变成优化黑洞

langflow 是一个 10,375 文件 / 4,572 源码候选 / 889 KB flat catalog 的真实仓库。
围绕它发生过：侦察、ADR、一整套分层实现、一次守卫标定尝试、以及一次
「要不要为它放宽守卫」的决策。最终结论是**接受它为 MVP 边界**（409 失败关闭）。

这个结论是正确的，但过程偏贵。事后看，**在第一次标定时就该同时回答两个问题**：

```text
它能不能成功？       → 需要多少调用、多少钱
它值不值得成功？     → 这类仓库在真实用户里占多大比例
```

只问前者会把工程引向「让它成功」，而不是「把边界说清楚」。

### E.5 交付物一度从用户眼前消失（流程失误，非技术问题）

把已提交到 `main` 的文档提交「挪到分支」时用了 `git reset --hard`，工作区随之回退，
**文件从用户本地磁盘上消失了**——提交还在对象库里，但用户看不到交付物。
教训：**用户判断我有没有干活，靠的是磁盘上能打开的文件**；要挪提交就必须同时说明
「文件会消失、怎么找回来」，或者一开始就别提交到 `main`。

### E.6 这些**不算**失败

以下属于正常的缺陷修复与必要的返工，不记为「走弯路」：

```text
· 分层 Scout 的守卫没在导航阶段生效、守卫失败被误报成 500  —— 实现缺陷，改对了
· Region 引用最初用内容摘要做作用域（会碰撞、且同一输入两次调用引用相同）—— 概念错误，改对了
· memos 的 Go 测试文件混进源码候选                              —— 分类遗漏，真实数据发现
```

---

## F. 明确接受的边界与积压

```text
1. 极端庞大/极宽的仓库可能返回 409
   langflow（10,375 文件 / 13.6× 超限）是当前实例。
   绑定约束是 region.max-scout-calls = 12，而深度（5/8）与单次载荷（6.6 KB/64 KB）都远未触及。
   这是**产品边界**，不是实现缺陷。

2. 不做任何任意的降级
   不截断分支、不随机采样、不丢弃终态组、不返回部分 RepositoryProfile、
   不在超限时退回「把整份 flat 目录发出去」。

3. `.env.example` 仍被路径政策整份排除
   它**通常是**模板文件，排除它会让分析损失掉一部分有用材料
   （真实仓库上最多一次排除掉 35 个文件，且其中确实都是模板）。
   但**文件名不能保证里面一定没有真实凭据**——`.env.example` 被填入真值是常见事故，
   而路径政策是唯一的整份排除点，放行它就得靠内容规则去兜底，那对二进制与自定义格式并不成立。
   因此当前仍保守排除：宁可少一份材料，不在这一层开一个「按文件名猜内容安全」的口子。
   这是 ADR-0006 的安全取舍，不是「排除它没有收益」。

4. 未知形态的凭据不受保证
   规则是「枚举 + 形态判断」：命中什么就保护什么。未知令牌格式、自研格式、
   被拼接 / 编码 / 分片构造的凭据都可能不命中。ADR-0006 的「不保证」一节仍然有效。

5. 尚未支持远端仓库发现
   当前 Software Asset 只支持本地 Git Repository；GitHub 等来源的发现属于上游能力。

6. 当前 MVP 不再继续优化 Repository Understanding
   预算是运维调参值，若将来有更大的仓库再次先撞总量上限，应按**新的观测**再调，
   而不是现在预先放大。

7. 包结构与零散文档清理属于积压
   已有若干处「阶段刚建成、还没接上线」时写下的 javadoc/package-info 没有跟着改
   （详见 `m2-repository-analysis-v3-code-walkthrough-probe.md` §21 的 F1–F9）。
   不影响运行，但没有阻塞后续工作时不做。
```

---

## G. Product Direction 的最终验证

`docs/validation/m2-product-direction-e2e-smoke.md` 记录了一次真实的三仓库端到端验证：

```text
同一个 Confirmed UserProfile @ revision 23
        +
hm-dianping / mall / memos（三个真实本地仓库，各自真实分析出 RepositoryProfile）
        ↓
14 个 ProductDirection（全部 CANDIDATE），207 条 EvidenceBasis，47 条 risk
```

### G.1 为什么「主题相似」不等于「塌缩」

固定同一个人格，必然会把两个主题带进三个仓库（自托管记账、草稿发布）——
**这是正确结果**：用户明确说了这两个痛点，任何负责任的方向集都该围绕它们。

判定是否塌缩的依据不是标题相似度，而是**到达同一目标的路径是否不同**：

```text
同一个「自托管记账」目标，三条不同的演化路径
  hm-dianping  → 三级缓存 + 秒杀事务消息资产，路径是「读链路性能与并发写入一致性」
  mall         → 通用响应/分页骨架 + ES 商品搜索 + 动态鉴权，路径是「检索聚合、离线同步」
  memos        → memo 数据模型 + CEL 视图 + MCP 服务，路径是「把笔记实体演化为账目、用协议层替代前端」
```

机械证据：207 条依据里 **92 条是 REPOSITORY_PROFILE 依据，全部指向各自仓库的 Profile**；
每个方向的 `differentiation` 都指名道姓地对照了原项目的具体能力；
风险项也各不相同（hm 是缓存一致性与中间件运维，mall 是「个人数据量小可能过度工程化」，
memos 是 Go 依赖面与 proto 生成链路）。

### G.2 同时被验证的三件事

```text
EvidenceBasis 正确性   14/14 方向的 userProfileRevision == 23；
                       14/14 的 repositoryProfileIds 与 candidateAssetIds 命中唯一输入；
                       每条被引用的 claim 都真实存在于它声称的来源里；0 处错配
生命周期                select → 第二个 select 让原方向 SUPERSEDED（全局唯一 SELECTED）；
                       只有 CANDIDATE 可 select / reject，两条非法转换均 409 且不改状态
无部分持久化           四次 basis 不成立的调用（revision 过期 / Profile 不存在 /
                       UserProfile 不存在 / expectedRevision 缺失）全部在调用模型之前失败，
                       且调用前后方向相关表行数完全不变（14 / 207 / 47）
```

---

## H. M3 之后的交付方式（本次复盘的直接产出）

M2 最大的过程教训是：**把里程碑切成一堆小步审查循环，会让学习速度变慢**。
核心架构一旦可用，继续按「一个边界 → 一次审查 → 一次 smoke」推进，
收益会迅速低于成本（E.2 / E.3）。

新的交付规则：

```text
1 个里程碑
  → 定义 What / Why / Invariants（清楚到可以判断对错）
  → 实现一个**相对完整的垂直切片**（而不是一串可独立审查的小补丁）
  → 真正理解核心 Domain / Use Case / 架构边界
  → **1 次**里程碑级审查
  → **1 次**聚焦 smoke
  → 复盘
  → 进入下一个里程碑
```

只有真正存在架构依赖时，才把一个里程碑拆成多个审查循环。

明确优先顺序（当取舍发生时按此排序）：

```text
主产品链路完整可用   >  边界情况的完备性
面试可讲清楚         >  生产级健壮性
真实学习价值         >  代码覆盖广度
可演示的完整闭环     >  更多特性
```

> 这条规则与 `ROADMAP.md` 的 M2 交付物并不冲突：M2 的**交付物**是完整的，
> 代价在于**推进方式**；H 只改变推进方式，不降低交付标准。

---

## 附：M2 的量化概览

```text
时间跨度        2026-09-23 → 2026-10-03（10 天）
主线提交        63 个（docs 24 / feat 21 / fix 14 / refactor 2 / test 1 / chore 1）
                统计口径：git log --since=2026-09-23 main，含本次复盘提交本身
ADR             新增 3 条（0004 两阶段理解 / 0005 分层 Scout / 0006 源码凭据边界）
验证记录        8 份（M2 相关）
真实 smoke      4 次（PD Round 1/2、多仓 Repository Analysis、V3 多仓、PD 端到端）
测试规模        1069（domain 178 / application 653 / infrastructure 108 / app 130）
```
