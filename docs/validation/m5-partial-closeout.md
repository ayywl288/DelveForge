# M5 — Partial Closeout & Release Readiness

日期：2026-10-16（Asia/Shanghai）。本轮为文档收尾与只读核查，不是新功能或发布执行。
Worktree：`E:\develop\DelveForge-worktrees\m5-lab`；分支 `feature/m5-lab`；核查基线
`7e5d59612349cf8e0179e6ad9f159096f9a95fa7`。开始时无已跟踪修改，原有 F1 `.pyc` 未跟踪文件保留。

**阶段性结论：M5 PARTIAL；建议进入最终集成审查，不建议直接发布或将完整 M5 标为 DONE。**
M5-A～E 及其修复已实现；用户在本轮任务中明确确认其独立审查 PASS。F1/F1b 的实验和记录也通过
独立审查，但隔离技术 Gate 均为 NO-GO。用户已决定停止当前候选研究，Recovery 写入正式延期。

本结论区分：当前源码／迁移／测试定义、历史实际执行证据、本轮只读复核、用户提供的独立审查结论，
以及尚未执行的发布 Gate。没有把报告中的“自测 PASS／待审查”改写成当时已独立通过，也没有重新执行完整回归。

## 1. 实现核查与可独立集成范围

A～E 必须包含后续 M5-001～010 修复一起审查，不能只挑最初 feature 提交而漏掉安全补强。
当前可用的是有限且受控的本地执行流程，不是任意代码沙箱或通用恢复系统。

| Slice | 源码中已存在的行为 | API / UI / Persistence / 测试依据 |
| --- | --- | --- |
| A，3f21bca | 明确 Verification FAIL 保存 VerificationResult，保留 Candidate，Step→VERIFICATION_FAILED；不推进 lastVerified | 既有 execute／Plan GET；UI 展示失败与可信 revision；无新 schema；VerificationOutcome / StepResultJsonCompatibility / API 故障回归 |
| B，ed659c1 | 原 Step 独立 Repair 授权，归档旧失败，从当前 Candidate 继续；首次 baseline 不变，重验原 baseline..新 Candidate 与全部 Criteria | POST repair；独立确认／历史展示；V18 repair_authorizations；Repair、再次失败、并发、撤权、CAS／新上下文回读测试 |
| C，2af0b55 及修复 | VERIFICATION_FAILED 独立授权 User Rollback；只有真实完整 baseline 证明与成功提交后才 FAILED，失败／未确认转 RECOVERY_REQUIRED | POST rollback；Candidate／baseline／新授权身份绑定；V19 rollback_authorization / rollback_result；真实 Git 内容、额外文件、锁、属性和 SQLite 检查点测试 |
| D，f4e4c79 及修复 | 仅专用 ConfirmedExecutionFailure 保存阶段／诊断／实际 Diff identity，无假 Candidate；先 ROLLING_BACK，再单次自动精确恢复；UNKNOWN 不触发 | 原 execute／repair 返回实际 Plan，UI 展示诊断；V20 execution_failure_result / automatic_rollback_result；APPLY/PUBLISH、未知退出、部分未发布树、恢复与持久化故障测试 |
| E，7122b5b + 97f587a | 精确恢复的 FAILED 独立授权 Retry→READY，归档前轮结果与授权；之后仍须显式执行并核对父 Retry ID | POST retry；prepare／execute／repair／rollback 绑定当前 Retry；V21 retry_authorizations；重复／并发／撤权／重启回读，M5-010 两 Session receipt 竞态 |

关键入口均已查到实际实现，而非仅依据 Roadmap：

- Domain：[EvolutionStep](../../backend/delveforge-domain/src/main/java/com/ayywl/delveforge/domain/evolution/EvolutionStep.java)
  和 [StepExecutionPolicy](../../backend/delveforge-domain/src/main/java/com/ayywl/delveforge/domain/evolution/StepExecutionPolicy.java)
  实施合法转换、基线／前序 Step／未决状态守卫；RepairAuthorization、RollbackAuthorization、RetryAuthorization
  保存独立动作事实。ID 为动作身份，不替代资产权限或新增多用户认证体系。
- Application：[ExecuteEvolutionStepUseCase](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/evolution/execution/ExecuteEvolutionStepUseCase.java)、
  [RollbackEvolutionStepUseCase](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/evolution/execution/RollbackEvolutionStepUseCase.java)、
  [RetryEvolutionStepUseCase](../../backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/evolution/execution/RetryEvolutionStepUseCase.java)
  及 ExecutionPreparationState／PrepareEvolutionStepUseCase 重查资产、Flow／Plan、副本绑定和真实内容。
- Workspace：[GitWorkspaceAdapter](../../backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/workspace/GitWorkspaceAdapter.java)
  共用整树真实内容、受支持 LF/CRLF、属性来源／BOM／宏／大小写守卫；不靠 stat/status/HEAD 单独证明精确恢复。
  [GitWorkspaceVerificationAdapter](../../backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/workspace/GitWorkspaceVerificationAdapter.java)
  保留 PASS／明确 FAIL／NOT_RUN／INCONCLUSIVE 区分。
- Persistence：[SqliteStepExecutionCommitAdapter](../../backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/evolutionplan/SqliteStepExecutionCommitAdapter.java)
  重放 Domain 后，原子 CAS Step／WorkingCopy 与完整旧历史；诊断／阻塞不因撤权而被抹去，但不授予写能力。
  [迁移目录](../../backend/delveforge-infrastructure/src/main/resources/db/migration) 新增 V18～V21，V1～V17 与本地 main 无差异。
- REST：[EvolutionStepExecutionController](../../backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/api/evolutionplan/EvolutionStepExecutionController.java)
  提供 execute、repair、rollback、retry；EvolutionPlanResponse 返回当前结果和授权／失败历史。
  GET 只读取。未查到 Recovery UseCase／Port／REST action／前端命令，commit adapter 也没有 Recovery 转移分支。
- UI：[EvolutionView.vue](../../frontend/src/views/EvolutionView.vue) 有独立动作勾选、状态／revision／历史展示，明确提示无 Recovery；
  [session.ts](../../frontend/src/state/session.ts) 和 [receipt-race.test.ts](../../frontend/tests/receipt-race.test.ts)
  使用独立 requestId、已采纳 GET、同名短时 receipt Web Lock 保护跨标签等待，不自动重发。

Repair／Retry 复用既有 Execution→Candidate→Verification，只有 PASS 推进信任。FAILED Retry 的用例不持有
AI／mutation／rollback Port，既不调用 Provider 也不依赖 Recovery；User Rollback 不要求重新满足当前验证标准。
A～E 通过已支持的成功／已知失败路径即可闭合；恢复失败或异常未决时停住是当前明确边界，不隐式调用未来 Recovery。

## 2. 正式延期的决策与保留目标

[ADR-0001 的 2026-10-16 refinement](../decisions/0001-separate-workspace-read-and-mutation-capabilities.md)
记录用户已接受的决策；[Architecture](../ARCHITECTURE.md) 与 [Roadmap](../ROADMAP.md) 已同步。
[Domain §8.15](../DOMAIN_MODEL.md#815-recover-working-copy) 只增加实现状态说明，保留完整目标和不变量。

- 旧写者停止或失去写入能力尚无可接受证明；重启、超时、PID 消失、SQLite CAS、普通锁释放均不足。
- [F1](m5-f1-ownership-spike.md)：真实 WMI writer 不在 Job 内，Owner 退出及 Job 归零后仍写。15/15
  场景断言成立包含不安全反例，原生 exit=2，不能解释成技术 GO。
- [F1b](m5-f1b-isolation-spike.md)：受限 Token 在 KERNELBASE 初始化失败，独立身份未建立；宿主对照中
  同 SID ACL 撤权不能撤销预开句柄，staging 原型遗漏父目录 junction。7 场景仅 5 个观察成立、2 个未到阶段，exit=1。
- 停止当前隔离方案研究；不将 Spike 集成生产，不引入 Recovery 写能力、启动补偿、重放或 F2/F3。
- RECOVERY_REQUIRED 和遗留 UNKNOWN 继续保留事实并阻塞，不伪造 FAILED／Verified State，也不因技术 NO-GO
  自动写 UNRECOVERABLE。未来必须先经独立安全架构设计、真实 OS 正反实验和独立审查，再讨论开放写能力。

未删除的完整目标仍是：独立用户授权下精确恢复原 Step baseline，成功后 WorkingCopy→EVOLVING、Step→FAILED，
其后 Retry 另行授权；确实无法恢复时的 UNRECOVERABLE 语义仍保留。当前没有实现这些 Recovery 转移。
新机制不能追溯证明旧执行安全；新旧历史处置也不得靠补造 owner／StopProof 解锁。

## 3. 当前版本会长期阻塞的状态：必须告知使用者

| 已保存／客户端状态 | 当前允许与禁止 | 是否有本版解除流程 |
| --- | --- | --- |
| VERIFICATION_FAILED + 可信 baseline、匹配 Candidate | 可独立 Repair 或 User Rollback；不能运行后续 Step | 有，须通过当前授权和物理守卫；若现场漂移则拒绝 |
| FAILED + 精确恢复原 baseline | 独立 Retry 保存 READY，再显式执行；FAILED 不等于成功 | 有，仍核对当前资产权限、绑定、Criteria 与实际内容 |
| Step／WorkingCopy RECOVERY_REQUIRED | 只读诊断／历史；禁止新的 Execution、Repair、Retry、再次 User Rollback 或 Recovery | 无；本版中长期阻塞，即使 HEAD 看似 baseline 也不自动解锁 |
| 遗留 EXECUTING | 可能停在授权后 Provider／提案／安全拒绝、mutation 或结果检查点；不能断言旧写者已停或物理 current 与记录一致 | 无重启接管／重放／取消／自动改判入口 |
| 遗留 VERIFYING | Candidate 已记录但无被接受的完整终态；不能推断通过或明确 FAIL | 无重新验证／改判入口 |
| 遗留 ROLLING_BACK | 恢复及其终态未完整保存；即使实际内容已回到 baseline，仍须保留原事实 | 无重放／改 FAILED 或 RECOVERY_REQUIRED 的入口 |
| UNKNOWN receipt、存储损坏／不可读、Web Locks 不可用 | 保留等待／阻塞，只采用匹配请求的可信结果；过期 GET 不能清理 | 只有现有匹配完成／明确授权前拒绝证明可清理；证据始终不足时可能永久等待 |
| UNRECOVERABLE 已存在 | 资格守卫禁止继续当前执行路径 | 本版不会通过 Recovery 产生／解除该状态 |

READY 也不是旧外部调用已停止的证明：首次／Retry 准备仍可能在 EXECUTING claim 之前调用 Provider。
若请求结果丢失，后端仍是 READY 而客户端 receipt 无匹配完成证据，也可能长期等待；不能清空 storage 后重送。
数据库拒绝全部终态时保留最后完整检查点，不自动重跑 Git。HTTP 200 可携带明确失败／阻塞状态，不能当作成功。

发布说明必须明确：刷新／重启不保证解除阻塞；不提供“重启即恢复”的承诺。保留原副本、数据库及证据，不以手工
SQL、reset/clean、删除 receipt、改 root 或项目导航 restore 代替 Recovery。导航软删除／恢复不改变业务状态，
受未决状态守卫限制，也不能拿它隐藏或取消任务。授权后的保守安全拒绝同样可能留下未决检查点。

## 4. 测试、修复与独立审查证据

### 历史实现与修复索引

| 证据链 | 可定位记录／提交 | 当前结论来源 |
| --- | --- | --- |
| A／B／C | [A](m5-a-verification-failure-handling.md)、[B](m5-b-candidate-repair.md)、[C](m5-c-user-rollback.md)；3f21bca／ed659c1／2af0b55 | 最初实现自验；C 后续 FAIL 不隐去 |
| M5-001／002／003／004 | [001/002](m5-review-findings-fix.md)、[003/004](m5-review-findings-003-004-fix.md)；dbf8e2b／88b8c13 + a2bd5fe | 真实 Git 内容／filter 红绿复现、GET 采纳保护；报告记录旧 FAIL 及修复 |
| D／M5-005～009 | [D](m5-d-execution-failure-handling.md)、[005/006](m5-d-review-fix.md)、[007/008](m5-d-review-findings-007-008-fix.md)、[009](m5-d-review-finding-009-fix.md)；f4e4c79／bbd85e2／5f778af／4fc6247 | 属性来源、大小写、宏／glob、BOM、目标阶段 UNKNOWN 故障覆盖 |
| E／M5-010 | [E](m5-e-failed-retry.md)、[010](m5-e-review-fix.md)；7122b5b／97f587a | Retry、SQLite／重启回读及双 Session receipt 红绿回归 |
| F1／F1b | [F1](m5-f1-ownership-spike.md)、[F1b](m5-f1b-isolation-spike.md)；92afdf8 + 56e9424／7e5d596 | 真实 OS 实验，技术 NO-GO；不是业务 Recovery 实现 |

独立审查 PASS 依据本轮用户明确提供的最终结论；仓库内上述实现／修复报告不是独立审查者原文。
用户此前提供的 M5-009 独立最终 Gate 还列出 152 项定向回归及附加矩阵通过，但不是全量重跑。
最终集成审查应归档审查范围／SHA 与原始结论；不将历史待审字样或失败阶段擦掉，也不把本轮只读核查冒充新独立审查。

### 最新可复用的生产回归证据

- 后端：M5-E `7122b5b` 报告的完整 `mvnw.cmd -o -Djava.io.tmpdir=<当轮专有根> verify`，
  2026-10-15 20:33:36～22:10:20，BUILD SUCCESS、报告记录 exit=0，包含 JAR 与 Boot repackage。
- 前端：M5-010 `97f587a` 的 `pnpm --dir frontend test`、`typecheck`、`build`；2026-10-16
  00:31:26 开始，200 tests／12 files 全通过，类型／构建 exit=0。无 lint script，不虚构 lint。
- 本轮 `git diff 7122b5b 7e5d596 -- backend` 为空；`git diff 97f587a 7e5d596 -- frontend` 为空。
  F1/F1b 没有修改生产代码，故可引用上述记录，不能累加 A～E 各轮／聚焦数量。

本轮实际只读重核 E 盘保留日志 `m5e-full-verify-second.log`：Reactor 和 repackage SUCCESS，
结束时间 2026-10-15T22:10:19+08:00；并重新解析 145 份**历史** XML：

| 模块 | XML | tests | failures / errors / skipped | 历史 XML mtime（+08） |
| --- | ---: | ---: | --- | --- |
| Domain | 12 | 240 | 0 / 0 / 0 | 2026-10-15 20:33:39～20:33:40 |
| Application | 65 | 780 | 0 / 0 / 0 | 20:33:41～20:33:43 |
| Infrastructure | 39 | 450 | 0 / 0 / 0 | 20:33:49～20:50:28 |
| App | 29 | 344 | 0 / 0 / 0 | 20:50:40～22:10:18 |
| 合计 | 145 | 1,814 | 0 / 0 / 0 | 属于上述 M5-E 历史执行，未生成新 XML |

前端原日志 `m5e-010-front-final.log`、`m5e-010-type-final.log`、`m5e-010-build-final.log` 也已只读核对：
200／12、vue-tsc、56 modules 构建成功。这些日志现位于 E 盘 Worktree 根（ignored），内容中的旧 C 盘路径
仅是历史执行位置，没有在旧路径执行命令；原日志和构建产物不加入本次提交。

M5-E 首轮完整 verify 与 timeout 复核的 Windows @TempDir cleanup error 仍记在原报告，随后独立复核与
第二轮完整 verify 成功；没有将失败运行算入通过数量。M0～M4 与 M5-A～D 的确定性回归由该后端全量覆盖，
前端由最新 200 项覆盖；不等于最新付费 Provider E2E、真实浏览器、用户数据升级或 OS 崩溃证明。

## 5. SQLite V2～V21 与数据／UI 发布差距

### 已有事实

V1 为空业务 baseline，V2～V17 是既有 Profile、Asset、Repository、Direction、Plan、WorkingCopy、结果、
Criteria、授权 revision、Flow 和导航迁移。当前相对本地 main `474375d8` **只新增 V18～V21**，旧迁移未修改。

| M5 迁移 | 增量及旧值 | 已有隔离验证 |
| --- | --- | --- |
| V18 | repair_authorizations，NOT NULL，默认 [] | RepairAuthorizationMigrationTest：从 V17，旧状态／结果字节不改写 |
| V19 | rollback_authorization、rollback_result，nullable | RollbackMigrationTest：从 V18，旧结果／Repair 字节保留 |
| V20 | execution_failure_result、automatic_rollback_result，nullable | ExecutionFailureMigrationTest：从 V19，八类旧状态／结果槽保留 |
| V21 | retry_authorizations，NOT NULL，默认 [] | RetryAuthorizationMigrationTest：从 V20，无虚构授权／旧 JSON 改写 |

这些测试 target 旧 schema 后迁移到 classpath 最新版；哨兵原文只证明迁移不重写，不代表八种哨兵记录都是合法 Domain 数据。
正常 Aggregate/API 与新 Spring context 回读另由 EvolutionStepExecutionApiIntegrationTest 的 Repair、Rollback、
m5d*、m5e* 故障测试覆盖。它们不等于 OS 进程崩溃／断电恢复。

HistoricalStepMigrationIntegrationTest 另验证 V10 的 SUCCEEDED／VERIFYING／EXECUTING 可经正常 Repository 回读；
StepResultJsonCompatibilityTest 验证旧成功 JSON 缺 outcome 仍可读、旧负值映射 INCONCLUSIVE，不制造明确失败。
V11 将历史自然语言 Criteria 保留为 UNSUPPORTED；历史可读不等于获得新执行资格，不能靠 BUILD/TEST 通过抹去业务标准。

### 发布前仍需完成的验证（本轮未执行）

1. 在**另行授权的隔离副本／合成数据**上做支持范围的升级矩阵：空库→V21、M5 前本地 main 基线 V17→V21、
   代表性 V2～V16→V21，以及 V18～V20 的部分版本→V21。核对 Flyway history／checksum／成功版本、完整性、
   表关系与旧字段，重复启动不重复迁移；还须核对真正发行版本的 schema，不能把 main 当成 Release Tag。
   不宣称已穷尽每一历史版本／记录组合。
2. 使用实际合法的 Profile→Asset→RepositoryProfile→Direction→Flow→Plan→Step／WorkingCopy 记录，
   覆盖成功、失败、多次 Repair／Retry、UNSUPPORTED、各未决状态。升级前后比较 GET、授权事实、失败历史、
   baseline/current/lastVerified 与 guard；不能只做 SQL 哨兵或 schema 列存在检查。
3. 升级必须识别明确数据文件／配置根，先有一致备份及完整性验证；不得复制活跃 WAL 库冒充一致备份。
   新程序直接启动真实用户库不属于本轮授权。旧版本不理解新增历史／父身份，不能只回退程序就保证兼容；
   如需降级，另行验证一致数据库与匹配软件状态的恢复方案，不借降级执行 Recovery 或删旧副本。
4. 用打包产物、明确隔离数据库与 Workspace root 进行启动／重启／GET smoke；验证 V21 自动迁移、数据路径、
   老数据可读、阻塞状态保留、不启动 Git／Provider 重放。启动后端不是旧 writer 停止证明。
5. 真实受支持浏览器双标签检查 Web Locks、延迟 storage 事件／乱序 GET、网络中断、重新加载、四种动作交替、
   正常终态清理，以及真实未决授权保留。检查首轮无 body execute 和无父 ID 的 Repair／Rollback 兼容，
   Retry 轮次则必须携带当前父身份。旧前端页面不遵守新锁协议，应有前后端配套部署与旧页面退出／更新策略，
   不把升级数据格式兼容当成旧代码已修复，不通过删除 receipt 处理 UNKNOWN。
6. 手动 UI 走明确 FAIL→Repair 再 FAIL／PASS、User Rollback→FAILED→Retry READY→显式执行，以及已知执行失败的
   Automatic Rollback。逐项检查费用／范围确认、父身份、历史可见、200 失败响应、额外文件保留和长期阻塞提示。
   可以先用隔离测试 Provider；本轮及未来未经单独授权都不调用付费 Provider。

## 6. Correctness blocker 与推荐 Gate

**当前没有从本轮限定只读核查中确认新的 A～E correctness blocker。** 这不是新的全面独立正确性审查或无缺陷承诺。
M5-001～010 不再按旧 FAIL 视作未修复：当前含修复源码／回归，且用户确认最终独立 PASS。
文档中的旧进度矛盾已在本次同步，完整 Recovery／UNRECOVERABLE 写入仍明确未完成。

Recovery 的技术 NO-GO **阻塞完整 M5／完整恢复能力发布**；它不构成 A～E 对不存在接口的运行时依赖。
若发布承诺包括任意不可信构建、旧 writer 的 OS 级隔离或任何未决现场可恢复，当前不能通过。
当前 Git 支持范围也须公开：原生 LF/CRLF 与合法 glob 保留；BOM、不支持编码、外部属性来源、
自定义宏／filter 等保守拒绝。某些授权后拒绝会保留未决检查点，不能宣传成所有 Git 仓库均可继续处理。

| Gate | 推荐标准 | 当前状态 |
| --- | --- | --- |
| 最终集成审查 | 对固定 main／候选 SHA 检查 A～E 全链路及全部修复、本文延期边界、API／UI／V21 一致性；归档独立结论 | 建议进入；本轮未代替该审查 |
| 合并条件 | 集成审查无 Critical／Important、只包含获准范围、不引入自动 Recovery／重放；确认生产源码与可复用验证记录匹配 | 尚未执行合并；本地 main 仍 474375d8，共同祖先相同 |
| 回归证据适用性 | 可复用当前 1,814／200 的具体版本记录；若集成修改生产源码、依赖或环境，则重新执行适用回归／构建，不能照搬旧数字 | 本轮仅文档，不重复完整 verify |
| 数据升级与分发 | 完成上节隔离升级、有效历史／GET、打包启动和降级边界演练；不默认触碰真实用户数据 | 尚有发布前验证缺口 |
| 用户可见边界 | 真实浏览器验收，发布说明明确长期阻塞、无 Recovery、支持标准／Git 属性范围和非 OS 沙箱；旧标签页升级策略明确 | 尚有发布前验证缺口 |
| 发布授权 | 选择版本、候选、发布说明与范围并单独授权；部分版本不能命名为完整 M5 验收完成 | 未授权／未发布；本地 Tag 列表为空，未查询或变更远端 Release |

没有证据支持现在直接发布。建议的下一动作仅是对这次阶段性收尾及固定候选进行独立最终集成审查；
具体合并、生产升级、打包验收或发布执行须另行任务，不由本轮自动继续。现有版本号未调整。

## 7. Backlog 与本轮实际验证边界

- **已决定延期：** Recovery 写能力、旧写者停止／隔离证明、历史无证明检查点处置、Recovery 审计与
  UNRECOVERABLE 转移。未来先独立安全架构设计及实证，不继续当前失败候选、不自动进入 F2/F3。
- **发布前任务：** 隔离升级／分发 smoke、真实浏览器与配套部署、长期阻塞使用说明、明确备份／降级流程。
- **保留能力边界：** 不支持的 Criteria／属性转换失败关闭；Provider exactly-once 未实现；Git 和 SQLite
  不是一个事务；历史值对象会增长，未提供完整 Attempt／事件流、压缩或通用后台系统。
- **未验证：** 真实用户库升级、付费 Provider E2E、OS／断电／磁盘损坏、任意外部写者和跨平台隔离。
  Git/API 测试效率仍留到完整 M5 后另行评估，本轮不评估或优化性能。

本轮实际执行：Git 根／分支／HEAD／状态及版本差异只读检查；文档、SQL、UseCase／Port／Controller／UI／测试定义核查；
保留日志尾部和前端记录读取；Python 标准库解析历史 Surefire XML；文档链接／差异／主目录保护检查。
没有执行 Maven、前端测试／构建、Git Fixture、数据库迁移、Provider 或进程隔离实验，没有访问真实业务数据库／副本。
只提交本轮文档；原日志／XML／原型／历史报告不改，不 Push、不合并、不修改 main 或 Tag。
文档检查实际通过：102 个本地链接目标存在；Domain §8.15 在新增状态说明后的原目标正文逐字保留；
backend／frontend／tools 与本轮基线无差异；git diff --check 通过。完整回归未重跑。

本轮无阻塞文档收尾的新 Decision Required：Recovery 延期已由用户裁定，不重复请求批准。
未来隔离技术选择和发布版本／日期／范围需单独决策，但不在本轮擅自选择。
最终 Commit SHA 见 Git 与交付消息（避免报告引用其自身提交形成自引用）。完成后停止等待独立审查。
