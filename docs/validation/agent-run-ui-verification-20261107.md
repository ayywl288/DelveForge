# AgentRun 界面简化与失败调查（2026-11-07）

## 范围与结论

- 调查对象：`4266954b-95a1-4357-a379-8a6235c170ed`，基线 `a5dd1681dc3f1897865de28bc64e371d3beec0d3`。
- 本次代码起点：`main` / `1a811415155358ed7c336758678036c98ed31c82`；工作分支 `codex/agent-run-ui-verification`。
- 本次只读取历史 SQLite、基线 Git 内容、保留 diff 和元数据；修改 DelveForge 的提示、一个凭据分类误判及运行记录展示。不重跑业务项目，不调用付费模型，不产生业务 Candidate/Delivery，不合并或推送。
- 原 Run 是模型主动返回 failed 后的 `MODEL_INCOMPLETE`，不是取消。唯一完成拒绝首先命中了 withheld guard。真正的测试错误和依赖安装阻塞也存在；新增 17 项测试通过不足以证明整个 Step 完成。

## 1. 历史运行事实与证据边界

SQLite 以 `mode=ro` 打开。Run 状态 `FAILED`，reason=`MODEL_INCOMPLETE`，取消标记 false，占用 false，retained=true，candidate_id=NULL。58 次模型调用、95 次工具调用、2,552,095 reported tokens。开始/结束 UTC 为 `2026-11-07T13:55:43.215681900Z` / `2026-11-07T14:07:37.755169400Z`。

- `2686 COMPLETION_CHECK_REJECTED`：`UNRESOLVED_CHECK_FAILURE`，原诊断 `Required source is withheld from the execution view`。
- `2722 MODEL_INCOMPLETE`：完整已净化说明已读取。模型称新增候客点后端、迁移、管理页面及 17 项测试完成，但前端安装、两份隔离输入、三项既有测试错误阻塞最终完成。
- 上述说明是模型陈述。命令输出独立证明 17 项新增测试通过、全套 20 项中 3 errors，以及 npm E403；并不证明界面业务验收或完整 Step 成功，也没有独立证明模型提到的所有 HTTP 400。
- `2723 UNVERIFIED_WORK_RETAINED` 对应现存 `partial.diff` 与 `partial-metadata.json`。diff 有 15 个文件，涉及候客点后端/测试、两份迁移和四个前端文件；没有改动下面三个错误对应的原测试/实现/配置。
- 现存运行目录只有上述两个文件。原始独立收据、完整 sourceView/resourceCapabilities JSON、原始 Surefire XML/TXT 均未在运行目录保留；原资产与正式 Working Copy 也没有可用 Surefire 报告。Docker 按该 Run 标签查询未找到保留容器。
- 因此这里的报告证据是持久化、已净化的 TOOL_FINISHED 输出（包括当时读取 Surefire TXT 的输出）及当前固定基线源码；不是声称重新取得了完整原始报告。下面资源名单是用同一基线和实际分类器重建，并与历史阻塞数量、完成拒绝互证。

## 2. 两个隔离资源

| 路径 | 原分类及原因 | 本次处理 |
| --- | --- | --- |
| `backend/bookstore/mvnw.cmd` | `PRESERVED_ONLY` / `blocksChecks=true`；第 132 行的 Windows PowerShell NetworkCredential 构造只引用两个环境变量，却被普通凭据赋值规则匹配为字面量 | 窄修正为 `SOURCE`：仅 `.cmd` 中完整独立语句、两个 `$env:` 参数的构造获豁免；字符串参数、拼接、附加语句、已知令牌仍受保护 |
| `docker-compose.dev.yml` | `PRESERVED_ONLY` / `blocksChecks=true`；第 9、67 行分别配置 MySQL 与 MinIO root password，确为非空字面量，不是环境变量引用 | 保留隔离及完成阻断，不展示值，不改为 PUBLIC_RESOURCE |

追踪链：`RepositoryContentPolicy.classify` → `GitRepositoryCompatibility.resources` → `DockerAgentSandbox.requireBaselineBudget` 的 `blocksChecks = !executionAvailable && buildInput(path)` → `sandbox.py` 的 sourceView.withheldSourceCount → `CommandCheckEvidence` 完成检查。

`buildInput` 保守地把 cmd/yml 视为构建输入；这不等于证明 Linux 测试实际执行了 mvnw.cmd 或启动了 Compose。本次不改变该判断或完成 guard。修正后固定基线探针仅剩 Compose 一份阻断输入；README、环境模板和文档仍可能是 PRESERVED_ONLY，但不是这两个 blocksChecks 资源。

安全后续路径：在单独授权的目标项目变更中，将 Compose 中的实际凭据迁出版本文件，改用受控引用；为测试提供明确的合成值/独立服务 fixture，再经正常基线准备及验证流程。只给测试一个 dummy JWT 并不能消除仍被隔离的 Compose 输入，不能据此放行当前 Run。

## 3. 后端三个错误及一次已修复的新测试失败

完整测试命令 `2652`：20 tests、0 failures、3 errors、退出码 1、BUILD FAILURE。工具聚合字段 failures=3 包含这些 errors，应以实际 Surefire 输出区分。

| 测试 | 实际证据 | 分类与限制 |
| --- | --- | --- |
| `BookstoreApplicationTests.contextLoads` | `2662` 读取 Surefire TXT：rateLimitAspect → jwtTokenProvider 注入失败，最终 `Could not resolve placeholder 'JWT_SECRET'`；基线是普通 @SpringBootTest，未激活测试 profile，application.properties 要求该变量 | 缺测试环境配置；不证明补一个变量后所有外部依赖即可启动 |
| `BookLinkingServiceTest.shouldGenerateMarkdownLinkWhenBookExists` | `2652` 报 `can not find lambda cache for this entity [com.dorian.bookstore.entity.Book]`（测试第 25 行） | 基线用 Mockito 模拟 BookMapper，但实际 service 创建 LambdaQueryWrapper 使用 Book 字段，未初始化 MyBatis 元数据；属测试/mock 上下文问题 |
| `BookLinkingServiceTest.shouldKeepPlainTextWhenBookNotFound` | 同一错误（测试第 36 行） | 同上；不是本次候客点改动的断言回归证据 |

相关测试、BookLinkingService 和配置都存在于固定基线，且不在本次 15 文件 diff 中，支持其为 Step 外既有测试设施问题的判断。但本次没有重跑基线，不能称为“已通过前后对照运行证明”。修复这些目标项目测试不属于当前 DelveForge 稳定化修改。

另一次 `2588` 是当前功能确实引入的问题：PickupPointControllerTest 检查 getPickupPoints 缺少方法级 @PreAuthorize。随后修改已进入保留 diff，`2598`、`2677`、`2713` 均记录 7 controller + 10 service tests 通过。它与最终剩余的 3 errors 不同。

## 4. 日志与源码快照

24 次 exec_command 全部有开始/结束事件，均记录 `sourceChanged=false` 且 before/after snapshot 相同。仓库里的 `*.log` 被忽略，`2677` 写 `./df-final-backend.log`、`2682` 删除，以及 `2713` 写 `./df-check.log`、`2718` 删除，均保持最终快照 `3e87b7b49aafa4bd6b74ffc2c4f7539f76358ef210c36c4a202dffa9e6fdfc51`。

所以**这些日志没有使这次 Run 的证据过期**。较早检查分别绑定 `523c2d...`、`334945...`，后来的真正源码修改使它们过期：`2603` api、`2615` 管理页面、`2620` 路由、`2622` 菜单，最后到达 `3e87b7...`。

一般情况下未被忽略的仓库日志仍可能改变源码身份。提示现明确禁止仓库内临时日志，优先直接输出，必要时只用 `/tmp/` 并在同次调用中读取和保留原退出码。每次命令容器的 /tmp 不跨调用持久化；历史 `2583/2632/2657` 尝试下一调用读取日志失败，说明必须同时解释这个生命周期。

## 5. 探索与必需检查

共 6 个不同的 command+cwd 被带 scope/kind 登记，全部是 test：2578、2598、2652、2672、2677、2713。选测、日志名、参数顺序或输出过滤变体均会形成不同的不可删除义务。最终有 2 个旧快照义务、2 个最终快照失败义务、2 个最终快照通过义务；通过新增 17 tests 不能清除全套或混合选测失败。

没有移除这些义务。系统提示现要求：依赖安装/环境查看/单测诊断/窄范围选测/实验构建通常同时省略两个验证字段；依据 Step 与仓库指引选定规范最终检查后才登记，重试保持 command 和 directory 不变；真实源码改动后重验；先读报告，不靠换日志名/过滤器重复运行；已证实同一环境阻塞时如实停止。没有加入累计时间、token 或工具次数上限。

## 6. 前端依赖阻塞与界面修改

历史 `2637/2647` 输出确认 zrender 6.0.0 的 `cdn.npmmirror.com` tarball 请求 E403。锁文件依赖及受控代理的固定 registry 范围与该拒绝相符（cdn 不在 PUBLIC_REGISTRIES）；未获得历史代理全日志，不能把所有上游原因都视为已证明。使用 `npm ci | tail` 的几次退出码 0 是管道末端状态，不是安装成功；它们没有登记验证范围。本次没有放宽网络或改目标项目锁文件。

前端遵循现有视觉体系，使用 impeccable 和[官方 Vercel Web Interface Guidelines](https://github.com/vercel-labs/web-interface-guidelines/blob/main/command.md)（Vue 适用部分）。修改：

- `frontend/src/components/AgentTimeline.vue`：短终态、删除 current-checks 大块及重复命令清单；保留 checkSummary 的当前状态、尝试数、历史失败和过期提示；删除独立 provider diagnostics 清单。
- `frontend/src/components/agentEventPresentation.ts`：MODEL_INCOMPLETE 当前状态不再重复完整说明；成功命令不再附通用解释；安全拒绝保留明确说明。
- `frontend/src/components/AgentActivityRow.vue`：目录、耗时、退出码、结果紧凑展示；只有一个“模型未完成说明”折叠入口。
- `frontend/src/components/AgentEventDetails.vue`：命令单行截断；完整命令/范围和 stdout/stderr 默认折叠；长说明在窄屏纵向排列。保留净化、原始输出截断标记和测试统计。
- `frontend/src/components/agentResultPresentation.ts`：一次简短结果说明；不复制模型长文本。
- `frontend/tests/agent-timeline.test.ts`：单处说明、无重复验证区、无常规成功请求清单、失败/重试/用量/旧工具可读性、命令及双流折叠等回归。

没有改变数据库事件、用量核算、源码身份或 Candidate/Delivery 条件。

## 7. 验证

- JDK 21，Maven Wrapper `verify`，指定 AgentProgressTest、CodingAgentServiceTest、AgentToolsProtocolTest、AgentToolActivityTest、CommandCheckEvidenceTest、CredentialMutationPolicyTest、DeterministicRepositorySecretPolicyTest、RepositoryContentPolicyTest：**231 项通过，0 失败/错误/跳过**；四个后端模块均编译/打包成功。这是相关回归加全模块构建，不是全仓库所有后端测试。
- `pnpm test`：**22 文件、325 项通过**；`pnpm build`（含 vue-tsc）通过。项目未定义独立 lint 脚本。
- 真实 Docker 回归：**5 项通过，0 失败/错误/跳过**（CommandUsabilityDockerTest 全部 3 项，以及 DockerAgentSandboxTest 的 withheldHighRiskSourceStaysOutsideExecutionAndBlocksCompletion、observedFailedTestCannotBeSkippedOrDeletedToFreeze）。新增测试实测 /tmp 日志不改变快照、探索失败不成为义务、真实修改仍使检查过期、成功检查不能抹去另一失败义务。首次默认 `protocol-v9-jdk21` 标签不存在，随后显式选用本机既有 `protocol-v9-jdk21-05d760e3e30b9cdf00ef751d99bb44694db1a09bdf87311bfa43d8069ee336c6`（image `4c28cb426f89`），无镜像下载。
- 浏览器用实际组件、全部现有样式和合成数据检查 1280px 桌面 / 390px 窄屏；58 条首次成功请求未渲染，错误和重试仍显示；模型长说明仅一处。命令预览恒为一行（25.5px），两种宽度都没有页面横向溢出，展开长命令和双流输出后也无横向溢出；Enter 可展开/收起历史、完整命令、输出和模型说明。截图 `target/agent-run-ui-simplified.png`。这不构成真实业务 E2E。
- 日志位于 `target/run-ui-{backend-tests,docker-tests,frontend-tests,frontend-build}.log`，初次 Docker 环境错误保存在 `target/run-ui-docker-missing-image.log`。未提交临时预览页/探针/日志。

## 8. 保留材料与剩余限制

原始 `partial.diff` 为 50,217 bytes，SHA-256 `5ee8f79d7eed0efff75ba1c79448cd4ee642111c8ee93069e7a6effb1d8df8f6`；元数据为 1,364 bytes，SHA-256 `18d8750cf45dc9e2455586ce1270020d44ac3fc894f8f9528b48397c74f91d73`。结束时重新核对两份文件及目录清单不变。

Compose 隔离、JWT 测试环境、MyBatis 测试初始化和前端受控依赖下载仍需在合适授权范围内解决。完整原始收据/sourceView/Surefire 报告不在现场。本次没有修改原资产或 Working Copy，没有重新验证业务产品，**不能宣称 READY FOR USER E2E 或已交付**。保留无关未跟踪目录 `codex-migration/`、`frontend/design/`、`grilling/`。

## 附录：24 次命令审计

来自持久化 TOOL_FINISHED；每项均与 TOOL_STARTED 按 toolCallId 核对。下表 snapshot 为完整身份的前 12 位，24 项 before/after 全部相等；所有取消/超时标记 false。未登记 scope/kind 的命令写作“探索”。不复制命令读取的源文件或凭据内容。

| 完成事件 | cwd | exit | snapshot | 类型 / scope | 命令 |
| --- | --- | ---: | --- | --- | --- |
| 2464 | `.` | 0 | `bafd3026fb9a` | 探索 | `tail -c 1500 database/seeds/bookstore.sql; echo '---'; head -c 600 database/seeds/bookstore.sql` |
| 2482 | `.` | 0 | `bafd3026fb9a` | 探索 | `sed -n 130,180p backend/bookstore/src/main/java/com/dorian/bookstore/exception/GlobalExceptionHandler.java; echo '---'; ls backend/bookstore/src/main/java/com/dorian/bookstore/security; ls ~/.m2 2>/dev/null &#124; head` |
| 2513 | `.` | 0 | `ed0f706657f6` | 探索 | `sed -n 1,20p backend/bookstore/src/main/java/com/dorian/bookstore/mapper/CategoryMapper.java` |
| 2573 | `.` | 0 | `523c2d2d9359` | 探索 | `ls backend/bookstore; ls -d frontend/node_modules 2>/dev/null; ls ~/.m2/repository 2>/dev/null &#124; head -3; java -version 2>&1 &#124; head -2` |
| 2578 | `backend/bookstore` | 1 | `523c2d2d9359` | test / authorizedStep: 后端候选点 CRUD/权限/校验自动化测试 | `sh mvnw -q -Dtest='PickupPointServiceTest,PickupPointControllerTest,BookLinkingServiceTest' -DfailIfNoTests=false test > /tmp/df-backend-test.log 2>&1; status=$?; tail -60 /tmp/df-backend-test.log; exit "$status"` |
| 2583 | `backend/bookstore` | 0 | `523c2d2d9359` | 探索 | `grep -nE 'Tests run&#124;FAIL&#124;ERROR\]&#124;PickupPoint(Service&#124;Controller)Test&#124;expected&#124;Caused by' /tmp/df-backend-test.log &#124; head -60` |
| 2588 | `backend/bookstore` | 1 | `523c2d2d9359` | 探索 | `sh mvnw -Dtest='PickupPointServiceTest,PickupPointControllerTest' -DfailIfNoTests=false test > /tmp/df-backend-test.log 2>&1; status=$?; grep -nE 'Tests run:&#124;FAILURE&#124;ERROR\]&#124;expected&#124;Caused by&#124;AssertionFailed' /tmp/df-backend-test.log &#124; head -40; exit "$status"` |
| 2598 | `backend/bookstore` | 0 | `3349457d9bbc` | test / authorizedStep: 后端候选点 CRUD/权限/校验自动化测试 | `sh mvnw -Dtest='PickupPointServiceTest,PickupPointControllerTest' -DfailIfNoTests=false test > /tmp/df-backend-test.log 2>&1; status=$?; grep -nE 'Tests run:&#124;BUILD' /tmp/df-backend-test.log &#124; head -20; exit "$status"` |
| 2627 | `frontend` | 1 | `3e87b7b49aaf` | 探索 | `ls node_modules 2>/dev/null &#124; head -3; echo '---'; npm ci > /tmp/df-npm-ci.log 2>&1; status=$?; tail -5 /tmp/df-npm-ci.log; exit "$status"` |
| 2632 | `frontend` | 0 | `3e87b7b49aaf` | 探索 | `grep -nE 'error&#124;ERR' /tmp/df-npm-ci.log &#124; head -20` |
| 2637 | `frontend` | 0 | `3e87b7b49aaf` | 探索 | `npm ci 2>&1 &#124; tail -25` |
| 2642 | `frontend` | 0 | `3e87b7b49aaf` | 探索 | `npm config get registry; npm ci --registry=http://registry.npmjs.org 2>&1 &#124; tail -5` |
| 2647 | `frontend` | 0 | `3e87b7b49aaf` | 探索 | `npm ci --ignore-scripts 2>&1 &#124; tail -8` |
| 2652 | `backend/bookstore` | 1 | `3e87b7b49aaf` | test / authorizedStep: 后端全量测试（含新增候客点与其既有测试） | `sh mvnw test > /tmp/df-backend-all.log 2>&1; status=$?; grep -nE 'Tests run:&#124;BUILD&#124;ERROR\]' /tmp/df-backend-all.log &#124; head -20; exit "$status"` |
| 2657 | `backend/bookstore` | 0 | `3e87b7b49aaf` | 探索 | `grep -nE 'Caused by&#124;Failed to load ApplicationContext&#124;Property&#124;Could not resolve&#124;Unknown database&#124;Communications' /tmp/df-backend-all.log &#124; head -20` |
| 2662 | `.` | 0 | `3e87b7b49aaf` | 探索 | `grep -nE 'Caused by&#124;Failed to load ApplicationContext&#124;Could not resolve&#124;Unknown database&#124;Communications link' /tmp/df-backend-all.log 2>/dev/null &#124; head -10; echo '--- fallback'; grep -nE 'Caused by' backend/bookstore/target/surefire-reports/com.dorian.bookstore.BookstoreApplicationTests.txt &#124; head -10` |
| 2672 | `backend/bookstore` | 1 | `3e87b7b49aaf` | test / authorizedStep: 候客点 CRUD/权限/校验自动化测试 | `sh mvnw test -Dtest='PickupPointServiceTest,PickupPointControllerTest,BookLinkingServiceTest' -DfailIfNoTests=false > /tmp/df-final-backend.log 2>&1; status=$?; grep -nE 'Tests run:&#124;BUILD' /tmp/df-final-backend.log &#124; head -10; exit "$status"` |
| 2677 | `backend/bookstore` | 0 | `3e87b7b49aaf` | test / authorizedStep: 候客点 CRUD/权限/数据校验自动化测试（本步新增测试） | `sh mvnw test -Dtest='PickupPointServiceTest,PickupPointControllerTest' -DfailIfNoTests=false > ./df-final-backend.log 2>&1; status=$?; grep -nE 'Tests run:&#124;BUILD' ./df-final-backend.log &#124; head -10; exit "$status"` |
| 2682 | `backend/bookstore` | 0 | `3e87b7b49aaf` | 探索 | `rm -f df-final-backend.log; ls; git status --porcelain &#124; head -30` |
| 2691 | `.` | 0 | `3e87b7b49aaf` | 探索 | `ls -la backend/bookstore/src/main/java/com/dorian/bookstore/entity/PickupPoint.java backend/bookstore/src/main/java/com/dorian/bookstore/mapper/PickupPointMapper.java backend/bookstore/src/main/java/com/dorian/bookstore/controller/PickupPointController.java backend/bookstore/src/main/java/com/dorian/bookstore/service/PickupPointService.java backend/bookstore/src/main/java/com/dorian/bookstore/dto/request/PickupPointRequest.java backend/bookstore/src/main/java/com/dorian/bookstore/dto/response/PickupPointResponse.java; echo '--- tests'; ls -la backend/bookstore/src/test/java/com/dorian/bookstore/controller/PickupPointControllerTest.java backend/bookstore/src/test/java/com/dorian/bookstore/service/PickupPointServiceTest.java; echo '--- sql/fe'; ls -la database/migrations/009_create_tb_pickup_point.sql database/migrations/010_seed_tb_pickup_point.sql frontend/src/api/pickupPoint.js frontend/src/views/admin/PickupPointManage.vue` |
| 2696 | `.` | 0 | `3e87b7b49aaf` | 探索 | `ls -la backend/bookstore/target/classes/com/dorian/bookstore/controller/ 2>/dev/null &#124; head; echo '--- 检查是否有占位/受保护痕迹'; ls -la database/migrations/ &#124; head -20` |
| 2701 | `.` | 0 | `3e87b7b49aaf` | 探索 | `cd /workspace 2>/dev/null &#124;&#124; cd /; pwd; echo '--- 隐藏/受保护线索'; find . -maxdepth 3 -name '*.env*' -o -maxdepth 3 -name '*PRESERVED*' 2>/dev/null &#124; head; echo '--- git?'; ls -a &#124; head -20` |
| 2713 | `backend/bookstore` | 0 | `3e87b7b49aaf` | test / authorizedStep: 候客点 CRUD/权限/数据校验自动化测试（最终源码） | `sh mvnw -Dtest='PickupPointServiceTest,PickupPointControllerTest' -DfailIfNoTests=false test > ./df-check.log 2>&1; status=$?; grep -nE 'Tests run:&#124;BUILD&#124;ERROR\]' ./df-check.log &#124; head -12; exit "$status"` |
| 2718 | `backend/bookstore` | 0 | `3e87b7b49aaf` | 探索 | `rm -f df-check.log; echo cleaned` |
