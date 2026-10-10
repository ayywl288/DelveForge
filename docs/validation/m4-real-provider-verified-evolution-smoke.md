# M4 — Real-Provider Verified Evolution Smoke

**Status:** Validation Record / **Not Source of Truth**

**Date:** 2026-10-10（UTC+08:00）

**Verdict:** **M4 REAL SMOKE: PASS — Verified B**

**PASS：已完成一次真实 Verified Evolution B。** 成功时间：2026-10-10 13:56:56（Asia/Shanghai）。真实 execute API 返回 HTTP 200，耗时 702.050 秒。完成后已停止任务应用、测试及启动器；没有开始 M5、下一 Step 或进一步功能开发。

本次使用真实直接 DeepSeek Gateway 与正常生产 API，复用现有 Plan、READY Step 和 WorkingCopy。没有重做 M1–M3，没有手写可信记录、制造提案、修正模型补丁、修改原资产来通过验证或绕过任何检查。

**归档说明：** 本文的生命周期、原资产 HEAD / 文件一致性和进程状态均记录 smoke 完成及交接时的检查结果。按用户后续指示，本报告归档到原仓库并单独提交；该文档提交会推进 main，不属于演化 A→B，也不改变持久化的 sourceRevision、baselineRevision、currentRevision 或 lastVerifiedRevision。数据库、Git bundles、运行日志和测试报告继续保留在外部证据目录；开发诊断分支未在本次归档中合并。

外部[原始最终报告](E:/df-m4-smoke/20261010-m4-provider/report.md)的 SHA-256 为 `36f3768f5fdc4612ab701454839b5724674343c8331a50e7a129e248fd4321a2`。此前的 [Self-Hosting Smoke](m4-real-provider-self-hosting-smoke.md) 和 [Windows Local Functional Smoke](m4-windows-local-functional-smoke.md) 保留历史 BLOCKED 证据；本记录不单独宣布 M4 里程碑完成或变更架构、领域规则。

## 1. smoke 完成时的最终生命周期与成功证明

| 对象 / 字段 | 实际结果 |
|---|---|
| Plan | `0c53adcd-ed64-499f-a784-d14136541756`，ACTIVE |
| Step | `5cb42254-cdfd-4933-849e-c09f9bf5d12d`，**SUCCEEDED** |
| WorkingCopy | `a706f7cf-eec8-43f5-a335-4bbe871c717a`，EVOLVING，物理 HEAD B、干净 |
| sourceRevision / baselineRevision | A = `47ca4487210505529d6f4b4082052530c33c33d3` |
| Candidate / currentRevision / lastVerifiedRevision | B = `6dfdc121fe2f61f1542d060fe56718ea6c3bb57e` |
| ExecutionResult | 实际 Candidate B，2 个 affectedPaths；warnings / errors 为空 |
| VerificationResult | 同一个 B，passed=true，BUILD / TEST / DIFF_CHECK 全部 exit 0，覆盖既有标准索引 1 / 2 / 3 |
| 原 SoftwareAsset | `E:\develop\DelveForge`，main HEAD A，工作区干净 |
| 开发分支 | `codex/m4-provider-diagnostics`，HEAD `9efee44518f9831cf8a06651db567e2bdf15c577`，干净；未合并或推送 |

第 10 次真实执行有 10 个实际 Gateway COMPLETE 记录。完整 Coding 响应经过原有解析、路径、revision、可见原文、hunk、净化禁写和 Criterion 检查后，正常执行链才冻结 baseline、修改隔离副本并提交 B。没有注入先前预览。提案是调用内对象，不单独持久化；接受证据来自真实 Gateway、正常严格执行链、实际 mutation 和检查点，没有保存原始 Provider 响应。

只读 observer 捕获四个持久检查点；秒数相对启动器，包含启动前凭据等待，不能当作 API 分阶段计时。

| 秒数 | Step | WorkingCopy | baseline | current | lastVerified |
|---|---|---|---|---|---|
| 64.123 | READY | READY | null | A | A |
| 317.629 | EXECUTING | EVOLVING | A | A | A |
| 320.854 | VERIFYING | EVOLVING | A | B | A |
| 818.079 | SUCCEEDED | EVOLVING | A | B | B |

正常 GET API、只读数据库、实际 Git 和上述记录一致。Plan 保持 ACTIVE，没有额外调用 Plan completion。

## 2. End-to-End Business Walkthrough

本案例实际解决的是一个窄的分析行为缺口：在 `app/shop_service.py` 这类路径没有 `service/` 目录段时，Repository Map 仍应从 Python 文件名提供 APPLICATION_SERVICE 提示。它没有建立新的 Python 分析器，也没有证明被提示的文件在业务上一定是应用服务。

### 2.1 证据层次与交互边界

| 证据类型 | 本文如何使用 | 不能据此声称什么 |
|---|---|---|
| 已保存的正常业务 API 请求 / 响应 | 摘录请求字段、HTTP 结果和响应 body；这是客户端与 DelveForge 的交互 | 不是原始 Provider prompt / response，也不是人工 UI 点击记录 |
| 已持久化的结构化业务结果 | 用只读 SQLite 核对画像六区、方向、Plan / Step 与执行验证结果 | 结构合法不等于自然语言分析全部正确 |
| 历史源码、真实 A..B Git diff 与 XML | 说明实际行为、修改范围和实际测试结果 | 不能把规范 Git diff 当作模型原始 unifiedDiff |
| 事后解释 / 推断 | 明确标注范围比较、可能原因和工程教训 | 不能重构遗失内容并冒充真实输出 |

本次文档整理未启动应用。指定证据中没有完整 M1 用户探索对话；最终 Coding 调用的完整 prompt / response、推理正文和调用内 Proposal 对象未保存，无法从数据库恢复。下文不伪造逐轮对话，只摘录与工程目标有关的业务信息；完整画像与 Evidence 留在外部目录。

### 2.2 UserProfile：需求与六个业务维度

**真实业务输入 → 已确认结构化结果 → 决策：** 复用前序正常确认的 `d5ba0573-8832-4bfd-afb8-5e45e6f13667 @ revision 29`；本续跑没有重新探索用户。正常 GET 返回 CONFIRMED，Discovery 请求明确携带整数 `expectedRevision:29`，以该版画像作为发现基线。

以下是[保存的 Profile API 结果](E:/df-m4-smoke/20261009-stabilization/attempt4/evidence/api/profile-response.json)的必要节录；六个有序内容区已与最终数据库同一 revision 逐项核对，省略其余信息及完整 Evidence。

| 维度 | 与本次演化相关的真实内容节录 |
|---|---|
| interests | “Java 后端”“Agent 工程”“源码理解” |
| behaviors | “围绕现有仓库进行增量改进”“阅读实际源码并进行回归测试” |
| painPoints | “Repository Understanding 对文件命名的角色提示仍有小范围覆盖缺口”；具体提及 `shop_service.py` 可能缺少 APPLICATION_SERVICE 提示 |
| technicalCapabilities | “正在学习 Java 后端”“能阅读实际源码并编写、运行回归测试”；使用现有 Windows JDK 21 与离线 Maven Wrapper |
| projectGoals | “在自己的 DelveForge 仓库中完成一个小而有意义的仓库分析行为改进”“为改进补对应的确定性回归测试”；通过独立 WorkingCopy 形成可信 revision |
| constraints | “这是工程功能 smoke，不是商业方向扩展”“保持其它语言和已有规则”；不新增依赖、不改 POM / Wrapper / 验证策略 / 生命周期 / 安全边界，正负测试与改动同补丁 |

因此，需求是判断这一已知缺口能否成为首个独立、可验证的小步。Asset 的 readPermissionAllowed=true、usageAuthorization=ALLOWED 与维护者许可是独立前提，画像确认不能替代资产授权。

### 2.3 RepositoryProfile：系统实际理解到了什么

**授权本地 Git 资产 → 固定 A 的只读分析 → 接受快照：** 最终规划使用 `9fa490fe-7145-41f3-9c1a-e232aee97cc7`，analyzedRevision=A。[实际 Analysis API 输出](E:/df-m4-smoke/20261009-stabilization/attempt4/evidence/api/analysis-response.json)返回 201；purpose / revision 已与 SQLite 核对。下面是已接受结果的摘要，不是本次重新分析。

| 主题 | 实际结构化结果的必要摘要 |
|---|---|
| 项目目的 | 结合用户兴趣、行为和技术能力，只读理解手边 Git 仓库，发现并选择个性化的产品演化方向 |
| 技术栈 | Java 21、Spring Boot 3.5.x、Maven 多模块 / Wrapper、SQLite / Flyway / MyBatis-Plus、JUnit 5、Git CLI、DeepSeek HTTP API；前端 Vue 3 / TypeScript / Vite |
| 相关模块 | Application 的 Use Case / 编排 / Ports；Infrastructure 的外部能力与持久化；Domain 的对象和 Policy；App 的装配 / REST；前端连通性页 |
| 可复用能力 | 固定 commit 的 Repository Understanding、Evolution Planning 与 WorkingCopy 装配、Evidence 追溯、Maven Wrapper、模块依赖守卫 |
| 限制 | 完整业务流程仍通过 REST API；Asset 仅支持用户指定 Git 仓库；许可由显式声明决定，不能自动从许可证文本推断 |

**分析质量观察：** 原快照还写了“Roadmap 中 M3 未开始”，Plan.currentState 也沿用了这项限制。A 的 `docs/ROADMAP.md` 实际已将 M3 标记为 DONE / FROZEN；这项模型描述不准确，不能作为项目当前事实。本文保留其历史含义，不修写可信记录。接受结构化 Profile 仍须审阅自然语言结论，执行时也须读源码，而非仅依赖摘要。

### 2.4 ProductDirection：候选、选择与来源连续性

**画像29 + 发现快照 → 五个真实候选 → 显式 select：** [保存的 Discovery 输出](E:/df-m4-smoke/20261009-stabilization/attempt1/evidence/api/04-discovery-response.json)基于 `36bd30c9-4932-4953-81f0-591202c01ce7`，返回 201、五个 CANDIDATE。标题节录如下：

| 候选 | 实际方向 | 结果与范围判断 |
|---|---|---|
| 1 | Python 下划线命名角色提示补全（单点规则扩展 + 同补丁正负回归测试） | 经正常 select API 选中 |
| 2 | 角色提示依据的 Evidence 化 | 本次未选；增加证据表达，超出单点规则目标 |
| 3 | 资产使用授权与仓库分析前置校验的正负回归基线 | 本次未选；以授权测试为主，不直接填补命名提示缺口 |
| 4 | 文档与实现漂移的只读自查 | 本次未选；涉及多份文档及组件比对 |
| 5 | 前端最小证据审阅页 | 本次未选；跨前端与接口消费 |

后四项的范围判断是本文对候选内容与用户约束的事后比较，不是保存的人工淘汰理由，也不宣称其生命周期均为 REJECTED。

选中 `ef65f8f2-a3bc-4db4-8bd4-559cc14fdc6d` 的真实 targetProduct 是让 Python 下划线命名参与角色推断，保持其它语言与已有规则，并在同补丁附正负测试；userFit 关联源码阅读与回归目标，estimatedComplexity 为“低”。[实际 select 请求](E:/df-m4-smoke/20261009-stabilization/attempt1/evidence/api/05-select-request.json)是无 body 的 POST，随后[正常 GET](E:/df-m4-smoke/20261009-stabilization/attempt4/evidence/api/selected-direction-response.json)与数据库均为 SELECTED。

可验证的是 smoke 客户端按已授权范围调用选择接口；没有保存额外人工理由或 UI 过程。当前规划复用同资产的 SELECTED 方向，新的 Base Profile 是 A 快照；Direction 的历史发现来源仍为旧快照，两者不是同一次分析。

### 2.5 EvolutionPlan：把方向变成一个可确认的小步

**选定方向 + 资产 + 新 Base Profile A → 真实规划 → prepare / confirm：** [Planning 请求](E:/df-m4-smoke/20261009-stabilization/attempt4/evidence/api/planning-request.json)只提交 `productDirectionId`、`baseAssetId`、`baseRepositoryProfileId`。[响应](E:/df-m4-smoke/20261009-stabilization/attempt4/evidence/api/planning-response.json)为 201 / PROPOSED，只有一个 Step；prepare 返回 200 / ACTIVE，confirm 返回 200，使 Step 从 PENDING_CONFIRMATION 进入 READY。没有修改模型规划或跳过前序步骤。

这是最终 Plan `0c53adcd-ed64-499f-a784-d14136541756`，不是历史 Windows smoke §9 的两步 Plan。数据库 position=0，即唯一第1步；[确认后的真实定义](E:/df-m4-smoke/20261009-stabilization/attempt4/evidence/api/confirm-response.json)为：

| 字段 | 真实定义 / 必要节录 |
|---|---|
| Goal | “让 Python 下划线文件命名参与角色提示推断并给出对应角色提示，在同一补丁内补齐确定性正负回归测试，同时保持其它语言与已有规则不变。” |
| Scope | “Repository Understanding 中命名到角色提示的推断规则这一单点行为的扩展与回归测试”；不涉及 Workspace、持久化、验证策略、生命周期、安全或构建配置 |
| plannedChanges | 读实际规则与注释 → 扩展 Python 提示 → 覆盖误判及其它语言边界 → 同补丁确定性测试；不引入外部服务、RAG、M5 或重构 |
| 复用 | Repository Understanding、模块依赖守卫、Wrapper 与换行约定 |
| Criterion 1 | BUILD_SUCCEEDS：“项目构建成功” |
| Criterion 2 | FULL_TEST_SUITE_PASSES：“项目完整自动化测试套件通过” |
| Criterion 3 | DIFF_CHECK_PASSES：“本次代码差异无空白错误或冲突标记” |

prepare 得到隔离环境并激活 Plan；confirm 才建立 Step 授权。此时 baseline=null、current=lastVerified=A。完整测试是要求本身，不能用 diff 或构建通过替代。

### 2.6 Step-specific Understanding 与接受 CodingProposal

**READY Step + WorkingCopy.lastVerified=A → 调用内理解 / 提案 → 严格接受：** [最终 execute 请求](E:/df-m4-smoke/20261010-m4-provider/run9/evidence/api/execute-request.json)无 body。产品内部重新读取固定 A，复用 Map、Region / File Scout、定向读取和净化；没有接收第6次成功预览作为可信输入。

运行源码 `9efee44` 的 `StepRepositoryUnderstanding` / `PrepareEvolutionStepUseCase` 表明：测试文件在 M4 调用内进入源码候选，材料与扩展累计受限；既有目标须本次可见、未被净化，并与 A 的精确 blob 对齐。预算仍为48文件 / 524,288B材料 / 786,432B请求 / 262,144B提案。最终输入使用单份 `numberedLines` + `endsWithLf`，没有第二份源码正文。

实际有10次 Gateway COMPLETE，最后一次 Coding 请求289,827B。至少实际修改的 Classifier 与其测试通过了可见性和精确原文校验。**完整选材名单、每次 Scout 输出及最终 Proposal 原文未单独保存，无法补出文件总数、完整 relatedPaths 或原始 hunk 数。** 离线往返探针中的三个文件是表示验证夹具，不能冒充真实选材名单。

接受结构由实际解析 / 校验链与结果支持：`CHANGE_PROPOSAL` 携带 expectedRevision=A、unifiedDiff 和 verificationStrategy；提案须匹配当前 Step 调用依据，通过路径、可见旧内容、hunk 与三项 Criterion 覆盖校验，才进入 Controlled Mutation。实际 Git 差异符合语义 Scope 的结论来自代码审阅，不能把结构校验说成已自动证明自然语言 Scope。下文是实际落地结果，不把结构说明或 Git diff 称作恢复出的原始 Provider 响应。

### 2.7 WorkingCopy → Candidate → Verified State

**已接受提案 → 隔离副本真实 B → 同 B 的生产验证 → Domain 推进信任：** 只有隔离 WorkingCopy 产生两个文件的 Git 改动；B 的唯一父提交是 A，ExecutionResult 保存同一 B 和 affectedPaths。在私有验证目录检出 B，实际 BUILD / TEST / DIFF_CHECK 全通过后，才原子保存 Step SUCCEEDED 和 lastVerified=B。

§1 的 VERIFYING 检查点已 current=B，但 lastVerified 仍=A；Candidate 的存在没有自动赋予信任。原资产在 smoke 窗口保持 A，报告归档是之后独立的维护者文档事件。代码与验证证据见下节。

## 3. 实际改动和针对 B 的 Maven Verification

实际 WorkingCopy：`E:\dfw4\a706f7cf-eec8-43f5-a335-4bbe871c717a`。B 仅有父提交 A，只修改两个文件：

- [RepositoryPathClassifier.java](E:/dfw4/a706f7cf-eec8-43f5-a335-4bbe871c717a/backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/repositoryanalysis/map/RepositoryPathClassifier.java:268)：只在 Python 下增加 `_service`、`_use_case`、`_usecase`、`_manager` 文件名后缀的 APPLICATION_SERVICE 提示，复用已有非空前缀 / suffix 规则，保留旧规则和枚举顺序；注释明确其它 Python 角色尚未覆盖。
- [RepositoryPathClassifierTest.java](E:/dfw4/a706f7cf-eec8-43f5-a335-4bbe871c717a/backend/delveforge-application/src/test/java/com/ayywl/delveforge/application/repositoryanalysis/map/RepositoryPathClassifierTest.java:173)：增加 3 项确定性测试，覆盖 4 个正例、5 个误判边界、3 个其它语言边界，均实际通过。

三个新增测试名：`classifiesPythonApplicationServiceByUnderscoreName`、`doesNotInventApplicationServiceForOtherUnderscoreNames`、`doesNotApplyPythonUnderscoreNamingToOtherLanguages`。

实际 diff 为 **68 行新增、5 行删除**。未改 POM、Wrapper、依赖、Workspace、持久化、领域生命周期、安全或验证配置；没有删改原有测试来通过验证。改动符合已确认 Step 的单点命名推断范围。

[实际 A..B Git diff](E:/df-m4-smoke/20261010-m4-provider/run9/evidence/candidate-B.diff)：7,612 字节，SHA-256 `91b70354685213ebc1ec460bf76578b1d766ed363ed787f0b784314625e99c83`。这是正常 mutation 后实际 Git commits 的规范差异，不是导出的 Provider 响应。规范 Git diff 中多个小 hunks 不代表模型提案曾被自动修正。

### 3.1 从实际 Git diff 解释行为

A 的 `RepositoryPathClassifier` 用目录段与通用文件名后缀给源码角色提示。原有服务后缀采用大小写惯例：

```java
private static final List<String> SERVICE_STEMS = List.of(
        "Service", "ServiceImpl", "UseCase", "UseCaseImpl", "Manager");
```

`app/shop_service.py` 没有服务目录段，且小写 `_service` 不匹配这些后缀，所以在 A 的这组规则下返回 UNKNOWN。这是源码可验证的行为解释；本次整理没有新运行分类器，也没有把该示例当成仓库真实存在的 Python 文件。

B 的真实新增片段为：

```java
private static final List<String> PYTHON_SERVICE_STEMS = List.of(
        "_service", "_use_case", "_usecase", "_manager");
```

```java
if (".py".equals(extension)) {
    addIfStemEndsWith(hints, stem, PYTHON_SERVICE_STEMS,
            RepositoryRoleHint.APPLICATION_SERVICE);
}
```

扩展名被传入 `sourceRoles`，新增分支只对 `.py` 生效；原有目录段、SERVICE_STEMS 与其它角色规则保持。既有 helper 仍要求 `stem.length() > suffix.length()` 且 `stem.endsWith(suffix)`，没有将任意含 service 的文件都判成应用服务。角色仍按 EnumSet 的既有顺序返回，无法判定时仍为 UNKNOWN。

实际新增正例的断言为：

```java
assertRole("app/shop_service.py", RepositoryRoleHint.APPLICATION_SERVICE);
assertRole("shop/order_service.py", RepositoryRoleHint.APPLICATION_SERVICE);
assertRole("app/place_order_use_case.py", RepositoryRoleHint.APPLICATION_SERVICE);
assertRole("app/task_manager.py", RepositoryRoleHint.APPLICATION_SERVICE);
```

| 实际新增测试 | 直接覆盖的输入与预期 |
|---|---|
| `classifiesPythonApplicationServiceByUnderscoreName` | 上述4个正例 → APPLICATION_SERVICE |
| `doesNotInventApplicationServiceForOtherUnderscoreNames` | `app/shop_mapper.py`、`app/user_profile.py`、`app/shop_services.py`、`app/shop_microservice.py`、`app/service.py` → UNKNOWN |
| `doesNotApplyPythonUnderscoreNamingToOtherLanguages` | `internal/shop_service.go`、`web/src/shop_service.ts`、`src/main/java/com/acme/shop_service.java` → UNKNOWN |

三项测试均有实际 B 的 Surefire 通过记录。4个正例并非分别覆盖4种后缀：`_usecase` 已加入代码，但这三项新增测试没有单独为它设置正例。1346项全通过不能替代这一覆盖范围说明，也不证明文件名启发式的业务语义必然正确。

### 3.2 同一 Candidate B 的生产验证

生产 Verification 在原子创建的私有 `.git/v` 中 clone / checkout 精确 B，并在动作前后核对 Candidate：

| 动作 | 实际动作 / 证据 | 结果 |
|---|---|---|
| BUILD | Wrapper，`--batch-mode --no-transfer-progress -o -DskipTests package` | exit 0 |
| TEST | Wrapper，`-o -DskipTests=false -Dmaven.test.skip=false -DfailIfNoTests=true`，同会话产出 effective POM 再执行 **test** | exit 0；**1346 tests，0 failures / errors / skipped** |
| DIFF_CHECK | 真实 `git diff --no-ext-diff --check A B --` | exit 0 |

effective POM goal 为 `org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom`，输出 `.git/delveforge-effective-pom.xml`。122 份完整 XML 与 effective POM 在生产清理前已捕获；Domain TEST 事实 executed=1346、failures=0 与 XML 汇总一致。

**B 执行的是现有生产 BUILD + TEST + DIFF_CHECK，并非宣称运行了 verify goal。** 开发修正独立完整运行 `mvnw.cmd verify`，1376 项通过。B = A 加 3 个生成测试，不包含开发诊断分支新增测试，两组数量不能混用。

## 4. 全部针对性真实尝试

本续跑编号 1–10 包含昨夜 2 次、今天 8 次。此前 Astra 的 5 次、`e5c9671` / `7d33812` / `47ca448` 和 1343 项是历史结果，没有重做。最新授权已取消旧次数额度；每次新尝试都有新证据或已验证修正，没有盲目重复同一个失败请求。

| 编号 | 调用 / 有限配置 | API 秒数 / HTTP | 首个失败或结果 |
|---|---|---|---|
| 1，昨夜 | preparation，非流式60s | 125.864 / 502 | Coding HTTP200；读取60,009ms失败，RestClientException → IOException；READY/A |
| 2，昨夜 | preparation，唯一120s对照 | 170.463 / 502 | HTTP200；读取120,013ms同类失败；READY/A |
| 3 | preparation，120s，加字节进度 | 184.546 / 502 | request244,558B；60,283ms只收到1B空白、非空白0B；120,017ms读取失败；READY/A |
| 4 | bounded SSE，16K / 8MiB | 117.761 / 502 | 56,579ms；completion与reasoning均16,384，content0B；完整条件未满足，拒绝；READY/A |
| 5 | SSE，64K / 32MiB，idle90s / total300s | 218.063 / 502 | 153,898ms响应完整；hunk位置guard拒绝，当时无operands，具体谓词未知；READY/A |
| 6 | preparation，加精确行数 / hunk指引 | 259.668 / 200 | 真实预览通过：3个相关路径、7 hunks、diff12,918B；未执行，READY/A |
| 7 | 正常execute，重新准备 | 236.624 / 502 | 191,024ms完整；第二hunk漏算此前+1 delta：oldStart260/newStart260，应261；READY/A |
| 8 | execute，完整单hunk，96K | 210.966 / 502 | 162,278ms完整；旧行数超出或原文不匹配，当时未区分；READY/A |
| 9 | execute，局部单hunk，加分类诊断 | 273.812 / 502 | 219,077ms完整；DIFF_CONTEXT_MISMATCH：oldLine47、seenOld0、expectedChars2、actualChars0；READY/A |
| **10** | **execute，单份可逆编号输入** | **702.050 / 200** | **Coding149,471ms完整；接受提案 → 实际B → Maven → SUCCEEDED / lastVerified B** |

第6次预览只保留结构元数据与hash，内容已移除；diff hash `6ae4758d96b748d2593672e8e9299bce7da3ee181c71ad01fd32cd55493a4647`。它未成为后续执行输入。

第10次Coding：request289,827B；HTTP200；headers239ms；firstReasoning1,239ms；firstContent130,343ms；raw SSE13,819,847B、44,016events；content30,126B、reasoning138,494B；completion44,014，其中reasoning36,220tokens。只记录计数和时序，未记录推理内容。

## 5. 工程问题复盘

下面按问题与证据组织，生产修复、客户端修正和未知故障分别说明。它们并非每个都曾阻断一个真实 Candidate；离线路径失败、维护构建失败与真实业务失败不能混算。

### 5.1 smoke-client 字段错误：先验证实际协议

- **现象 / 根因：** [Windows smoke §8](m4-windows-local-functional-smoke.md#8-第四次尝试step-contract-simplification)中 Discovery 返回400；客户端提交 `expectedUserProfileRevision:29`，DTO实际要求 `expectedRevision`。这是已确认的客户端契约错误，未进入 Use Case 或 Provider。
- **修改 / 前后差异：** 修正外部客户端，并对固定 JAR 的 DTO / Jackson 做离线反序列化核对。正确整数 revision 与非空快照列表通过，旧字段、字符串 revision、空列表拒绝；无请求体的 select / prepare / confirm / execute 也按真实路由核对。此修正没有生产提交，不能把邻近 `56b63aa` 的 Step 契约修改归因于该字段错误。
- **结果 / 教训：** 后续 §9 和稳定化运行确实通过 Discovery / Planning，推进到 Workspace。外部脚本是系统链路的一部分，应在花费 Provider 预算前与实际 DTO 对齐。更早的安全门槛和诊断转发工具 chunked 不兼容分别见历史记录，也不能算成 Provider 已执行失败。

### 5.2 cleanliness 诊断缺口：先区分命令失败与真实 dirty

- **现象 / 根因：** 历史 §9 的 post-checkout guard 拒绝，原始 status exit / stdout 未保存，失败目录已补偿删除。离线重放成功只能说明未复现；历史根因仍 UNKNOWN。
- **实际修正：** `e5c9671` 保留原 guard，新增固定类别 STATUS_COMMAND_FAILED / STATUS_OUTPUT_NONEMPTY，非零退出优先；只记 exitCode、stdoutBytes、stderrPresent，不记录自由文本、路径、异常 message 或源码。
- **回归 / 后续证据：** 五组状态回归和完整1342项通过；稳定化尝试1采到 exit=0、stdout=859B、stderrPresent=false，才有依据继续调查物化缺失。修复的是可观察性，未宣称修复了遗失证据的历史根因。[稳定化报告](E:/df-m4-smoke/20261009-stabilization/report.md)保留各轮结果。
- **教训：** 异常位置不能替代外部命令事实；删除失败候选前保留最小安全诊断，仍须失败关闭。

### 5.3 Windows Git 长路径：checkout 成功不等于文件完整物化

- **现象 / 已确认根因：** 稳定化尝试1在长 UUID 路径下缺少5个 tracked 文件，路径长度260–265；HEAD匹配，status自身成功但非空。[路径重放](E:/df-m4-smoke/20261009-stabilization/evidence/path-length-replay.json)与[短路径 / longpaths 对照](E:/df-m4-smoke/20261009-stabilization/evidence/path-length-controls.json)证实：相同 revision 短目录完整，长目录默认配置缺文件，物化阶段启用 longpaths 后完整。
- **实际修正 / 差异：** `7d33812` 在 GitWorkspaceAdapter 的 Git 命令局部加入 `-c core.longpaths=true`，与已有写入执行器对齐；未改宿主全局配置。仅给后续 status 加配置不会补回之前漏物化的文件，必须覆盖 clone / checkout 等实际过程。
- **回归 / 真实结果：** 真实 Git 的 >260字符测试修复前失败、后通过，含 cleanliness、源不变与 discard；定向34项、完整1343项通过。之后真实 WorkingCopy 为 READY，最终 B 干净。该确认适用于本轮已复现缺陷，不能倒推历史 §9 必然同因。
- **教训：** Windows 路径预算必须覆盖生产 UUID、包名和目录深度，短名称探针不能证明生产布局正确；HEAD、文件物化和 cleanliness 是不同核验。

### 5.4 嵌套 Maven / 私有目录：验证器也会触及路径上限

- **现象 / 已确认根因：** 私有验证目录再嵌套 App 测试 UUID 与 Git clone，旧布局在离线真实 Git 中出现 GIT_DIR too big / FilenameTooLong。`47ca448` 先将随机前缀缩为 dfv-；224→204字符探针成功，却未覆盖真实双 UUID 深度。
- **进一步证据 / 修正：** [精确嵌套布局探针](E:/df-m4-smoke/20261009-m4-continuation/evidence/exact-nested-path-preflight.json)在目标235字符时仍 exit128；[`.git/v` 对照](E:/df-m4-smoke/20261009-m4-continuation/evidence/short-private-name-control.json)将其降至212，命令全通过，内层仍是 A 的旧 dfv 实现。`a0a7784` 改为原子创建 `.git/v`；已有文件 / 目录立即拒绝，创建失败不删除非本次所有路径。
- **回归 / 真实结果：** 目录归属回归先红后绿，聚焦40项，完整1349项最终通过；真实 B 后来在同私有目录成功验证并清理。早期一轮嵌套 Maven exit1未保存原因，低内存是可能解释而非已确认根因，失败日志仍保留在[昨夜报告](E:/df-m4-smoke/20261009-m4-continuation/report.md)。
- **教训：** 应按“生产验证目录 + 被验证项目自身集成测试”计算路径深度；缩名必须同时保留原子独占、symlink、归属和清理约束。

### 5.5 HTTP 200、读取期限、SSE 与 reasoning 预算

- **真实现象：** HTTP200只证明响应头已到。续跑1 / 2的 Coding 读取分别在60,009 / 120,013ms失败；第3次120,017ms失败前，60,283ms仅收到1B空白，非空白0B。整体 API 时间还包含选材，不能解释成 Provider 有60秒超时。
- **证据与根因边界：** 客户端配置期限与失败高度对应；[本地心跳对照](E:/df-m4-smoke/20261010-m4-provider/evidence/heartbeat-probe.jsonl)支持 JDK / Spring 非流式绝对期限不会因空白心跳重置。但最初 IOException 为什么未读完，生成、排队、网络停滞或连接终止均未获历史服务端证据，根因仍 UNKNOWN。
- **实际修改 / 差异：** `f8e8827` 补 phase / 稳定 code / 已知 HTTP status / duration / 异常类型；`dc598f4` 再补请求与正文计数及首尾时序。`1c34eb1` 增加可配置的有界内部 SSE：区分 idle 与 absolute total，限制 raw / 单事件 / token 预算；只拼接 content，reasoning仅计数，要求正常 stop 和 DONE，不把半段 JSON、EOF 或单独 stop 当完整结果。没有广泛网络重试，默认非流式仍保留。
- **输出预算证据：** 第4次 completion=reasoning=16,384、content=0，强支持 reasoning 用尽预算，但未保存 finish_reason，不能断言已证明 length。后续有限64K / 96K配置取得完整正文，第一失败转为补丁校验；最终首个content在130,343ms才到，reasoning36,220 tokens。SSE让进展可观察，不保证模型必定输出正确提案，也不豁免 idle / total / 大小限制。
- **回归 / 结果 / 教训：** 读取诊断红绿回归、SSE聚焦29项、完整1368项通过；真实尝试4拒绝不完整响应，5以后可以区分传输完整与补丁错误。应分别衡量首字节、首content、总时长、reasoning与可用输出，不能盲目加超时或把HTTP200当成功。详见[第3次](E:/df-m4-smoke/20261010-m4-provider/evidence/attempt3-result.json)、[第4次](E:/df-m4-smoke/20261010-m4-provider/evidence/attempt4-result.json)和[流式回归](E:/df-m4-smoke/20261010-m4-provider/evidence/bounded-stream-verification.json)。

### 5.6 hunk 失败与单份编号表示：改善输入，不修造补丁

- **真实现象 / 已知事实：** 第5次位置guard缺 operands，谓词未知；[第7次](E:/df-m4-smoke/20261010-m4-provider/evidence/attempt7-result.json) oldStart260 / newStart260、此前delta=1，因此应为261；第8次旧行数超出或上下文不匹配，当时无法区分；[第9次](E:/df-m4-smoke/20261010-m4-provider/evidence/attempt9-result.json)首旧行47、expectedChars2 / actualChars0、seenOld0，确认为 CONTEXT_MISMATCH。这些正文已完整，不能再归为HTTP读取失败。
- **实际修改 / 中间结果：** `2b25744` 增加精确LF行数、hunk位置指引与纯数字诊断；第6次未编号预览通过但未执行。`f0fb494` 尝试完整单hunk，真实8仍失败；`3453ed8` 改回局部单hunk并区分 OLD_COUNT_EXHAUSTED / CONTEXT_MISMATCH。均未放宽 validator、修正模型diff或删除旧源码来配合错误补丁。
- **最终选择与证据：** `9efee44` 用 `numberedLines` 的实际行号键替换原 content 字段，配 `endsWithLf`；每行正文只有一份，空行、CRLF的CR和末尾LF可逆。模型可直接定位 oldStart，减少估算负担；Session原文、净化禁写、精确blob与所有提案守卫不变。重复完整行视图曾被审批拒绝，最终采用的是经批准的单份替换。[9个离线往返](E:/df-m4-smoke/20261010-m4-provider/evidence/single-copy-line-probe.json)、聚焦41项与完整1376项支持表示正确，第10次真实执行获得 B。
- **教训与因果限制：** 单hunk和编号是当前生成选择，不是新的 Domain 规则；接收方仍严格校验。第6次未编号也通过，因此一次最终成功不能证明编号是唯一原因或保证普遍可靠性。

### 5.7 Verification-before-trust：失败安全是成功证据的一部分

- **实际行为：** 所有提案准备失败都保留 READY / A、baseline=null，没有 Candidate，不需制造失败恢复。第10次接受后才冻结 baseline=A，按 §1 三个执行检查点提交；VERIFYING 时 current=B、lastVerified=A，验证通过才 SUCCEEDED / lastVerified=B。
- **证据 / 修改：** [execute 正常响应](E:/df-m4-smoke/20261010-m4-provider/run9/evidence/api/execute-response.json)、[持久检查点](E:/df-m4-smoke/20261010-m4-provider/run9/evidence/checkpoints.jsonl)、Git和XML一致。这里没有新增领域修复，没有手写结果或修改可信 revision；SSE与表示改动只改善外部提案取得方式。
- **可复用教训：** 准备失败与已有 Candidate 验证失败是不同恢复边界。本次可以复用 READY 输入，不能据此推导 M4 支持重置、重试已失败 Candidate。原资产保持只读、Step授权、revision一致性与验证后信任推进必须共同成立。

## 6. 开发修正、回归与完整验证

授权开发工作树：`C:\Users\28226\.codex\worktrees\m4-provider-diagnostics\DelveForge`。短独立构建副本 `E:\dfp6`，临时目录 `E:\dft6`。最终全部 **585 个 tracked 构建输入**的物理文件hash匹配干净开发提交对应工作树。

| 完整提交 | 修改 / 测试 |
|---|---|
| `f8e8827be460bb59d92482b76e92e8bc5e376cb7` | 昨夜安全phase/code/status/duration/types诊断；完整1346。历史深路径失败和短路径通过均保留在昨日报告。 |
| `a0a778460674d3ecdab39fda1de8524d2c5456ca` | 昨夜短、原子、归属明确的.git/v；最终1349。Windows路径缺陷有离线复现，与历史provisioning原因不同。 |
| `dc598f484e441bc5722409d4c0f3a7f880b17e5e` | 请求字节、headers/首尾字节/进度；聚焦12，完整1351。 |
| `1c34eb180243d91ff36f5eb2e86e0a397e2517be` | 可选有界内部SSE、完整stop+DONE、raw/event/token预算、idle与total；聚焦29，完整1368；ADR-0003补充传输说明。 |
| `2b25744a141fccddd173df049ec84b19eff325a2` | 精确LF行数、位置指引、纯数字hunk拒绝日志；聚焦29，完整1372。 |
| `f0fb4947e50d8f3d3289a8434401c335487fac87` | 中间完整单hunk策略；聚焦29，完整1372。真实8随后拒绝，此指引已由后续局部策略取代。 |
| `3453ed8ed5ad0ef6a3c5c0bebbb5d5eaa74b1993` | 局部单hunk；固定类别区分OLD_COUNT_EXHAUSTED与CONTEXT_MISMATCH，只记录数字；聚焦31，完整1374。 |
| `9efee44518f9831cf8a06651db567e2bdf15c577` | 同一批净化文件，单份numberedLines+endsWithLf替换content，不重复源码；Session原文与所有严格校验保持。架构文档说明当前表示选择。9个离线往返，聚焦41，**完整1376**。 |

最终JDK21 / Wrapper完整验证为 `mvnw.cmd -Djava.io.tmpdir=E:\dft6 verify`，外层堆512MB；1376项零失败/错误/跳过，123份新XML，13:42:16完成。没有因聚焦通过而省略完整构建。

红测和失败证据均保留：读取进度2项、hunk/行数元数据、数字类别2项、单份编号表示红测后转绿。被拒的“重复完整行视图”实验测试恢复为已有提交，因为该行为没有实现；没有删除既有测试。该实验初始fixture固定正文假设的错误也已校正并记录。

SSE首次完整构建启动时一次未引用PowerShell参数被拆分，Maven在测试前报前缀错误；正确引用后通过，启动错误日志保留。最后来源审计脚本一次用了本机Python不提供的st_birthtime，改为Windows创建时间st_ctime；当时尚未注入凭据或调用Provider，补齐校验后才继续。最终报告首次工具代码构造有转义语法错误，没有执行进程，已改用字面文本写入。这些不是Provider或Maven测试失败。

## 7. 已确认、推断与UNKNOWN

- 非流式失败高度符合客户端配置的绝对响应期限；第3次实际只有1B空白。真实Spring/JDK本地HTTP对照表明，连续心跳不会重置该绝对期限。整体API耗时包含选材，不能据此推导Provider有固定60秒超时。
- 后续SSE首个内容常在130–195秒，支持当前长推理/生成是因素；仍不能从历史IOException证明过去究竟是生成、排队、网络停滞还是连接终止。
- 第4次16,384completion全部为reasoning、content为0，强支持输出预算耗尽；finish_reason未保存，不能断言已证明length。之后使用明确有限预算，保留idle和total限制。官方定义max_tokens、SSE与DONE，本Adapter要求正常stop与DONE，部分结果仍拒绝。[DeepSeek Chat Completions API](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/)
- 5、7、8、9的传输都完整，第一失败已是严格补丁拒绝；7的delta、9的首旧行不匹配有数字证据。没有依据削弱validator或自动修正patch。
- 编号后的第10次通过，但第6次未编号预览也通过。一次成功不能证明编号是唯一原因，或保证未来所有提案成功。
- 最初post-checkout cleanliness失败的原退出码/输出仍缺失，离线成功不恢复历史根因；最初Provider异常的确切历史原因同样保持 **UNKNOWN**。没有编造Git输出或异常消息。

## 8. 配置、完整性、审批与风险

成功运行 `run9`，localhost:18819，产物SHA-256 `3917975aba2b0483466db6142a6e545e240d837e078c0518bc5010112d9ab6fd`，对应9efee44。真实直接DeepSeek HTTPS，没有mock、代理拦截或广泛网络重试；既有模型/thinking选择未改。凭据只经隐藏stdin进入内存/环境，不在参数、源码或证据中。Workspace子进程保持原环境过滤。

**实际成功预算：** streaming=true，connect/idle90s，absolute300s，max_tokens98,304，raw32MiB，event64KiB；Verification action900s / invocation1200s、reports160、output8MiB。Application既有48文件、524,288B材料、786,432B请求、262,144B提案预算保持。

YAML默认仍为非流式、timeout60s、max_tokens16,384、response8MiB；不能宣称这些默认预算已通过真实M4。开发分支未合并进原资产，运行产物与A版本区别明确保留。

每次新执行前检查实际授权、SELECTED方向、Base Profile A、实体绑定、物理可信revision、clean状态、隐藏index标志与隔离路径。数据库快照使用只读连接的 **SQLite backup API**，没有复制活动SQLite文件或手写状态。只有一个应用拥有实际数据库/WorkingCopy，重构建没有并发启动。

[最终一致恢复快照](E:/df-m4-smoke/20261010-m4-provider/run9/evidence/snapshot-verified-B/manifest.json) 包含smoke.db、已verify的original.bundle / working-copy.bundle及文件/HEAD/index/config哈希。integrity_check=ok，Git连通性通过，WorkingCopy无.git/v残留。最终数据库为 `E:\df-m4-smoke\20261010-m4-provider\run9\data\smoke.db`，已停止。

原资产583个tracked文件、main HEAD、index/config与本轮基线完全一致。实际B仅在隔离WorkingCopy。最终没有Java/Python任务应用、测试或启动器。Java exit1是证据保存后的主动终止，启动器exit0，不是smoke失败。

**审批事件：** 第6次原预览导出因原模型/源内容风险被自动审批拒绝，改为结构元数据/hash和内容移除。重复完整源码行视图因扩大出站载荷被拒绝，没有实施；更小的单份替换表示经过离线证明后获批，没有绕过机制。第8次首次启动审批超时，请求未启动；工具明确允许的一次重试获准，不计为额外Provider尝试。实际Git差异证据来自mutation后的Git状态并获准保存。

**历史共享Git配置例外：** 昨日创建受管工作树时共享config相对更早快照发生变化，现有extensions.worktreeConfig=true；旧内容未完整保留，不能精确归因全部差异，此前已披露。昨日run1以来本续跑的tracked文件、main HEAD、index/config与基线一致；没有为PASS修改或恢复共享配置。授权开发提交会增加分支refs/objects，不能把“原资产文件和main未变”称作整个共享.git逐字未变。

**Windows宿主风险：** 这是受控Windows本地功能smoke，**不是任意不可信仓库的安全认证**。仓库Maven/测试可接触宿主文件系统；环境剥离、路径守卫及凭据格式扫描不等于OS沙箱或所有秘密的安全证明。所有既有授权、隔离、revision、洁净与Verification-before-trust检查保留。

## 9. 尚未解决的限制与交付判断

§5中的诊断、已复现Windows路径修复、有界SSE和输入表示改动已有各自回归；“已修复”仅指各自明确的缺陷或能力，不表示下列限制已解决。

| 当前已知限制 | 证据 / 对交付的影响 |
|---|---|
| 成功依赖非默认配置 | SSE=true、connect / idle90s、total300s、max_tokens98,304、raw32MiB、event64KiB；默认非流式60s / 16,384tokens / 8MiB尚未获得同等真实成功验证 |
| 耗时与Provider消耗较高 | execute整体702.050s，约11分42秒。最终10个Gateway记录的promptTokens合计107,423、completionTokens50,749，其中reasoningTokens41,582；reasoning已包含在completion中，不能再相加。最后Coding占prompt66,066 / completion44,014 / reasoning36,220。未采集账单、缓存计费细节及全部历史usage，不能据此给出金额或全任务成本 |
| 成功开发分支尚未合并 | `codex/m4-provider-diagnostics @ 9efee44` 运行产物包含额外修正；原资产A与Candidate B不包含这些维护修正。本次文档提交也不合并开发分支，不能声称 main 的默认交付配置已完成同一路径 |
| 历史UNKNOWN保留 | 早期cleanliness原退出码 / 输出；最初Provider IO失败的确切服务端或网络原因；第5 / 8次未分类的具体guard；昨夜两个子Maven exit1的原始原因。后续更好诊断不能恢复这些原件 |
| 自然语言结果与覆盖范围 | Profile / Plan关于“M3未开始”的历史描述不准确；命名提示是启发式，未证明Python文件真实业务职责；`_usecase`未在三项新增测试中单独设正例 |
| Windows宿主风险 | 普通Maven / 测试仍可访问当前用户宿主文件系统；没有任意不可信仓库安全执行认证，环境过滤与内容净化不等于OS隔离 |
| M5及失败恢复边界 | M5未实现。EXECUTING / VERIFYING后异常保留最后完整检查点并阻止继续，不能强制SUCCEEDED、推进lastVerified、reset活动Candidate或从未知失败状态自动重试 |

**未来可选优化，均未在本任务实施：** 用另行授权的观测评估默认运行预算与交互耗时；改进分析摘要的事实审阅；补充命名边界的针对性覆盖；独立审查并决定开发分支交付方式。完整恢复流程与任意不可信仓库隔离属于其它明确范围，不能以本次PASS代替设计与验证。

## 10. 证据索引与交接

**本次文档补录的只读核验：** 以 `mode=ro` 打开已停止的 `run9/data/smoke.db`，integrity_check=ok；Profile六区、RepositoryProfile各区、SELECTED方向关键字段、Step.goal / scope / plannedChanges / Criteria与保存的API body一致。真实WorkingCopy仍为B、唯一父提交A、干净，实际Git diff逐字节匹配7,612B外部产物。重新汇总现有122份XML得到1346项全通过及三项新增测试记录，没有运行Maven。Provider计数合计来自 `verified-B-evidence.json.gateway_metrics`，未读取或发布推理正文。内部文档链接与外部证据路径在提交前核对。

复盘写法与证据边界参照 [M1 User Discovery](../retrospectives/m1-user-discovery.md)、[M1 Repository Analysis](../retrospectives/m1-repository-analysis.md)、[M2 Product Direction](../retrospectives/m2-product-direction.md)、[M2 E2E Smoke](m2-product-direction-e2e-smoke.md)、[M3 复盘](../retrospectives/m3-evolution-planning-working-copy.md)及 [M3 Smoke](m3-evolution-planning-working-copy-smoke.md)。它们是历史验证 / 复盘，不作为本次B的测试结果；架构与领域解释仍以 [ARCHITECTURE.md](../ARCHITECTURE.md)、[DOMAIN_MODEL.md](../DOMAIN_MODEL.md) 和 [ROADMAP.md](../ROADMAP.md)为准。

- [完整Verified B事实](E:/df-m4-smoke/20261010-m4-provider/run9/evidence/verified-B-evidence.json)：API、Execution/Verification、四个检查点、完整XML、Provider安全指标与原资产审计。
- [实际候选与新增测试证据](E:/df-m4-smoke/20261010-m4-provider/run9/evidence/candidate-mutation-evidence.json)；实际diff见上。
- `run9/evidence/api/`、`checkpoints.jsonl`、`verification/`、`snapshot-verified-B/`。
- `evidence/attempt3-result.json` 至 `attempt10-result.json`、`running.jsonl`；各run的normalAPI、结构日志、数据库与前后恢复快照保留。
- 各轮 `*-verification.json`、完整/聚焦/红测日志与完整XML。最后为 `single-copy-source-verification.json`、`single-copy-source-full-test-reports/`。
- [昨夜报告](E:/df-m4-smoke/20261009-m4-continuation/report.md)、[历史稳定化报告](E:/df-m4-smoke/20261009-stabilization/report.md) 保留。

独立构建副本E:\dfp6保留预期开发文件修改，585个输入hash匹配提交工作树；它不是原资产或真实WorkingCopy。历史run3–run8、离线探针和临时目录保留，没有后台进程。

本次Verified-B成功路径没有未完成项。剩余限制：历史根因UNKNOWN、默认预算尚未真实通过、单次功能成功不等于普遍可靠性/安全认证。没有M5、自动恢复、下一Step或进一步功能开发。
