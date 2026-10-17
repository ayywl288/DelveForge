# M5 Final Acceptance & Partial Closeout — 2026-10-17

## 结论与适用范围

**M5 PARTIAL READY FOR LOCAL MERGE**

当前授权范围的回归、隔离升级、真实打包启动／重启及真实浏览器 Gate 均通过。没有发现阻塞 M5 PARTIAL 本地集成的 Critical／Important 缺陷；明确延期与未实测范围继续作为能力边界保留。

本轮只验收 M5-A～E 及既有修复，M5 保持 **PARTIAL**。Recovery 写入正式 DEFERRED；不将完整 M5 标为 DONE。本结论不是发布许可，不授权 Push、合并、Tag、真实用户库升级或付费 Provider 调用。

原始资料根：`E:\develop\DelveForge-worktrees\m5-lab-artifacts\m5-final-acceptance-20261017`。下文资料路径均相对此根。脚本、实际命令、原生退出码、数据库、日志、截图及文件哈希一并保留，历史报告没有被改写以隐藏失败。

## 1. 候选、源码与冻结边界

| 项目 | 本轮核实结果 |
| --- | --- |
| Worktree | `E:\develop\DelveForge-worktrees\m5-lab` |
| 分支 | `feature/m5-lab` |
| 起始候选 HEAD | `9afe395b912b5d18507ec6adb0c784784b6a5815` |
| 已报告受测生产源码提交 | `dc19cfa8d3ba70871c378ae3edaebc8f0f1b1d00` |
| 本地 main 基线 | `474375d8f9f2dcd822037fa85c5e197198c4e5cf` |
| HEAD 相对生产提交 | 只有 `m5-project-deletion-guard-fix.md` 的验证记录变化，生产／测试／迁移／POM 无变化 |
| 起始工作树 | 已跟踪文件干净；仅原有未跟踪 `tools/spikes/m5-f1/__pycache__/` |
| 本轮源码修改 | 无生产代码、测试、依赖或 migration 修改；必要入口说明和最终验收文档变更见第 7 节 |
| 交付状态 | HEAD 保持起始候选不变；3 个既有入口文档修改、2 个新说明／报告待审阅，未提交、未 Push、未合并；原未跟踪 pyc 原样保留 |

已检查 AGENTS、PRODUCT、ARCHITECTURE、DOMAIN_MODEL、ROADMAP、ADR-0001、ADR-0009、M5 partial closeout、Deletion Guard 修复记录、LOCAL_DATA_ENVIRONMENTS、README、本地使用说明、V18～V21 及相关实现／测试。

当前 main 与候选差异覆盖既有 M5-A～E、安全修复和隔离 Spike／NO-GO 记录；没有把 Spike 接入生产。源码复核确认 Retry 不持有 AI／Workspace 写 Port；Rollback 与 Execution 的授权、范围、检查点和资产资格仍独立校验。没有新增 Recovery Port／UseCase／API／UI、启动接管或自动重放。

为保留原 target／日志，先复制 782 个已跟踪文件并逐文件校验 SHA-256。完整后端最终使用短路径 `E:\df-m5-acceptance-20261017\candidate` 中未经修改的原 POM、源码与资源；前端使用 `run-01/candidate/frontend` 与现有依赖的隔离副本。长路径构建首轮失败后改用短路径，不改系统 Git／安全配置，不缩减测试。

证据：`run-01/candidate-manifest.json`、`run-01/preserved-pyc.json`、`source-freeze-check.json`、`toolchain-versions.txt`、`final-integrity.json`。

## 2. 本轮完整回归与构建

### 2.1 后端

在短路径候选副本根运行原 Maven Wrapper，使用现有本地依赖：

```powershell
.\mvnw.cmd -o '-DargLine=-Djava.io.tmpdir=E:/df-m5-acceptance-20261017/tmp' verify
```

本轮 shell 的 `MAVEN_OPTS` 为 `-Xmx512m -Djava.io.tmpdir=E:/df-m5-acceptance-20261017/tmp`，TEMP／TMP 指向该 E 盘 tmp；移除 Provider key、SPRING_APPLICATION_JSON 和继承的 JVM 注入选项。没有增加 skip、排除测试或付费 Provider 条件。

| 模块 | Tests | Failures | Errors | Skipped | XML |
| --- | ---: | ---: | ---: | ---: | ---: |
| delveforge-app | 403 | 0 | 0 | 0 | 29 |
| delveforge-application | 780 | 0 | 0 | 0 | 65 |
| delveforge-domain | 240 | 0 | 0 | 0 | 12 |
| delveforge-infrastructure | 473 | 0 | 0 | 0 | 39 |

**完整命令退出 0，BUILD SUCCESS；本轮合计 1896 项，失败 0、错误 0、跳过 0，145 份新 XML。** 四个后端模块及 Spring Boot repackage 全部完成。

计数仅来自本次全新输出目录的 Surefire XML，包含各参数化调用；完整命令的退出码和 Reactor／打包结果另外核对。不借用历史 1,814、104 或其他定向数字，也不把浏览器测试宿主的一个 `serve` 测试加入完整回归总数。

首轮长目录副本中的 raw Git fixture 出现 `Filename too long`，保留在 `run-01/backend-verify.log`。确认环境原因后，仅停止该轮测试 JVM，退出 1；该轮未完成，不能声称完整回归通过。该轮已生成 105 份 XML，记录 1,392 项、0 failures、140 errors、0 skipped；这些是报告计数，不能解释为 1,392 项全部通过。错误分布为 AnalyzeRepositoryWorkflow 1、GitAttributeSafety 91、GitEvolutionExecution 11、GitRollbackContent 37。 原日志、XML 和失败现场保留；短路径重跑没有修改源码或测试规则。

证据：`run-02/backend-command.txt`、`run-02/backend-verify.log`、`run-02/backend-exit.txt`、`backend-summary.json`、`backend-surefire/`。长测试的只读线程诊断保存在 `run-02/*thread-diagnostic.txt`，不作为测试通过证据。

### 2.2 前端

沿用 package.json 的 pnpm 12.9.1 配置：

| 实际命令 | 原生退出码 | 本轮结果 |
| --- | ---: | --- |
| `pnpm test` | 0 | 12 文件，200 项通过；0 失败／错误／跳过 |
| `pnpm typecheck` | 0 | vue-tsc 通过 |
| `pnpm build` | 0 | vue-tsc 与 Vite 生产构建通过，56 modules |
| lint | 不适用 | 仓库未定义 lint script，没有虚构此项通过 |

构建输出包含 `index-D1eY8oBa.js` 与 `index-BjEaVj83.css`。Vitest 是 jsdom 自动化证据；真实浏览器结果另列于第 5 节。证据：`run-01/frontend-results.json`、`run-01/frontend-{test,typecheck,build}.log`、构建产物与 `artifact-manifest.json`。

工具实测：JDK 21.0.12.1、Maven Wrapper 3.9.14、Git 2.53.0.windows.2、Node 22.16.0、pnpm 12.9.1；没有升级仓库依赖。

## 3. 隔离 SQLite 升级与读取

只使用本轮新建合成库。使用当前 Flyway 与当前 migration 逐步建立旧 schema，装载该版本可表示的合法业务数据，再迁移到 V21。它验证 schema／数据兼容性，不冒充运行过所有历史二进制或升级过真实用户数据库。

业务样本主要来自现有 API 集成 fixture：确定性 AI、真实 Application／Domain／SQLite／Git 路径产生 Verification Failure、Repair、User Rollback、Automatic Rollback、Retry 与成功记录。VERIFYING、ROLLING_BACK、UNSUPPORTED 的额外检查点是另建数据库中的明确合成投影，经当前领域重建校验；构造过程保存在 `pending-fixture-construction.json`，不声称是实际进程崩溃产生的现场。原观察库与 UNKNOWN 没有因此改写。

| 起点 → V21 | 代表内容与结果 |
| --- | --- |
| 空库 | V1～V21 完整应用；最终 schema 与所有升级路径一致 |
| V2、V4 | Profile 当前版本／快照，Asset、Repository Profile 与证据保持 |
| V8 | 合法 PROPOSED Plan、未授权 Step、NULL baseline；旧 Criteria 迁为 UNSUPPORTED，领域可读 |
| V10 | 成功／失败历史；额外覆盖缺少 `outcome` 的旧 M4 成功 JSON，原 JSON 不被回填改写 |
| V13、V16 | 历史字段、授权版本及已存在的 Flow／导航关系保持；既有迁移的保守归属行为保留 |
| V17 | Verification Failure 与额外 VERIFYING 未决样本，状态／可信基线不改判 |
| V18 | Repair 授权／原失败快照；包括两次 Repair 后的成功历史；新增字段不伪造授权 |
| V19 | User Rollback 后 FAILED、独立授权与结果；另覆盖 ROLLING_BACK 未决授权 |
| V20 | Automatic Rollback 成功 FAILED、失败 RECOVERY_REQUIRED 及失败诊断／恢复结果 |
| V21 → V21 | Retry READY、上一轮完整失败及授权快照；再次迁移为 no-op，不自动 Execute |

按不同输入历史去重，共 **19 条有效空库／历史升级或最新版本校验场景**。后续补充和重复空库对照没有再次计数。具体数据库及逐库结果见 `upgrade-matrix-02/`（排除无效 V8 夹具）、`upgrade-v8-valid/`、`upgrade-m5-history/`、`upgrade-pending-history/`、`upgrade-legacy-m4/`。

每条有效路径核对 Flyway 的 version、description、script、checksum、success 与现有 history 前缀；最终 21 条成功 migration 及 schema 与空库对照相同。比较升级前每个已有列的逐行原值，包括原始结果 JSON、授权身份、顺序、baseline、currentRevision、lastVerifiedRevision；新字段只接受 migration 定义的 NULL／空历史默认值。

执行 `integrity_check`、`foreign_key_check`，并另外检查实际对象引用与 Flow／revision 绑定。项目多张表未依赖数据库外键，因此未将“foreign_key_check 无输出”冒充关系完整性。覆盖 User Profile、Asset、Repository Profile、Direction、Flow、Plan、Step、WorkingCopy；没有发现孤儿引用或信任推进。

最终有效关系／revision 检查为 **42 库通过**（`relational-integrity-final.json`），包含重复对照及原始业务快照，不能等同于 42 种升级起点。领域读取执行记录分批保留在 `domain-read/`、`domain-read-02/`、`pending-domain-read/`、`history-domain-read/`、`legacy-domain-read/`；后四批分别 10、3、9、2 项通过，首批在正确拒绝无效 V8 前已有 10 项通过。有效样本重建不改写业务列。

保留两项夹具校准记录：第一次装载与 migration 自带单例 guard 行冲突；V8 初版夹具误留了未执行 Step 的 baseline，SQL 升级虽成功，领域重建正确拒绝。两者均在新输出目录修正，原失败资料保留，没有修改生产迁移或放宽领域校验。

所有 SQLite 快照使用 `sqlite3.Connection.backup` 并校验完整性，未复制活动 WAL 主文件冒充备份。没有访问真实用户数据库。

## 4. 实际打包启动、重启与 API

**13 个空库／历史场景各启动两次，共 26 次实际 JAR 启动／重启通过；4 项错误配置自然非零退出，另有一次隔离默认值启动。所有读取一致、业务表值和源资产／WorkingCopy 文件哈希不变，Provider trap 请求为 0。** 详细启动命令、进程退出、GET 响应和配置见 `packaged-smoke-run-03/{results,configuration-results,summary}.json` 及逐次日志。JAR SHA-256：`050563451cf6f56143271cb9ac95d723efcaa36095ceabcc4ef26f6dbd374078`。

采用本轮完整 verify 生成的 Spring Boot JAR，前端使用本轮 Vite 生产产物。后端主探测端口 18805，录证 Provider trap 为本机 18809；所有路径均为新建隔离目录。child 环境清除全部继承的 SPRING／DELVEFORGE／DEEPSEEK 配置及 JVM 注入项；命令行指定数据库、Workspace Root、classpath 配置、本机绑定和空 Provider key，缺省路径探测另行限定独立 cwd／user.home。

历史副本路径只加入本轮已拥有的合成目录之 existing Roots，未授权真实用户目录。每库读取 connectivity、workspace-configuration、项目与上下文、Profile、Asset、Repository Profile、Direction、Plan、WorkingCopy；核对实际 root、V21、业务 JSON 与两次进程结果。连通性接口的 `timestamp` 每次应答正常更新，单独校验其递增；其余 GET 业务 JSON 严格比较。初轮 `packaged-smoke-run-02` 的脚本误将这个技术时间字段计入逐字比较而停止，未计为通过；修正后在全新 run-03 重跑，原日志和目录保留。早期仅等待完整构建的脚本校准目录也未计入实际启动结果。关闭方式是对本轮拥有的空闲 GET-only Java 进程执行受控 terminate，再启动同一 JAR；没有将它宣传为正常关机、断电、OS 崩溃或旧写者停止证明。

对 EXECUTING、VERIFYING、ROLLING_BACK、RECOVERY_REQUIRED 的有效请求参数探测必须拒绝 Execute／Repair／Rollback／Retry／Deletion；UNSUPPORTED READY 必须在 Provider 前拒绝。删除使用当前导航版本并核对 `PROJECT_DELETION_BLOCKED`，避免把过期版本误算作执行守卫。拒绝前后完整表值和 Git 文件哈希必须相同。

错误配置检查包括缺失的必需配置文件、数据库路径为目录、父路径为文件、空数据库参数；要求自然非零退出且不创建默认数据目录，不能用超时强杀冒充正常拒绝。省略所有数据路径仍使用应用的既有默认值；本轮以独立 cwd／user.home 验证，不声称应用已经强制要求显式数据路径。用户必须明确核对路径，说明已写进入口文档。

原标签页关闭后，在后端仍停止期间重新打开原 origin，新标签页从浏览器存储恢复未知请求等待标记；`browser-evidence-final/browser-unknown-before-packaged-restart.txt` 记录了无后端状态响应时的明确提示。另将原浏览器 UNKNOWN 所属合成库交给同一新 JAR，在原端口 18765 启动，沿用原 origin 18766。当前 Step 仍为 EXECUTING；浏览器重新加载、正常 GET 后继续显示等待／未知，未重发写请求，未清除原 receipt。全部表值及源资产／副本文件逐项哈希保持一致。证据：`packaged-original-unknown-restart/{ready,result}.json`、`browser-original-unknown-restart.json`、`browser-evidence-final/browser-unknown-packaged-restart.{txt,jpg}`。

较早数据库快照不包含后续 Git 变化。`physical-head-readback.json` 明确记录 User Rollback 的旧快照所指副本后来已被合法 Retry 推进；本轮只读取这种历史，不从旧备份执行。它不能作为继续操作已推进副本的依据。

## 5. 真实 Edge 浏览器验收

使用实际 Edge 扩展连接，在独立本机 origin 打开 Vite 构建产物。主要业务后端是复用仓库确定性 AiGateway 和真实 Git／SQLite 的临时测试宿主；正常路径未用 API 假响应替代业务后端。Automatic Rollback 失败通过现有 Rollback Port 的确定性异常注入验证 Application／Domain／UI，不声称复现了磁盘或 OS 故障。

| 场景 | 本轮实际证据 |
| --- | --- |
| Verification FAIL → Repair → 再 FAIL → Repair PASS | 真实浏览器两次独立授权；原 baseline 不变，失败／授权历史保留，只在 PASS 后推进 lastVerified |
| User Rollback → FAILED → Retry READY → 显式 Execute | 原基线精确恢复，Retry 不调用 AI／修改代码；随后浏览器明确 Execute，最终成功 |
| Automatic Rollback 成功 | 注入本次 index.lock 受控拒绝，真实 Git 恢复确认后 FAILED；物理 HEAD 与已保存原基线一致 |
| Automatic Rollback 失败 | Port 故障注入后 RECOVERY_REQUIRED；即使 current 等于 lastVerified，也不自行解除阻塞；刷新仍无 Recovery 入口 |
| 双标签、storage 事件、乱序 GET | 旧 GET 延迟 12 秒，小于前端 15 秒读取期限；后发 GET 先完成、新 Execute receipt 已产生，旧响应没有解除等待。真实 storage 事件让另一标签页禁用重复执行，刷新保留标记；最终请求日志只见一次 Execute |
| 网络中断／GET 超时 | 代理停止、查询失败、响应丢失和 60 秒 GET 场景保持等待，不自动重发；60 秒场景单独记作超时，未算作成功送达的乱序响应 |
| 拒绝与删除 | 无锁／存储不可读时未发送 Execute；RECOVERY_REQUIRED 删除按钮禁用并解释原因。合法成功历史完成软删除／导航恢复，逐表比较只有导航表变化 |
| 打包重启后的原 UNKNOWN | 原 origin、原数据库、原 UNKNOWN receipt 在实际新 JAR 启动后重新加载仍阻塞；没有自动写请求，数据库和 Git 内容不变 |

浏览器 Verification 标准使用实际 DIFF_CHECK 的明确 FAIL／PASS；Maven 编译、测试失败与崩溃分类另由完整后端真实集成测试覆盖，不声称每一种 Maven 故障都经过浏览器重做。

锁／存储故障使用独立端口包装页，在原构建 bundle 加载前移除 navigator.locks 或让 Storage.getItem 抛错；原 JS／CSS 构建产物未修改。这是实际浏览器引擎中的故障注入，和 jsdom／Vitest 分列。没有直接清空未知 receipt，也没有为通过而重发未知请求。

期限内实测覆盖“旧 GET 起始没有 receipt，随后另一标签页写入新 Execute”的排列。原 M5-010 的旧 Retry receipt 替换及更多交错排列由本轮完整 Vitest race suite 覆盖；不把这两类证据混为同一次实测。

证据：`browser-control/`、`synthetic-snapshots/`、`browser-http*.jsonl`、`browser-12s-race-summary.json`、`browser-race-request-summary.json`、`soft-delete-integrity.json`、`browser-evidence-final/*.txt` 与关键 JPG。临时测试宿主源码和故障代理归档到 `helpers-final/`（早期版本另保存在 `helpers/`）；宿主的单个 serve 测试不计为后端完整回归。

最初的测试替身从 checkout 读取 CRLF 生成了不合法补丁，实际系统拒绝后留下 EXECUTING 和 UNKNOWN；该现场保留，并在不同数据库／origin 修正夹具。初始代理缺少 PUT 导致项目关联保存收到 501，前端正确要求重新打开项目；补齐代理后按界面正常重新读取，没有改产品代码或强清标记。断网期间，测试代理构造中文错误原因时还出现了 Latin-1 编码异常，浏览器收到连接中断；它只作为断网故障注入，未计为正常 HTTP 响应或产品故障。代理退出记录见 `proxy-shutdown-summary.json`。上述校准不是生产修复或成功业务路径。

## 6. Findings、未完成项与风险

**Critical：0。Important：0 个当前范围的未解决阻塞。Minor：1 项说明一致性问题。** 浏览器侧栏仍有“M5 暂缓”，FAILED 提示仍有早期“没有恢复入口／不能强制重试 Candidate”的概括，和现有显式 Retry 能力并列时易混淆；操作授权与失败关闭未被削弱。本轮只修正必要入口文档，不扩大到 UI 文案改造。

环境／夹具问题：首轮长路径 Git 错误、旧格式合成夹具校准、CRLF 测试补丁、代理 PUT 缺失、超出期限的 GET、连通性时间戳比较及代理错误响应编码均已明确记录并隔离处理。它们没有被删除或改写为成功证据。

以下是本次验收之外或明确保留的边界：

- Recovery 写入、旧写者停止／隔离证明、接管、Recovery 审计及 UNRECOVERABLE 判定／写入继续延期。既有 RECOVERY_REQUIRED 与遗留 UNKNOWN 长期阻塞，刷新／重启不保证解除。
- 不是任意不可信代码的 OS 沙箱；本机 Maven／插件／测试的宿主访问不由 WorkingCopy 隔离解决。不承诺公网认证部署。
- 付费 Provider E2E、真实用户库、跨平台、断电／磁盘损坏、写入期间强杀与外部逃逸写者没有在本轮验证；确定性成功不能外推这些结论。
- Provider exactly-once 未实现；Git 与 SQLite 不是一个事务。旧前端长期混用、跨 origin 或不同浏览器配置文件不应被当成绕过未决请求的办法。
- 当前 Git 输入支持范围保守，不支持的属性来源／编码／filter 等拒绝；某些授权后拒绝可保留未决检查点，不能宣传成任意仓库都可继续。
- 不提供向下迁移。降级需要升级前一致备份、对应代码和物理 WorkingCopy 核对；不能把旧数据库快照作为撤销已发生 Git 操作的工具。

## 7. 必要文档与剩余发布条件

本轮仅更新 README、本地 M4 使用说明、M5 PARTIAL 本地边界说明、必要的 Roadmap 指引和本报告。历史 Slice／Deletion Guard／NO-GO 报告、原日志和原型不改写；原未跟踪 pyc 保留且不纳入提交。

剩余发布条件与本地合并判定分开：选择并授权版本／发布范围、完成候选与环境核对、按一致备份流程安排真实数据升级、关闭旧页面并确认未决请求、明确受支持输入与本机信任边界；若发布承诺包含付费 Provider 成功、完整 Recovery、任意不可信代码隔离或所有故障可恢复，还需要相应独立验证／架构决策，当前不能作此承诺。

本轮不 Push、不合并、不发布、不移动 Tag、不修改 main、不访问真实用户数据库、不调用付费 Provider、不创建账户或修改系统安全配置。完成报告与本轮证据归档后停止，等待用户下一步安排。
