# M2 Repository Understanding V2 真实链路验证记录 — Round 2

**Status:** 验证记录，非 Source of Truth
**Last Updated:** 2026-10-01
**Validated Revision:** M2 Task 1–4（Repository Map → Scout → 读取计划 → 真实读取接入 AnalyzeRepositoryUseCase）
**Round:** 2 — 未经人工调优的真实表现
**对照:** `docs/validation/m2-product-direction-discovery-smoke-test-round1.md`

> 本文档记录 Repository Understanding V2 在真实环境下的一次端到端验证，以及它对下游
> Product Direction Discovery 的实际影响。
>
> **它不是规范性文档。** 领域规则以 `DOMAIN_MODEL.md` 为准，架构规则以 `ARCHITECTURE.md`
> 与 `AGENTS.md` 为准，里程碑与优先级以 `ROADMAP.md` 为准，长期方向以 ADR-0004 为准。
> 本文档只记录「实际观察到了什么」，不新增规则、不替代上述任何文档。
>
> Round 2 与 Round 1 一样**不做任何实现调整**：本轮的目标是取得未经人工调优的真实表现。
> 中途发生的一次修复是**观测工具**的缺陷（§2.3），不是产品代码。

---

## 1. Objective

### 本轮回答的问题

```text
1. 真实 DeepSeek Scout 能否从 Repository Map 中指出有意义的业务实现区域？
2. 聚焦区域轮转与独立的定向源码预算，能否让真实业务源码进入最终 Repository Analysis？
3. 得到的 RepositoryProfile 是否具备 V1 缺失的实现级理解与依据？
4. 在同一用户语义下重跑 Product Direction Discovery，仓库复用是否实质改善？
5. V2 是否已经满足当前 M2/M3 的需要，还是存在具体的失败模式要求再一次升级？
```

### 验证的真实链路

```text
real HTTP API
    ↓
real SoftwareAssetController
    ↓
real AnalyzeRepositoryUseCase
    ↓
real RepositoryUnderstanding
    ├── real RepositoryMapBuilder        ← real Git（GitWorkspaceAdapter，读提交树）
    ├── real RepositoryScoutExtraction   ← 真实 DeepSeek 调用
    ├── real RepositoryReadPlanner
    └── real RepositoryReadExecutor      ← real Git 读取 + 执行期尺寸复核
    ↓
real RepositoryAnalysisExtraction        ← 真实 DeepSeek 调用
    ↓
real RepositoryProfileRepository → real SQLite / Flyway / MyBatis-Plus
    ↓
real DiscoverProductDirectionsUseCase    ← 真实 DeepSeek 调用
    ↓
real ProductDirectionDiscoveryService → real ProductDirectionRepository
```

没有 fake AiGateway、没有 mock Repository、没有测试专用捷径、没有手工构造的领域对象、
没有手工修改模型输出。用户侧输入同样经真实生产端点建立（§2.2）。

### 本轮不覆盖

```text
readiness / automatic discovery trigger（未实现）
Select / Reject、INV-D09 跨 Aggregate 协调（未实现）
并发、异常路径、多轮 Discover 的稳定性（由自动化测试覆盖，见 AGENTS.md §10）
前端展示与真实使用感受
```

### 原始产物

本次运行的 Provider 请求与响应原文保存在
`backend/delveforge-app/target/smoke-m2-r2/capture/`（构建目录，不提交）。
其中**不含任何凭据**：观测中继从不记录 `Authorization` 头。正文已摘录全部判断依据。

---

## 2. Environment

### 2.1 运行环境

```text
Backend    : java -jar delveforge-app-0.1.0-SNAPSHOT.jar（正常启动）
数据库     : 独立临时 SQLite（backend/delveforge-app/target/smoke-m2-r2/smoke.db）
             Flyway 全部 migration 在本次运行中新建，不依赖任何既有数据库状态
Repository : E:/develop/java-redis/back-end/projects/hm-dianping（与 M1 / Round 1 同一仓库）
Provider   : 环境变量提供，本记录不含任何凭据
构建       : ./mvnw verify → BUILD SUCCESS，846 tests，0 failures（运行前后各一次）
```

本次运行期间**未修改被分析仓库**：

```text
运行前  HEAD = 18e6b63cf218eca717cd00ecf4e3e0a12dccf5b4   git status --porcelain 行数 = 7
运行后  HEAD = 18e6b63cf218eca717cd00ecf4e3e0a12dccf5b4   git status --porcelain 行数 = 7
```

### 2.2 用户侧输入：与 Round 1 语义一致，不引入新的 User Discovery

为了把变量收敛到 Repository Analysis 一侧，本轮**没有重新做 User Discovery 探索**，
而是用真实生产端点 `PATCH /api/user-profiles/{id}` 把 Round 1 确认画像的内容逐条写入，
再经真实 `sufficiency-assessment`（一次真实模型调用）进入 REVIEWING，最后 `confirm`。

```text
userProfileId : ce16b159-6994-4557-8b8c-0840f867bf45
revision      : 24
status        : CONFIRMED
evidence 数量 : 17（USER_INPUT，一条对应一条画像陈述）
对比 Round 1  : revision 21 / evidence 12
```

内容与 Round 1 §2 完全一致（2 兴趣 / 3 行为 / 3 痛点 / 3 技术能力 / 3 目标 / 3 约束）。

**已知的用户侧差异：** Round 1 的 12 条 Evidence 是模型在三轮探索中自行归并的，
本轮是逐条语句一一对应（17 条）。**内容相同、粒度更细**——模型可引用更多条用户依据，
但每条的依据没有变化。这会影响「模型有多少条用户依据可选」，不影响用户说了什么。
本轮把它当作已知偏差记录，而不是当作等价。

### 2.3 观测工具与它的第一次失败（必须先说明）

生产日志按设计只记聚合数量，**不记 Prompt、不记模型响应、不记文件路径**
（`AGENTS.md` §8.8）。而本轮需要 Scout 的目录载荷大小、聚焦区域、编号与解析结果。
因此使用了一个**本地观测中继**：把 `delveforge.ai.deepseek.base-url` 指向本机
127.0.0.1 上的转发进程，由它原样转发到 `https://api.deepseek.com` 并把请求/响应原文落盘。

```text
它不做什么：不 mock、不 stub、不修改模型输出、不合成响应、不记录 Authorization 头
它做什么  ：原样转发 + 落盘。产品代码一行未改。
```

**第一次调用就失败了，原因在中继而不在产品：** 中继最初只按 `Content-Length` 读请求体，
而 Spring 的 RestClient 以 **chunked** 方式发送 JSON，于是中继把**空请求体**转发给了
DeepSeek，上游返回 `Failed to parse the request body as JSON: EOF while parsing a value`，
应用按设计翻译为 502 `EXTERNAL_CAPABILITY_UNAVAILABLE`。

这次失败与用户侧内容无关，也**不构成产品结论**；原文保存在
`target/smoke-m2-r2/instrument-failure/`。修复中继后重新发起同一请求即成功。
**没有产品代码或提示词因为这次失败而被修改。**

---

## 3. Repository Map Statistics

`analyzedRevision = 18e6b63cf218eca717cd00ecf4e3e0a12dccf5b4`
（== 分析开始前的 `git rev-parse HEAD`，成立；与 Task 1 基线、Round 1 是同一个 revision）

```text
已提交 blob / 文件总数        139
FOUNDATION                    43
SCOUT_SOURCE                  84
NONE                          12
Scout 目录载荷实际字节        14 837
配置的 maxCatalogBytes        65 536   （占用 22.6%）
```

与 Task 1 基线的对照：**完全一致**（Task 1 记录 139 / 43 / 84 / 12）。
revision 没有变化，因此这里的相等是预期结果而非巧合——`RealRepositoryShapeTest`
在同一个 revision 上断言这三个数字，本次 `./mvnw verify` 中通过。

目录未超限，因此**没有触发失败关闭**，不存在截断、采样或降级。

---

## 4. Real Scout Result

### 4.1 第一次真实调用是否成功

```text
Scout 调用                    成功（HTTP 200，模型 deepseek-flash，response_format=json_object）
解析                          成功
引用校验                      成功
无效 / 编造的 RF 引用          0
聚焦区域数量                  6（上限 6，下限 3）
选中的不同文件数              70（70 个引用全部唯一，无重复）
usage                         prompt 4 593 / completion 1 049（其中 reasoning 667）
```

目录里给了 84 个源码候选，Scout 引用其中 70 个，**没有一次越界**。
`RF-*` 全部命中本次 Map，且全部落在源码候选组内。

### 4.2 聚焦区域

| # | label | 选中 | 实际被读取 |
|---|---|---|---|
| 1 | 对外 HTTP 接口与业务入口 | 9 | 3 |
| 2 | 秒杀下单与异步补偿链路 | 15 | 3 |
| 3 | 多级缓存与缓存一致性机制 | 12 | 3 |
| 4 | 登录鉴权与会话管理 | 14 | 3 |
| 5 | 商铺检索与点评社区功能 | 14 | 3 |
| 6 | Redis 脚本与分布式锁实现 | 6 | 3 |

「实际被读取」一列是轮转 3 轮的必然结果（§5），不是 Scout 的取舍。

### 4.3 逐区域明细（`READ` = 最终进入了分析材料）

```text
Area 1 对外 HTTP 接口与业务入口
  [READ]  RF-50 UserController.java                          API_ENTRY            3096B
  [READ]  RF-47 ShopController.java                          API_ENTRY            2595B
  [READ]  RF-45 BlogController.java                          API_ENTRY            2643B
  [----]  RF-51 VoucherController.java                       API_ENTRY            1323B
  [----]  RF-52 VoucherOrderController.java                  API_ENTRY             827B
  [----]  RF-46 FollowController.java                        API_ENTRY             942B
  [----]  RF-44 BlogCommentsController.java                  API_ENTRY             335B
  [----]  RF-48 ShopTypeController.java                      API_ENTRY             711B
  [----]  RF-49 UploadController.java                        API_ENTRY            2135B

Area 2 秒杀下单与异步补偿链路
  [READ] RF-109 VoucherOrderServiceImpl.java                 APPLICATION_SERVICE  6977B
  [READ] RF-110 VoucherServiceImpl.java                      APPLICATION_SERVICE  7470B
  [READ]  RF-87 SeckillOrderConsumer.java                    INTEGRATION          8593B
  [----] RF-103 SeckillOrderTransactionListener.java         APPLICATION_SERVICE 10651B
  [----]  RF-74 SeckillReconciliationJob.java                UNKNOWN             10149B
  [----]  RF-97 IVoucherOrderService.java                    APPLICATION_SERVICE   573B
  [----]  RF-98 IVoucherService.java                         APPLICATION_SERVICE   381B
  [----]  RF-92 ISeckillVoucherService.java                  APPLICATION_SERVICE   335B
  [----] RF-104 SeckillVoucherServiceImpl.java               APPLICATION_SERVICE   561B
  [----] RF-112 GlobalIdGenerator.java                       UTILITY              994B
  [----]  RF-78 SeckillVoucherMapper.java                    PERSISTENCE          334B
  [----]  RF-84 VoucherOrderMapper.java                      PERSISTENCE          277B
  [----]  RF-68 VoucherOrder.java                            DOMAIN_MODEL        1498B
  [----]  RF-67 Voucher.java                                 DOMAIN_MODEL        1721B
  [----]  RF-62 SeckillVoucher.java                          DOMAIN_MODEL        1157B

Area 3 多级缓存与缓存一致性机制
  [READ] RF-102 MultiLevelCacheServiceImpl.java              APPLICATION_SERVICE 29074B
  [READ]  RF-91 IMultiLevelCacheService.java                 APPLICATION_SERVICE  2758B
  [READ] RF-111 CacheClientUtils.java                        UTILITY             6781B
  [----]  RF-85 CacheEvictionConsumer.java                   INTEGRATION          2994B
  [----]  RF-86 CacheEvictionProducer.java                   INTEGRATION          2896B
  [----]  RF-53 CacheEvictionMessage.java                    DOMAIN_MODEL         639B
  [----]  RF-69 CacheEvictionEvent.java                      UNKNOWN              1209B
  [----]  RF-70 CacheEvictionListener.java                   UNKNOWN              3507B
  [----]  RF-71 CacheReconstructException.java               UNKNOWN               934B
  [----] RF-122 ShopCacheVO.java                             DOMAIN_MODEL        3667B
  [----] RF-123 VoucherCacheVO.java                          DOMAIN_MODEL        2718B
  [----] RF-115 RedisConstants.java                          UTILITY             2821B

Area 4 登录鉴权与会话管理
  [READ] RF-108 UserServiceImpl.java                         APPLICATION_SERVICE  7214B
  [READ]  RF-96 IUserService.java                            APPLICATION_SERVICE   993B
  [READ]  RF-72 LoginInterceptor.java                        UNKNOWN               588B
  [----]  RF-73 RefreshTokenExpirationInterceptor.java       UNKNOWN              1927B
  [----] RF-121 UserHolder.java                              UTILITY               365B
  [----] RF-114 PasswordEncoder.java                         UTILITY              1062B
  [----] RF-118 RegexUtils.java                              UTILITY              1120B
  [----] RF-117 RegexPatterns.java                           UTILITY               646B
  [----]  RF-54 LoginFormDTO.java                            DOMAIN_MODEL          160B
  [----]  RF-58 UserDTO.java                                 DOMAIN_MODEL          150B
  [----]  RF-65 User.java                                    DOMAIN_MODEL         1137B
  [----]  RF-66 UserInfo.java                                DOMAIN_MODEL         1498B
  [----]  RF-82 UserMapper.java                              PERSISTENCE           253B
  [----]  RF-81 UserInfoMapper.java                          PERSISTENCE           265B

Area 5 商铺检索与点评社区功能
  [READ] RF-105 ShopServiceImpl.java                         APPLICATION_SERVICE  9528B
  [READ] RF-100 BlogServiceImpl.java                         APPLICATION_SERVICE  7004B
  [READ] RF-101 FollowServiceImpl.java                       APPLICATION_SERVICE  2954B
  [----]  RF-93 IShopService.java                            APPLICATION_SERVICE   641B
  [----]  RF-89 IBlogService.java                            APPLICATION_SERVICE   498B
  [----]  RF-90 IFollowService.java                          APPLICATION_SERVICE   424B
  [----]  RF-94 IShopTypeService.java                        APPLICATION_SERVICE   404B
  [----] RF-106 ShopTypeServiceImpl.java                     APPLICATION_SERVICE  3826B
  [----]  RF-63 Shop.java                                    DOMAIN_MODEL        1800B
  [----]  RF-64 ShopType.java                                DOMAIN_MODEL        1116B
  [----]  RF-59 Blog.java                                    DOMAIN_MODEL        1665B
  [----]  RF-60 BlogComments.java                            DOMAIN_MODEL        1396B
  [----]  RF-61 Follow.java                                  DOMAIN_MODEL         908B
  [----]  RF-56 ScrollResult.java                            DOMAIN_MODEL         184B

Area 6 Redis 脚本与分布式锁实现
  [READ] RF-129 seckill_compensate_v2.lua                    UNKNOWN              7336B
  [READ] RF-127 seckill.lua                                  UNKNOWN              1588B
  [READ] RF-128 seckill_compensate.lua                       UNKNOWN              1089B
  [----] RF-130 unlock.lua                                   UNKNOWN               155B
  [----] RF-119 SimpleRedisLock.java                         UTILITY              2098B
  [----] RF-113 ILock.java                                   UTILITY               182B
```

### 4.4 按结构角色统计（70 个被选中文件）

```text
API_ENTRY              9
APPLICATION_SERVICE   30
DOMAIN_MODEL          15
PERSISTENCE            5
INTEGRATION            4
UTILITY                9
CONFIG_BOOTSTRAP       0
UNKNOWN               10
```

逐个回答本轮要求的问题：

```text
选中真实 Controller / API 实现？      是，9 个 controller 全部被选中
选中 Service / Application 实现？     是，19 个 *ServiceImpl / *Service 被选中
选中 domain / entity / model？        是，15 个 entity/dto/vo（Shop、Blog、Voucher、User…）
选中 mapper / persistence？           是，5 个 mapper 接口
选中运行时 Lua 脚本？                 是，4 个脚本（seckill / 补偿 v1 / 补偿 v2 / unlock）
UNKNOWN 是否被关注？                  是，10 个 UNKNOWN 被选中（job/、event/、interceptor/、exception/、lua）
是否被单一主题主导？                  否，最大区域 15 条，最小 6 条，六个区域都进入轮转
六个区域是否真的是不同的实现区域？    是，六者在「用户能看到什么」层面各不相同
```

**不把 label 当作仓库事实。** 上面只评价它们作为**选文件假设**的质量：
6 个区域各自指向一批同主题实现，彼此无重叠语义；没有一个区域是另一个的换名。
本轮没有出现 Round 1 那种「区域是仓库自身工程关注点」的情况——
即使 Area 3 与 Area 6 仍带有工程色彩，它们指向的也是**产品实现**（缓存读服务、秒杀脚本），
而不是构建脚本或压测编排。

---

## 5. Read Planning and Execution

### 5.1 Foundation

```text
候选数量（FOUNDATION lane）            43
规划选中 count                         12（== maxFiles=12，触顶）
实际材料 count                         12
规划期跳过诊断                         1 条：SELECTED_BUT_TOO_LARGE（见 §5.4、§11）
执行期 too-large count                 0
执行期 total-budget-exceeded count     0
实际准入字节                           33 036 / 98 304（33.6%）
最大已准入文件                         docs/10.bugfix-cache-eviction-broadcast.md 6 898B（上限 32 768，21%）
最大候选（未准入）                     src/main/resources/db/hmdp.sql 151 948B（上限 32 768，464%）
```

按类别轮转实际准入的 12 个文件（顺序即计划顺序）：

```text
HmDianPingApplication.java      SOURCE_CODE（恰好只有 CONFIG_BOOTSTRAP 提示）
pom.xml                         BUILD_METADATA
.gitignore                      CONFIGURATION
docs/1.stress_testing_report.md DOCUMENTATION
jmeter/check_seckill.py         SCRIPT_AUTOMATION
rocketmq/docker-compose.yml     DEPLOYMENT
jmeter/null_shop_ids.csv        OTHER
config/CaffeineConfig.java      SOURCE_CODE
jmeter/round_state_b1_r1.json   CONFIGURATION（.json 属 CONFIGURATION_EXTENSIONS，不是 OTHER）
docs/10.bugfix-…-broadcast.md   DOCUMENTATION
jmeter/count_orders.py          SCRIPT_AUTOMATION
jmeter/shop_ids.csv             OTHER
```

按类别汇总本次准入：`SOURCE_CODE 2 / CONFIGURATION 2 / DOCUMENTATION 2 / SCRIPT_AUTOMATION 2 /
BUILD_METADATA 1 / DEPLOYMENT 1 / OTHER 2 / DATA_SCHEMA 0`。

这一份汇总与初次判读时的记录不同：初次把 `jmeter/round_state_b1_r1.json` 记为 `OTHER`，
实际它是 `CONFIGURATION`。更正后的数字见 §11。

### 5.2 Targeted Source

```text
候选数量（SCOUT_SOURCE lane）          84
Scout 指出的不同文件                   70
规划选中 count                         18（== maxFiles=18，触顶）
实际材料 count                         18
规划期跳过诊断                         见下
执行期 too-large count                 0
执行期 total-budget-exceeded count     0
实际准入字节                           108 281 / 163 840（66.1%）
最大单文件                             MultiLevelCacheServiceImpl.java 29 074B（上限 65 536，44%）
```

实际到达最终 Repository Analysis 的定向源码，按计划顺序：

```text
 1  controller/UserController.java
 2  service/impl/VoucherOrderServiceImpl.java
 3  service/impl/MultiLevelCacheServiceImpl.java
 4  service/impl/UserServiceImpl.java
 5  service/impl/ShopServiceImpl.java
 6  resources/seckill_compensate_v2.lua
 7  controller/ShopController.java
 8  service/impl/VoucherServiceImpl.java
 9  service/IMultiLevelCacheService.java
10  service/IUserService.java
11  service/impl/BlogServiceImpl.java
12  resources/seckill.lua
13  controller/BlogController.java
14  mq/SeckillOrderConsumer.java
15  utils/CacheClientUtils.java
16  interceptor/LoginInterceptor.java
17  service/impl/FollowServiceImpl.java
18  resources/seckill_compensate.lua
```

轮转形状与设计一致：6 个区域各贡献 3 个（第 1、2、3 轮各 6 个），
没有任何区域独占预算。

### 5.3 planned vs actually admitted

```text
planned                      = 12 + 18 = 30
actually admitted after read = 30
差值                          = 0
```

生产日志的聚合行（唯一一行，`result=READY`）：

```text
operation=repository-analysis result=READY foundationSelectedCount=12
    targetedSourceSelectedCount=18 materialCount=30
    tooLargeCount=0 totalBudgetExceededCount=0
```

### 5.4 规划期跳过诊断

```text
NOT OBSERVABLE WITH CURRENT INSTRUMENTATION（生产日志与 HTTP 响应都没有）
```

生产日志的 `tooLargeCount` 与 `totalBudgetExceededCount` **只来自执行期**
（`RepositoryReadResult`）。规划期记在 `RepositoryReadPlan` 里的逐条跳过诊断
既不进日志、也不出现在任何 HTTP 响应里。

可以从两侧数量推出**总量**（84 个源码候选 → Scout 指出 70 → 规划 18；
43 个 Foundation 候选 → 规划 12），但**推不出**「某一条具体候选是因为什么原因被挡下」。

这条缺口在本次记录中**实际发生过一次影响**：初稿把 `DATA_SCHEMA 0` 归因于类别优先级，
而真实原因是规划期的一条 `SELECTED_BUT_TOO_LARGE`。该条跳过是**在本次更正时通过
独立重放（真实 Map + 生产规划器 + 真实预算）才被看到的**，生产运行本身没有产出它。

按 `maxFiles` 用尽而停止属于通道级行为，不是逐条拒绝。因此本轮 12 + 18 个名额的
截断**本来就不产生逐条诊断**；本轮唯一一条规划期逐条诊断来自尺寸复核，而不是名额耗尽。

### 5.5 旧 M1 选材策略未参与（任务书 §11）

`RepositoryAnalysisMaterialCollector` / `RepositoryAnalysisMaterialPolicy` 在本次真实运行中
**没有参与，也没有被当作回退**。本轮**没有删除这两个类**。

来自三条独立证据：

```text
装配层   RepositoryAnalysisWiringTest.doesNotWireTheOldMaterialSelectionPolicy
         断言上下文中不存在 collector Bean（本次 ./mvnw verify 中通过）

行为层   材料 == 计划（30 == 30），且 52 个 Scout 选中文件一个都没有被读取。
         旧策略会把同一类别里的候选成批读进来，不会只读 30 个、更不会刚好等于计划。

对照层   Round 1 实际读到的 16 个文件中，有 8 个本次**不在材料里**：
         config/MvcConfig.java、config/MybatisConfig.java、config/RocketMQConfig.java、
         resources/application.yaml、jmeter/run_v2.bat、jmeter/setup_tokens.py、
         docs/6.stress_test_plan_v2.md、docs/4.prompt_for_review-refactor.md

回退     本轮没有发生任何失败，因此「失败就退回旧路径」的分支未被触发；
         该分支不存在这一事实由 AnalyzeRepositoryUseCaseTest 的失败原子性用例覆盖。
```

---

## 6. RepositoryProfile Result

```text
repositoryProfileId : 0a618365-575b-491b-b56f-1b0d877f14d3
assetId             : a29453ec-861a-4e5f-b6e3-42d041f06ba7
analyzedRevision    : 18e6b63cf218eca717cd00ecf4e3e0a12dccf5b4
分析耗时            : 46.3 秒（Round 1：27.7 秒）
usage               : prompt 41 436 / completion 5 727（其中 reasoning 2 493）
结构                : techStack 15 / modules 8 / capabilities 10 /
                      reusableAssets 10 / limitations 8 / risks 9 / evidence 26
```

### 6.1 purpose

```text
这是一个基于 Spring Boot 的点评/电商平台（黑马点评）后端，已被重构为高并发架构：
多级缓存（Caffeine L1 + Redis L2 + MySQL L3）承担热点读，RocketMQ 事务消息 + Lua 预扣 +
Redisson 锁承担秒杀写，并配套 JMeter 压测与 Python 对账脚本验证零超卖。
```

### 6.2 capabilities（10）

```text
1  三级缓存读链路：Caffeine（L1，8s TTL）→ Redis（L2，TTL 随机抖动）→ DB（L3）
2  缓存防护：空值哨兵防穿透、Redisson 分布式锁 + 有界等待预算防击穿、TTL 随机偏移防雪崩
3  秒杀下单：Redis Lua 预扣库存并写事务流水 Hash 与时间索引 ZSET，RocketMQ 事务消息
   （半消息 + 反向回查），消费者按 status=SUCCESS 门禁落库
4  三层幂等：消费端按 orderId 快速查单、DB 一人一单二次校验、tb_voucher_order 主键唯一约束
5  缓存失效写链路：事务内更新 DB，事务提交后发布事件先删 Redis 再经 RocketMQ 广播清 L1
6  库存补偿：DB 扣减失败时回补 Redis 库存与用户标记，并把事务流水置 CANCELLED、从 ZSET 移除
7  用户能力：Redis Token 登录/登出、手机验证码、BitMap 月度签到与连续签到统计
8  社区能力：博客点赞/热榜、点赞 Top5（ZSet）、粉丝 Feed 收件箱、共同关注（Set 交集）
9  商铺能力：按类型分页、按名称分页、Redis GEO 半径 5km 附近商铺查询
10 工程验证能力：JMeter 场景、Python 秒杀对账与落库采样
```

### 6.3 reusableAssets（10）

```text
1  MultiLevelCacheServiceImpl + IMultiLevelCacheService：三级缓存读服务（单实体与泛型集合两版、
   三态 RedisLookup、有界锁等待预算、中断与故障快速失败语义）
2  CaffeineConfig：L1 本地缓存 Bean（容量 + 8s expireAfterWrite + recordStats）
3  seckill.lua：原子预扣库存 + 事务流水 Hash + ZSET 时间索引
4  seckill_compensate.lua / seckill_compensate_v2.lua：库存补偿脚本（v2 为独立契约版本）
5  SeckillOrderConsumer：消费端幂等 + 精确状态门禁 + 异常分类参考实现
6  CacheClientUtils：互斥锁/逻辑过期两套防击穿与穿透/雪崩处理工具组件
7  jmeter/check_seckill.py："零超卖"五项对账证据链脚本
8  jmeter/count_orders.py：消费端吞吐采样工具
9  rocketmq/docker-compose.yml：本地 RocketMQ 编排
10 docs/10.bugfix-cache-eviction-broadcast.md：消息转换器丢失的根因与三段式验证记录
```

**与 Round 1 的直接对比：** Round 1 的 10 项 reusableAssets 中 **0 项**是业务实现类；
本轮 10 项中 **6 项**是业务实现（1、3、4、5、6，以及 2 中的具体配置参数搭配业务语义），
2 项是脚本、1 项是编排、1 项是文档。

### 6.4 limitations / risks 的层次变化

Round 1 的 limitations 主要是「压测脚本路径硬编码」「v1 报告作废」这类**工程产物层面**的问题。
本轮 8 条 limitations 里出现了只有读过实现才能写出的内容：

```text
- 一致性仅为 AP 模型：8s 只是单节点单条目过期上限，不构成全系统一致性上界
- 秒杀 Redis 预扣库存写入不随 MySQL 事务回滚，已知边界下与 DB 的不一致可能残留
  （VoucherServiceImpl.addSeckillVoucher 注释）
- 补偿脚本 v2 为独立契约版本，尚未接入生产调用链，生产仍使用 v1
- 秒杀链路存在明确未覆盖项：重试耗尽/DLQ、锁失效后的落库/补偿竞争、对账 Job 删除证据、
  旧 Java 补偿不幂等、活动窗口校验等（SeckillOrderConsumer 注释）
```

最后一条尤其值得注意：它引用的是**代码注释里作者自己写下的未覆盖项**，
这是「文件真的被读进去」才能产生的依据，而不是从文件名猜的。

### 6.5 模型自己报告的盲区

```text
limitations 第 1 条：
「提供的材料中不含 application.yml/application.properties、数据库表结构与实体/DTO 类文件，
  配置项与数据模型无法从现有材料确认」
limitations 第 8 条：
「仓库中未见单元测试类、CI 配置或部署脚本」
```

两条都**与事实相符**（见 §11：`application.yaml` 在 CONFIGURATION 类别内因路径序落选；
`db/hmdp.sql` 因 151 948 字节超过 Foundation 的 `maxFileBytes` 在规划期被跳过；
TEST_CODE 按路由进 `NONE` lane）。
模型没有假装读过它们，而是明确标注了不可确认——这正是 Scout 输出被限定为
inspection hint、只有真正读到的内容才能成为事实这一设计的直接体现。

---

## 7. Evidence Provenance and Composition

### 7.1 来源正确性（机械核对）

```text
RepositoryProfile evidence 总数                      26
sourceRef 不在本次实际读取材料中的                   0
不同 sourceRef 数                                    26（无重复）
sourceType                                           全部 REPOSITORY
```

**没有一条 Scout-only 路径成为 Evidence，没有一条未读文件出现在依据里。**
机制层面成立。

### 7.2 依据构成

```text
业务实现源码            14 / 26  （service impl 7、Lua 2、service iface 1、
                                  controller 1、mq 1、interceptor 1、util 1）
配置与入口               3 / 26
脚本与工具              5 / 26
文档                     2 / 26
构建元数据               1 / 26
部署编排                 1 / 26
```

对照 Round 1 §10 记录的事实：Round 1 的 16 个被引用文件**全部**是配置 / 脚本 / 文档 /
构建元数据，`controller/`、`service/`、`entity/`、`mapper/` **一个都没有**。

### 7.3 模型有没有编造或夸大

抽查了几条最容易造假的陈述：

```text
「tb_voucher_order 主键唯一约束」        —— 出自 SeckillOrderConsumer 的幂等说明（已读）
「8s 是单节点单条目过期上限」            —— 出自 CaffeineConfig 的 expireAfterWrite（已读）
「v2 尚未接入生产调用链」                —— 出自 seckill_compensate_v2.lua 自身的声明（已读）
「jmeter 脚本硬编码明文口令」            —— 出自 check_seckill.py / count_orders.py（已读）
「压测报告数据作废」                     —— 出自 docs/1（已读）
```

没有发现指向未读文件的依据，也没有发现对已读文件内容的实质性歪曲。
**唯一需要注意的是它无法确认的部分已经被它自己标注为不可确认**（§6.5）。

---

## 8. Product Direction Result

### 8.1 执行

```text
POST /api/product-directions/discovery
{
  "userProfileId": "ce16b159-6994-4557-8b8c-0840f867bf45",
  "expectedRevision": 24,
  "repositoryProfileIds": ["0a618365-575b-491b-b56f-1b0d877f14d3"]
}

HTTP 201，耗时 39.6 秒，返回 4 个方向，status 全部 CANDIDATE
usage: prompt 5 104 / completion 6 820（其中 reasoning 4 444）
GET 回读 4/4 与 POST 语义相等
```

持久化行数：

```text
product_direction                     4
product_direction_repository_profile  4
product_direction_candidate_asset     4
product_direction_risk               15   （4+4+4+3）
product_direction_evidence_support   53   （== 全部 EvidenceBasis 总数）
```

EvidenceBasis 逐条核对：53 条，origin 指向本轮 Profile@24 或本轮 RepositoryProfile，
**失败 0 条**（INV-D06 要求的那一半）。

### 8.2 四个方向

#### Direction 1 — `8a08069c-eaa9-4ec1-b879-136f00df9660`

```text
title         自托管个人记账与账单导入/导出服务
problem       记账数据全部存放在第三方服务器上，自己无法直接掌控，从记账 App 导出数据
              自行查看也很麻烦，导致「看自己的账」这件事本身有门槛。
targetProduct 自托管的 Spring Boot 记账后端：接收手机记账 App 导出的账单文件，清洗去重后
              落到自己的 MySQL，提供分类/月度统计查询与 CSV/JSON/Markdown 多格式导出接口；
              前端只做最小可用页面或直接复用现成页做少量修改。
userFit       技术栈正是 Java + Spring Boot，可独立完成后端与数据处理；自评前端能力弱，
              因此把复杂度集中在后端、界面压到最低；目标明确写着要做一个能自主掌控数据、
              方便导出查看、并且自己每天真的会用起来的记账工具，而不是 demo。
differentiation 原仓库业务域是商铺/优惠券/秒杀，本方向转向单用户个人财务数据域，是读多写少、
              以导入清洗和导出为主的产品；可复用同一套缓存与幂等工程能力，
              但业务语义、数据模型与用户场景完全不同。
estimatedComplexity 中等
```

依据（reusableCapability 5 条）：

```text
[RP] pom.xml                                  依赖清单
[RP] service/impl/MultiLevelCacheServiceImpl  三级缓存读链路
[RP] service/IMultiLevelCacheService          缓存对外接口
[RP] service/impl/ShopServiceImpl             查询走缓存 + 更新后发失效事件
[RP] service/impl/VoucherServiceImpl          泛型集合多级缓存（JavaType）
```

**判断：** 用户依据直接支撑（痛点 5 条逐条对应）；仓库依据**首次出现实现级内容**——
4/5 条指向具体实现类。`risks` 第 1 条直接引用了本轮模型自己报告的盲区
（「材料不含 application.yml、表结构与实体/DTO，数据模型需自行重建」），
说明下游方向如实继承了上游已知的不确定性。

#### Direction 2 — `085086d8-6523-40d8-87d4-d930592ad003`

```text
title         草稿箱到发布：个人博客草稿整理与发布流水线
problem       用户经常写完博客后留在草稿箱，未发布也未整理，学习过程记录持续沉淀不下来，
              写作的「最后一公里」缺失。
targetProduct 自托管的博客草稿与发布服务：草稿状态机、定时扫描长期未更新的草稿并提醒或归档、
              Markdown 渲染后发布到自己的站点或静态站点，附带标签与排序聚合。
differentiation 原仓库的博客模块是社区互动（点赞、热榜、粉丝 Feed、关注），本方向把重心从
              「社交分发」移到「个人写作流程闭环」，解决内容从草稿到发布的完成率。
estimatedComplexity 中低
```

依据（reusableCapability 4 条）：`HmDianPingApplication`（`@EnableScheduling`）、
`UserServiceImpl`（Redis Token 登录 / BitMap 签到）、`LoginInterceptor`、
`BlogServiceImpl`（ZSet 点赞与 Feed）。

**判断：** 与 Round 1 的 Direction 5（博客草稿服务）是同一用户需求，但复用依据从
「3 条 config/入口 + 1 条测试脚本」变成「1 条入口 + 3 条实现」，
且方向明确区分了「原仓库是社区互动，本方向是个人写作闭环」。

#### Direction 3 — `3131af19-2689-4da2-9533-21e0f61c941a`

```text
title         三级缓存读服务 Starter 化，并补上失效广播的可观测与自愈
problem       仓库里的缓存失效广播曾因缺少消息转换器抛异常且被静默 catch，从上线起从未真正
              发出；三级缓存与失效逻辑目前与业务代码耦合，无法直接复用到自己的其它项目，
              广播失败也没有重试、计数或告警。
targetProduct 基于 Spring Boot 自动配置的多级缓存 Starter：Caffeine L1 + Redis L2 + DB L3
              读链路、穿透/击穿/雪崩防护、跨节点失效广播（含失败重试、失败计数与告警、
              广播可达性自检），可被自己的记账或博客服务作为依赖直接引入。
estimatedComplexity 中高
```

依据（reusableCapability 5 条）：`CaffeineConfig`、`MultiLevelCacheServiceImpl`、
`IMultiLevelCacheService`、`CacheClientUtils`、`docs/10`。

**判断：** 这是 Round 1 Direction 3 的延续，也是本轮最需要单独记一笔的方向。
它的 `problem` 第一句仍然在描述**仓库自身**的缺陷；`userNeed` 引用
`user-statement-12`（「自己做一个能自主掌控数据、方便导出查看的记账工具或方案」）
来支撑「做一个缓存 Starter」——**支撑关系是间接的**，与 Round 1 §8 记录的「形态 B」同型。
差别在于：Round 1 时它的仓库依据只有 `CaffeineConfig` + `RocketMQConfig` + 文档，
本轮它引用了 `MultiLevelCacheServiceImpl`（29KB 的完整实现）与 `CacheClientUtils`，
**技术依据第一次落在了真实实现上**，而不再只是配置与文档。

#### Direction 4 — `a41b6a2b-1f59-43df-85d6-05104627b481`

```text
title         个人账目对账与异常检测报告工具
problem       从记账 App 导出的数据自己查看很麻烦，而且很难判断账目是否完整——有没有漏记、
              有没有重复记录、月度收支是否对得上，用户目前没有任何可信度校验手段。
targetProduct 对账与自检工具（服务或命令行）：把导出的账单与自己的库比对，按「账单条数一致、
              无重复条目、DB 与导出汇总金额对齐、无未匹配条目、修正记录为零」输出证据链式
              对账报告，Markdown/CSV 即可，不需要复杂前端。
differentiation 与「自托管记账主服务」不同，这个方向不做日常记账，只做「数据可信度验证」：
              把仓库中用于证明秒杀零超卖的对账方法论与多指标证据链迁移到个人财务场景，
              产品形态是校验器而不是记账应用。
estimatedComplexity 中低
```

依据（reusableCapability 5 条）：`VoucherOrderServiceImpl`（事务消息）、
`SeckillOrderConsumer`（幂等 + 状态门禁）、`seckill.lua`（原子预扣 + 流水）、
`check_seckill.py`（五项对账证据链）、`count_orders.py`（采样）。

**判断：** 这是本轮与 Round 1 差别最大的方向。Round 1 的 Direction 4 是
**「可复现的压测与对账编排工具箱」**——把仓库的压测工件直接产品化，用户需求是事后接上的，
且 `userFit` 引用了一条语义上反对它的依据（「想做一个自己每天真的会用起来的东西」）。
本轮的方向**保持了同样的技术依据**（对账方法论），但把它**迁移到了用户自己的问题上**：
problem 陈述的完全是用户的事（漏记、重复、对不上），仓库只提供方法。
Round 1 §8 记录的「形态 B 语义错配」在这个方向上**没有再出现**。

### 8.3 逐维度判断

#### Personalization（个性化）

```text
D1 用户依据直接支撑 —— 痛点、能力边界、约束逐条可回对原话
D2 用户依据直接支撑 —— 草稿积压是用户明说的痛点
D3 用户依据关系较弱 —— userNeed 与「缓存 Starter」是间接关系（见上）
D4 用户依据直接支撑 —— 且是「用户自己没想到、但确实存在」的延伸
```

与 Round 1 相比：Round 1 是 2 直接 + 1 部分 + 2 弱；本轮是 3 直接 + 1 弱。

#### Repository reuse（仓库复用）

按本轮 4 个方向各自引用的 Repository 依据统计：**19 条**（含跨方向重复），
去重后 **17 个文件**：

```text
业务实现                11   service-impl 6 / service-iface 1 / mq 1 /
                             util 1 / interceptor 1 / lua 1    （controller 0）
配置与入口               2   HmDianPingApplication、CaffeineConfig
脚本                     2   check_seckill.py、count_orders.py
文档                     1   docs/10.bugfix-cache-eviction-broadcast.md
构建元数据               1   pom.xml
部署                     0
```

去重后的 17 个被引用文件中，**11 个是业务实现**。
Round 1 的对应数字是 **16 个文件中 0 个业务实现**。

方向层面的变化同样明确：

```text
Round 1  5 个方向的复用点塌缩为两类：
         {1,5} 同栈工程骨架 / {2,3,4} 仓库工件（配置·脚本·文档）
         没有任何方向能引用业务实现

Round 2  4 个方向分别引用不同的实现：
         D1 → 多级缓存读链路（MultiLevelCacheServiceImpl / IMultiLevelCacheService /
              ShopServiceImpl / VoucherServiceImpl）
         D2 → 登录会话与博客社区实现（UserServiceImpl / LoginInterceptor / BlogServiceImpl）
         D3 → 缓存基础设施实现（同上 + CacheClientUtils + CaffeineConfig）
         D4 → 秒杀幂等与对账方法论（VoucherOrderServiceImpl / SeckillOrderConsumer /
              seckill.lua / check_seckill.py / count_orders.py）
```

**「把仓库工程产物产品化」这一形态只剩 D3 一个，而且它引用的是实现而不是配置。**

#### Evidence semantics（依据是否真的支撑判断）

Round 1 的语义错配（用「想做一个每天真的会用起来的东西」支撑「压测工具箱」）在本轮**没有复现**：
D4 已经从「压测工具箱」变成「个人对账工具」，那条反义依据不再被引用。
D3 仍然存在间接支撑，但它已经明确写在 §8.2 里，且在 `differentiation` 中
如实承认自己是「从业务功能转向基础设施组件」，没有假装是用户需求驱动的。

需要明确的是：**「Evidence 可追溯」与「Evidence 支撑该判断」仍然是两件独立的事**，
系统层面依然只保证前者（F1 未变）。

#### Diversity（多样性）

| Direction | 解决的问题 | 目标产品 | 使用场景 | 复用的实现 |
|---|---|---|---|---|
| 1 | 数据主权 + 导入导出 | 自托管记账后端 | 日常记账/查询/导出 | 多级缓存读链路、缓存事件失效 |
| 2 | 草稿积压 | 博客草稿发布流水线 | 内容工作流 | 登录会话、ZSet 社区实现、定时任务 |
| 3 | 缓存失效不可观测 | 多级缓存 Starter | 被自己的项目依赖 | 缓存实现 + 工具 + 缺陷文档 |
| 4 | 账目完整性无校验 | 对账自检工具 | 记账之后的数据校验 | 幂等落库、Lua 原子性、对账方法论 |

```text
解决的问题       4/4 各不相同
目标产品         4/4 各不相同
使用场景         4/4 各不相同
复用的实现       4/4 各不相同（D1 与 D3 有交集，但一个做业务读、一个做基础设施）
```

Round 1 的复用维度塌缩为两类的现象**消失了**：本轮没有任何两个方向共用同一套复用依据集。
方向数从 5 降到 4，但仍在 Prompt 要求的 3–5 区间内。

**一个中性的观察：** D1 与 D4 都锚定「记账」，D2 锚定「博客」。四个方向里有两个落在同一个
用户兴趣上，这是用户画像本身只有两个兴趣的直接结果，不是方向生成的问题。

---

## 9. Round 1 vs Round 2 Comparison

| | Round 1（V1 material selection） | Round 2（V2 Map → Scout → read plan） |
|---|---|---|
| 分析方式 | 确定性类别轮转，无 AI 参与选材 | AI Scout 在描述符上指出区域 + 确定性轮转与预算 |
| 材料规模 | 40 个以内（本次实际 16 个被下游引用） | 30 个（12 Foundation + 18 Targeted Source） |
| 业务源码进入分析 | **0** | **18**（含 29KB 的 `MultiLevelCacheServiceImpl`） |
| Lua 业务脚本 | 未进入 | 3 个（含补偿脚本 v2） |
| 分析耗时 | 27.7 秒 | 46.3 秒（多一次 Scout 调用） |
| RepositoryProfile evidence | 26 | 26 |
| 其中业务实现依据 | 0（按 Round 1 §10 记录） | 14 |
| reusableAssets 中的实现类 | 0/10 | 6/10 |
| Profile 是否指出自身材料盲区 | 否 | 是（limitations 第 1、8 条） |
| 方向数 | 5 | 4 |
| 方向引用的 Repository 文件 | 16，0 业务实现 | 17，11 业务实现 |
| 复用维度 | 塌缩为 2 类 | 4 类，无一重复 |
| 方向驱动来源 | 2/5 由仓库工件驱动（形态 B） | 1/4（D3），且依据落在实现上 |
| 语义错配 | D4 的 userFit 引用反向依据 | 未复现 |

### 明确回答 §1 的问题

```text
Q3 「新 Profile 是否描述了仓库实际实现了什么，而不只是工程形态？」
   是。capabilities 10 条里 9 条是业务能力（三级缓存读、防穿透/击穿/雪崩、秒杀下单、
   三层幂等、缓存失效写链路、库存补偿、用户签到、社区互动、GEO 附近查询），
   只有第 10 条是工程验证能力。

Q4 「重要能力是否有实现级依据？」
   是。26 条 evidence 中 14 条直接指向实现文件；reusableAssets 中的
   MultiLevelCacheServiceImpl / seckill.lua / SeckillOrderConsumer / CacheClientUtils
   各自都有对应的实现文件依据。

Q5 「可复用资产是否已经是具体实现资产？」
   大部分是。10 项中 6 项是具体实现，2 项是脚本，1 项编排，1 项文档。
   Round 1 的「CaffeineConfig + RocketMQConfig + 文档体系」这一组合已被实现资产取代。

Q6 「是否仍有重要能力只能靠文档/配置支撑，而实现其实存在？」
   有，但已不是主要形态。`CacheEvictionConsumer/Producer`、`ShopCacheVO`、
   `RefreshTokenExpirationInterceptor`、rest of controllers 等实现文件
   被 Scout 选中却没有被读取（§5.4 的 52 个），
   因此「缓存失效广播」这条能力在 Profile 里仍主要靠 `docs/10` + `CacheClientUtils` 支撑，
   而 `CacheEvictionProducer.java` 本身没有进入材料。
```

---

## 10. Failure-Stage Diagnosis

按任务书给出的类别逐条核对本轮结果：

```text
A. Repository Map / classification blind spot
   未观察到。84 个源码候选完整覆盖 controller/service/entity/mapper/mq/job/event/
   interceptor/utils/lua 全部业务实现目录，Scout 也全部看到了。

B. Scout 在候选良好时选了差文件
   未观察到。9/9 controller、19/19 service、15/15 entity+dto+vo、5/5 mapper、
   4/4 Lua 全部被选中；70 个引用零越界、零重复。

C. Scout 选了好文件但被预算挡下
   部分成立，但**不是失败**。Scout 指出 70 个文件，只有 18 个进入材料，
   差额 52 个全部由 planner 的 maxFiles=18 挡下。
   代价具体可见：entity/DTO 全类被挡下，导致 Profile 的 limitations 第 1 条
   「材料中不含数据库表结构与实体/DTO 类文件」。

D. 文件被读了但最终分析没能提取实现事实
   未观察到。18 个源码 + 12 个基础材料 → 10 条业务能力、6 个实现级可复用资产、
   14 条实现级依据，且指出了代码注释里作者自己写下的未覆盖项。

E. Profile 改善了但 Product Direction 没能用上
   未观察到，且是本轮改善最明显的一环（§9 对照表）。

F. Evidence 引用有效但语义不支撑
   残留 1 例：D3 的 userNeed（见 §8.2）。其余 3 个方向未观察到。
   这仍然是「系统不检查语义支撑度」这一结构性问题的表现（Round 1 F1 未变），
   不是本轮 Repository Understanding 的问题。

G. 观测不足以判定阶段
   规划期跳过诊断 NOT OBSERVABLE（§5.4）。本轮该缺口没有妨碍结论，
   因为 30/30 全部准入、执行期零剔除，唯一约束是通道级 maxFiles。
```

**结论：本轮没有出现需要升级到新机制的失败模式。**
唯一一处「部分成立」是 C，而它的性质是**预算取舍**，不是机制失效——
Scout 指出的区域质量没有问题，是 18 个名额的分配问题。

---

## 11. Budget Observations

当前取值（`delveforge.repository-analysis.*`）与本轮实测：

| 通道 | maxFiles | maxFileBytes | maxTotalBytes | 实际文件 | 实际字节 | 触顶项 |
|---|---|---|---|---|---|---|
| Foundation | 12 | 32 768 | 98 304 | 12/12 | 33 036（34%） | **maxFiles**（并另有 1 个候选因 maxFileBytes 被跳过） |
| Targeted Source | 18 | 65 536 | 163 840 | 18/18 | 108 281（66%） | **maxFiles** |

逐条回答：

```text
maxFiles 是否触顶？              两条通道都触顶，是唯一真正约束了准入数量的约束。
是否有很多文件被 maxFileBytes 挡下？
                                 Foundation 1 个（db/hmdp.sql，151 948B），Targeted Source 0 个。
                                 它不是「很多」，但它恰好是 DATA_SCHEMA 类别唯一的候选，
                                 因此一个文件就让整个类别在本次材料中不可见。
maxTotalBytes 是否实质约束？      没有。两条通道分别只用了 34% 与 66%。
更小的文件是否在大的被挡下后继续进入？
                                 是，但只在类别内部：DATA_SCHEMA 无后备候选，
                                 所以该类别本轮没有替补。
Targeted Source 是否拿到了足够的真实实现内容？
                                 是。18 个文件 108KB，包含三级缓存完整实现、秒杀下单、
                                 消费者幂等、4 个 Lua 脚本、3 个 controller。
Foundation 是否在没饿死 Source 的前提下仍然有用？
                                 有用。7 个类别各拿到 1–2 个名额。
```

### 三条被观察到的边界

初稿把「DATA_SCHEMA 0 个名额」与「三份 jmeter 数据文件占掉 25% 预算」记为
**类别之间平权**的问题。用真实 Map（`18e6b63c…`）、生产规划器与真实预算独立重放之后，
这个归因**不成立**——重放逐字节复现了本轮的 12 个文件选择，而机制是下面三条。
本节只把它们记为**重访证据**，不据此提出任何规则或改动。

**边界 1 — 整份大文件可以超过 Foundation 的 `maxFileBytes`。**

```text
src/main/resources/db/hmdp.sql    DATA_SCHEMA    151 948 字节
                                  Foundation maxFileBytes = 32 768（超出 464%）
规划期处置                        SELECTED_BUT_TOO_LARGE（不是被别的类别挤掉）

DATA_SCHEMA 在本次 FOUNDATION 候选里只有这一个文件，因此该类别必然是 0。
这与类别之间的优先级无关：即使给它最高的优先级，结果也一样。
```

**边界 2 — 类别内的相对路径序，在文件数上限下决定同类文件里谁被选中。**

```text
FOUNDATION 的 CONFIGURATION 候选共 11 个，按相对路径升序：
    .gitignore
    jmeter/round_state_b1_r1.json
    jmeter/round_state_b1_r2.json
    jmeter/round_state_b1_r3.json
    jmeter/round_state_b2_100.json
    jmeter/round_state_b2_1000.json
    jmeter/round_state_b2_500.json
    rocketmq/conf/broker.conf
    rocketmq/conf/proxy.conf
    src/main/resources/application.yaml
    src/main/resources/mapper/VoucherMapper.xml

CONFIGURATION 在 maxFiles=12 下得到 2 个名额 → .gitignore 与 round_state_b1_r1.json。
application.yaml 排在同一类别的第 10 位，名额在此之前已经用尽。
```

也就是说：`application.yaml` 的落选发生在**类别内部**，由确定性的相对路径序与
文件数上限共同决定，而不是被别的材料类别抢走。

**边界 3 — 按扩展名的分类会把运行时/状态 JSON 与真正的配置放在一起。**

```text
jmeter/round_state_b1_r1.json  → CONFIGURATION（.json ∈ CONFIGURATION_EXTENSIONS）
src/main/resources/application.yaml → CONFIGURATION
src/main/resources/mapper/VoucherMapper.xml → CONFIGURATION
jmeter/shop_ids.csv → OTHER
jmeter/null_shop_ids.csv → OTHER
```

本轮实际准入的 `OTHER` 是 **2 个**（两张 shopId CSV），不是 3 个；
第三份被记为 `OTHER` 的 `round_state_b1_r1.json` 实际属于 `CONFIGURATION`。
两张 CSV 本身在 Round 2 的 Profile 里是被引用的依据（空值穿透测试载荷），
不是无信息的填充物。

### 关于「按类别分层优先级」这一解读

曾经考虑过一种读法：把材料类别分成 HIGH / NORMAL / LOW 三层，按层先后调度。
**该解读没有实现，也不被本轮 smoke 证据支持。**

```text
在同一份真实 Map 与同一组预算上模拟「严格按层穷尽」的结果：
    selected = 12   字节 13 603（本轮实际 33 036）
    SOURCE_CODE 5 / BUILD_METADATA 1 / CONFIGURATION 5 / DEPLOYMENT 1
    DOCUMENTATION 0   SCRIPT_AUTOMATION 0   OTHER 0   DATA_SCHEMA 0
    其中 CONFIGURATION 的 5 个里有 4 个是 jmeter/round_state_*.json

- db/hmdp.sql 仍为 0（边界 1 是尺寸问题，层优先级管不到）；
- application.yaml 仍选不中（它在同类内仍排最后，而名额在层内就已用尽）；
- docs/1、docs/10、check_seckill.py、count_orders.py 会被挤出材料——
  而这四项正是本轮 Profile 与方向 D3/D4 实际引用的依据。

在现有类别枚举序下，另一种更宽松的读法（每轮内先 HIGH 再 NORMAL 再 LOW）
与现状等价，唯一差别是 DEPLOYMENT 的位置。
```

本轮**没有修改任何一行生产代码**；上面只是对同一份输入的一次只读推演，
用于判断该解读是否值得落地。结论是：它不解决边界 1 与边界 2，并会减少边界 3 之外的可用材料。

---

## 12. Revisit-Condition Evaluation

### ADR-0004 的 5 条 Revisit Conditions

```text
1. Scout catalog 过大
   否。84 条描述符 14 837 字节，上限 65 536，占用 22.6%。离上限还有 4.4 倍空间。
   按此推算，即使仓库规模再大 4 倍也不会触发。分层 Scout 在本轮没有证据支持。

2. 许多重要被选中的文件超过单文件读取上限
   否（1 个，不构成「许多」）。Targeted Source 通道 0 个：最大的
   MultiLevelCacheServiceImpl（29 074B）在 V1 下正是被整份跳过的那个文件，
   现在完整进入了分析。Foundation 通道 1 个：db/hmdp.sql（151 948B）。
   该条条件本身没有触发，但它是 §11 边界 1 的来源。

3. 出现带具体目标导向的检索需求
   否。Repository Analysis 仍然发生在 Product Direction 之前，没有查询意图。

4. 需要 implementation-level 的依赖与位置信息
   部分。本轮 Profile 已经能指出「某能力的实现在哪个文件」，但**还不能**回答
   「这个能力被谁调用、依赖谁」。Evolution Planning 是否真的需要后者，
   目前没有证据——Round 2 的方向生成没有因为缺少调用图而受限。

5. Map 的启发式分类在多个真实仓库上反复误判
   否（仅一个真实仓库，且分类结果与 Task 1 基线一致）。
   §11 边界 3 记录了一个**与本条件同类但程度更轻**的现象：
   `.json` 让运行时状态转储与真正的应用配置落在同一个类别里。
   本轮只有这一个仓库、这一处，不足以支撑「反复误判」。
```

### M1 §9 的 5 条 Revisit Conditions

```text
1. M2 Product Direction 因为 RepositoryProfile 缺少关键实现事实而明显失真
   → 本轮已修复。Round 1 是 TRIGGERED 的那一条，Round 2 不再成立。

2. 在多个真实 Repository 上都采样不到业务主体
   → 仍然是单一仓库，未验证。但本轮采样不到业务主体的问题已消失。

3. Product Direction / Evolution Planning 明确需要 implementation-level facts
   → 部分成立（同 ADR-0004 第 4 条），但没有实际的阻塞证据。

4. 大型 Repository 让当前 sampling 明显失效
   → 未验证（仍是 139 个 blob 的仓库）。

5. context budget 成为真实的 Provider failure 来源
   → 否。三次真实调用的 prompt 分别是 544 / 4 593 / 41 436 tokens，
     均在 provider 上限内，没有发生超限失败。
```

---

## 13. Remaining Risks

```text
R1  Scout 的稳定性尚未验证
    本轮只有一次真实 Scout 调用。6 个区域的质量、70/70 的引用合法率都是**单次结果**。
    Round 1 的 User Discovery 第一次调用就因解析失败 502，
    说明「一次成功」不能推出「每次成功」。需要多仓库、多次运行才能判断。

R2  Foundation 材料在三种情况下会缺席（§11 三条边界）
    1) 整份文件超过 maxFileBytes：一个 151 948B 的 db/hmdp.sql 就让 DATA_SCHEMA 整类不可见；
    2) 类别内相对路径序 + 文件数上限：CONFIGURATION 有 11 个候选、只有 2 个名额，
       application.yaml 排第 10 位而落选；
    3) 扩展名分类把运行时状态 JSON 与真正的配置并入同一类别。
    目前由模型自己在 limitations 里标注盲区来兜底，但这依赖模型愿意说。
    本轮只有这一个仓库、一次运行，尚不足以判断是否会在其它仓库重演。

R3  52 个 Scout 选中的文件没有进入分析，其中 entity/DTO/mapper 整体缺席
    本轮方向质量没有因此受损，但 Evolution Planning 阶段可能需要数据模型事实。

R4  规划期跳过诊断不可观测（§5.4）
    本轮不影响最终结论，但它确实让一条真实存在的规划期跳过（db/hmdp.sql）
    只以「某类别为 0」的形式浮现，并使初稿给出了错误的归因；
    该条跳过是在事后独立重放时才被看到的。

R5  Evidence 的语义支撑度仍然没有任何系统级检查（F1）
    Round 1 的错配在本轮没有复现，但那是因为模型这次没有犯，
    不是因为系统能够发现它犯错。

R6  单一仓库、单一用户画像
    本轮的改善是在黑马点评（Java/Spring Boot/缓存/秒杀）上测得的，
    而当前用户画像的技术栈恰好与它同源。换一个技术栈不匹配的仓库是否同样改善，未知。
```

---

## 14. Final Conclusion

### 分项结论

```text
production pipeline correctness   PASS
    真实 HTTP / 真实 Git / 真实 Provider / 真实 SQLite 全链路跑通：
    Scout 成功且 70/70 引用合法；30/30 材料全部准入；执行期零剔除；
    Profile 26 条依据全部指向真实读过的文件；4 个方向全部 CANDIDATE；
    POST/GET 4/4 语义相等；五张表行数与方向数、依据数逐条对应。
    运行期间 M2 链路无 ERROR（唯一的 ERROR 来自 §2.3 的观测工具缺陷）。

Scout usefulness                  PASS
    6 个区域全部指向真实业务实现；9/9 controller、19/19 service、
    15/15 entity+dto+vo、5/5 mapper、4/4 Lua 被选中；0 编造引用。
    label 只是选文件假设，但作为假设它们全部成立。

business-implementation coverage  PASS
    18 个业务源码进入最终分析（V1：0）。包含 V1 因体积被整份跳过的
    MultiLevelCacheServiceImpl（29KB）。证据中 14/26 是实现级。

RepositoryProfile quality         PASS（有一处已知边界）
    capabilities 10 条中 9 条是业务能力；reusableAssets 10 项中 6 项是具体实现；
    limitations/risks 里出现了只有读过代码才能写出的内容（含代码注释中的未覆盖项）。
    边界：应用配置与数据库 schema 不在材料内，模型如实标注为不可确认。

Product Direction improvement     PASS
    方向引用的 17 个 Repository 文件中 11 个是业务实现（Round 1：16 个中 0 个）；
    复用维度不再塌缩；Round 1 的语义错配未复现；
    「把仓库工件产品化」形态从 2/5 降到 1/4 且依据落在实现上。

current budget adequacy           ADEQUATE（名额是真实约束）
    两条通道都因 maxFiles 触顶；字节分别只用了 34% 与 66%——
    准入数量由名额决定，这点没有变化。
    Foundation 另有 1 个候选（db/hmdp.sql，151 948B）因 maxFileBytes 被跳过，
    它恰好是该类别唯一的候选。
    本轮没有证据支持调整任何预算数值：既没有出现「名额不够导致材料不足」，
    也没有出现「总量不够」。

need for further Repository Understanding escalation
    STOP
```

### STOP — V2 对当前里程碑已经足够

**理由不是「结果看起来不错」，而是「没有观察到需要新机制的失败模式」：**

```text
- ADR-0004 的 5 条 Revisit Conditions 中，4 条不成立，1 条（implementation-level
  依赖信息）没有实际的阻塞证据；
- M1 §9 触发本轮重访的那一条（条件 1）已经不再成立；
- 唯一「部分成立」的失败类别是 C（Scout 选了好文件但被预算挡下），
  它的性质是取舍分配，不是机制失效——带宽足够（占用 22.6% / 34% / 66%）；
- 没有任何一条证据支持 RAG / Embedding / AST / chunking / 分层 Scout。
```

### §11 三条边界的定位

§11 记录的边界 1–3 都是**观察到的现象**，不是新规则，也不构成对实现的建议。
本轮不对它们做任何处置。它们各自已经在现有文档里有归属：

```text
边界 1  整份大文件超过单文件上限   → ADR-0004 Revisit Condition 2（本轮 1 例，未触发）
边界 2  类别内路径序 + 文件数上限  → 现有确定性行为，本轮首次观察到其后果
边界 3  扩展名把状态 JSON 并入配置 → ADR-0004 Revisit Condition 5（本条件的程度未达到）
```

是否要据此改动实现、改动哪一处、什么时候改动，属于 M2 之后的独立决策——
而且到目前为止，能支持这类决定的是**一个仓库的一次运行**，样本量还不足。
本轮在此只做记录，不做推荐。

---

## 15. 如何复现

```text
1. 构建并验证：
     ./mvnw verify          → 846 tests, 0 failures

2. （可选，为了观测 Scout 原文）启动本地观测中继，把 base-url 指向它。
   中继必须支持 **chunked** 请求体——Spring RestClient 不使用 Content-Length。

3. 以正常方式启动 Backend（Provider 凭据由环境变量提供，本记录不含任何凭据）：
     java -jar backend/delveforge-app/target/delveforge-app-0.1.0-SNAPSHOT.jar \
       --delveforge.persistence.database-file=<新的临时路径> \
       --server.port=<端口>

4. 建立与 Round 1 语义一致的 Confirmed UserProfile：
     POST  /api/user-profiles
     PATCH /api/user-profiles/{id}                    （六个内容区 + additionalEvidence）
     POST  /api/user-profiles/{id}/sufficiency-assessment
     POST  /api/user-profiles/{id}/confirm            （body: {"revision": <当前 revision>}）

5. 建立 RepositoryProfile（真实 Git + 两次真实模型调用：Scout + 分析）：
     POST /api/software-assets                        （location 指向本地 Repository）
     POST /api/software-assets/{id}/analysis

6. 执行发现：
     POST /api/product-directions/discovery
          {"userProfileId":"...","expectedRevision":N,"repositoryProfileIds":["..."]}

7. 回读并核对：
     GET  /api/product-directions/{id}
     比对五张 product_direction* 表的行数
```

真实模型调用使输出**不可复现**：同一组输入再跑一次会得到不同的区域与方向。
可复现的是**结构性质**——引用合法性、材料规模、依据的 origin 正确性、
POST/GET 一致性、各表行数关系。

### 与 Round 1 文档的关系

Round 1 记录的是 V1 material selection 的后果，**本轮不修改它**。
两份文档应并列阅读：Round 1 §10 的 TRIGGERED 判定是本轮重访的直接依据，
本轮 §9 的对照表给出重启后的实际变化。
