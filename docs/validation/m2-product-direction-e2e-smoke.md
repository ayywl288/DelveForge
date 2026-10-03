# M2 — Product Direction 端到端 Smoke（M2 收尾验证）

**Status:** Validation Record / **Not Source of Truth**
**Last Updated:** 2026-10-03
**Validated Revision:** `main` @ `7d47d97`（Repository Analysis V3 已冻结）
**性质:** M2 **产品价值**验证，不是又一次 Repository Analysis 压力测试。
**依据:** `PRODUCT.md`、`DOMAIN_MODEL.md`、`ARCHITECTURE.md`、`ROADMAP.md`、
`docs/validation/m2-product-direction-discovery-smoke-test-round1.md`（结构性质的对照基线）

> 本文档记录一次真实端到端运行**实际得到了什么**。方向内容属于**那一次模型输出**，
> 不可复现；可复现的是结构性质（数量、状态、basis 正确性、原子性、生命周期）。
> 历史验证记录未被改写。

---

## 1. 环境与真实输入

```text
Backend     java -jar delveforge-app-0.1.0-SNAPSHOT.jar（main @ 7d47d97）
Provider    真实 DeepSeek（凭据由环境变量提供，本记录不含任何凭据）
数据库      临时 SQLite（Flyway 迁移到 V6）
仓库        5 个本地快照中的 3 个；未使用 langflow（已是接受的 Repository Analysis 边界）
```

### 1.1 三个仓库的 revision（未更换）

| 逻辑名 | analyzedRevision | 分析耗时 |
|---|---|---|
| hm-dianping | `8a5fa2b607ede3666ab0df1c96edf851aa4293e5` | 31.0 s |
| mall | `3910bf80a9723b165e707f25649d6befb517ee53` | 85.2 s |
| memos | `aea105e081c97dcd45eea25adcf2b69d89c8e4ef` | 128.6 s |

三个 revision 与之前所有轮次一致。

---

## 2. 唯一的 Confirmed UserProfile（三个仓库共用）

建立方式：真实 User Discovery 链路——`POST /api/user-profiles` → 三次
`POST /api/user-profiles/{id}/discovery-turn`（真实模型调用）→ `POST /{id}/confirm`。

```text
userProfileId  0a5606e9-b691-4b0f-9a6f-9f1a29c9135e
revision       23
status         CONFIRMED
evidence       14 条（sourceType = USER_INPUT，sourceRef 是三轮原始输入）
```

六个区（模型从三轮输入中提取，未做人工修改）：

| 区 | 条数 | 内容 |
|---|---|---|
| interests | 2 | 记账/个人财务管理 · 写博客记录学习过程 |
| behaviors | 2 | 用手机记账 App 记录日常花销 · 写博客记录学习过程但经常写完留在草稿箱未发布 |
| painPoints | 3 | 记账数据存在别人的服务器上自己拿不到 · 想导出记账数据时很麻烦 · 博客写完常丢在草稿箱 |
| technicalCapabilities | 3 | 主要使用 Java 和 Spring Boot · 会使用 Redis 但自认为还不够精通 · 前端能力较弱，基本只能改现成页面 |
| projectGoals | 4 | 想要数据完全在自己手里的记账方式 · 做能掌控数据、方便导出的记账工具 · 希望是每天真的会用起来的产品而不是 demo · 希望在面试时讲清楚技术难点 |
| constraints | 2 | 前端能力较弱 · 可投入时间不多，基本只有晚上和周末 |

**这一个 Profile 在三次发现中一字未改**——这正是本轮要测的不变量。

---

## 3. 三个 RepositoryProfile（本轮真实分析产出）

| 仓库 | RepositoryProfileId | purpose（摘要） | modules | capabilities | reusableAssets | evidence |
|---|---|---|---|---|---|---|
| hm-dianping | `30f4324d-…` | 基于 Spring Boot 的本地生活点评后端，完成三级缓存 / 秒杀事务消息 / 分布式锁的高并发重构 | 10 | 11 | 12 | 27 |
| mall | `96dbab6f-…` | 基于 Spring Boot + MyBatis 的电商系统后端：后台管理系统 API、前台商城 API 与基于 Elasticsearch 的商品搜索服务 | 7 | 12 | 16 | 37 |
| memos | `673893eb-…` | 自托管短笔记（memo）应用：附件/评论/反应/Space 协作、多数据库、Connect/gRPC API 与 React 前端 | 15 | 19 | 12 | 34 |

三份 Profile 的**可复用资产**分别是：

```text
hm-dianping  三级缓存读链路 / 有界等待防击穿 / RocketMQ 事务消息 + 消费端幂等 /
             Redis Lua 原子预扣与库存补偿 / 分布式锁 / 全局 ID / 对账与压测脚本 / 多级缓存失效广播
mall         mall-common 统一响应与分页（CommonResult/CommonPage/ResultCode）/ Elasticsearch 商品搜索
             （EsProduct、ik_max_word、nested、聚合）/ JWT + 动态鉴权 / 订单服务一致性处理 / 容器化编排
memos        memo 数据模型与 markdown 引擎（属性提取/附件引用校验）/ CEL 可复用视图与过滤编译 /
             三套数据库驱动与迁移 / Connect/gRPC 契约 / MCP 服务端 / 分享链接与 webhook /
             传输无关的读授权决策 / React Query 乐观更新与 SSE
```

**资产差异是真实存在的**——这一点决定了 §6 的跨仓库比较不是文字游戏。

---

## 4. 三个仓库的 ProductDirection 输出

> 发现请求固定为 `{userProfileId, expectedRevision: 23, repositoryProfileIds: [该仓库]}`。
> 每次发现都是一次独立的真实模型调用。

### 4.1 hm-dianping（5 个方向）

| # | 标题 | 复杂度 | 风险 | 依据槽（userNeed/userFit/reusableCapability） |
|---|---|---|---|---|
| 1 | 自托管个人记账与一键导出的「数据自有账本」 | 中 | 4 | 6 / 6 / 8 |
| 2 | 记账数据迁移与对账流水线：把旧 App 导出变成可信本地账本 | 中高 | 4 | 5 / 4 / 9 |
| 3 | 草稿箱清空器：把学习记录从草稿推到发布的打卡工具 | 中 | 4 | 2 / 6 / 5 |
| 4 | 记账月报看板：用多级缓存把个人账本查询做成面试难点 | 中高 | 4 | 3 / 5 / 10 |
| 5 | 家庭共享自托管账本：多设备并发写入下的数据自持 | 中高 | 4 | 4 / 5 / 7 |

**它用到的 hm-dianping 专属资产**（取自 `differentiation` / `technicalValue` / `reusableCapability` 槽）：

```text
D1  三级缓存、Redis token 会话与请求级续期、MyBatis-Plus 持久化建模、分页/流式导出
D2  RocketMQ 事务消息、消费端主键幂等与状态门禁、可恢复异常重投、五项零超卖对账脚本
D4  三级缓存读链路（Caffeine L1 → Redis L2 → MySQL L3）、防穿透/击穿/雪崩
D5  分布式锁、缓存失效广播、多设备并发写入的一致性处理
```

### 4.2 mall（4 个方向）

| # | 标题 | 复杂度 | 风险 | 依据槽 |
|---|---|---|---|---|
| 1 | 自托管记账核心 + 一键导出归档服务 | 中 | 3 | 3 / 6 / 6 |
| 2 | 离线优先记账同步层：幂等写入与冲突合并服务 | 中高 | 3 | 3 / 4 / 4 |
| 3 | 账目检索与聚合分析服务：把商品搜索资产迁到个人财务 | 中高 | 3 | 3 / 3 / 4 |
| 4 | 草稿清空器：自托管博客草稿自动发布管道 | 低到中 | 3 | 2 / 3 / 7 |

**它用到的 mall 专属资产**：

```text
D1  mall-common 的分层结构、CommonResult 统一响应、CommonPage 分页契约
D2  OmsPortalOrderServiceImpl 的库存校验与锁定、订单号生成、超时取消等一致性处理思路
D3  EsProduct 索引定义（ik_max_word 分词、attrValueList 为 nested）、EsProductController 的
    导入/搜索/筛选/排序/聚合接口、docker-compose-app.yml 的部署编排
D4  JwtTokenUtil + JwtAuthenticationTokenFilter、DynamicSecurityService / DynamicAuthorizationManager、
    logstash.conf 日志收集
```

`D3` 的差异化原文点明了迁移路径：
「原仓库用搜索索引做商品搜索与品牌、分类、属性的聚合；本方向把同样的导入、nested 字段、
过滤、排序、聚合能力迁移到账目领域」。

### 4.3 memos（5 个方向）

| # | 标题 | 复杂度 | 风险 | 依据槽 |
|---|---|---|---|---|
| 1 | 把 memo 变成账目的自托管记账后端（账目即 memo） | 中 | 3 | 6 / 5 / 7 |
| 2 | 草稿到发布的「零摩擦发布」流水线 | 中低 | 3 | 2 / 4 / 8 |
| 3 | 本地优先的定期导出与归档守护服务 | 中 | 3 | 5 / 4 / 6 |
| 4 | 用已有 AI 客户端查自己的账（本地财务 MCP 服务） | 中 | 3 | 4 / 4 / 5 |
| 5 | 从现有记账 App 迁入并往返导出的数据迁移管道 | 中 | 3 | 4 / 4 / 6 |

**它用到的 memos 专属资产**：

```text
D1  memo 数据模型、markdown 属性提取（标题/链接/代码/任务）、CEL 可复用视图与过滤编译为 IR 再按驱动渲染 SQL、
    proto/gen 与三套数据库迁移
D2  编辑器对外契约 EditorController、React Query 乐观更新与回滚、memo 读授权（PRIVATE/PROTECTED/PUBLIC/SPACE）、
    webhook 投递与 SSEHub 实时推送
D3  store.Driver 持久化接口与三套数据库读取语义一致、对象存储客户端、容器入口的非 root 降权与 *_FILE 密钥注入
D4  MCP 服务端模式与生成的 Connect 契约、CEL 过滤白名单、传输无关的读授权决策
D5  多数据库建表脚本与迁移、附件存储（MEDIUMBLOB / BYTEA）
```

**合计**：14 个 ProductDirection，全部初始为 `CANDIDATE`，共 **207 条 EvidenceBasis**、47 条 risk。

---

## 5. 产品价值评估

### A. 用户个性化（是否有可见的用户依据）

**是**，而且是逐条落到具体依据上的——不是泛泛的「适合你」。最清楚的一例是
**hm-dianping D1 的 `userFit`**：

> 用户主要使用 Java 和 Spring Boot，会 Redis 但自认为不够精通，前端弱到基本只能改现成页面；
> 可投入时间只有晚上和周末，**适合后端为主、页面简单的项目**。

它把 `technicalCapabilities` 与 `constraints` **合成了一条取舍**（后端为主、前端最小化），
而不是各自复述一遍。同一方向的 `targetProduct` 直接落在这条取舍上：
「前端只做简单页面或复用现成模板，重点在后端与数据归属」。

其它区的可见使用：

```text
painPoints           14 个方向的 problem 全部直接引用用户痛点：11 个落在
                     「数据在别人服务器上 / 导出麻烦」，3 个落在「博客草稿发不出去」
                     （二者有重叠，例如 memos D5 同时覆盖迁移与导出）
projectGoals         「面试讲技术难点」出现在 10 个方向的 userFit 里，且都指定了**具体**难点；
                     「每天真的会用而不是 demo」出现在 2 个方向的 userFit 里
                     （作为 userNeed 依据时出现得更多，见 §7 的 115 条 USER_PROFILE 依据）
constraints          「只有晚上和周末」直接进入风险与复杂度判断
                     （例如 mall D1 的 targetProduct 选择「后端 CRUD + 导出，前端最小化」）
behaviors            所有「记账」方向都以「用户已有每天记账的习惯」为理由选择
                     **演化现有工具**而非从零学习一种新方式
```

反向验证：**没有任何一个方向是「只看仓库」就能生成的**——若把用户换成另一个 Profile，
这 14 个 problem 的措辞与取舍都不成立。

### B. 资产接地（是否建立在仓库真实具备的能力上）

**是**，且每个方向都能指出它打算演化**哪一个具体资产**：

| 方向 | 它要演化的现有资产 |
|---|---|
| hm D1 | 三级缓存读链路 + Redis 会话（`MultiLevelCacheServiceImpl`、`IMultiLevelCacheService`） |
| hm D2 | `SeckillOrderTransactionListener` + `SeckillOrderConsumer` 的事务消息与幂等消费模板 |
| hm D4 | 三级缓存 + 防穿透/击穿/雪崩整套读链路 |
| mall D1 | `CommonResult` / `CommonPage` / `ResultCode` 统一响应与分页契约 |
| mall D3 | `EsProduct` 索引定义与 `EsProductController` / `EsProductServiceImpl` 的搜索与聚合 |
| mall D4 | `JwtTokenUtil` + `DynamicAuthorizationManager` 动态鉴权 |
| memos D1 | memo 数据模型 + markdown 属性提取 + CEL 视图过滤编译 |
| memos D2 | `EditorController` 编辑器契约 + memo 读授权 + webhook/SSE |
| memos D4 | `server/mcp` 的 MCP 服务端模式与 Connect 契约 |

**没有发现「不看仓库也能生成」的方向**：`differentiation` 一律写成
「原项目是 X（具体能力），本方向保留 Y，把领域换成 Z」。

### C. Evolution Before Rewrite

**是**。14 个方向全部呈现「现有资产 → 增量演化 → 新目标产品」的形状，没有一个是
「忽略仓库、从零建一个无关项目」。三种典型的演化关系：

```text
复用同类资产、换领域       mall D3：商品搜索索引 → 账目检索与聚合（nested 字段、分词、聚合全部迁移）
复用一致性机制、换业务     hm D2：秒杀订单的事务消息与对账 → 个人记账 ETL 与账实核对
复用产品外壳、加一层       memos D1：memo 作为流水载体，扩金额/类别/账户字段；前端复用现有列表与编辑器
```

难度分层也存在：既有 `低到中`（mall D4 草稿发布），也有 `中高`（hm D2/D4/D5、mall D2/D3），
说明模型没有把所有方向都压成"照抄一遍"。

### D. 差异化（是否讲清「为什么这不是一个教程项目」）

**普遍成立**，`differentiation` 都有具体对照物，不是形容词。两个最好的例子：

```text
memos D1  «Memos 本身是通用短笔记应用，没有金额、类别、账户等财务语义，也没有金额聚合与
          面向记账工具的导出格式；这个方向把 memo 从自由文本演化为有约束、可聚合、可导出的
          账目实体，产品定位从「笔记」变成「个人账本」。»

mall D1   «原仓库是面向商家的电商交易系统，核心是商品、SKU、订单与支付；本方向保留通用的分层结构、
          统一响应、分页与鉴权骨架，去掉商品、订单、支付、搜索等业务资产，把领域整体换成
          个人账目与数据导出归档。»
```

**弱点（如实记录）**：`technicalValue` 里有一部分是「可以讲」的技术清单
（例如「可以讲 Spring Boot + MyBatis-Plus + MySQL 的持久化建模」），
对面试而言偏常见；真正有区分度的是那些点明**原仓库独有机制**的条目
（幂等键设计、事务消息状态门禁、CEL→IR→SQL 渲染、传输无关读授权）。
模型在两类之间没有做更强的高低排序——但本轮的 Product Direction 契约也**不要求**它排序，
排序属于用户选择，不属于系统推荐。

### E. 技术价值（是否对用户声明的方向有用）

**是**。用户声明的是「后端为主、Java/Spring、Redis 想更精通、要在面试讲难点」，
方向给出的技术点基本都落在这一区间，且**多数与仓库已有机制直接相关**：

```text
在用户能力范围内、又确实有深度    RocketMQ 事务消息与消费端幂等（hm D2）
                                  CEL 过滤编译 + 三套数据库读语义等价（memos D1/D3）
                                  幂等键与冲突合并（mall D2）
偏离用户技术栈的地方都有交代      memos D1/D4 是 Go 项目，而用户栈是 Java；
                                  模型没有回避，而是把价值定位在「协议设计 / 授权边界 / 过滤表达式映射」
                                  这类与语言无关的设计问题上，并把它列进风险（依赖面、升级维护成本）
```

---

## 6. 跨仓库比较（本轮最重要的部分）

### 6.1 相同用户 + 不同仓库 → 方向确实不同

```text
因为它是 hm-dianping 才出现的方向
    D2 记账数据迁移与对账流水线   ← 只有它有一套「事务消息 + 幂等消费 + 五项零超卖对账脚本」
    D4 月报看板复用多级缓存        ← 只有它有 Caffeine→Redis→MySQL 三级读链路与防穿透/击穿
    D5 多设备并发写入的一致性      ← 只有它有分布式锁与缓存失效广播

因为它是 mall 才出现的方向
    D3 账目检索与聚合              ← 只有它有 Elasticsearch 商品搜索（ik 分词、nested、聚合）
    D4 草稿发布管道复用动态鉴权     ← 只有它有 JWT + DynamicAuthorizationManager + 日志收集
    （D1 的差异化落在 CommonResult/CommonPage 与分层模块结构上）

因为它是 memos 才出现的方向
    D1 账目即 memo                ← 只有它有 memo 数据模型 + markdown 属性提取 + CEL 视图
    D2 零摩擦发布流水线            ← 只有它有 EditorController 契约 + 读授权 + webhook/SSE
    D3 归档守护服务                ← 只有它有三套数据库驱动与对象存储客户端
    D4 本地财务 MCP 服务           ← 只有它有 server/mcp 与生成的 Connect 契约
    D5 数据迁移管道                ← 只有它有多数据库迁移与附件存储差异（MEDIUMBLOB/BYTEA）
```

### 6.2 但**目标产品**确实收敛——必须如实说明

受同一个 Profile 驱动，两个主题在三个仓库里都出现了：

| 主题 | hm-dianping | mall | memos |
|---|---|---|---|
| 自托管记账/数据自持 | D1、D4、D5 | D1、D2、D3 | D1、D3、D4、D5 |
| 博客草稿发布 | D3 | D4 | D2 |

这是**符合预期**的：用户明确要「一个自己能掌控数据、方便导出的记账工具」和「草稿发不出去」
这两个痛点，任何负责任的方向集都应该围绕它们。**真正的差异体现在「怎么到达那里」**：

```text
同一个「自托管记账」目标，三条完全不同的演化路径：
  hm-dianping → 用三级缓存与秒杀一致性资产，路径是「读链路性能与并发写入」
  mall        → 用通用响应/分页骨架与搜索/鉴权资产，路径是「检索聚合、离线同步、动态鉴权」
  memos       → 用 memo 数据模型与 MCP/CEL 资产，路径是「把笔记实体演化为账目、用协议层替代前端」
```

**结论：没有塌缩成「同一批通用主题换措辞」。**判定依据不是标题相似度，而是
① `differentiation` 引用的对照物互不相同、② `reusableCapability` 槽引用的资产互不相同
（207 条依据里 92 条是 REPOSITORY_PROFILE 依据，全部指向各自仓库的 Profile，
见 §7 的机械校验）、③ 风险项也各不相同（hm 的风险是缓存一致性与中间件运维，
mall 的是过度设计「个人记账数据量小，引入搜索索引/消息队列可能过度工程化」，
memos 的是 Go 依赖面与 proto 生成链路）。

---

## 7. Evidence / basis 机械校验

对全部 14 个方向、207 条 EvidenceBasis 逐条核对：

```text
userProfileId        14/14 == 本轮 Profile（0a5606e9-…）
userProfileRevision  14/14 == 23
repositoryProfileIds 14/14 == 该仓库本轮唯一使用的 RepositoryProfile
candidateAssetIds    14/14 == 该仓库本轮登记的唯一 SoftwareAsset
初始 status          14/14 == CANDIDATE

USER_PROFILE 依据      每条 origin.userProfileId / userProfileRevision 正确，
                       且 claim 确实存在于本轮 UserProfile 的 14 条 evidence 中
REPOSITORY_PROFILE 依据 每条 origin.repositoryProfileId 正确，
                       且 claim 确实存在于该仓库 RepositoryProfile 的 evidence 中
```

```text
校验结果：14 directions / 207 bases / 0 problems
```

**没有发现**：过期 revision、张冠李戴的 RepositoryProfile、不存在的 SoftwareAssetId、
指向不可用依据的 evidence。

---

## 8. 生命周期 Smoke（hm-dianping 的 5 个方向）

| 步骤 | HTTP | 5 个方向的状态（按 D1…D5） |
|---|---|---|
| 初始 | — | CANDIDATE · CANDIDATE · CANDIDATE · CANDIDATE · CANDIDATE |
| select D1 | 200 | **SELECTED** · CANDIDATE · CANDIDATE · CANDIDATE · CANDIDATE |
| select D2（已有 SELECTED） | 200 | **SUPERSEDED** · **SELECTED** · CANDIDATE · CANDIDATE · CANDIDATE |
| reject D3 | 200 | SUPERSEDED · SELECTED · **REJECTED** · CANDIDATE · CANDIDATE |
| reject D1（已 SUPERSEDED） | **409** | 状态未变 |
| select D3（已 REJECTED） | **409** | 状态未变 |

结论：

```text
• 全局同时只有一个 SELECTED：第二次 select 让原方向自动进入 SUPERSEDED（INV-D09），不是「第二个 select 失败」
• 只有 CANDIDATE 可被 reject；只有 CANDIDATE 可被 select —— 两条非法转换都返回 409 且不改状态
• 未选中/未拒绝的方向保持 CANDIDATE 不变
• GET 回读的状态与 POST 返回一致，且 5 个方向的状态互不串扰
```

---

## 9. 失败与持久化一致性

**basis 不成立时的拒绝**（这些都在调用模型之前失败）：

| 场景 | HTTP | code |
|---|---|---|
| `expectedRevision` 过期（传 22，实际 23） | 409 | CONFLICT |
| `repositoryProfileIds` 指向不存在的快照 | 404 | NOT_FOUND |
| `userProfileId` 不存在 | 404 | NOT_FOUND |
| `expectedRevision` 缺失 | 400 | INVALID_REQUEST |

**没有留下部分结果**——四次失败调用前后，相关表的行数完全不变：

```text
product_direction                    14 → 14
product_direction_evidence_support  207 → 207
product_direction_risk               47 → 47
```

**其余边界/破坏性场景沿用既有测试**（本轮没有新建故障注入套件）：
`ProductDirectionTest`、`ProductDirectionDiscoveryServiceTest`、
`DiscoverProductDirectionsUseCaseTest`、`SqliteProductDirectionRepositoryIntegrationTest`
覆盖状态机、领域拒绝、整批原子性与持久化冲突。

---

## 10. Findings

| 级别 | 内容 |
|---|---|
| **MINOR** | 部分方向的 `technicalValue` 混入通用技术清单（「可以讲 Spring Boot + MyBatis-Plus 的持久化建模」），对面试场景区分度弱。这是**模型表达层面的取舍**，不是链路缺陷；契约并不要求系统对方向排序，因此本轮不改 Prompt、不做调优。 |
| **OBSERVATION** | 三个仓库的目标产品主题有收敛（自托管记账、草稿发布）。这是固定用户基线的**正确**结果，不是塌缩——演化路径与所复用资产三者互不相同（§6.2 给出判定依据）。若将来需要更强的方向多样性，那是一个产品决策（是否引入「同一用户多方案差异化」约束），不属于本轮范围。 |
| **OBSERVATION** | 模型对「过度工程化」有自发判断：mall D2 自己指出「个人记账数据量可能很小，引入 RocketMQ 有过度工程化风险」，mall D3 同样指出搜索索引可能是过度设计，hm D2 指出本地部署 RocketMQ 对只想记账的用户运维成本偏高。这是**风险字段在起作用**，对用户挑选方向有实际价值。 |
| **OBSERVATION** | memos 是 Go 项目而用户技术栈是 Java：模型没有回避这个错配，而是把价值重新定位到与语言无关的设计问题（协议、授权边界、过滤表达式映射），并把依赖面与维护成本列为风险。 |

**没有 BLOCKER / IMPORTANT 级别的问题。** 未发现 correctness 或产品价值层面的失败。

---

## 11. 最终结论

```text
Can M2 be considered complete after this smoke?
YES

Reason:
同一个 Confirmed UserProfile @ revision 23 与三个真实软件资产配对后，
得到了 14 个彼此不同的 ProductDirection，且：
  · 每个方向都能指出它要演化的具体既有资产（无「不看仓库也能生成」的方向）
  · 每个方向都能指出它为什么不是通用/教程项目（differentiation 有具体对照物）
  · 207 条依据全部指向本轮真实的 UserProfile @23 与对应 RepositoryProfile，零错配
  · 生命周期（select / supersede / reject / 非法转换）与「全局唯一 SELECTED」完全符合领域规则
  · basis 不成立时在调用模型之前就被拒绝，且不留下部分结果

产品问题「同一个确认过的用户画像，配上三个不同的真实软件资产，
是否会产生有意义不同且依据扎实的产品方向」的答案是肯定的。
```

Repository Analysis V3 已在上一个 Task 冻结（`docs/validation/m2-repository-analysis-v3-stabilization.md`），
本轮没有触碰它，也没有重开任何已决设计。

---

## 12. 测试与验证

```text
./mvnw clean verify → BUILD SUCCESS
domain 178 / application 653 / infrastructure 108 / app 130 = 1069 tests，0 failures
```

本轮**没有修改任何生产代码、测试或配置**：本文档是唯一的代码库变更。

---

## 13. 复现

```text
1. ./mvnw -q -DskipTests package
2. java -jar delveforge-app.jar --delveforge.persistence.database-file=<临时路径> --server.port=8080
3. POST /api/user-profiles
   POST /api/user-profiles/{id}/discovery-turn × N   （真实模型调用）
   POST /api/user-profiles/{id}/confirm              {"revision": N}
4. 对每个仓库：
   POST /api/software-assets                          （location 指向本地 Repository）
   POST /api/software-assets/{id}/analysis            （真实 Git + 真实模型调用）
5. POST /api/product-directions/discovery
        {"userProfileId":"…","expectedRevision":23,"repositoryProfileIds":["…"]}
6. POST /api/product-directions/{id}/select | /reject
   GET  /api/product-directions/{id}
```

驱动脚本与原始产物留在 `target/`（未提交）。方向内容属于那一次模型输出，不可复现；
可复现的是结构性质。

---

## 14. 未改动确认

```text
生产代码 / 测试 / application.yml / Prompt               0 处改动
Repository Analysis 链路                                 未触碰（V3 已冻结）
三个测试仓库 HEAD 与工作树                                未变
真实凭据 / 凭据取值 / 宿主绝对路径                        本文档中均无
```
