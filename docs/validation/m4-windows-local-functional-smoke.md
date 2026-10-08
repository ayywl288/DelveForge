# M4 — Windows Local Functional Smoke

**Status:** Validation Record / **Not Source of Truth**

**Date:** 2026-10-08（UTC+08:00）

**Verdict:** **M4 REAL SMOKE: BLOCKED**

最新尝试为下方 §7 的 **Final Analyzer Retry Stabilization** 记录；§1–6 保留此前 BLOCKED 的历史证据。

本次按用户更新的边界做受控 Windows 本地功能尝试，不使用 Docker、VM、容器或沙箱框架。
应用启动成功，但首次 M1 AI 请求被诊断转发工具拒绝，未到达真实 Provider。
因此没有完成 M1→M4，也没有证明真实演化成功；不能归因于模型质量或 M4 执行正确性。
此前 `m4-real-provider-self-hosting-smoke.md` 保留为不同安全前提下的历史检查记录。

## 1. 凭据隔离修复与验证

- 固定实现 / 源码 revision A：`9eac80c5683c1d8eb5e8be08f3148dce5df2cc61`。
- `WorkspaceCommandRunner` 启动受控 Git / Maven 进程前移除 DeepSeek key 的环境变量别名，
  以及可能携带完整凭据配置的 `SPRING_APPLICATION_JSON`。
- `GitWorkspaceAdapter` 独立的读取 / provisioning 进程也使用同一凭据过滤。
  过滤只改变子进程环境，不改变父应用的 Provider 配置；原有验证与环境注入限制保留。
- 回归覆盖配置别名、父环境不变，以及实际父子 Java 进程：`parent=true;child=false`。
  使用非秘密 sentinel，不使用真实 key 做自动化测试。
- 定向 `WorkspaceCommandRunnerTest`：4 tests，0 failures / errors / skipped。
- 完整 `mvnw.cmd verify`：**1310 tests，0 failures / errors / skipped，BUILD SUCCESS**，耗时 07:55。
  Domain / Application / Infrastructure / App 分别为 216 / 713 / 212 / 169。
  这是修复的确定性验证，**不是 smoke 中的 Candidate 验证**。
- 未修改 Domain、AI prompt、Git mutation、验证证据规则或生命周期。

## 2. 固定制品与运行环境

- 原 SoftwareAsset 目标：`E:\develop\DelveForge`，起始 HEAD=A，工作树干净；
  未发现 assume-unchanged / skip-worktree 标志。
- JDK：`E:\develop\jdks\jdk-21.0.12.1`；现有 Maven Wrapper 3.9.14。
- 固定 JAR 来自上述完整验证，复制到仓库外 `artifact/app.jar` 后启动，不从 WorkingCopy 运行应用。
- JAR SHA-256：`472003c81c91711fc90e04a2d48b9e1824114a5e947bf3450efa4ea4f8e07629`。
- 全新独立目录：`E:\df-m4-smoke\20261008-local1`。
  SQLite=`data/smoke.db`，Workspace=`workspaces`，日志=`logs`，证据=`evidence`，临时文件=`tmp`。
- 应用仅绑定 `127.0.0.1:18784`；启动成功，PID 50212。停止后无本次服务监听。
- Disposable key 通过不回显输入进入启动器内存，再传给应用环境；未放进命令参数或配置文件。
- 模型保持配置默认 `deepseek-flash`，单次调用 timeout=60s。
- 验证覆盖仅使用已授权预算：

```text
--delveforge.workspace.verification.max-test-reports=160
--delveforge.workspace.verification.action-timeout=900s
--delveforge.workspace.verification.invocation-timeout=1200s
--delveforge.workspace.verification.max-output-bytes=8388608
```

M2 / M4 材料、Scout、扩展与 Proposal 预算保持当前配置；未更改测试选择、POM、Wrapper 或信任规则。

## 3. 实际 API 流程与阻断位置

1. `POST /api/user-profiles` → **201**，约 0.132s。
   Profile=`c297b95d-d008-4b1f-8c78-c30f47381e4e`，`EXPLORING / revision=1`。
2. `POST /api/user-profiles/{id}/discovery-turn` → **502**，约 0.085s，
   `EXTERNAL_CAPABILITY_UNAVAILABLE`。
   输入如实说明 Java / Agent 学习、维护者授权与本次小型仓库分析改进目标。
   候选是 `RepositoryPathClassifier` 的 Python 下划线文件名角色提示及正负回归测试，
   要求完整 Maven TEST，不预制 Step、Criteria、Proposal 或验证结果。
3. 只读 `GET /api/user-profiles/{id}` → **200**。
   Profile 仍为 `EXPLORING / revision=1`，全部内容与 evidence 为空。

为记录真实执行中使用的 Proposal 与选材路径，本次使用仓库外的透明本地诊断转发，
准备原样转发到 DeepSeek HTTPS，不修改请求或模型响应、不制造输出、不做重试。
**工具存在传输兼容性错误**：只接受 `Content-Length`，没有处理 chunked 请求体。
Spring/JDK 发出的 JSON 请求使用 `Transfer-Encoding: chunked`，被工具前置长度检查拒绝，
应用将该 HTTP 错误映射为上述 502。

停止后，用相同缓存 Spring/JDK 库和非秘密 sentinel 做完全本地诊断，确认请求路径为
`/chat/completions`、`Content-Length=null`、`Transfer-Encoding=chunked`，
拒绝产生的异常类型与本次日志一致。该诊断不访问 Provider、不调用产品 API，也不重试 smoke。
上游调用计数为 **0**；没有真实模型输出可供评价。

本次按一次有界尝试的限制停止，没有修正转发后重新运行，没有 Repair / Retry / Rollback。
后续独立尝试需要先离线验证诊断工具的 HTTP framing，或直接使用已存在的 Provider HTTPS 配置；
不需要修改产品验证规则。

## 4. 状态、完整性与保留证据

- 数据库只存在 1 个 UserProfile；SoftwareAsset、RepositoryProfile、ProductDirection、
  EvolutionPlan、EvolutionStep、WorkingCopy 均为 **0**。
- Workspace 目录为空；没有执行 `/confirm`、`/execution-preparation`、`/execute`。
- 未产生 Candidate B，没有 M4 三个持久化检查点、ExecutionResult、VerificationResult、
  smoke Surefire 报告或 A→B diff。`SUCCEEDED` 与可信 revision 提升均未发生。
- 应用被停止；启动器总耗时约 183.801s。记录的 exit=1 来自 Windows 显式终止，
  不是应用启动崩溃。
- **报告写入前**的前后快照证明：HEAD=A、工作树均干净；578 个受控文件 SHA-256 完全一致；
  `.git/HEAD`、index、config 的 SHA-256 完全一致。报告的后续提交属于授权记录，不是演化修改。
- 保留脱敏 API 请求 / 响应、应用日志、只读 DB、运行配置、前后快照、传输诊断与最终审计，
  均位于上述仓库外目录；不提交运行产物或 DB。
- 对保留的 21 个文本产物（含修复验证日志）扫描 Provider key 格式，无命中。
  本次未把 key 发送给 Provider，也未在文件、日志或报告中保留 key。

## 5. 剩余安全边界与结论

这是本机、维护者授权的功能尝试，**不是任意不可信仓库可安全执行的认证**。
凭据环境过滤不限制文件系统权限：仓库构建 / 测试若被执行，仍以当前 Windows 用户运行，
可能访问工作目录之外的私有文件、用户配置 / Maven cache、网络和同用户进程。
独立数据库、WorkingCopy、临时目录与固定 JAR 不能消除这些宿主权限风险。
本次实际未进入仓库构建 / 测试阶段，不能声称该风险已通过 smoke 得到隔离验证。

最小凭据继承修复已验证；真实端到端 smoke 被诊断工具阻断。
M4 保持 IN_PROGRESS，不冻结、不启动复盘或 M5。

**M4 REAL SMOKE: BLOCKED**

## 6. 第二次尝试：Direct DeepSeek HTTPS

**Date:** 2026-10-08（UTC+08:00）

**Verdict:** **M4 REAL SMOKE: BLOCKED — M2 最终分析输出契约不满足**

### 6.1 离线预检与固定环境

- 原仓库 revision A：`7e65f9f7c3c51f05025cab051d45cf94210aa6fe`，起始工作树干净。
  保留 `9eac80c` 的凭据环境过滤，不修改生产代码、prompt、Maven 或验证规则。
- 复用 §2 的固定 JAR，其构建实现为已完成 1310 项测试的 `9eac80c`；
  A 与该实现仅相差前次 smoke 报告。制品 SHA-256 仍为
  `472003c81c91711fc90e04a2d48b9e1824114a5e947bf3450efa4ea4f8e07629`。
- 启动前只读检查源码与配置：`DeepSeekAiGatewayConfiguration` 使用现有 JDK HttpClient / RestClient；
  base URL=`https://api.deepseek.com`，Adapter 路径=`/chat/completions`，
  model=`deepseek-flash`，timeout=60s。预检没有发出 Provider 请求。
- 新独立目录：`E:\df-m4-smoke\20261008-direct1`，全新 `data/smoke.db` 与空 `workspaces`。
  应用从独立 `artifact/app.jar` 启动，绑定 `127.0.0.1:18794`，PID 83940。
  日志、临时文件及证据均在该目录；使用现有 Windows JDK 21 / Maven Wrapper。
- Provider URL 显式配置为上述直接 HTTPS 地址；**没有转发、HTTP proxy、interceptor 或模型替身**。
  仅复用普通进程启动、API 客户端与 SQLite / 文件的只读观察功能。
- Disposable key 通过不回显输入注入应用环境，不进入参数、文件或日志。
  验证预算保持 §2 的四项覆盖，材料预算等其它配置不变。

### 6.2 实际业务 API 与 Provider 调用

1. `POST /api/user-profiles` → **201**，0.133s。
   Profile=`d5ba0573-8832-4bfd-afb8-5e45e6f13667`，`EXPLORING / revision=1`。
2. `POST /api/user-profiles/{id}/discovery-turn` → **200**，14.479s。
   同样如实输入小型仓库分析改进及回归测试目标；没有手工构造 Step 或 Proposal。
   真实提取及充分性评估成功：`sufficient=true`，`REVIEWING / revision=29`。
3. `POST /api/user-profiles/{id}/confirm` → **200**，0.035s。
   正常确认 revision=29，画像为 `CONFIRMED`。
4. `POST /api/software-assets` → **201**，0.012s。
   Asset=`c6c27043-6d99-4463-9875-77bc073bab7c`，location 为原仓库，
   显式 read permission 与 `ALLOWED` 使用授权；不声称额外开源许可证。
5. `POST /api/software-assets/{id}/analysis` → **502**，91.791s。
   首个失败：`EXTERNAL_CAPABILITY_UNAVAILABLE`，发生在最终 Repository Analysis 输出解析。
6. 停止演化流程，仅执行 Profile / Asset 的只读 GET，均 **200**，然后停止应用。

Repository Understanding 的现有聚合日志证明：`scoutPath=HIERARCHICAL`，
Region Scout=2、File Scout=5；foundationSelectedCount=12、targetedSourceSelectedCount=24，
实际 materialCount=36；tooLargeCount=0、totalBudgetExceededCount=0，
secretExcludedCount=1、sanitizedSpanCount=5。

源码调用点与这些日志可核对 **10 次 AI Gateway 逻辑调用**：M1 提取及充分性各 1，
M2 Scout 共 7，M2 最终分析 1。它们使用真实直连 Provider；不是模拟结果。
没有拦截 HTTP，因此未独立采集传输层请求数、Provider usage 或原始模型响应，
不将逻辑计数冒充传输层观测。

### 6.3 确切第一失败与边界

异常链为 `AiGatewayException → JsonParseException`，位置：
`RepositoryAnalysisExtraction.extract:114 → RepositoryAnalysisProposalParser.parse:72 → AiJsonObjectReader.read:51`。
最终 Provider 内容已交回 Application。`readTree` 后的 `parser.nextToken()` 在检查尾随内容时
触发 `_reportUnexpectedChar`，因此返回内容不符合“恰好一个合法 JSON 对象且无额外内容”的契约。
不是前次转发工具错误，也不是已观测到的 HTTP 鉴权或网络连接失败。

原始模型文本未保留，不能断言额外内容是 Markdown、解释文字或某个具体字符，
也不能重构一个假响应。拒绝属于本次真实结构化输出质量问题；没有放宽解析规则、
删除尾随内容、重试模型或修改 prompt。RepositoryProfile 在解析失败后未保存。
这阻断了端到端目标，**尚未进入 M4，不能据此判断 M4 mutation / verification 正确性**。

### 6.4 最终状态、证据与结论

- Profile 最终为 `CONFIRMED / revision=29`；数据库有 1 个 Profile、1 个 Asset。
  RepositoryProfile、ProductDirection、EvolutionPlan、EvolutionStep、WorkingCopy 均为 **0**。
- 没有方向选择、规划、WorkingCopy provisioning、Step confirmation、CodingProposal 或 Git mutation。
  Candidate / Verified B 未产生；没有 A→B diff、M4 三个 DB 检查点、ExecutionResult 或 VerificationResult。
- **本次 smoke 未运行 Maven 验证命令，Surefire 报告数为 0**。
  之前 1310 项测试的结果只属于固定实现验证，不作为本次演化成功的证据。
- 应用停止，运行约 238.907s；exit=1 为 Windows 显式终止结果。本次监听已消失。
- 报告更新前，前后 HEAD 均为 A、工作树均干净；579 个受控文件与
  `.git/HEAD` / index / config 的哈希完全一致。未修改原资产或任何 WorkingCopy。
- 外部目录保留脱敏 API 请求 / 响应、应用日志、只读 DB、运行参数、前后哈希、
  调用与业务摘要、最终审计。29 个文本产物的 Provider key 格式扫描无命中。
  不提交 DB、日志、制品或运行脚本，只追加本记录；前次 BLOCKED 证据保持原样。
- §5 的宿主风险仍成立：环境过滤不是文件系统权限沙箱。若执行构建 / 测试，
  它仍可拥有当前 Windows 用户对其它文件、用户配置、cache、网络及同用户进程的权限。
  此次严格属于受控本地功能测试，不是任意不可信仓库的安全执行认证。

按一个有界尝试的限制在首个失败处停止，无自动 Repair / Retry、M5 或其它开发任务。
M4 保持 IN_PROGRESS，真实端到端演化成功尚未证明。

**M4 REAL SMOKE: BLOCKED**

## 7. 第三次尝试：Final Analyzer Retry Stabilization

**Date:** 2026-10-08（UTC+08:00）

**Verdict:** **M4 REAL SMOKE: BLOCKED — 首步非空 Preconditions 不满足当前执行能力**

### 7.1 聚焦修复与验证

- 修复提交 / 源码 revision A：`95a72428e0e72d23d6fb82a514d2cca89e9b9544`。
- 最终 `RepositoryAnalysisExtraction` 对模型输出契约违反最多调用两次（初次 + 一次重试）。
  请求只构造一次，材料建立不可变快照；不重新 Scout、读取或净化，不修补模型内容。
- `AiOutputContractViolationException` 区分已收到内容的 JSON / 字段 / 引用契约错误。
  严格 JSON Reader 的接受规则没有改变；Reader I/O 等技术失败仍是普通 AI 边界失败。
  最终 Parser 的字段错误及本次材料之外的 Evidence 引用也明确归为输出契约违反。
- Provider 调用在捕获范围之外；即使 Gateway 自己抛同名契约子类型也不重试。
  网络、鉴权、超时、Workspace、预算及无关 Domain 失败均不自动重试。
  Domain 创建与保存仍在完整提案通过之后执行，两次失败不保存 Profile。
- 聚合日志只记录 operation、attempt、maxAttempts、result 与 retrying，不输出响应或异常消息。
  未改 prompt、Domain 不变量、依赖、M4 mutation / verification 或 JSON 接受规则。
- 聚焦 6 个测试类：**105 tests，0 failures / errors / skipped，BUILD SUCCESS**，14.214s。
  新覆盖第二次成功、耗尽、严格引用、输入快照、同一个请求、技术 I/O 不重试，以及
  正常流程不重跑 Scout / Workspace、不泄露原始 canary、只保存一次。
- 完整 `mvnw.cmd verify`：**1329 tests，0 failures / errors / skipped，BUILD SUCCESS**，12:25。
  Domain / Application / Infrastructure / App 分别为 216 / 732 / 212 / 169。
  测试通过后才提交并启动 smoke；没有重复运行测试来制造成功结果。

### 7.2 固定制品、复用输入与直连环境

- 新隔离目录：`E:\df-m4-smoke\20261008-direct2-retry`。
  JAR、DB、Workspace、日志、临时目录和证据各自位于该目录内；原仓库不作为应用运行目录。
- 固定 JAR 来自本次完整验证，与 A 的实现对应。
  SHA-256：`d35b8171e11b708d7bc786eee4775c8b9165e06d83ed7325645561facc45d1b4`。
- 输入 DB 为 §6 应用停止后的字节一致副本，保存了相同的已确认 Profile 和有授权 Asset，
  当时没有 RepositoryProfile / Direction / Plan / Step / WorkingCopy。保留复制前后 SHA-256，
  没有编辑 SQL、字段或下游实体；没有改动前次 DB。
- 启动后通过正常 GET 核对 Profile=`CONFIRMED / revision=29`，Asset 的 location 为原仓库，
  readPermission=true、usageAuthorization=`ALLOWED`。新 Repository Analysis 重新解析当前 A，
  没有复用旧 revision 的分析快照。正常 API 支持该继续流程。
- 使用现有 Windows JDK 21、Maven Wrapper 和 `9eac80c` 凭据过滤。
  Key 只经不回显输入进入应用环境，不写参数或文件。
- 应用 PID 54656，绑定 `127.0.0.1:18804`；直接 DeepSeek HTTPS，model=`deepseek-flash`，timeout=60s。
  启动前只读确认直连配置，没有预检 Provider 请求，没有 proxy、interceptor、容器或新基础设施。
  验证预算仍为 160 reports / 900s action / 1200s invocation / 8388608 bytes。

### 7.3 实际 API、生成结果与重试观察

1. GET 复用 Profile / Asset → **200 / 200**，0.383s / 0.018s。
   标识沿用 §6，状态与授权通过核对。
2. `POST /api/software-assets/{id}/analysis` → **201**，89.615s。
   RepositoryProfile=`4c6b9891-ceea-482d-8f02-8e58e42dce0a`，analyzedRevision=A。
   日志：Region attempts=2、File attempts=5，材料=36（foundation=12、targeted=24），
   tooLarge / totalBudgetExceeded=0，secretExcluded=1、sanitizedSpanCount=3。
   最终分析日志为 `attempt=1 maxAttempts=2 STARTED → ACCEPTED`；**本次真实调用未触发最终分析重试**。
   重试路径的成功与耗尽由前述自动化回归证明，不冒充本次真实观察。
3. `POST /api/product-directions/discovery` → **201**，28.368s，产生 4 个真实候选。
   其中一个为“补齐 Python 下划线文件命名的角色提示（RepositoryPathClassifier 小步增量）”。
4. 正常 select → **200**，0.053s。
   Direction=`99c0ca60-f2fd-4511-991b-d83e31e8d735`，状态=`SELECTED`；
   选择与已授权的小型仓库分析改进及正负回归目标一致，未手工构造方向。
5. `POST /api/evolution-plans/planning` → **201**，28.364s。
   Plan=`fe74bc4f-386a-4978-a0e4-c93ff0ebc8bb`，`PROPOSED`，仅一个 Step。
   Step=`1dfcd69b-019a-44da-b005-8c6e4ceb7c45`，目标为补齐 Python 下划线文件名角色提示，
   同一补丁附正负确定性回归；范围限于该命名风格，保持其它语言与原规则。
   Criterion 正常生成为 `FULL_TEST_SUITE_PASSES / 项目完整自动化测试套件通过`。
6. 发现下述执行资格阻断后停止，只读 GET Plan → **200**，0.037s。

本次源码调用点与日志可核对 10 次 AI Gateway 逻辑调用：M2 Scout 7、最终分析 1、方向 1、规划 1。
不包含复用输入在前次运行中的 M1 调用；没有 HTTP 拦截，传输层请求数与 token usage 未独立采集。
API 保留真实生成的 Profile、方向与 Plan，不伪造 Step、Proposal 或验证结果。
RepositoryProfile 仍读取了 README 的旧进度描述并产生“文档与实现不一致”的判断；
该事实作为材料质量风险保留，不在本次改写 Profile 或修正 README。

### 7.4 确切第一阻断：M3 / M4 执行资格不兼容

模型为首步生成了三个非空 Preconditions（原文）：

1. 实际源码确认现有 Python 下划线文件名未参与角色推断，并确认其它语言与已有规则的边界
2. 在独立 WorkingCopy 中准备可构建的仓库副本，原仓库保持不动
3. 本地 JDK 21 与离线 Maven Wrapper 可用，测试不依赖外部服务

该 Plan 的 JSON、字段与 Criterion 已被正常接受；阻断不是最终分析重试耗尽、Provider 传输失败，
也不是 Maven 测试失败。问题是首步包含自然语言源码确认及全局环境前提，当前执行没有这些条件的证明能力。
现有 `EvolutionPlan.startStep()` 第 123–124 行对非空 Preconditions 抛
`EvolutionPlanStateException("External Step Preconditions cannot yet be established for execution")`。
当前 M3 指令要求仅列 Step 专属依赖、不重复全局执行不变量，但本次输出仍混入了这些条件。

因此在执行就绪检查处停止，没有删除条件、声称条件已被证明、修改规则、重问规划或手工修改数据库。
**没有发出 prepare / confirm / execute 请求，也没有观测到这些端点返回 409**；
报告不把源码中可确定的资格阻断伪造成一次已经发生的 HTTP / 执行失败。
没有为了到达该已知阻断而继续消耗源码 Scout 或 Coding Proposal 调用。

### 7.5 最终状态、完整性与风险

- DB：Profile=1、Asset=1、RepositoryProfile=1、Direction=4、Plan=1、Step=1、WorkingCopy=0。
  只读 checkpoint 记录 Plan=`PROPOSED`，Step=`PENDING_CONFIRMATION`，baseline / execution / verification=null。
  这是规划状态，不是三个 M4 执行检查点。
- 没有 WorkingCopy，currentRevision / lastVerifiedRevision 不存在；没有 Candidate / Verified B、
  CodingProposal、演化代码 diff、ExecutionResult 或 VerificationResult。
  **本次 smoke Maven 命令及 Surefire 报告均为 0**；1329 项结果只证明修复实现，不能证明演化成功。
- 应用已停止，运行约 689.288s；exit=1 为 Windows 显式终止。未留下本次监听或自动重试任务。
- 报告更新前的前后快照：HEAD 均为 A、工作树干净，580 个受控文件与
  `.git/HEAD` / index / config 的 SHA-256 完全一致。原资产未被 smoke 修改。
- 外部目录保留输入快照来源与哈希、脱敏 API、业务摘要、聚合日志、DB、checkpoint、固定 JAR、
  修复测试日志及最终审计；30 个文本产物和 DB 的 Provider key 格式扫描无命中。
  不提交运行文件或秘密；只追加本次记录，保留全部此前 BLOCKED 历史。
- 环境过滤仍不隔离宿主文件权限。构建 / 测试若被执行，拥有当前 Windows 用户对其它文件、
  配置、cache、网络与同用户进程的权限。本次是受控本地功能尝试，不能认证任意不可信仓库可安全执行。

在第一执行资格阻断处结束，没有额外生产修复、第二次 smoke、M5、语言 Adapter 或规划结果改写。
M4 保持 IN_PROGRESS；实际端到端 Verified B 尚未取得。

**M4 REAL SMOKE: BLOCKED**
