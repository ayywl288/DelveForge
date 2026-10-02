# ADR-0006: Repository Source Secret Boundary Before External AI Requests

Status: Proposed

Date: 2026-10-02

> **本 ADR 记录的是决策，不是实现进度。** 当前**尚未实现**（Task 10B-2）。
> 权威状态以代码与 `ROADMAP.md` 为准。
>
> 支撑本决策的代码侦察：`docs/validation/m2-repository-secret-boundary-reconnaissance.md`
>
> 与本 ADR 相邻但**不同**的一条既有决策：ADR-0002（日志的结构化-only 策略）。
> 本 ADR 不重新设计日志，两件事针对不同的出口。

## Context

### 问题

Repository Analysis 会把**仓库文件的内容**读进 Application，并原样交给外部 AI Provider：

```text
仓库里受版本控制的文件内容
        ↓  Workspace 读取
分析材料（相对路径 + 原文）
        ↓
外部 AiGateway / DeepSeek 的请求体
```

其中可能有用户并不打算交给第三方的凭据：硬编码口令、API Key、访问令牌、私钥、
带凭据的连接串、`.env` 之类的凭据文件。

这个问题**不是** ADR-0002 的问题：

```text
ADR-0002 管的是   「DelveForge 自己的日志里不出现运行时自由文本」
本 ADR 管的是    「仓库里的凭据不要被送到外部 Provider、并因此被复述到下游产物」
```

两者共享「DelveForge 不主动扩大凭据的暴露面」这个目标，但出口、威胁与可控手段都不同。

### 现有失败路径（代码事实）

侦察确认了三件事（细节与行号见 validation 记录）：

```text
1. **当前没有任何一层拒绝凭据文件。** 实测（只读探针，见 validation §2.1）：
   .env / .env.local / .npmrc / .pypirc / .netrc / .aws/credentials /
   *.pem / *.key / *.p12 / *.jks / id_rsa / service-account.json
   ——全部是 RepositoryCandidateLane.FOUNDATION，因此会被 Foundation 通道选中、
   被读取，并**原样**进入送往 DeepSeek 的请求。
   （.env / .npmrc 甚至是被当作「可分析配置」主动支持的。）
   触发它不需要特殊构造：一个含 .env 的普通仓库就够了。

2. 内容离开本机的唯一出口是 RepositoryAnalysisExtraction.describeFiles
   （entry.put("content", file.content())，:192）。
   六处 AiGateway.generate 调用点中，只有这一处承载仓库文件内容。

3. 从读到发之间没有任何转换。模型若复述了内容，那段文本会依次进入
   RepositoryProfile 的字段、SQLite、HTTP 响应，以及**下一次**发给 Provider 的
   Direction Discovery Prompt——同一条信息会反复出机器。
```

### 为什么现在做

```text
M2 的 Repository Analysis 已经走通真实仓库（分层 Scout 已接入）；
analyze 端点是可达的生产路径，不是实验代码。
凭据一旦到达第三方 Provider，**无法撤回**：调用已经发生、内容已离开本机。
```

### 已确定、不因本 ADR 改变的前提

```text
Repository Analysis 对原始软件资产保持只读（RULE-DOM-005）。
不修改仓库内容，不写入 Software Asset。
所有 LLM 访问经 AI Gateway（RULE-ARCH-008）。
不引入 AST / RAG / embedding / 语义检索（与本问题无关）。
```

## Decision

在**仓库内容离开本机的唯一出口**处，加一层 Application 级的确定性边界：

```text
原始本地仓库内容（raw）
        ↓  RepositoryMaterialSecretBoundary     ← 唯一出口，唯一一处
模型可见的仓库材料（safe/model-visible）
        ↓
AiRequest → AiGateway → 外部 Provider
```

边界由两层组成，**两层都做**：

### 第 1 层 — 路径排除（确定性，可称为硬保证）

整份文件按定义就是凭据载体时，**不进入材料**（因而不被读、不被发）：

```text
.env, .env.*, *.env
*.pem, *.key, *.p12, *.pfx, *.jks, *.keystore
id_rsa, id_dsa, id_ecdsa, id_ed25519（不含 .pub——公钥不是凭据）
.npmrc, .pypirc, .netrc, .aws/credentials
```

这一层不依赖内容形态，因此覆盖两类第 2 层做不到的东西：二进制凭据文件，以及
「整份文件就是一个不带已知前缀的随机串」。

### 第 2 层 — 内容净化（已实现规则内确定，保护范围尽力而为）

对**保留下来的**每个文件，在构造请求之前替换掉识别出的凭据字面量：

```text
PEM 私钥块（-----BEGIN … PRIVATE KEY----- … -----END …-----）   整块替换
已知令牌前缀（AKIA / ghp_ / gho_ / ghs_ / sk- / xox* / AIza / JWT 三元组 …）  替换
Authorization 头的取值（Bearer / Basic）                          替换取值
凭据位置的赋值（password / secret / token / api_key / … 后跟 : 或 =）  **只替换值，保留键名**
连接串中的 userinfo（scheme://user:password@host）                替换口令部分
```

替换成固定占位符，**保留行结构、缩进与键名**：模型仍然看得出「这里有一个口令配置项」，
但看不到口令本身。这一点直接决定了分析价值损失的大小。

### 占位符 / 示例值策略：不区分真假

处于凭据位置的值**一律替换**，包括 `"your-password-here"`、`${DB_PASSWORD}`、
`changeme`、`xxxxx` 这类看起来是示例的值。

理由：

```text
「这个值是不是真凭据」无法可靠判定，试图区分只会让行为不可预测；
而示例值被替换掉的上下文几乎不为分析所用。
统一替换让行为只依赖**位置与形态**，不依赖对真假的猜测——也因此可以写出确定的测试。
```

### 边界位置：唯一一处

边界由 `RepositoryAnalysisExtraction` 在构造请求之前调用一次，作用于整个材料列表。

```text
RepositoryUnderstanding.understand(...)  →  List<RepositorySourceFile>   原始材料
        ↓
RepositoryAnalysisExtraction.extract(files)
        sanitize(files)                    ← 边界在此，且只在此
        aiGateway.generate(buildRequest(safeFiles))
```

选择这一处而不是别处，依据是侦察给出的两条事实：

```text
(a) 它是仓库内容离开本机的最后位置，也是唯一承载仓库内容的外呼点（6 个调用点之一）。
(b) 它在**读取之后**：读取阶段的执行期尺寸复核（maxFileBytes / maxTotalBytes）
    作用于真实内容。若边界上移到读取阶段，净化会改变字节数，把
    「读多少」与「给模型看什么」两个关注点耦合起来——那是两个不同的决定。
```

两条通道的材料此时已经合流（`RepositoryReadResult.material`），因此 Foundation 与
定向源码**自动获得同一份保护**，不需要各写一遍。

## Options Considered

| | 保护 | 误伤（FP） | 漏检（FN） | 对分析价值 | 能否称为硬保证 | 复杂度 |
|---|---|---|---|---|---|---|
| **A 仅路径排除** | 只对列出的文件名成立 | 低 | **高**：源码里的凭据、未列出的文件名全漏 | 丢几个凭据文件；若把 `application.yml` 也排除则损失很大 | 对已列文件名成立 | 极低 |
| **B 仅内容净化** | 对已实现规则命中的字面量成立 | 中（哈希、base64 资源、示例值） | **中高**：二进制凭据、未知格式、拼接构造全漏 | 小（只替换值，保留键名） | **不能** | 中 |
| **C 检测到就整次失败** | 与 B 相同 | **高代价**：FP 从「少看一个值」放大成「整个仓库分析不了」 | 与 B 相同 | **最大损失** | 不能 | 中 |
| **D 混合（A + B）** | A 的硬保证 + B 的尽力而为 | A 低 + B 中 | A 补上二进制与整文件凭据 | 可控 | **分两条分别陈述** | 中 |
| **E 在 AiGateway Adapter 处统一净化** | 覆盖所有出站流量（最强单点） | 无法区分字段语义，会误伤用户自己粘贴的内容 | 同 B | 影响所有流程 | 不能（在字符串层） | 中高 |

### 为什么拒绝 C：它被 B 严格支配

C 与 B 使用**同一套检测**，因此漏检完全相同。差别只在检测命中之后做什么：

```text
B  替换那几处 → 整份文件其余部分照常参与分析
C  整个仓库分析不了
```

C 没有换来任何额外的保护（命中之外的部分它一样看不见），却把每一次误伤都升级成
「用户拿不到任何结果」。而且用户无法从错误信息得知是哪一处触发的——一旦输出内容辅助
定位，那条信息本身又泄漏了一处。**在一个用户分析自己仓库的 local-first 工具里，
这个代价换不到对等的东西。**

### 为什么拒绝 A 单独使用 / B 单独使用

见 §7 侦察结论 C2 / C3：最可能含口令的 `application.yml` 是重要分析材料（不能按路径丢），
而 `.p12` / `.jks` 在文本层无从识别（只能按路径丢）。两者互补，缺一都会留下明显的洞。

### 为什么拒绝 E（在 Provider Adapter 处净化）

E 是**最强的单点**——所有出站请求都会经过它，包括未来新增的调用点。但：

```text
层次错位    「仓库里的凭据不该给第三方」是 Application 的政策，
            Infrastructure 的通用 AI Adapter 不该懂「仓库」这件事（RULE-ARCH-008 的反面）
语义丢失    在那个位置只剩序列化后的字符串，无法区分「这是文件内容」与
            「这是用户自己粘贴进 User Discovery 的内容」——后者是用户主动提供的，
            要不要净化是另一个决策，不该被这里顺手改掉
不可见      哪些流程受保护会变成需要读 Adapter 才能知道的事
```

若将来要覆盖**所有**出站流量，E 是正确的那个点——但那是一个不同的决策（涉及用户数据的
分类），记录在 Revisit Conditions 里。

### 为什么不在 RepositoryUnderstanding 或 UseCase 处净化

两处都能覆盖今天的需求，但都会把「谁忘了调用」变成唯一的安全保证。相比之下，
把边界放在**构造请求的那个组件内部**，意味着「这个组件发出的请求已经过边界」是一条
组件级性质，可以独立测试；将来若出现第二种把仓库内容变成请求的流程，
Revisit Condition 2 要求把边界上提到共享点。

## Exact Guarantee and Non-Guarantees

### 保证

```text
G1  路径命中第 1 层规则的文件不会被读取，也不会出现在任何 AiGateway 请求中。
G2  保留下来的文件中，被第 2 层规则命中的字面量不会原样出现在 AiGateway 请求中。
G3  在当前代码下，边界是仓库文件内容进入外部 Provider 的唯一出口。
G4  边界自身失败时整次分析失败关闭：不发送未净化的请求，不产生任何 RepositoryProfile。
```

G1 / G2 / G4 是**代码可验证**的性质，对应 §测试策略里的用例。

### 不保证

```text
N1  **不保证「所有凭据都不会泄漏」。** 第 2 层是已实现规则的集合加启发式，
    未知令牌格式、自研格式、被拼接/编码/分片构造的凭据都可能不命中。
N2  不保证路径本身不含凭据。路径始终原样发送——它是 Evidence 的定位依据，
    去掉或改写它会让「依据可追溯」失效。
N3  二进制内容在文本层无从识别（宽松解码后只剩替换字符），只有其**路径**
    命中第 1 层时才受保护。
N4  不覆盖用户自己粘贴进 User Discovery 的凭据——那是另一份数据、另一个出口。
N5  不保证模型不会从它**看到过的**（已净化的）内容里推断出敏感信息。
    边界管的是「未经净化的字面量不出去」，不是「模型不想事」。
```

**本 ADR 不声称「凭据不可能泄漏」。** 第 1 层可以对已列举的文件名给出硬保证，
第 2 层只能给出「对已实现规则成立」的保证，而规则覆盖不到的部分始终存在。
把这一点写清楚，比给出一个做不到的强断言更符合本项目的既有做法。

## Failure Semantics

```text
路径命中第 1 层          → 该文件不进入材料（不是失败）。以聚合计数形式可见，
                            不记录路径——路径是用户数据。
净化过程本身抛错          → 整次分析失败关闭（RepositoryNotAnalyzable 之外的新语义
                            需与既有失败语义对齐，见下），绝不退回发送未净化的内容。
净化后材料为空            → 与既有「读不出材料」同义，失败关闭。
```

**明确禁止：**

```text
静默丢弃命中内容而不留任何可观测计数
「净化失败就用原文发」这类降级
「先发出去，出问题再说」的重试策略
```

失败语义与既有链路对齐：与「读不出材料」同属「这次分析无法完成」，因此复用
`RepositoryNotAnalyzableException`（409）的对外含义，而不是掉进「未知服务端故障」。
具体类型在 10B-2 决定，本 ADR 只要求：**对外是「当前无法分析」，且不输出任何内容或路径。**

## Impact on Foundation and Targeted Materials

```text
两条通道的材料在 RepositoryReadResult.material 合流，因此天然同受保护，
不存在「只保护了源码、漏了配置」这种可能。

代价（真实存在，不掩饰）：
  第 1 层   少数文件整体退出分析。.env / *.pem 这类文件对「这个项目实现过什么」
            几乎没有贡献，损失很小。
  第 2 层   application.yml / .properties 仍然保留（键名、结构、非凭据值都在），
            只有凭据值变成占位符。模型依然能得出「它用 MySQL、配了 Redis」这类结论，
            而拿不到口令本身。
```

预算与顺序不受影响：边界作用于**已经规划好、已经读进来**的材料，不新增读写、
不改变 `maxFiles` / `maxFileBytes` / `maxTotalBytes` 的语义，也不改变读取顺序。
净化让内容变短，不会让任何一条既有的尺寸复核改变结论。

## Downstream Propagation Reasoning

问题：把凭据挡在第一次出机器之前，是否足以阻止**源出**凭据出现在
RepositoryProfile / Evidence / SQLite / API 响应 / 后续 Product Direction Prompt？

**结论：足够。不需要额外的 post-model 过滤器。**

推理依据是一条代码事实加一条推理：

```text
事实   净化发生在 create 请求之前，因此模型在整个分析过程中看到的内容已经过边界。
推理   模型能复述的，是它看到过的；它没有见过未经净化的原文，
       因此它的输出不可能包含**来自该仓库**的未净化凭据。
       下游所有环节（Profile 字段 → SQLite → API → 下一次 Prompt）都只是模型输出的搬运，
       源头没有，下游就不可能出现。
```

因此**不新增** post-model 过滤。加一个「扫描模型输出里的疑似凭据」的步骤，
只会引入第二套规则、第二处误报来源，而它挡不住的东西（模型从没见过的凭据）本来就不存在，
挡得住的东西（模型见过但已被净化）也本来就不会出现。

**什么情况下这条推理会失效**（即：那时才需要 post-model 保护）：

```text
出现第二种把仓库内容变成外呼请求的流程，且它没有经过本边界
    → 那时模型可能见过未经净化的原文，Profile 里就可能出现明文凭据
      （这正是 Revisit Condition 2 要处理的情形：把边界上提到共享点，
       而不是在下游补一个过滤器）
```

也就是说，**下游的干净取决于上游的单点是否唯一**。本 ADR 的选择是用「唯一出口」
来保证这件事，而不是用下游补救。

## Test Strategy（Task 10B-2 实施时的矩阵）

全部使用合成金丝雀（canary），不使用任何真实凭据。金丝雀是形如
`CANARY-<随机后缀>` 的字面量，加上各规则的代表形态（PEM 块、`ghp_` 前缀串、
`password: …` 赋值等）。

| # | 用例 | 断言 |
|---|---|---|
| T1 | 在 **Foundation** 文件里放金丝雀，跑完整 `extract` | 捕获到的**确切 `AiRequest`** 中（system + user 两个 message 的全文）不含该金丝雀 |
| T2 | 在**定向源码**文件里放金丝雀，同 T1 | 同上——证明两条通道同受保护 |
| T3 | `.env` / `*.pem` 出现在仓库里 | 该路径**从未被 readFile 调用**（Workspace 替身记录调用），且不出现在请求里 |
| T4 | 保留结构的断言 | 键名仍在（`password:` / `api_key =`）、行数与缩进未变、非凭据值完好；`purpose` 等正常结论仍可解析 |
| T5 | Region Scout / File Scout 阶段 | 两者的请求体里**没有**文件内容（只有描述符）——保证边界没有把「本来就不发内容」的阶段也改坏 |
| T6 | 回显式确定性 AI 替身（把收到的材料原样填进 proposal 字段） | 落库的 `RepositoryProfile` / Evidence 里不含金丝雀 |
| T7 | 边界自身的日志 | 捕获日志输出，断言不含金丝雀、不含路径；但**包含**「排除了几个文件 / 替换了几处」的计数 |
| T8 | 占位符与疑似 FP | 按策略：凭据位置的值一律被替换（`"your-password-here"`、`${DB_PASSWORD}` 也替换）；**非凭据上下文不被误伤**（例如标识符 `tokenCount`、纯哈希字面量按规则集的明确取舍） |
| T9 | 失败语义 | 让净化阶段抛错 → 分析失败、不调用 Provider、`repository_profile*` 三张表 0 行 |
| T10 | 装配 | 生产装配里边界 Bean 存在，且 `RepositoryAnalysisExtraction` 持有它（结构断言，与既有 `RepositoryAnalysisWiringTest` 同一手法） |

T1 / T2 是本 ADR 的核心用例：它们直接验证 G2，且验证对象是**端口的真实入参**，
不是任何中间表示。

## Consequences

### Positive

- 仓库凭据在**离开本机之前**被挡下；下游（Profile / SQLite / API / 后续 Prompt）随之干净
- 复用既有唯一出口，不新增外呼点、不改变读取与预算语义
- 第 1 层给出可陈述的硬保证；第 2 层的行为是确定的（同样输入 → 同样输出），可测试
- 不引入新依赖，与 local-first 定位一致

### Negative

- 少数文件整体退出分析（第 1 层），部分配置值变成占位符（第 2 层）
- 规则集需要维护：新增令牌格式要补规则；补规则要配测试
- 规则集天然不完整，用户可能误以为「已经检查过了就一定是干净的」——
  §不保证一节是对这种误读的唯一防线

### Risks

- **误读为完整保护。** 缓解：ADR 与配置/日志文案都不使用「已确保无凭据」这类表述。
- **规则过度扩张导致误伤**，把正常代码改得不可读。缓解：只替换值不替换键名与结构，
  且 T8 把误伤边界钉住。
- **规则集长期不更新**，新令牌格式静默漏过。缓解：Revisit Condition 1。

## Revisit Conditions

```text
1. 在真实仓库上观察到规则集的 FP / FN
     → 用真实数据校准规则；不因为「看起来够用」而定案。

2. 出现第二种把仓库内容变成外呼请求的流程（新的 extraction / 新的 Adapter 路径）
     → 把边界上提到共享点，或改成类型约束
       （只有经过边界的材料类型才能进请求），而不是在下游补过滤器。

3. 需要保护用户自己提供的数据（User Discovery 里粘贴的凭据）
     → 那是另一份数据、另一个决策；若要做，正确的位置是 Provider Adapter（本 ADR 的选项 E）。

4. 日志/产物离开本机、变成多用户、或 Provider 的数据政策变化
     → 重新评估威胁模型与保留策略（与 ADR-0002 的同类条件合并评估）。

5. 边界使分析结论质量出现可观察的下降
     → 重新评估「排除 vs 净化」的取舍，特别是第 1 层名单是否过宽。
```

## References

```text
AGENTS.md
  §8.7 Error Handling
  §8.8 Logging
  §9   RULE-DOM-005（Repository Analysis 只读）

docs/decisions/0002-structural-only-exception-logging.md   （相邻但不同的出口）
docs/decisions/0004-two-stage-repository-understanding-with-validated-file-references.md
docs/decisions/0005-hierarchical-repository-scout-for-oversized-source-catalogs.md

docs/validation/m2-repository-secret-boundary-reconnaissance.md   （本 ADR 的代码依据）

application.port.workspace.WorkspaceReadPort.readFile
application.repositoryanalysis.readplan.RepositoryReadExecutor
application.repositoryanalysis.extraction.RepositoryAnalysisExtraction
application.repositoryanalysis.extraction.RepositorySourceFile
application.repositoryanalysis.map.RepositoryPathClassifier
infrastructure.workspace.GitWorkspaceAdapter
infrastructure.ai.DeepSeekAiGatewayAdapter
```
