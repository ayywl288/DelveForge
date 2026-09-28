# M2 Product Direction Discovery 真实链路验证记录 — Round 1

**Status:** 验证记录，非 Source of Truth
**Last Updated:** 2026-09-28
**Validated Revision:** M2 Task 1–6（Product Direction Discovery，含 API 与 Composition Wiring）
**Round:** 1 — 未经人工调优的真实表现

> 本文档记录 M2 Product Direction Discovery 在真实环境下的一次端到端验证。
>
> **它不是规范性文档。** 领域规则以 `DOMAIN_MODEL.md` 为准，架构规则以 `ARCHITECTURE.md`
> 与 `AGENTS.md` 为准，里程碑与优先级以 `ROADMAP.md` 为准。本文档只记录「实际观察到了什么」，
> 不新增规则、不替代上述任何文档。
>
> 它也不是「系统正确」或「产品假设成立」的证明。它验证的是这一条链路在一个具体用户画像与
> 一个具体仓库上确实可用，并如实记录它这次**给出了什么、没给出什么**。
>
> Round 1 刻意不做任何实现调整：本轮的目标是取得**未经人工调优**的真实表现。

---

## 1. Validation Scope

### 验证的真实链路

```text
real HTTP API
    ↓
real Controller（ProductDirectionController）
    ↓
real DiscoverProductDirectionsUseCase
    ↓
real DirectionDiscoveryExtraction
    ↓
real DeepSeek AiGateway adapter      ← 真实 Provider 调用
    ↓
real DirectionDiscoveryProposalParser
    ↓
real DirectionProposalResolver
    ↓
real ProductDirectionDiscoveryService（Domain Service）
    ↓
real ProductDirectionRepository
    ↓
real SQLite / Flyway / MyBatis-Plus
```

上游可信输入同样全部经真实链路建立：

```text
real User Discovery（3 次真实模型调用的 discovery-turn + confirm）
real Software Asset registration
real Repository Analysis（真实 Git + 真实 Provider）
```

没有 fake AiGateway、没有 mock Repository、没有测试专用捷径、没有手工构造的领域对象、
没有手工修改模型输出。

### 运行环境

```text
Backend   : java -jar delveforge-app-0.1.0-SNAPSHOT.jar（正常启动）
数据库    : 独立临时 SQLite（backend/delveforge-app/target/smoke-m2-r1/smoke.db）
            Flyway V1–V6 全部在本次运行中新建，不依赖任何既有数据库状态
Repository: E:/develop/java-redis/back-end/projects/hm-dianping（与 M1 smoke 同一仓库）
Provider  : 环境变量提供，本记录不含任何凭据
```

### 本次覆盖什么

- User Discovery → Review → Confirm 是否能在真实链路上得到 Confirmed UserProfile；
- 真实 Repository Analysis 是否能得到 RepositoryProfile @ analyzedRevision；
- Discovery 是否能在真实链路上完整执行，产出 3–5 个 CANDIDATE 方向；
- POST 返回值与 GET 持久化结果是否一致，各相关表是否真的新增了对应记录；
- 每个 EvidenceBasis 的 origin 是否指向本轮真实的 Profile / revision，Evidence 是否真实存在；
- 每个方向的内容质量、个性化程度、能力复用程度与相互差异；
- M1 已知的 material sampling 局限是否已经影响 M2 的方向质量。

### 本次不覆盖什么

- readiness / automatic discovery trigger（未实现）；
- Select / Reject、INV-D09 跨 Aggregate 协调（未实现）；
- 并发、异常路径、多轮 Discover 的稳定性（由自动化测试覆盖，见 `AGENTS.md` §10）；
- 真实前端展示与用户实际使用感受。

### 原始产物

本次运行的原始报文保存在 `backend/delveforge-app/target/smoke-m2-r1/r1/`（属于构建目录，
不提交）。目录被 `mvn clean` 清除后无法复现，正文已摘录全部判断依据。

---

## 2. 可信输入：User Profile

### 建立方式

通过真实 User Discovery HTTP 链路：`POST /api/user-profiles` 创建 → 三次
`POST /api/user-profiles/{id}/discovery-turn`（真实模型调用）→ `POST /{id}/confirm`。

```text
userProfileId  : 840d406d-e7aa-4df3-8764-01f6dbac12ae
revision       : 21
status         : CONFIRMED
evidence 数量  : 12
```

### 内容摘要（模型从三批用户输入中提取，未经人工修改）

```text
interests (2)
  个人记账与花销管理
  写博客记录学习过程

behaviors (3)
  使用手机记账 App 记录日常花销
  有写博客记录学习过程的习惯
  经常写完博客后留在草稿箱

painPoints (3)
  记账数据全部存放在第三方服务器上，自己无法直接掌控
  从记账 App 导出数据自己查看很麻烦
  博客写完后常丢在草稿箱，未发布或整理

technicalCapabilities (3)
  技术栈以 Java 和 Spring Boot 为主
  会使用 Redis，但自评不够精通
  前端能力较弱，只能修改现成页面

projectGoals (3)
  自己做一个能自主掌控数据、方便导出查看的记账工具或方案
  做一个自己每天真的会用起来的产品，而不是再做一个 demo
  项目能在面试时拿出来讲，并讲清楚其中的技术难点

constraints (3)
  目前记账依赖手机 App
  现有记账数据存储在他人服务器，导出查看受限
  能投入项目的时间不多，基本是晚上和周末
```

### 记录：第 1 轮 discovery-turn 失败

第一次 `discovery-turn`（也是内容最实质的一次输入，主题是「教程项目没有个人特色」）
返回 **502**：

```text
HTTP status   : 502
error code    : EXTERNAL_CAPABILITY_UNAVAILABLE
发生阶段      : UserProfileProposalParser.parse → AiJsonObjectReader.read
                （模型返回的内容不是「恰好一个 json 对象」）
可见安全信息  : 失败发生在 M1 的 User Discovery 解析边界，不属于 M2 链路
```

之后重试该输入时 Profile 已进入 `REVIEWING`，`discovery-turn` 按 §6.1 拒绝（409），
因此**这条输入最终没有进入 Profile**。本轮没有为了「凑一条更好的画像」而回退状态重跑。

这一点对本文档的解读有影响：用户画像里缺失了他最核心的那句诉求
（「想把它变成有我自己的东西」），而 M2 的方向只能依据画像里实际存在的内容。
这是一次真实链路的真实损失，不是本轮的人为简化。

---

## 3. 可信输入：Repository Profile

### 建立方式

`POST /api/software-assets` 登记 → `POST /api/software-assets/{id}/analysis`（真实 Git +
真实 Provider）。

```text
repositoryProfileId : 6ccf1abe-f03f-490f-9031-2c839196e8f0
assetId             : cdedabcf-0439-4531-a5e5-8dc1d21c5c2c
analyzedRevision    : 18e6b63cf218eca717cd00ecf4e3e0a12dccf5b4
                      （== 分析开始前 git rev-parse HEAD，成立）
purpose             : 基于 Spring Boot 的黑马点评单体点评系统，围绕商铺查询多级缓存与
                      秒杀下单链路做高并发重构，并配套 JMeter 压测、自动对账与面试/复盘文档
capabilities (9)    : 多级缓存读取 / 缓存穿透防御 / 击穿防护与分布式锁 / 缓存失效广播 /
                      秒杀下单链路（RocketMQ 事务消息）/ 三层幂等与防超卖 / 事务流水与自动
                      对账 / 压测编排与参数化 / 环境固化
reusableAssets (10) : 压测脚本与编排、对账/采样/注入脚本、CaffeineConfig、RocketMQConfig、
                      RocketMQ 容器编排、docs 文档体系
limitations (9)     : 压测与应用同机、无 MySQL 事务流水表、明文硬编码密码、脚本路径硬编码、
                      v1 报告作废、广播失败无告警、采样脚本快照 bug、对账窗口降级、强依赖 Redis
risks (7)           : 缓存一致性兜底 8s、自定义 @Bean 覆盖陷阱、凭据泄露、对账 TTL 依赖、
                      环境手动就绪、压测数字不可复现、Redis 重启丢状态
evidence 数量       : 26
分析耗时            : 27.7 秒
```

分析前后源仓库状态一致（HEAD 未变，`git status --porcelain` 行数不变）。

**注意 `capabilities` 与 `reusableAssets` 的构成**：前者含「三层幂等与防超卖」这类业务能力，
但它的 Evidence 自身写明「由文档描述，代码侧可见事务消息与对账脚本」；
后者的 10 项里没有一项是业务实现类。这与 M1 §5 记录的局限一致，也是 §9 判断的直接输入。

---

## 4. Discovery 执行

### 触发前状态

```text
product_direction                    0
product_direction_evidence_support   0
product_direction_repository_profile 0
product_direction_candidate_asset    0
product_direction_risk               0
```

### 请求依据（不含 secret）

```text
POST /api/product-directions/discovery
{
  "userProfileId": "840d406d-e7aa-4df3-8764-01f6dbac12ae",
  "expectedRevision": 21,
  "repositoryProfileIds": ["6ccf1abe-f03f-490f-9031-2c839196e8f0"]
}
```

### 结果

```text
HTTP status   : 201
总耗时        : 35.2 秒（含真实 Provider 调用）
返回 Direction: 5
status 集合   : {CANDIDATE}
id 列表       :
  45e29171-55ff-4d91-81b7-a68b592157f2
  7211730e-efe8-49c0-9f66-15e07cfa9922
  f9e7c854-7b33-4918-9f52-e3358f5ed7d1
  19ecb500-4344-4d04-9bf4-6915dd9433e7
  0f53a9d6-1137-4cae-b31d-b82de110f87c
```

预期形态全部成立（201 / 5 个 / 全部 CANDIDATE）。整轮运行中 M2 链路没有产生 ERROR 日志。

---

## 5. 持久化验证

### POST 与 GET 的一致性

对每个 Direction 执行 `GET /api/product-directions/{id}`，与 POST 响应做 JSON 语义比较
（按内容比较，不依赖字段文本顺序）：

```text
45e29171…  GET 200  semanticEqual=True  diff=[]
7211730e…  GET 200  semanticEqual=True  diff=[]
f9e7c854…  GET 200  semanticEqual=True  diff=[]
19ecb500…  GET 200  semanticEqual=True  diff=[]
0f53a9d6…  GET 200  semanticEqual=True  diff=[]
```

比较覆盖 `id` / `userProfileId` / `userProfileRevision` / `repositoryProfileIds` /
`candidateAssetIds` / 全部 recommendation content / `status` / `evidenceSupport`
（含每个 EvidenceBasis 的 Evidence 与 EvidenceOrigin）。

### 数据库新增记录

```text
product_direction                    5   （== 本轮方向数）
product_direction_repository_profile 5   （每个方向 1 条）
product_direction_candidate_asset    5   （每个方向 1 条）
product_direction_risk              15   （每个方向 3 条）
product_direction_evidence_support  54   （== 全部 EvidenceBasis 总数）
```

依据行（54）与实际生成的 EvidenceBasis 逐条对得上，没有只写 parent row 的情况。

### 每个方向的实际依据

```text
5/5  repositoryProfileIds == ["6ccf1abe…"]     未混入未使用的输入 Profile（本次只输入了一份）
5/5  candidateAssetIds    == ["cdedabcf…"]     与该 RepositoryProfile 的 assetId 一致
```

---

## 6. Evidence Traceability（机械核对）

对 54 个 EvidenceBasis 逐条核对：

```text
USER_PROFILE origin      userProfileId == 本轮 Profile
                         userProfileRevision == 21（本轮确认的版本）
                         Evidence 确实存在于该 Profile 的 Evidence 集合中
REPOSITORY_PROFILE origin repositoryProfileId == 本轮实际引用的 RepositoryProfile
                         Evidence 确实存在于该 RepositoryProfile 的 Evidence 集合中

合计 54 条，失败 0 条
```

机制层面完全成立：**每一条依据都真的来自它声称的地方，没有出现跨 Profile、跨 revision
或凭空的依据**。这是 INV-D06 与 §3.6 要求的那一半。

另一半——「这条依据在语义上是否支撑它所在的判断」——见 §7 与 §8，结论并不一样。

---

## 7. 逐条方向内容与判断

以下为模型真实输出，未经任何人工修正。`[UP@21]` 表示依据来自本轮 User Profile @ revision 21，
`[RP]` 表示来自本轮 Repository Profile。

### Direction 1 — id `45e29171-55ff-4d91-81b7-a68b592157f2`

```text
title               : 自托管个人记账服务：MySQL 为唯一真相、可随时导出
problem             : 用户用手机记账 App 记账，数据全部存放在第三方服务器上，想导出来自己
                      查看很麻烦，一直想自己弄一个能自主掌控数据、方便导出查看的记账工具或方案
targetProduct       : 自托管的个人记账后端服务：账目录入与查询走自己的 Spring Boot 单体 +
                      MySQL，统计查询用 Redis 缓存，数据可随时导出为 CSV/JSON；对外以 REST
                      接口 + 服务端渲染的简页面/CLI 为主
userFit             : 技术栈 Java + Spring Boot 对应现有单体选型；前端弱因此以接口和轻页面
                      为主；记账是他每天都会产生的行为；时间只有晚上和周末，复用已有工程骨架
                      压低启动成本
differentiation     : 保留工程化骨架（Spring Boot 单体结构、MyBatis-Plus 与分页插件、拦截器链、
                      Redis/Lettuce 与 RocketMQ 配置模板），但把业务域从「商铺查询 + 秒杀券」
                      整体替换为单用户财务数据域，不再复用商铺/优惠券/秒杀的任何业务语义
technicalValue      : 数据主权与导出格式设计（增量导出、幂等重放）；MyBatis-Plus 分页与聚合统计；
                      统计结果的缓存策略与失效；把明文硬编码的配置收敛为外置配置与凭据管理
estimatedComplexity : 中低
risks (3)           : 单用户数据量下多级缓存与消息中间件的收益难以自证，容易变成过度设计；
                      自建服务录入体验可能不如现有 App 顺手，存在做出来但不用起来的风险；
                      现有仓库密码明文硬编码，若不先处理会把已知风险带进新产品
```

**依据**

```text
userNeed (4)  [UP@21] 用手机记账 App 记录花销 / 数据全在别人的服务器上 /
                     从记账 App 导出数据自己查看很麻烦 / 一直想自己弄一个记账工具或方案
userFit  (4)  [UP@21] 技术栈主要是 Java 和 Spring Boot / 前端很弱，只能改改现成的页面 /
                     能投入的时间不多 / 想做一个自己每天真的会用起来的东西，不是再做一个 demo
reusableCapability (4)
              [RP] Spring Boot 单体应用与依赖清单（pom.xml）
              [RP] MVC 拦截器链包含 RefreshTokenExpirationInterceptor 与 LoginInterceptor
              [RP] MyBatis-Plus 注册分页插件，数据库类型为 MySQL
              [RP] 运行配置声明端口 8083、Tomcat 线程数、Lettuce 连接池 max-active=200、
                   RocketMQ NameServer/生产者组/回查次数/消费线程数
```

**判断**

| 维度 | 结论 | 理由 |
|---|---|---|
| 来自真实需求？ | 直接支撑 | problem 几乎逐字对应用户 painPoints 的三条，没有泛化成「开发一个平台」 |
| 目标是否具体？ | 是 | 自托管单体 + MySQL 为唯一真相 + CSV/JSON 导出 + REST/轻页面，能想象出成品 |
| userFit 是否引用本人？ | 是 | 引用的是技术栈、前端弱、时间约束、每日使用意愿，都是这个用户特有的组合 |
| 是否利用已有能力？ | 部分支撑 | 复用点全是**工程骨架**（依赖清单、拦截器、分页插件、运行配置），没有任何业务能力进入复用 |
| differentiation 是否真实？ | 是 | 明确说「不再复用商铺/优惠券/秒杀的任何业务语义」，如实描述了它是替换而非演化 |
| technicalValue 是否可展示？ | 是 | 数据主权与增量导出、分页聚合、缓存失效、凭据外置，都是能讲也能做的点 |
| 复杂度是否相符？ | 是 | 「中低」与「替换业务域、复用骨架」的实际规模相符 |
| 风险是否具体？ | 是 | 三条都指向这个方向的真实问题（过度设计、不用起来、沿用明文密码），不是通用模板 |

---

### Direction 2 — id `7211730e-efe8-49c0-9f66-15e07cfa9922`

```text
title               : 记账导出文件的异步导入管道：幂等去重 + 自动对账
problem             : 从记账 App 导出的数据自己整理很麻烦，批量导入时容易出现重复记录和账目
                      与明细对不上；需要一条能把导出文件可靠吃进来、重复导入不产生脏数据、
                      并能自证对账的链路
targetProduct       : 一个导入服务：上传第三方记账 App 导出的 CSV 后，先经 Redis/Lua 幂等键
                      去重、再由 RocketMQ 异步处理落 MySQL，最后输出一份自动对账报告
                      （导入条数、去重条数、落库条数与一致性校验）
userFit             : 会 Redis 但不精通，这个方向把已有「Lua 预扣 + 唯一索引 + 乐观锁」三层
                      幂等思路迁移到新场景；希望面试时能讲清技术难点，重复消费与幂等容易讲透；
                      时间有限，可直接复用已有脚本与消息配置
differentiation     : 复用秒杀链路的「幂等 + 防重 + 对账」技术骨架，但目标从「高并发下不超卖」
                      改为「批量导入不重复、账目可自证」；衡量指标从 QPS 变成导入正确性与对账通过率
technicalValue      : 为什么需要三层幂等；消息重复消费的边界；对账脚本如何用退出码驱动失败判定；
                      以及「事务流水只存在 Redis、没有 MySQL 流水表」这个已知薄弱点如何被修正
estimatedComplexity : 中
risks (3)           : 个人记账数据量小，引入 RocketMQ 属于重组件，必须在面试叙事中说明取舍；
                      照搬会把「对账依赖 Redis 无 MySQL 流水表」的薄弱点复制到新产品；
                      明文硬编码密码在涉及真实个人账目数据时后果更严重
```

**依据**

```text
userNeed (3)  [UP@21] 用手机记账 App 记录花销 / 从记账 App 导出数据自己查看很麻烦 /
                     一直想自己弄一个记账工具或方案
userFit  (3)  [UP@21] 会使用 Redis，但不敢说精通 / 能投入的时间不多 /
                     目标是在面试的时候能拿出来讲，最好能讲清楚里面的技术难点
reusableCapability (4)
              [RP] 自定义 RocketMQTemplate 并把 Producer 替换为 TransactionMQProducer…
              [RP] jmeter/check_seckill.py 五项对账检查与退出码（脚本）
              [RP] jmeter/count_orders.py 5 秒采样与 autocommit（脚本）
              [RP] docs/4.prompt_for_review-refactor.md 的面试追问点（文档）
```

**判断**

| 维度 | 结论 | 理由 |
|---|---|---|
| 来自真实需求？ | 部分支撑 | 用户说的是「导出查看很麻烦」，方向把它解读成「导入重复、账目对不上」——这是模型的延伸，不是用户说过的痛点 |
| 目标是否具体？ | 是 | CSV 导入 → Redis/Lua 去重 → RocketMQ 落库 → 对账报告，链路清楚 |
| userFit 是否引用本人？ | 部分支撑 | 「面试能讲清技术难点」确实来自用户；但「每天真的会用」这条未被引用，而这个方向恰恰不是每日触点 |
| 是否利用已有能力？ | 部分支撑 | 4 条依据里 2 条是压测/对账**脚本**、1 条是**文档**，只有 1 条是代码；「三层幂等」这个被声称复用的能力没有任何实现级依据 |
| differentiation 是否真实？ | 是 | 「指标从 QPS 变成导入正确性与对账通过率」是具体且真实的差异 |
| technicalValue 是否可展示？ | 是 | 幂等边界、重复消费、退出码驱动对账都是能讲透的点 |
| 复杂度是否相符？ | 是 | 「中」与「引入消息中间件的管道」相符 |
| 风险是否具体？ | 是 | 三条都命中该方向的真实取舍，包括「照搬薄弱点」这种自我批评 |

---

### Direction 3 — id `f9e7c854-7b33-4918-9f52-e3358f5ed7d1`

```text
title               : 个人数据查询的多级缓存层 + 缓存失效可观测
problem             : 用户希望快速查看和统计自己的账目数据，同时现有项目里缓存失效广播曾因
                      缺少消息转换器而从未真正发出、失败也无告警，缓存一致性只能靠 8 秒 TTL
                      兜底，这个缺陷需要一个能被看见、被验证的解法
targetProduct       : 可独立使用的多级缓存查询层：Caffeine(L1) → Redis(L2) → MySQL(L3) 承载
                      个人数据的统计读，RocketMQ 广播通知各节点清理本地缓存，并新增缓存失效
                      事件的观测与告警、命中率指标面板和失效链路的三段验证用例
userFit             : 会 Redis 但不精通，这个方向能把缓存穿透/击穿/一致性这些面试高频点吃透；
                      账目统计页是高频入口；时间有限，可直接复用已有的 Caffeine 与 RocketMQ
                      配置模板
differentiation     : 不动任何秒杀业务逻辑，只抽出缓存与广播这一层基础设施，并为它补上原项目
                      缺失的观测和告警能力；产品定位从「点评系统」变成「带可观测性的多级缓存
                      查询层」，把已知缺陷从遗留项升级成一等功能
technicalValue      : 多级缓存一致性与 8 秒脏数据窗口的权衡；自定义 @Bean 覆盖自动配置时的副作用；
                      用 Caffeine recordStats 输出命中率并做告警；广播链路三段验证方法
estimatedComplexity : 中
risks (3)           : 个人数据量下多级缓存收益难以用真实流量证明；告警依赖 RocketMQ 可用性，
                      再次静默失败时告警可能同样不触发；沿用被判定不能代表生产的压测口径会被追问
```

**依据**

```text
userNeed (3)  [UP@21] 记账数据全在别人的服务器上 / 从记账 App 导出数据自己查看很麻烦 /
                     一直想自己弄一个记账工具或方案
userFit  (3)  [UP@21] 会使用 Redis，但不敢说精通 / 想做一个自己每天真的会用起来的东西 /
                     目标是在面试的时候能拿出来讲，最好能讲清楚里面的技术难点
reusableCapability (4)
              [RP] CaffeineConfig：L1 本地缓存 maximumSize 10000/5000、expireAfterWrite 8s
              [RP] RocketMQConfig：替换 Producer 为 TransactionMQProducer 并补装 MessageConverter
              [RP] docs/10.bugfix-cache-eviction-broadcast.md：广播从未发出的缺陷与三段验证
              [RP] docs/4.prompt_for_review-refactor.md 的面试追问点（文档）
```

**判断**

| 维度 | 结论 | 理由 |
|---|---|---|
| 来自真实需求？ | **关系较弱** | 用户的需求是「自己掌控记账数据、方便导出」。方向把问题定义成「现有项目缓存广播缺陷需要可观测解法」——这个问题的来源是**仓库**，不是用户。用户的四条痛点里没有一条能推出「我想要一个多级缓存层」 |
| 目标是否具体？ | 是 | L1/L2/L3 + 广播 + 观测告警 + 命中率面板，边界清楚 |
| userFit 是否引用本人？ | 部分支撑 | 「Redis 不精通」「想每天用」「面试能讲」都真实；但用它来支撑「做一层缓存基础设施」是间接的 |
| 是否利用已有能力？ | **直接支撑** | 这是五个方向里对仓库能力引用最实的一个：CaffeineConfig 的具体参数、RocketMQ 配置、以及一个被文档完整记录的缺陷与验证方法 |
| differentiation 是否真实？ | 是 | 「只抽缓存层、不动秒杀业务、把已知缺陷升级成一等功能」是具体差异 |
| technicalValue 是否可展示？ | 是 | 脏数据窗口权衡、@Bean 覆盖副作用、命中率告警、三段验证，都是硬内容 |
| 复杂度是否相符？ | 是 | 「中」与「抽一层 + 补观测」相符 |
| 风险是否具体？ | 是 | 三条都指向该方向的真实软肋 |

**这个方向的形态值得单独记一笔**：它的**技术依据很强**（全部落在具体实现与具体缺陷上），
但**用户依据很弱**（用户的痛点是数据主权，不是缓存一致性）。如果只看
`reusableCapability` 会认为它质量很高；只有把 `userNeed` 一起读才会发现它是被仓库"推"出来的方向。

---

### Direction 4 — id `19ecb500-4344-4d04-9bf4-6915dd9433e7`

```text
title               : 可复现的压测与对账编排工具箱
problem             : 用户想在面试时拿出可信的技术证据，但现有仓库的 v1 压测报告因代码不一致、
                      口径混淆、环境未固化而整体作废，压测脚本还硬编码本机路径、依赖手动注入
                      token 与新建券，换机或代码漂移后结论无法复现
targetProduct       : 一键运行的压测与对账编排工具：容器化中间件拉起环境，按健康检查 → 阶梯
                      压测 → 自动对账 → 生成报告的流程执行，任一检查失败即中止并保留证据，
                      可挂到任意 Spring Boot 项目上重复使用
userFit             : 直接目标是面试时能拿出来讲、并讲清技术难点，这个方向产出的正是一套可展示
                      的证据链；时间只有晚上和周末，应复用已有编排与对账脚本；技术栈天然对口
differentiation     : 不做业务系统，而是把仓库里已经存在的压测编排、对账脚本与压测文档体系
                      产品化成一个工具；它解决的正是「v1 报告作废」这个已记录的问题，
                      衡量标准是可复现性而不是功能覆盖
technicalValue      : 压测口径与环境固化的定义方式；容器内存上限与 JVM/连接池参数的可复现配置；
                      自动对账五项检查与退出码驱动编排；如何在文档中诚实区分「同机回归对比」
                      与「生产容量结论」
estimatedComplexity : 中
risks (3)           : 泛化到其他项目时场景脚本仍需按业务重写；依赖 Docker 与中间件环境；
                      同机压测数字不能代表生产，措辞不严谨会重蹈 v1 数据作废的覆辙
```

**依据**

```text
userNeed (2)  [UP@21] 目标是在面试的时候能拿出来讲，最好能讲清楚里面的技术难点 /
                     能投入的时间不多，基本是晚上和周末
userFit  (3)  [UP@21] 技术栈主要是 Java 和 Spring Boot / 能投入的时间不多 /
                     想做一个自己每天真的会用起来的东西，不是再做一个 demo
reusableCapability (6)
              [RP] jmeter/run_v2.bat 编排顺序与中止条件（脚本）
              [RP] jmeter/check_seckill.py 五项对账检查（脚本）
              [RP] rocketmq/docker-compose.yml 容器编排（部署）
              [RP] docs/1.stress_testing_report.md（文档）
              [RP] docs/6.stress_test_plan_v2.md（文档）
              [RP] docs/10.bugfix-cache-eviction-broadcast.md（文档）
```

**判断**

| 维度 | 结论 | 理由 |
|---|---|---|
| 来自真实需求？ | **关系较弱** | 两条 userNeed 依据是「面试能讲」和「时间不多」。这两条能推出「做一个适合讲的东西」，但推不出「做一个压测工具箱」——用户从未表达过对压测、对账或工具化的兴趣 |
| 目标是否具体？ | 是 | 「一键跑：健康检查 → 阶梯压测 → 自动对账 → 报告」边界很清楚 |
| userFit 是否引用本人？ | **关系较弱，且存在语义错配** | 它引用了「用户想做一个自己**每天真的会用起来**的东西，不是再做一个 demo」来支撑一个**压测工具箱**。这条依据的实际含义与该方向恰恰相反：没有人每天用压测工具。依据的存在性成立，支撑关系不成立 |
| 是否利用已有能力？ | 直接支撑 | 6 条依据全部指向仓库里真实存在的脚本、部署文件与文档，是本轮对仓库**工件**引用最密集的方向 |
| differentiation 是否真实？ | 是 | 「不做业务系统，把已有压测体系产品化」是明确的差异 |
| technicalValue 是否可展示？ | 是 | 环境固化、参数可复现、退出码编排、口径诚实性，都能讲 |
| 复杂度是否相符？ | **存疑** | 「中」这个判断偏乐观：容器化一键环境 + 可复现配置 + 对账编排 + 报告生成，且要泛化到其它项目 |
| 风险是否具体？ | 是 | 三条都指向真实困难，包括对自身价值的怀疑 |

---

### Direction 5 — id `0f53a9d6-1137-4cae-b31d-b82de110f87c`

```text
title               : 博客草稿的定时整理与发布提醒服务
problem             : 用户有写博客记录学习过程的习惯，但经常写完就丢在草稿箱里，既没有整理
                      也没有发布，缺少一个能在合适时间把草稿推出来、督促他完成发布的机制
targetProduct       : 自托管的草稿处理服务：定时扫描本地草稿文件，抽取状态与摘要，按
                      「草稿 → 待发布 → 已发布」状态机推进，生成待办与提醒并通过消息通道推送，
                      附带一个登录后可查看待办清单的简单后台页
userFit             : 写博客并丢在草稿箱是他本人真实产生的行为，不是假想需求；想做一个自己
                      每天真的会用起来的产品，提醒类服务天然有每日触点；前端弱，可用服务端
                      渲染/现成模板只做待办清单页；Spring Boot 与定时任务是已确认的主栈能力
differentiation     : 业务域从交易/点评转到内容工作流，复用点收敛到定时调度、Redis 键去重与
                      消息通道这些通用能力；与前面几个记账方向在目标产品、用户价值和所依赖的
                      资产上都不同
technicalValue      : 定时任务的幂等与去重（避免同一草稿被反复提醒）；消息推送的可靠性与失败
                      重试；草稿状态机的并发更新；用拦截器复用登录态做多端访问
estimatedComplexity : 中低
risks (3)           : 现有仓库没有博客相关模块，扫描解析草稿与推送通道都需要新写，可复用面
                      比记账方向窄；接入外部推送通道会引入第三方凭据，需要额外做凭据管理；
                      如果只做提醒而不闭环到发布动作，容易再次被判定为 demo 而非每天会用的产品
```

**依据**

```text
userNeed (3)  [UP@21] 有写博客记录学习过程的习惯 / 经常写完博客后留在草稿箱 /
                     想做一个自己每天真的会用起来的东西，不是再做一个 demo
userFit  (4)  [UP@21] 技术栈主要是 Java 和 Spring Boot / 前端很弱 /
                     能投入的时间不多 / 想做一个自己每天真的会用起来的东西
reusableCapability (4)
              [RP] HmDianPingApplication：@EnableScheduling / @EnableAspectJAutoProxy / @MapperScan
              [RP] RocketMQConfig：TransactionMQProducer 与 MessageConverter
              [RP] MvcConfig：拦截器链与放行路径
              [RP] jmeter/setup_tokens.py：批量注入测试 token（脚本）
```

**判断**

| 维度 | 结论 | 理由 |
|---|---|---|
| 来自真实需求？ | 直接支撑 | 直接对应用户第二条真实痛点（草稿积压），是五个方向里第二强的一条 |
| 目标是否具体？ | 是 | 扫描草稿 → 状态机 → 待办提醒 → 简单后台页，可想象 |
| userFit 是否引用本人？ | 是 | 引用的是他自己说过的行为（写博客丢草稿箱）、能力边界（前端弱）与约束（时间少） |
| 是否利用已有能力？ | **关系较弱** | 4 条依据里 3 条来自 config/入口类、1 条是测试脚本；而方向自己的风险第 1 条就承认「现有仓库没有博客相关模块，可复用面比记账方向窄」。也就是说，这些依据**存在但基本用不上** |
| differentiation 是否真实？ | 是 | 明确说明与记账方向在目标产品、用户价值、依赖资产上的不同 |
| technicalValue 是否可展示？ | 是 | 定时任务幂等、推送可靠性、状态机并发、登录态复用，都能讲 |
| 复杂度是否相符？ | 是 | 「中低」与「扫描 + 状态机 + 提醒」相符 |
| 风险是否具体？ | 是 | 尤其第 1、3 条是对自身复用面与「又会变成 demo」的自我审视 |

---

## 8. EvidenceSupport 语义支撑度汇总

机制核对（§6）54/54 通过；语义判断如下。

| Direction | userNeed | userFit | reusableCapability |
|---|---|---|---|
| 1 自托管记账服务 | **直接支撑** | **直接支撑** | 部分支撑（仅工程骨架） |
| 2 异步导入管道 | 部分支撑 | 部分支撑 | 部分支撑（脚本/文档占 3/4） |
| 3 多级缓存层 | **关系较弱** | 部分支撑 | **直接支撑** |
| 4 压测编排工具箱 | **关系较弱** | **关系较弱（语义错配）** | **直接支撑** |
| 5 博客草稿服务 | **直接支撑** | **直接支撑** | **关系较弱** |

三个反复出现的形态：

```text
形态 A  用户依据强 + 仓库依据弱     Direction 1、5
        方向是「拿这套技术栈去做一件用户真想做的事」，仓库只提供骨架。

形态 B  用户依据弱 + 仓库依据强     Direction 3、4
        方向是「把仓库里某个真实存在的工程产物产品化」，用户需求是被事后接上的。

形态 C  两边都中等                  Direction 2
```

**形态 B 是本轮最值得注意的现象**：它的 `reusableCapability` 逐条都可追溯、技术含量也不低，
机制上无懈可击；只有把 `userNeed` 一起读，才会发现用户从未要求过那个东西。
这说明「Evidence 可追溯」与「Evidence 支撑该判断」是两件独立的事，
前者已经由系统保证，后者目前完全没有被检查。

---

## 9. Cross-Direction Diversity

| Direction | 解决的问题 | 目标产品 | 使用场景 | 复用的软件能力 | 演化路径 |
|---|---|---|---|---|---|
| 1 | 数据主权 + 导出 | 自托管记账服务 | 日常记账/查询/导出 | Spring Boot 骨架、MyBatis 分页、拦截器 | 业务域替换 |
| 2 | 导入去重 + 对账 | 异步导入管道 | 批量导入第三方导出文件 | 事务消息配置、对账/采样脚本 | 技术骨架迁移 |
| 3 | 缓存一致性可观测 | 多级缓存查询层 | 统计读 + 失效观测 | Caffeine/RocketMQ 配置、缺陷文档 | 基础设施抽取 |
| 4 | 压测可复现 | 压测编排工具箱 | 一键跑压测与对账 | JMeter 脚本、对账脚本、容器编排 | 工具产品化 |
| 5 | 草稿积压 | 草稿整理提醒服务 | 日常内容工作流 | 定时任务、消息、拦截器 | 业务域替换 |

```text
解决的问题       5/5 各不相同
目标产品         5/5 各不相同
用户使用场景     5/5 各不相同
复用的软件能力   塌缩为两类：{1,5} 工程骨架 / {2,3,4} 仓库工件（配置·脚本·文档）
演化路径         4 类，但 {1,5} 同型（业务域替换）、{3,4} 同型（把仓库既有产物变成产品）
```

**判定：部分重叠。**

不是「同一个项目换标题」——五个方向在目标产品与使用场景上确实分开，没有出现
「同一个系统 + 换两个功能」的情况。但**复用维度塌缩**：没有任何一个方向能引用业务实现，
于是一半方向只能围绕仓库里可见的东西（配置、脚本、文档）展开，
其中 Direction 3 与 Direction 4 在结构上是同一种动作——**把仓库的某个工程产物产品化**。

---

## 10. M1 Material Selection revisit condition

M1 §7 定义的四条触发条件逐条核对。

### 条件 1 —「方向建议停留在 tech stack / config / infra 层面，无法利用真实业务能力」

**成立。** 本轮 16 个被引用的 Repository 文件（去重后）全部落在以下四类，
**没有一个是业务实现**：

```text
配置与入口（6）  HmDianPingApplication.java、config/CaffeineConfig.java、config/MvcConfig.java、
                 config/MybatisConfig.java、config/RocketMQConfig.java、resources/application.yaml
构建元数据（1）  pom.xml
压测与脚本（5）  jmeter/run_v2.bat、jmeter/check_seckill.py、jmeter/count_orders.py、
                 jmeter/setup_tokens.py、rocketmq/docker-compose.yml
文档（4）        docs/1.stress_testing_report.md、docs/6.stress_test_plan_v2.md、
                 docs/4.prompt_for_review-refactor.md、docs/10.bugfix-cache-eviction-broadcast.md

按类别统计 reusableCapability 的 22 条依据：
  java-source 8（全部是 config/入口）  doc 6  python-script 4  build/runtime-config 3  jmeter 1
```

`controller/`、`service/`、`entity/`、`mapper/` 下的任何文件都没有进入过任何方向的依据。

### 条件 2 —「reusableCapability 非常泛化，很少能指出具体已有实现」

**不成立。** 依据相当具体：CaffeineConfig 的 `maximumSize 10000/5000` 与
`expireAfterWrite 8s`、RocketMQConfig 的 `TransactionMQProducer` 与 MessageConverter、
`run_v2.bat` 的编排顺序与中止条件、`check_seckill.py` 的五项检查。问题不是「泛化」，
而是**层次**：具体，但具体在配置/脚本/文档上。

### 条件 3 —「differentiation 因不了解原项目核心业务而失真」

**不成立，但受限。** Direction 1 明确写「不再复用商铺/优惠券/秒杀的任何业务语义」，
Direction 3 写「不动任何秒杀业务逻辑」，Direction 5 承认「仓库没有博客相关模块」。
模型没有编造它看不到的业务细节，也没有把「点评/秒杀」套用到新方向上——
它选择了绕开。所以不是失真，是**可用的差异化空间被压缩**。

### 条件 4 —「明显存在『如果 RepositoryProfile 知道核心业务实现，模型会给出不同方向』」

**成立。** 最直接的证据是 Direction 3 与 Direction 4 的 `problem` 陈述：

```text
Direction 3  「…同时现有项目里缓存失效广播曾因缺少消息转换器而从未真正发出、失败也无告警，
              缓存一致性只能靠 8 秒 TTL 兜底，这个缺陷需要一个能被看见、被验证的解法」
Direction 4  「…现有仓库的 v1 压测报告因代码不一致、口径混淆、环境未固化而整体作废，
              压测脚本还硬编码本机路径…」
```

这两段「用户问题」的实际主语是**这个仓库**，不是这个用户。它们之所以被生成，
是因为 Prompt 里可见的材料（配置类、脚本、文档）**主要就是这些东西**。
把 `service/` 下的秒杀下单实现、`entity/`、`mapper/` 放进材料，
模型可引用的「已有软件能力」会完全不同，方向大概率也会不同。

### 判定

```text
M1 Material Selection revisit condition: TRIGGERED
```

触发的是 M1 §7 的条件 1（并强烈指向条件 4）。**本轮不修改 material selection**——
按 Round 1 的规定，这里只记录现象与证据，修改与否留给复盘裁定。

需要同时说明的是：本轮的方向并非不可用。Direction 1 与 Direction 5 是用户依据很扎实的方向，
它们的问题只是「复用面窄」，而这一点是**当前材料供给的必然结果**，不是模型能力问题。

---

## 11. Product Hypothesis Observation

PRODUCT.md §6.1 H2：*相比直接让通用 LLM 生成项目创意，结合用户长期兴趣、真实行为和可利用
软件资产后，系统能够产生用户认为更想真正开发的项目方向。*

### 正向证据

- Direction 1（自托管记账服务）与 Direction 5（博客草稿服务）**都不是通用 Prompt 会产出的方向**：
  它们各自锚定在这个用户的两条具体痛点上（第三方记账 App 的数据主权、草稿箱积压），
  并且都在 `userFit` 里引用了他的真实能力边界（前端弱）与真实约束（只能晚上和周末）。
  通用「给我一个项目点子」的 Prompt 拿不到这些输入，产出的是「待办事项应用」「个人博客系统」
  这类与该用户无关的通用题目。
- Direction 1 还主动规避了用户明说的反面要求（「不是再做一个 demo」），把「每天真的会用到」
  落成了具体形态（日常记账 + 查询 + 导出）。
- Direction 2 的 `differentiation` 指出「衡量指标从 QPS 变成导入正确性与对账通过率」，
  这是只有结合了具体软件资产才写得出来的差异描述。

### 反向证据

- 5 个方向里有 2 个（Direction 3、4）的主要驱动力来自**仓库的工程产物**，
  用户需求是被事后接上的；其中 Direction 4 的 `userFit` 还引用了一条在语义上反对它的依据。
- 因此「结合用户与资产后产生的方向**整体**更值得开发」这一说法本轮**不能成立**：
  资产侧的输入（当前材料）在某些方向上反而把结果拽向了「把仓库工件产品化」，
  而不是「为用户做一件他真的想做的事」。

### 判定

```text
G. Product hypothesis observation: MIXED
```

说明：`SUPPORTED` 只表示本轮观察对当前产品假设提供了正向证据，**不等于假设已被证明**。
本轮既有正向证据（2–3 个方向确实是通用 Prompt 产不出的）也有反向证据
（2 个方向由仓库工件驱动，用户关联弱）。

---

## 12. Grades

| | 项目 | 判定 | 依据 |
|---|---|---|---|
| A | Pipeline operability | **PASS** | 真实 HTTP/Provider/SQLite 全链路一次跑通：201 / 5 个方向 / 全部 CANDIDATE；POST 与 GET 5/5 语义相等；五张表新增行数与方向数、依据数逐条对应；运行期间 M2 链路无 ERROR |
| B | Evidence traceability | **PASS** | 54/54 EvidenceBasis 的 origin 指向本轮 Profile@21 或本轮 RepositoryProfile，且 Evidence 确实存在于对应集合中；0 失败 |
| C | Direction personalization | **MIXED** | D1/D5 直接锚定用户真实痛点与约束；D2 部分；D3/D4 的 `userNeed` 与用户表述之间是间接甚至反向关系（见 §8） |
| D | Repository capability reuse | **MIXED** | 依据具体、可追溯，且 D3/D4 对仓库工件确有实质利用；但 0/5 方向引用任何业务实现，D1/D5 的「复用」实际只是同栈工程骨架 |
| E | Cross-direction diversity | **MIXED** | 问题/目标产品/使用场景 5/5 各不相同（非「换标题」）；但复用的软件能力塌缩为两类，且 D3 与 D4 在演化路径上同型（把仓库工程产物产品化） |
| F | M1 material-selection revisit | **TRIGGERED** | M1 §7 条件 1 成立、条件 4 强烈指向；16 个被引用文件全部是配置/脚本/文档/构建元数据，无一是业务实现（见 §10） |
| G | Product hypothesis observation | **MIXED** | 2–3 个方向是通用 Prompt 产不出的（正向）；2 个方向由仓库工件驱动、用户关联弱（反向）（见 §11） |

---

## 13. Findings（本轮只记录，未修复）

> Round 1 禁止修改 Prompt / Domain rules / Evidence rules / Repository Analysis / sampling /
> API / Persistence。以下全部仅为记录。

### F1 — Evidence 可追溯 ≠ Evidence 支撑该判断

```text
Observed problem : 54/54 依据机制上全部通过，但其中至少 Direction 4 的 userFit 存在明确的
                   语义错配：用「用户想做一个每天真的会用起来的东西」去支撑「压测工具箱」，
                   而这条依据的实际含义与该方向相反。Direction 3 的 userNeed 同样是
                   被仓库缺陷而非用户痛点驱动的。
Evidence         : §7 Direction 3 / Direction 4；§8 的「形态 B」
Likely layer     : Domain Service 的 requireRequiredJudgements 目前只检查「该组非空」
                   与「至少一条来自指定一侧」；语义支撑度没有任何检查
Suggested action : 不在本轮裁定。若要在系统层面对此负责，需要先确定「语义支撑」由谁判定
                   （Prompt 要求？模型自评？人工 Review？）——这本身是一个产品决策
```

### F2 — 方向被仓库工程产物牵引

```text
Observed problem : 5 个方向中 2 个（Direction 3、4）的「问题」实际描述的是仓库自身的缺陷与
                   工件，用户需求是事后接上的。
Evidence         : §7 Direction 3 / 4 的 problem 逐字引用；§10 的条件 1 统计
Likely layer     : Repository Analysis material selection（M1 已知局限 → M2 端到端后果）
Suggested action : 见 §10 触发条件。是否重启 material selection 是 M2 后的独立决策，
                   不在本轮范围
```

### F3 — User Discovery 的解析失败会静默丢失用户输入

```text
Observed problem : 第一次 discovery-turn 因模型返回非单一 json 对象而 502，用户输入未进入
                   Profile；之后 Profile 已 REVIEWING，同一输入再提交被 409 拒绝
                   （状态机行为正确）。结果是这条输入永久缺失于本次 Discovery 的输入。
Evidence         : §2「记录：第 1 轮 discovery-turn 失败」；app.log 中
                   AiJsonObjectReader.read → UserProfileProposalParser.parse
Likely layer     : M1 User Discovery（解析边界）与前端重试语义
Suggested action : 不在本轮范围。可考虑的点是「失败一轮是否应保留用户输入以便重试」，
                   这属于 User Discovery 的产品行为，不属于 M2
```

### F4 — MyBatis-Plus 对复合主键子表持续输出启动期 WARN

```text
Observed problem : 启动日志有 8 条 WARN（"Can not find table primary key" /
                   "Not found @TableId annotation"），涉及 product_direction_* 与
                   user_profile_* / repository_profile_* 等全部复合主键子表。
Evidence         : app.log 启动段
Likely layer     : Infrastructure（MyBatis-Plus 对无单主键 DO 的固有提示）
Status           : 不是 M2 引入的回归——M1 的子表同样产生这些 WARN；本次运行功能未受影响
```

### F5 — 复杂度判断偏乐观（仅记录，非缺陷）

```text
Observed problem : Direction 4 把一个「容器化一键环境 + 可复现配置 + 对账编排 + 报告生成 +
                   跨项目泛化」的工具评为「中」，与实际规模不符。
Evidence         : §7 Direction 4 的 estimatedComplexity 与 risks
Likely layer     : Prompt（复杂度刻度的定义）
Status           : 单条观察，不足以支撑修改 Prompt
```

---

## 14. 需要用户亲自判断的部分

**USER JUDGMENT REQUIRED**

本轮无法代替用户回答「是否愿意继续考虑 / Select / 演化」。
§7 已完整列出五个方向的全部内容与依据，供复盘时逐条裁定。
以下只提供事实性对照，不给出推荐：

```text
用户依据最强        Direction 1（自托管记账服务）、Direction 5（博客草稿服务）
                    ——痛点、能力边界、约束都能一一对回用户原话
仓库依据最强        Direction 3（多级缓存层）、Direction 4（压测编排工具箱）
                    ——逐条可追溯到具体实现与具体缺陷
两者都居中          Direction 2（异步导入管道）
自我审视最诚实      Direction 4 的风险第 1 条、Direction 5 的风险第 1、3 条
                    ——主动指出了自身的复用面或「又会变成 demo」的风险
疑似语义错配        Direction 4 的 userFit
```

---

## 15. 如何复现

```text
1. 以正常方式启动 Backend（Provider 凭据由环境变量提供，本记录不含任何凭据）：
     java -jar backend/delveforge-app/target/delveforge-app-0.1.0-SNAPSHOT.jar \
       --delveforge.persistence.database-file=<新的临时路径> \
       --server.port=<端口>

2. 建立 Confirmed UserProfile（真实模型调用）：
     POST /api/user-profiles
     POST /api/user-profiles/{id}/discovery-turn   × N（body: {"input": "..."}）
     POST /api/user-profiles/{id}/confirm          （body: {"revision": <当前 revision>}）

3. 建立 RepositoryProfile（真实 Git + 真实模型调用）：
     POST /api/software-assets                      （location 指向本地 Repository）
     POST /api/software-assets/{id}/analysis

4. 执行发现：
     POST /api/product-directions/discovery
          {"userProfileId":"...","expectedRevision":N,"repositoryProfileIds":["..."]}

5. 回读并核对：
     GET  /api/product-directions/{id}
     比对五张 product_direction* 表的行数
```

Discovery 与 Repository Analysis 都包含真实模型调用，因此**输出不可复现**：
同一组输入再跑一次会得到不同的方向。可复现的是**结构性质**——数量（3–5）、
状态（全部 CANDIDATE）、依据的 origin 正确性、POST/GET 一致性、整批原子性。
本记录中的方向内容只代表 2026-09-28 那一次真实运行。
