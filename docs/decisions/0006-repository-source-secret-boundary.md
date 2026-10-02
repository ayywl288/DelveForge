# ADR-0006: Repository Source Secret Boundary Before External AI Requests

Status: Accepted

Date: 2026-10-02

> 本 ADR 已由 Task 10B-2 实现并测试通过。实现落在：
>
> ```text
> application/repositoryanalysis/secret/           政策本身（路径规则 + 内容规则）
> application/repositoryanalysis/readplan/         第一个执行点：读取之前整份排除
> application/repositoryanalysis/workflow/         第二个执行点：交给模型之前净化
> ```
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

解法是**一条 Application 级的确定性凭据政策**，在两个位置执行：

```text
原始本地仓库内容（raw）
        ↓  ① 路径排除        RepositoryReadPlanner：整份凭据文件不进入候选
保留下来的文件被读取（仍是原文）
        ↓  ② 内容净化        RepositoryUnderstanding：替换识别出的凭据字面量
模型可见的仓库材料（safe/model-visible）
        ↓
AiRequest → AiGateway → 外部 Provider
```

**这不是「在唯一出口加一层过滤」。** 早期草稿是那样写的，而它与「高风险文件不被读取」
自相矛盾——到唯一出口时 `readFile` 早就发生过了。两个执行点是**同一条政策**（同一个
`RepositorySecretPolicy` 实例）落在两个能力不同的位置上，详见 §边界位置。

政策由两类规则组成，**两类都做**：

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

### 边界位置：一条政策，两个执行点

这是本 ADR 最容易被写错的一处。早期草案把边界写成**读取之后的一个点**，却又要求
「高风险文件不被读取」——这两个说法不能同时成立：`readFile` 已经发生过了。
修正后的设计是**一条政策、两个执行点**：

```text
仓库描述符
        ↓  ① 路径排除        RepositoryReadPlanner 构候选队列时
保留下来的文件被读取
        ↓  ② 内容净化        RepositoryUnderstanding.understand 返回之前
模型可见的仓库材料
        ↓
RepositoryAnalysisExtraction → AiGateway → 外部 Provider
```

两个执行点是**同一个 `RepositorySecretPolicy` 实例**，不是两套系统。之所以必须有两个点，
是因为两者能做的事情不同：

```text
① 在读取之前   可以整份不读。二进制凭据（.p12 / .jks）在文本层无从识别，只有路径能识别；
               而没读进来的文件不可能出现在请求里。
② 在读取之后   只能改内容。application.yml 这类配置是最可能的凭据位置，同时也是最有价值的
               分析材料——不能整份丢掉，只能替换其中的取值。
```

任缺一处都会留下明显的洞：只有 ① 会漏掉源码与配置里的凭据，只有 ② 会漏掉那些内容层
无从识别的整份凭据文件。

**为什么第一个点落在读取规划器。** 它是两条通道（Foundation 与定向源码）**共同**经过的
唯一处，并且是名额（`maxFiles`）被消耗的地方。在候选进入轮转之前排除，才能保证一个被
挡下的 `.env` 不占用名额——否则「仓库里多了一个凭据文件」会变成「分析少看了一个正常文件」。

**为什么第二个点落在理解阶段的末尾，而不是更下游的抽取组件里。**

```text
(a) 本轮转出本类的材料就是这次分析唯一的材料来源。把净化放在返回之前，
    「本方法返回的已经是模型可见材料」成为一条关于单个组件的性质，可以直接测试，
    而不必逐个调用方去确认它记得过边界。
(b) 失败语义在那里是现成的：该处已经负责把「分层 Scout 的守卫失败」翻译成
    「当前无法分析」，边界失败走同一条路，不需要在调用链上再开一个翻译点。
(c) 净化会改变字节数，而读取阶段的执行期尺寸复核（maxFileBytes / maxTotalBytes）
    作用于**真实内容**。把净化放在读取之后，两者互不干扰；放在读取之中就会把
    「读多少」与「给模型看什么」两个决定耦合起来。
(d) secret 包只依赖材料类型，不依赖 workflow 的异常类型——放在更下游会让这个方向反过来。
```

两条通道的材料在 `RepositoryReadResult.material` 已经合流，因此 Foundation 与定向源码
**自动获得同一份保护**，不需要各写一遍。

## Options Considered

方案里的「路径排除」与「内容净化」指的是**手段**；它们落在上面决策的两个执行点上
（路径排除在读取之前，内容净化在读取之后）。下表比较的是手段本身。

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

### 为什么不用「在 AiGateway 之前再加一道后置过滤」

早期草案考虑过在最终分析之外再补一个「扫一遍模型输出里的疑似凭据」的步骤。结论是不加，
理由在 §下游传播里：消费者只能复述它见过的东西；源头已经净化，下游就不可能出现。
加一道后置过滤只会带来第二套规则、第二处误报来源，而它挡不住的东西（模型没见过的凭据）
本来就不存在。真正会需要它的情形只有一种——出现第二条未经边界的外呼流程——
而那时的正确做法是把边界上提到共享点（Revisit Condition 2），不是在下游补过滤。

## Exact Guarantee and Non-Guarantees

### 保证

```text
G1  路径命中第 1 层规则的文件**不会被读取**（更不会被发给模型）：它在候选进入轮转之前
    就被排除，因此既不产生 readFile 调用，也不可能出现在请求里。
G2  保留下来的文件中，被第 2 层规则命中的字面量不会原样出现在送往 AiGateway 的请求中。
G3  在当前代码下，这两个执行点覆盖了仓库文件内容离开本机的全部路径：六处
    AiGateway.generate 调用点里，只有 RepositoryAnalysisExtraction 承载仓库文件内容，
    而它拿到的材料必然经过第 2 层。
G4  任一执行点失败时整次分析失败关闭：不发送未净化的内容，不产生任何 RepositoryProfile。
```

G1 / G2 / G4 是**代码可验证**的性质，对应 §测试策略里的用例。G3 的表述刻意写成
「当前代码下」：它依赖「只有一处把仓库内容变成外呼请求」这一事实，而不是一条结构性保证
（见 Revisit Condition 2）。

### 它保证的与「模型不可能生成同样的字符串」是两件事

本 ADR 保证的是**传播路径**，不是一个关于生成模型的数学命题：

```text
保证    当适用的边界规则命中时，仓库里的原始凭据在这条 Repository Analysis 请求上
        **没有经由材料传播出去的路径**——它要么没被读，要么读进来也被替换掉了。
不保证  生成模型不会独立地输出某个恰好相同的字面量。这不是本边界要管的事，
        也无法由任何输入侧的过滤来保证。
```

把这两者混为一谈会得出错误的结论：要么以为「过滤是徒劳的」（因为模型可能巧合），
要么以为「过滤之后不可能有任何相同字符串」（那是做不到的承诺）。

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
N6  不保证生成模型不会独立输出与某个凭据相同的字符串。
    本边界消除的是**来自这份仓库的传播路径**，不是字符串层面的巧合。
```

**本 ADR 不声称「凭据不可能泄漏」。** 第 1 层可以对已列举的文件名给出硬保证，
第 2 层只能给出「对已实现规则成立」的保证，而规则覆盖不到的部分始终存在。
把这一点写清楚，比给出一个做不到的强断言更符合本项目的既有做法。

## Failure Semantics

两个执行点能保证的事情不同，因此分开写：

```text
① 路径判定自身抛错（读取之前）
     → 整次分析失败关闭：**一个文件都不会被读**，不写任何快照，不再调用任何模型。
       注意此时**可能已经发生过一次 Scout 调用**：链路是 Map → 量 flat 目录 → Scout
       → 读取规划（路径判定在这里）→ 读取。Scout 只看描述符、看不到任何文件内容，
       因此那次调用与凭据无关，也不需要为它改流程——但不能笼统写成「不调用任何模型」。
       准确的表述是：不再调用**最终分析**。

② 内容净化自身抛错（读取之后）
     → 整次分析失败关闭：材料已经读进来了，但**不调用最终分析、不写任何快照**，
       绝不退回发送未净化的材料。
       —— 这里不能说「不读」，文件确实已经读过了。

共同保证（两个执行点都成立）
     → 不发送未经净化的材料、不持久化任何 RepositoryProfile。

路径命中第 1 层（不是失败）
     → 该文件不进入材料，以规划诊断的形式记一条 EXCLUDED_BY_SECRET_POLICY
       （只有路径——这一层从来没读过它的内容），并在聚合日志里记一个计数。
       同一个文件被多个聚焦区域提到时只记一条：诊断是文件的属性。
净化后材料为空
     → 与既有「读不出材料」同义，失败关闭。
```

**明确禁止：**

```text
静默丢弃命中内容而不留任何可观测计数
「净化失败就用原文发」这类降级
「先发出去，出问题再说」的重试策略
```

失败语义与既有链路对齐：与「读不出材料」同属「这次分析无法完成」，因此翻译成
`RepositoryNotAnalyzableException`（409），而不是掉进「未知服务端故障」。原因标识为
`SECRET_BOUNDARY_FAILED`，原始失败留在 cause 里。对外只表达「当前无法分析」，
响应体里不含任何内容、路径或内部原因标识——`ApiExceptionHandler` 返回固定文案。

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

### 预算与选材语义

两个执行点对既有预算的影响**不同**，必须分开说：

```text
第 1 层（路径排除）
  它**必然改变读取之前的合格候选集合**——被排除的文件不再参与选材。
  这正是它的目的（「不读它」而不是「读了不用」），所以「合格候选集合变了」
  不是副作用，是定义的一部分。
  但它不改变 maxFiles / maxFileBytes / maxTotalBytes 本身，也不改变选材顺序：
  剩下的候选按原来的类别轮转与路径顺序参与，只是少了几项。
  它也不占用名额：排除发生在候选进入轮转之前，因此被挡下的文件不会让
  后面那个安全的候选失去机会。

第 2 层（内容净化）
  **不改变读取预算语义**。它作用于已经读完、已经通过执行期尺寸复核的材料，
  不新增或减少文件数，不改变 maxFiles 的判断。
  读取阶段的尺寸复核依据的是**真实内容**的字节数；净化发生在那之后，
  因此既不会让一个原本放得下的文件变得放得下，也不会反过来。
  净化的确会改变文本长度——替换标记与被替换的取值哪个更长并不确定，短取值换成长标记
  时文本会**变长**。但这不影响任何结论：尺寸复核早已完成，净化之后的长度不再参与判定。
```

两条通道的预算与轮转逻辑一字未改：本 ADR 不动 `maxFiles` / `maxFileBytes` /
`maxTotalBytes`，也不动任何顺序策略。

## Downstream Propagation Reasoning

问题：把凭据挡在第一次出机器之前，是否足以阻止**源出**凭据出现在
RepositoryProfile / Evidence / SQLite / API 响应 / 后续 Product Direction Prompt？

**结论：足够。不需要额外的 post-model 过滤器。**

推理依据是一条代码事实加一条推理：

```text
事实   净化发生在 create 请求之前，因此模型在整个分析过程中看到的内容已经过边界。
推理   模型能复述的，是它看到过的；它没有见过未经净化的原文，
       因此**来自该仓库**的未净化凭据在它的输出里没有来源。
       下游所有环节（Profile 字段 → SQLite → API → 下一次 Prompt）都只是模型输出的搬运，
       源头没有，下游就不可能出现。
```

措辞上要避免两处过头：这不是「生成模型绝不可能输出某个相同字符串」（见 §不保证 N6），
也不是「只要过滤了就一定干净」——它成立的前提是**适用规则命中了那份凭据**
（见 §不保证 N1）。

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

## Test Strategy（已落地的矩阵）

全部使用合成金丝雀（canary），不使用任何真实凭据。金丝雀是形如
`CANARY-<随机后缀>` 的字面量，加上各规则的代表形态（PEM 块、`ghp_` 前缀串、
`password: …` 赋值等）。

全部使用合成金丝雀（`CANARY-…` 形态），不使用任何真实凭据。落地情况：

| # | 用例 | 落在哪里 | 断言 |
|---|---|---|---|
| T1 | Foundation 里的金丝雀 | `AnalyzeRepositoryUseCaseTest.sendsOnlySanitizedRepositoryMaterialToTheFinalAnalyzer` | **这次调用真正发给 AI 的那条请求**里不含金丝雀，且含替换标记（证明是替换而非没读） |
| T2 | 定向源码里的金丝雀 | 同上 | 同一条请求里同样不含——两条通道同受保护 |
| T3 | `.env` / `*.pem` 出现在仓库里 | `RepositoryUnderstandingTest.neverReadsAPathExcludedByTheSecretPolicy`、`AnalyzeRepositoryUseCaseTest.neverReadsACredentialFileExcludedByThePolicy` | 该路径**从未被 readFile 调用**（Workspace 替身记录），且不在材料里 |
| T4 | 结构保留 | `DeterministicRepositorySecretPolicyTest.keepsStructureSoTheMaterialStaysUseful` / `keepsRelativePathUnchanged` | 行数、缩进、键名、非凭据值完好；相对路径不变 |
| T5 | Region / File Scout 阶段 | 既有用例（`RepositoryScoutExtractionTest`、分层 Scout 用例） | 两阶段只发描述符，边界没有改动它们 |
| T6 | 回显式替身 | `AnalyzeRepositoryUseCaseTest.doesNotPropagateSourceSecretsIntoTheProfile`、`RepositoryAnalysisApiIntegrationTest.neverSendsRepositorySecretsToTheProvider` | 落库的 `RepositoryProfile` / Evidence 与 HTTP 响应里都没有金丝雀，同时**确实**带回了已净化的材料 |
| T7 | 边界自身的输出 | `RepositoryAnalysisApiIntegrationTest.logsSafeAggregatesWithoutFilePaths`、`ApiExceptionHandler` 的既有断言 | 日志只有聚合计数、没有金丝雀与路径；`secret 包没有 logger` |
| T8 | 占位符与疑似误伤 | `replacesPlaceholderShapedValuesToo` / `doesNotCorruptOrdinaryIdentifiers` / `doesNotExcludeNamesThatMerelyContainThePattern` / `doesNotExcludePublicKeys` | 凭据位置一律替换（含示例值）；`tokenCount`、`password.equals(...)`、`about.env.md`、`id_rsa.pub` 不被误伤 |
| T9 | 失败语义 | `RepositoryUnderstandingTest.failsClosedWhen*`、`AnalyzeRepositoryUseCaseTest.doesNotSaveProfileWhenTheSecretBoundaryFails`、`RepositoryAnalysisSecretBoundaryFailureApiTest` | 不调用最终分析、不写快照、对外 409 且响应体无内部标识/路径/内容 |
| T10 | 装配 | `RepositoryAnalysisWiringTest.wiresTheRepositorySecretPolicy` | 生产装配里存在凭据政策 Bean，且是规则写死在代码里的实现 |
| T11 | 规则本身 | `DeterministicRepositorySecretPolicyTest`（22 个用例） | 路径规则、内容规则、占位符策略、确定性与替换计数 |
| T12 | 两个执行点 | `RepositoryReadPlannerSecretPolicyTest`（6 个用例） | 两条通道都过政策；被排除项不占名额、不影响其余顺序 |

T1 / T2 是核心用例：断言对象是**端口的真实入参**，不是任何中间表示。
T3 是第 1 层与第 2 层的分界证据：它证明的是「没有被读」，而不是「读了又换掉」。

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

## Implementation Record（Task 10B-2）

```text
secret/RepositorySecretPolicy                 政策接口：excludes(path) + sanitize(files)
secret/DeterministicRepositorySecretPolicy    规则写死在代码里的实现；纯函数，无 logger
secret/SanitizedRepositoryMaterial            模型可见材料 + 替换计数
secret/RepositorySecretBoundaryException      边界无法安全完成时的失败出口

RepositoryReadPlanner                         ① 在两条通道构候选队列时排除整份凭据文件
RepositoryUnderstanding                       ② 返回之前净化；两处失败都在这里翻译成
                                              RepositoryNotAnalyzableException
RepositoryAnalysisUseCaseConfiguration        政策 Bean，注入上面两处（同一个实例）
```

运行时观测（一行聚合日志，不含路径与内容）：

```text
operation=repository-analysis … secretExcludedCount=N sanitizedSpanCount=M
```

替换标记固定为 `[redacted-credential]`，代码中只有一个常量。

## Revisit Conditions

```text
1. 在真实仓库上观察到规则集的 FP / FN
     → 用真实数据校准规则；不因为「看起来够用」而定案。
       规则集会随着新出现的令牌形态而过时，这是它最需要被重访的地方。

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
