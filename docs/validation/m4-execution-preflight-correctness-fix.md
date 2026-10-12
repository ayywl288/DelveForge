# M4 Execution Preflight Correctness Fix

日期：2026-10-12。实施起点：main `5c0488ae3ad264694564767afc0237a1fad1038e`。
这是冻结后的单独授权 correctness 修复；不重新设计 M4，不实现 M5，也不构成最终发布验收。

## 1. 根因与修复

真实执行中，Provider 已返回可解析内容，但 CodingProposal 未覆盖全部既有标准，被
`CodingProposalValidator` 拒绝。Step 同时含有 `UNSUPPORTED`；即使模型补齐索引，原执行入口的
证据能力检查也会在 mutation 前拒绝。因此原调用顺序允许为一个确定不能执行的 Step 支付 AI 成本。
原 Provider 失败和本地 CodingProposal 失败均使用 `AiGatewayException`，HTTP / UI 分类混在一起。

修复顺序如下：

1. `PrepareEvolutionStepUseCase.prepare` 只读核对资产授权、Step / Plan / WorkingCopy 与可信 Git revision。
2. 复用 `VerificationCriterion.supportedBy`，探测已有有限 Coding Action 是否能为每项标准提供证据。
   无法适配时抛出 `UnsupportedVerificationCriteriaException`，诊断只含从 1 开始的标准索引。
3. 只有通过预检才创建 Session、调用 Scout / Coding Gateway、解析并严格校验提案。
4. 原 `ExecuteEvolutionStepUseCase` 的提案依据、Criteria 覆盖、实际验证能力和资格重查继续保留。
5. 原子 EXECUTING / baseline 检查点、Git mutation、Candidate、Verification 和可信推进顺序不变。

预检早于所有执行准备的 Provider 调用及状态写入。它仅确认标准与动作的证据类型适配，
不预先证明测试存在、依赖缓存完整或构建一定成功；实际 Candidate 仍须通过原验证流程。
`CodingProposalValidator` 没有修改，缺失覆盖仍严格拒绝。

Provider 异常在 `StepRepositoryUnderstanding.Session.call` 的 Gateway 边界包装；前后资格重查仍保留。
Coding Parser / Validator 异常在 Prepare Use Case 包装为本地提案失败，不把 Workspace、授权或
revision 失败改称 Provider 故障。没有重构 Scout、Repository Understanding 或 Workspace。

## 2. UNSUPPORTED 的来源与规划处理

新计划的路径是 `EvolutionPlanningExtraction` 输出的 Proposal → `PlanningProposalParser.criteria`
显式读取 `kind` / `description` → `PlanningProposalResolver` 原样传递 →
`EvolutionPlanningService` 创建正式 EvolutionStep。生成层没有从描述猜测技术种类。

按 DOMAIN_MODEL §8.11，业务承诺、未分类要求及受控验证无法证明的外部条件有意保留为
`UNSUPPORTED`。原规划提示已经列出四种技术 Criteria、固定说明以及 M4 不执行 UNSUPPORTED 的边界。
本次只补充生成选择：优先规划技术可验证的小步骤，不自行追加不可自动验证的业务承诺；
必须保留非自动化验收时，保留原要求并在 risks 中明确 Step 当前不能自动执行。

提示不能保证模型合规，也没有新增「所有合法 Plan 必须可自动执行」的 Domain 规则。
规划侧强制拒绝所有 UNSUPPORTED、拆分业务验收或变更计划准入语义需要单独设计评审，本轮未实施。
旧数据的 V11 迁移也会把历史未分类文本标为 UNSUPPORTED；这是另一条历史来源，不能与新库模型输出混为一谈。
历史 Plan、标准、数据库与 WorkingCopy 均未修改。

## 3. 安全错误分类与前端

| 情况 | 之前 | 现在 | 用户提示要点 |
| --- | --- | --- | --- |
| 标准无法自动验证 | 可能先因覆盖失败得到 502 / EXTERNAL_CAPABILITY_UNAVAILABLE；若覆盖完整，后续为 409 / CONFLICT | 422 / EXECUTION_CRITERIA_UNSUPPORTED | 本次未调用 AI、未修改代码；重复点击不能解决，需审阅验证标准 |
| 执行准备 Provider 通信 / 响应失败 | 502 / EXTERNAL_CAPABILITY_UNAVAILABLE | 502 / EXECUTION_AI_FAILED | 尚未开始代码变更；先查询状态，不自动重试，再次调用可能计费 |
| 已返回 Coding 内容但解析 / 契约校验失败 | 502 / EXTERNAL_CAPABILITY_UNAVAILABLE | 422 / CODING_PROPOSAL_INVALID | 已返回内容但提案被拒绝；先查询状态，重复执行不保证解决 |

日志按 PREFLIGHT / PROVIDER_CALL / PROPOSAL_PARSE / PROPOSAL_VALIDATION 区分阶段，
只输出固定码、标准序号、异常类型及堆栈位置；不输出异常 message、Prompt、响应正文或凭据。
前端仍只使用固定错误码文案，忽略响应中的自由文本。

这三个确定拒绝码加入既有等待标记判定，仍须 GET 成功确认 Step 为 READY、无 ExecutionResult，
WorkingCopy 为 READY / EVOLVING 且 currentRevision 等于 lastVerifiedRevision，才清除标记。
状态查询失败、已有 Candidate、断线或未知响应继续保护未知结果；没有自动重放 execute。
预算 / 授权 / revision 拒绝及进入执行后的真实失败保持原错误、锁和完整检查点处理，不新增恢复操作。

## 4. 确定性验证

- 相关后端测试：78 项通过（Application 54，App 24），零失败 / 错误 / 跳过。
  包括 ExecutionPreparationUseCaseTest、CodingProposalValidationTest、ExecuteEvolutionStepUseCaseTest、
  PlanningProposalBoundaryTest、ExecutionFailureDiagnosticsTest、EvolutionStepExecutionApiIntegrationTest。
- 完整后端 `mvnw.cmd verify`：137 个测试类 / 1482 项全部通过，零失败 / 错误 / 跳过。
  最后的 Spring Boot repackage 失败，因此不能把整个常规 verify 命令标为 PASS；详情见下文。
- 前端 `pnpm test`：10 个文件 / 136 项通过，零失败。
- 前端 `pnpm typecheck`、`pnpm build`：通过。项目没有 lint script。

新增核心证据：混合四项 Criteria（第四项 UNSUPPORTED）连续两次 execute，Gateway 调用为 0；
数据库 Plan / Step / WorkingCopy 行、标准、Git HEAD、源码与副本快照均不变，baseline / ExecutionResult /
VerificationResult 均为空。原正常 BUILD / FULL TEST / SELECTED TEST / DIFF CHECK 用例继续执行真实测试夹具验证。
缺少 Criteria 覆盖仍触发严格 Validator；Provider 与本地提案失败映射为不同安全码，日志 canary 不泄漏。
前端新增三类实际 Vue 提示、GET 查询不重放执行，以及查询失败 / Candidate 存在时保留等待标记的回归。

合法准备场景原来使用 UNSUPPORTED 的测试夹具改为受支持的技术 Criteria；专门的拒绝用例保留该语义。
旧本地提案失败断言按新 422 / CODING_PROPOSAL_INVALID 更新，没有删除保护性断言。
初次沙箱测试出现 Vite 子进程 EPERM 及测试夹具 Git clone 失败；在沙箱外用相同隔离测试重跑，
未改动 Git 安全守卫。
所有 Spring 集成测试显式隔离数据库；Provider 由 mock / fake 或拦截 HTTP 响应提供。

### 本地打包占用与替代验证

2026-10-12 19:36 完整回归在 repackage 阶段无法将默认 App JAR 重命名为 `.jar.original`。
只读核实：仍有 Java 后端使用该路径，独占读取返回 Windows 共享冲突 `0x80070020`。
没有停止或重启该后端，也没有删除被占用文件。

为完成构建验证，把当前 Domain / Application / Infrastructure 构建产物安装到本地 Maven 缓存，
仅在忽略的 `target/m4-preflight-build/pom.xml` 复制 App POM，保留原 parent、依赖、源文件和资源，
改用独立 output directory / finalName。受跟踪的 POM 与构建配置没有修改。
完整测试已先通过；这次独立 package 不重复执行测试。

```powershell
.\mvnw.cmd -pl backend/delveforge-infrastructure -am install -DskipTests
.\mvnw.cmd -f backend/delveforge-app/target/m4-preflight-build/pom.xml package -Dmaven.test.skip=true
```

两步通过，独立 Spring Boot repackage 于 19:39 完成，BUILD SUCCESS。
本地可执行产物：
`backend/delveforge-app/target/m4-preflight-package/delveforge-app-m4-preflight-0.1.0-SNAPSHOT.jar`。
Manifest 的 Main-Class 为 JarLauncher，Start-Class 为 DelveForgeApplication；内嵌三个业务模块
逐一与本次 target JAR 的 SHA-256 匹配；App 内 77 项类 / 资源与完整回归使用的 target/classes 逐字节一致。
产物大小 40,562,840 字节，SHA-256：
`c87496b3ab50da523a27224fb98c06835ab8c7de6ae7f8c2365b85c1a7ca726d`。
这是本机替代打包证据；临时 POM 和产物不纳入 Git，不是公开 Release Asset，也没有启动验收。

## 5. 交付边界

未调用真实付费 Provider，未进行新的真实 M1–M4 E2E，未改模型预算、旧数据库或历史副本。
未修改或纳入 README-test.md、docs/images/、frontend/design/。
当前含 UNSUPPORTED 的历史 Step 仍不能执行，不因代码修复而自动变成可执行计划。
运行中的旧后端不会自动加载源码修复；需由用户安排重启到独立目录中的新可执行 JAR 及最终验收。
默认路径下的 JAR 已生成普通归档，但未完成 Spring Boot repackage，不能当作这次的新可执行产物。
后端停机后可再正常打包；本轮不擅自停机或替换运行中程序。
本次结果不证明 M4 能稳定演化任意仓库，也不证明 Repair / Retry / Recovery 已实现。
