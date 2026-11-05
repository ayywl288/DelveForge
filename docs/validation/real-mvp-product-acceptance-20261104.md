# 真实后端 MVP 产品验收（2026-11-04）

**功能实现、完整测试、独立业务验收和正常 Git Delivery 均已实测通过；本轮严格验收流程判定 FAIL。**
现有产品在 Candidate 冻结后自动交付，早于本次要求的独立业务验收。执行者启动前漏查了这一行为，不能把事后成功的复核追认为“先验收、后交付”。没有追改时间线、回滚重演或追加付费 Run。

## A. 工程与真实项目

已审查 `7a240af` 并正常合并到本地 main：`63104f6`。未强制合并、删分支、改历史或推送。合并后重新 package，内嵌三个模块逐字节等于 reactor 构件、五个沙箱脚本等于源码，并核对新错误反馈标记。此次打包跳过测试，不计为新增测试通过；原修正验证为 1861 通过/58 跳过、Docker 23/1、Python 46/0。

| 项目 | 本次固定事实 |
| --- | --- |
| 仓库 | [spring-petclinic/spring-petclinic-rest](https://github.com/spring-petclinic/spring-petclinic-rest/tree/5180cc9bd0163393c4e628bedc1bc4c27cfaab1b) |
| License | Apache-2.0，核对原 LICENSE.txt |
| Commit A | `5180cc9bd0163393c4e628bedc1bc4c27cfaab1b` |
| 原 tree | `5b86dfa96cf739cb07e8afc2af5734784f3fc251` |
| 规模 | 154 个跟踪文件，87 个生产 Java 文件、22 个测试 Java 文件 |
| 已有业务 | 主人、宠物、兽医、就诊记录的 REST 管理；长期维护的真实示例应用，非空脚手架 |
| 技术 | Spring Boot 4.1.2、Maven、MVC/Data JPA/JDBC、MapStruct、OpenAPI；本次 JDK21 |
| 独立运行 | 默认内存 H2，无生产凭据；HTTP 验收使用真实 Spring 服务和持久化 |

这是刻意选取的后端验收样本，不是原随机全栈试验续跑。源、数据库、正式工作区和 Agent 根目录均在 `data/real-mvp-acceptance-20261104/` 下独立保存。

执行包 SHA256：`a2563fad38beea90f32ef3316a60cb839ada127d93d04168dac58c5e485ed1d9`。
镜像 ID：`sha256:0028af7e325a8ff25bdcb6d0bb66f9f1b85eb0589f692010e2b3a4889918cb22`。
镜像 sandbox.py 与包/源码 SHA256 均为 `6720304167eacee88fd40f894771e0098460eb5b2503832155a9d5ae22cc8725`。

## B. 就绪与正常产品流程

任何模型调用前，真实 Git/Docker Adapter 的独立只读基线检查经历：冷缓存停于 MapStruct processor 缺失，0 测试；暖缓存测试通过但 Windows 1455/内存不足中断了完整 verify 记录与清理；用户释放进程后，按所有权清理本次中断探针并重查同一版本。最终 **预检 READY、完整 Maven verify 均为 248 executed、0 failure/error/skipped**。先前记录保留，没有修改上游源码、依赖、测试或安全策略。

沙箱为非 root、只读根、执行网络 none、2 GiB/2 CPU/256 pids，依赖由原受限代理准备。执行视图 PROJECTED，10 项 preserved-only、0 项 withheld source；没有放宽凭据保护。

| 产品对象 | 实际身份与动作 |
| --- | --- |
| UserProfile | `bcb95b04-9b0a-4371-8040-d2f7b4d16b40`，用户明确确认 revision 16 后，正常 API 进入 CONFIRMED |
| 复用信息 | 仅复用原 CONFIRMED Profile `704880db-d392-46a0-9500-ad69a2140556` r31 的“使用 Java 编写后端服务”；未套用旧兴趣/目标或虚构角色技能 |
| SoftwareAsset | `46fb3a11-43a0-42fc-86c1-df81a2f0eaa0`，正常注册/授权 API，最终明确 Apache-2.0、ALLOWED、读许可 |
| RepositoryProfile | `33d483e6-b94c-4928-a030-6b577e35673e`，真实模型只读分析，analyzedRevision=A |
| 项目作用域 | `127c07f1-e02f-45d5-8e89-ee095e8b1c1b`，独立 flow |
| Direction | `6396d65e-1bab-426c-a2dc-4bc8ae8f4b38`，4 个真实候选中，用户明确委托选择生产守卫方向后正常 SELECTED |
| Plan / Step | `71352d4a-5d26-4802-b4fb-1d1b0caf9c0e` / `466807f3-db98-48e0-b250-1cc58aa0acfe`，唯一 Step，核对既定范围后正常确认 |
| WorkingCopy | `fbf8ee6e-eb11-41ba-8610-daf00e482d6d`，正常 prepare；执行前 source/prepared/current/lastVerified 均为 A，154 文件与原源相同 |

没有手工构造领域记录或 SQL 改状态。执行者首次误用 licenseNote 字段，规划前被 ASSET_LICENSE_INFORMATION_REQUIRED 拒绝，随后正常授权 API 补记已核验的 licenseInfo。首份模型规划又因限制条目不是 Base Profile 原文被 CURRENT_LIMITATIONS_NOT_FROM_BASE_PROFILE 拒绝；保留 502，通过现有 planningNotes 给出契约反馈后接受单步提案，未改校验器或手造计划。

## C. 唯一真实 Coding Agent

Run：`4d713d1d-f09a-4cb7-97d2-13d8dd72e2e5`。正常费用确认 API，JDK21，完整检查入口 `.`；真实 DeepSeek 配置 model 为 `deepseek-flash`。未手修目标，未追加正式 Run。

| 指标 | 实测 |
| --- | --- |
| 结果 / 耗时 | SUCCEEDED / CANDIDATE_FROZEN，约 5 分 30 秒 |
| 模型 / 工具 | 22 次模型、28 次工具 |
| Token | prompt 374,738；completion 5,323；合计 380,061 |
| 缓存 | 已报告 cache-hit 331,648；cache-miss 未提供，不能填 0 |
| 工具 | search 6、read 10、list 1、edit 7、checks 4 |
| 修改 | 控制器 3 次、测试文件 4 次成功编辑；最终仅 2 个文件变化 |
| 检查收敛 | 定向 40 项：2 failure → 1 failure → 全通过；再完整 254 项全通过，无跳过 |

新增 6 项回归测试，既有方法/断言未删除或禁用；两处成功用例补充了真实归属夹具。模型在完整检查后才声明完成。本次未观察到补丁冲突、普通读取错误或 NO_PROGRESS，不把确定性回归当成本次实际恢复经历。

单次 prompt 最高 40,272 Token，未见明显压缩迹象；日志无独立压缩计数，不精确断言压缩次数。小范围、干净基线下有可测收敛，不能据此推断大项目可靠性或模型私下思考。前置发现/方向/规划另有 8 次请求、104,940 Token；本轮共记录 **30 次 Provider 请求、485,001 Token**。completion 已含提供者计入的生成用量，不再次叠加 reasoning；未核验金额，不编造货币成本。

## D. Candidate 与独立业务验收

| 项目 | 封存证据 |
| --- | --- |
| Candidate | `f2eca05d-6e35-468c-af1e-3c3b426e785b`，来自上述 Run，baseline=A |
| 等级 | v5 FULLY_TESTED，baselineDebt=[] |
| 目标 tree B | `47e3a21630bd232366fa20405d3343c9efb3a362` |
| diff SHA256 | `0656fc21e5d85165a6d19c9dede469085bb2fc129cc4707b1063392a8b3f6a69` |
| evidence SHA256 | `622c74b381cab905ff729cc93b040ff7a7c619b4e6e790ef3e6bfd4265e534fe` |
| 最终源码快照 | `dca07d5050cea8e424543b6bab3866a66eef727fb0dca9090b7ef89159096e47` |

只改 OwnerRestControllerV1.java 与 OwnerRestControllerV1Tests.java。生产代码在对象修改、savePet/saveVisit 前检查双方存在性和关联，成功路径保持。

合同在任何模型调用前固定。沙箱按原策略清理后，将封存 diff 机械重放到独立验证副本并核对 tree=B；它不是正式 WorkingCopy，不构造 Candidate 或接受修订。按现有内容策略准备同一投影视图，在无网络只读输入容器中完整离线 verify：**254 通过，0 失败/错误/跳过**。前后快照均等于封存值，随后运行真实 Spring/H2 服务：

| 用例 | 观察 | 结果 |
| --- | --- | --- |
| A1 匹配更新 | 204，后续 GET 为更新值，归属保持 | PASS |
| A2 错配更新 | 404，宠物和就诊未变 | PASS |
| A3 缺失主人更新 | 404，状态未变 | PASS |
| A4 缺失宠物更新 | 404，无实体创建 | PASS |
| A5 匹配新增就诊 | 201，按返回 ID 读到正确 petId/日期/描述，计数增加 1 | PASS |
| A6 错配新增 | 404，记录不增加 | PASS |
| A7 缺失主人新增 | 404，记录不增加 | PASS |
| A8 缺失宠物新增 | 404，记录不增加 | PASS |
| A9 读取/无关数据 | 嵌套读取 200，无关主人/宠物未变 | PASS |
| A10 非法请求体 | 两个写接口均 400，状态未变 | PASS |

外部驱动两次自身错误记录保留：遗漏保留资源身份导致快照不一致；补齐协议元数据后完整 verify 通过，运行包定位命令引用出错。修正启动器后复用同快照/同镜像全量验证，仅以 package -DskipTests 重建运行包；该打包不计为新测试通过。最终 HTTP **10/10、0 跳过**，未修改 Candidate、目标代码或成功标准。

## E. 正式交付、完整性与时序偏差

Delivery 的唯一键就是 Candidate ID，不另造 Delivery UUID。其状态 COMPLETED，接受修订：
`37ead350afef9b84d8d9d3ad7a8d29a10d1a6115`，父提交=A、tree=B。
Plan COMPLETED、Step SUCCEEDED、WorkingCopy CLOSED，currentRevision=lastVerifiedRevision=接受修订。
正式 Git 工作树干净，只改上述 2 文件，全部 154 个 Git blob 与独立测试见证一致。两个改动文件在 Windows 副本为 CRLF、Linux 见证为 LF，仅行尾不同，不宣称物理字节完全相同。

原源 154 文件、HEAD/tree/index/状态未变；旧随机原源/正式副本各 360 文件及既有失败、用量、保留标记未变。新产品库仅 1 Run/1 Candidate/1 Delivery，occupied 已释放。

**时序未满足要求。以下均为 America/Los_Angeles、2026-11-04：**

| 时间 | 原始证据 |
| --- | --- |
| 21:19:34 | 事件 109，Candidate durably frozen |
| 21:19:47 | 事件 110，自动 DELIVERY_INTENT |
| 21:20:04 | 事件 115，DELIVERY_COMPLETED，正式提交被接受 |
| 21:32:56 | 独立 HTTP 结果文件写入，10/10 |
| 此后 | 显式 POST delivery 返回已有 COMPLETED，是幂等读回，非首次交付 |

CandidateDeliveryService.start() 定期发现 pendingCandidates() 并 submit，没有本次外部业务验收前置条件。执行者启动前漏查了这点，不应假定交付会等待外部验收。它是现行自动交付行为，不能改称人工请求之后才发生。

## F. 最终判定与下一步

| 判断 | 结果 |
| --- | --- |
| DISCOVERY / ANALYSIS VERIFIED | PASS |
| DIRECTION / PLANNING VERIFIED | PASS |
| REAL CODING AGENT VERIFIED | PASS |
| CANDIDATE VERIFIED | PASS |
| BUSINESS ACCEPTANCE VERIFIED | PASS |
| DELIVERY VERIFIED | PASS，实际提前发生 |
| 先独立验收后 Delivery | **FAIL** |
| 本轮严格 MVP E2E 验收 | **FAIL**，六项能力证据不能替代必需时序 |

本次只验证后端范围及默认 H2/Spring Data JPA 的真实 HTTP；没有另验前端、外部数据库或所有鉴权配置。RepositoryProfile 选材也未覆盖所有测试/API 版本，局限保持。未放宽路径/凭据/隔离、重构编辑工具、增设累计用量限制、引入 checkpoint/resume 或推送。

最小后续动作：若仍要求外部验收前禁止交付，则先使“新 Candidate 自动提交”可按运行配置关闭，保留已存在 Delivery 的恢复和原显式交付 API；用确定性测试证明验收前无正式 Git 写入、显式提交后可完成。其代价是增加一种运行方式，须明确采用；本次不隐式改动交付语义，也不追加付费 Run。另一选择是用户明确接受“自动交付后独立复核”的协议，不能倒改本轮记录。

## 可复盘材料

- [结构化证据](real-mvp-acceptance-20261104/evidence.json)：身份、时间线、检查、用量和判定。
- [封存 Candidate](real-mvp-acceptance-20261104/frozen-candidate.json)：原始 diff/testEvidence 字符串及哈希。
- [预设合同](real-mvp-acceptance-20261104/acceptance-contract.json)、[HTTP 驱动](real-mvp-acceptance-20261104/business_acceptance.py)。
- target/real-mvp-acceptance-20261104/ 保留 API、探针失败、构建、重放及完整性记录；data/real-mvp-acceptance-20261104/ 保留真实数据库、原源和正式接受副本。

面试可只读查看本机项目 API 的 project:127c07f1-e02f-45d5-8e89-ee095e8b1c1b、Run、Candidate、Delivery 及正式接受提交。重演业务时在 tree B 对应的新隔离应用中运行 `python business_acceptance.py http://127.0.0.1:9966/petclinic`；驱动会修改隔离内存数据库，应使用新实例。复盘无需追加模型调用，并须说明自动交付时序限制。