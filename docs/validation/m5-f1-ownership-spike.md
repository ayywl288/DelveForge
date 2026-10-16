# M5-F1 — Execution Ownership & Safe Takeover Spike

- 日期：2026-10-16（Asia/Shanghai）。
- Worktree：`C:\Users\28226\.codex\worktrees\m5-lab\DelveForge`。
- 分支／基线：`feature/m5-lab`／`97f587aeacc7c13fc0ee63ff0a6029b8e1acba1f`；开始时干净。
- **最终 Gate：NO-GO。不能将本原型用于授予 Recovery 写入资格。**
- 最终真实进程实验 **15/15 场景断言成立**，包括成功复现不安全机制的负向场景。
  场景通过不代表 Recovery 安全通过；原型原生退出码为 **2（已复现 NO-GO）**。
- 本轮只新增原型、原始实验结果和报告。生产 Domain、Application、API、Git adapter、SQLite
  schema、前端、领域状态机及现有历史均未修改；没有 Recovery 写入、付费 Provider 或真实仓库构建。

## 1. 已确定的契约与本轮边界

已阅读当前 AGENTS、PRODUCT、DOMAIN_MODEL §6.4/6.5、INV-V08～11、§8.15、ARCHITECTURE 的
M4/M5 检查点章节、ROADMAP M5、ADR-0001 的 M4/M5 refinement，以及 M5-D/E 报告和当前代码。

现有文档已经规定：Recovery 精确恢复当前 Step 的原始 baseline 后，WorkingCopy 回到 EVOLVING、
Step 回到 FAILED；不能改用其他可信 revision；后续 Retry 必须另行授权。历史不得覆盖。
本次用户进一步确定第一版只考虑双 RECOVERY_REQUIRED，须先证明旧写入者停止，独立授权后才
能考虑恢复；遗留 EXECUTING／VERIFYING／ROLLING_BACK UNKNOWN 不接管、不重放、不改判。

以下所有“建议”均未成为现有生产契约。没有因 Spike 结果调整上述停止判据。

## 2. 当前执行入口与进程边界：源码检查事实

源码路径前缀：`backend/<module>/src/main/java/com/ayywl/delveforge/`。

| 入口／关键类 | 当前实际行为 | Ownership 集成必须覆盖的边界 |
| --- | --- | --- |
| application/.../preparation/PrepareEvolutionStepUseCase | 首次／Retry 在 EXECUTING CAS 之前理解仓库、请求 Provider；Repair 在授权／EXECUTING 后准备。提案只在调用内 | HTTP Provider 本身不修改副本，但等待中的调用可能继续进入执行；迟到响应不是停止证明。准备中的 Git 读取也使用另一启动路径 |
| application/.../execution/ExecuteEvolutionStepUseCase | 保存 EXECUTING 后 applyCandidate；保存 Candidate／VERIFYING 后验证；确认失败先保存诊断／ROLLING_BACK 再系统回滚 | 作用域必须覆盖直接文件写入、所有原生命令、验证和终态 CAS；不能在状态改为 RECOVERY_REQUIRED 后先释放独占而子进程尚未停止 |
| application/.../execution/RollbackEvolutionStepUseCase | User Rollback 先保存独立授权／ROLLING_BACK；真实恢复和复查后保存终态 | User／Automatic Rollback 共用恢复机制；不能只保护用户入口 |
| infrastructure/workspace/GitWorkspaceAdapter:608、726 | 补丁／私有 index 在 `.git` 中落盘；Git apply、Candidate 发布；恢复只覆盖可证明归属的修改，整树核对原 baseline | Java 直接 Files 写入不属于子进程 Job，必须由父操作的独占范围保护；属性、精确内容和额外文件守卫仍独立保留 |
| infrastructure/workspace/GitWorkspaceVerificationAdapter:91 | 在副本 `.git` 内建立验证目录，clone／checkout、Maven Wrapper、读报告，finally 清理 | Wrapper、Maven 插件和测试可创建子孙进程；临时目录属于调用技术产物，不能因重启无条件删除 |
| infrastructure/workspace/WorkspaceCommandRunner:38、105 | ProcessBuilder 创建进程；超时／中断／输出异常时枚举当时 descendants、强制终止并限时等待捕获的句柄 | 这是调用内有限清理，不是跨进程进程树隔离；句柄及停止事实不持久化，枚举不能封闭后续创建／代创建 |
| infrastructure/workspace/GitWorkspaceAdapter:1491 | 第二套 ProcessBuilder：读取及 provision 的 clone／checkout 也使用它；通常无限等待；异常只 destroy 根进程，不等待完整后代退出 | 不能只替换 WorkspaceCommandRunner 就声称所有启动已受管；provision/discard 的目录创建／删除另有技术所有权，不等同于 Execution Ownership |
| infrastructure/persistence/evolutionplan/SqliteStepExecutionCommitAdapter:35、EvolutionStepMapper | Domain 重放，Step／WorkingCopy 事务 CAS；比较身份、状态、结果、历史、revision、绑定和资格 | 可阻止过期数据库提交，不能阻止旧命令继续写文件；当前无跨进程 owner／epoch／Job／停止证明字段，也不支持 Recovery 转移 |

当前 Java 生产源码仅发现上述两处 ProcessBuilder 创建点。原始资产分析保持只读；新副本准备、
补丁执行、回滚、验证临时产物及清理均需分别识别，不能把读能力扩大为任意写能力。
浏览器 Web Lock／requestId 是 receipt 保护，不能作为后端或 OS 子进程所有权。

## 3. 被实际实验的候选机制

原型：[ownership_spike.py](../../tools/spikes/m5-f1/ownership_spike.py)，仅 Python 标准库 ctypes。
Windows 11 build 22631、Python 3.11.3 x64；没有新增生产依赖。

1. 每个新 Fixture 一个 `Local\\...gate` 命名 Mutex；每轮唯一 operation UUID 和命名 Job。
   Mutex 跨独立 Python“后端实例”持有；持锁核对当前 operation，直至实验观察结束。
2. 启动前创建 Job，配置 KILL_ON_JOB_CLOSE，不允许 BREAKAWAY／SILENT_BREAKAWAY。
3. 使用 STARTUPINFOEX／PROC_THREAD_ATTRIBUTE_JOB_LIST 在 CreateProcessW 创建时加入 Job；
   不采用“先运行，再 AssignProcessToJobObject”。Job 句柄不继承给 child。
4. 提供真实创建前崩溃、原子入 Job 但主线程尚 suspended 时崩溃的故障点；未知／不存在的
   ownership、Job 或不匹配 operation 均阻塞，不回退成普通 ProcessBuilder。
5. 通过真实 TerminateProcess 强制终止自有 Owner，没有运行 Owner 的 Python finally。
   另用显式保留的测试 Job 句柄制造“Owner 死亡但旧写者继续运行”，不把 keeper 隐藏成生产保证。
6. `job_empty_under_gate` 只表示 Job 的局部计数观察，**不是 StopProof，也不是 Recovery 授权**。
   日志／JSON 是隔离原型记录，不是已经实现的 SQLite checkpoint 协议。

候选 API 选择参考微软官方 [Job Objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects)、[JOB_LIST 属性](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-updateprocthreadattribute)及[原子入 Job 的说明](https://devblogs.microsoft.com/oldnewthing/20230209-00/?p=107812)。文档不是本轮实验通过的替代证据。

每次 run 创建全新的 `delveforge-m5f1-*` 临时根，不接受已有 Workspace。内部进程角色要求该
新目录的 Fixture 标记；writer 最长 45 秒，Owner 最长 35 秒，所有等待有界。清理使用本次
Popen 对象、已打开的自有 writer／Job 句柄及本 Fixture 的停止标记，不通过进程名批量杀进程。
没有递归删除用户目录；实验目录保留供检查。

## 4. 最终真实进程实验

最终原始结果：[final-results.json](../../tools/spikes/m5-f1/evidence/final-results.json)。
所有独占／竞争者都是独立 OS 进程，没有以 JVM／Python 线程模拟崩溃。

| 场景 | 实际结果 |
| --- | --- |
| 00：正常自然结束 | finite writer 在停止请求前自然退出，已知进程句柄 signal，Job active=0；Owner 正常结束 |
| 01：根进程先正常退出、子孙仍长时间运行 | Job total=6、active=4；第二实例被 gate 拒绝；受控停止后已知 writer 句柄退出 |
| 02：只有 Mutex，Owner 异常终止 | 第二实例取得 WAIT_ABANDONED=128，但旧 writer 文件在约 200ms 内从 19 增到 39 字节；普通独占释放不足以证明停止 |
| 03：Owner 异常终止、关闭最后 Job 句柄 | 本机普通 child／grandchild 的真实句柄退出；新实例因找不到可核对的 Job 保守阻塞，不把 Job 缺失当证明 |
| 04：额外 Job 句柄存在、Owner 异常终止 | 旧 writer 从 12 增到 31；新实例看到 active=4，拒绝局部停止结论；显式终止自有 Job 并核对后得到局部空 Job |
| 05：持久化原型记录／建 Job 后、创建进程前崩溃 | 没有 writer 副作用；新实例缺少内核证明继续阻塞 |
| 06：CreateProcess 原子入 Job、suspended 时父进程崩溃 | 自有 suspended 进程句柄退出；未执行 writer；无未入 Job 后再 Assign 的启动窗口 |
| 07：显式 CREATE_BREAKAWAY_FROM_JOB | CreateProcess 被拒绝，错误码 5；没有 escaped writer 文件 |
| 08：无效 JOB_LIST 句柄 | CreateProcess 被拒绝，错误码 6；未执行 writer，不启用不受管 fallback |
| 09：历史缺 ownership／不支持协议 | 分别 BLOCKED_LEGACY／BLOCKED_UNPROVEN_PROTOCOL |
| 10：停止确认后释放 gate，新实例创建新 operation／writer | 活跃新 Owner 时旧请求被 gate 拒绝；新 Owner 崩溃后旧 operation 请求被 BLOCKED_STALE_OPERATION 拒绝，新 writer 仍从 18 增到 37 |
| 11：04 的三轮重复 | 连同 04 共四组，每组停止观察期间四个独立竞争进程，共 16 次均被 gate 拒绝；没有在 check/use 之间释放 gate |
| 12：WMI 服务代创建负向测试 | 创建成功，ReturnValue=0；实际 writer 不属于受管 Job。Owner 被终止、Job active=0 后，writer 仍从 10 增到 29，原型取得的局部 Job 观察不是完整停止证明 |

### 两个决定性缺口

**WMI 反例可复现，不是“没有发现子进程”。** 受管根进程运行固定的 PowerShell CIM 命令，
请求 Win32_Process.Create 创建本脚本的有界 writer；使用该 writer 的真实进程句柄调用
IsProcessInJob，结果为 false。TerminateJobObject 后 QueryInformationJobObject 为 active=0，
该 writer 句柄仍 WAIT_TIMEOUT，文件继续增长。没有授予或执行任何生产 Recovery 写入。

**Job 计数归零不等于已知进程句柄完成退出。** 最终 run 的 `11-race-repeat-0` 在 active=0
时两条已知 writer 句柄立即等待结果均为 258（WAIT_TIMEOUT），后续有界 join 才 signal。
没有证明那一小段间隙仍能写文件，不能据此声称发生了写入；但它直接否定“计数=0 等价于
已完成句柄等待”。原型在 gate 仍被持有时增加了已知句柄 join，仍未声称覆盖所有潜在写者。

## 5. 早期失败与判据调整记录

- 首轮隔离执行：`%TEMP%\delveforge-m5f1-c6ln61nr`，13/14；CIM 本机访问限制导致 broker
  场景超时，没有将该次未到目标阶段记录成逃逸复现。
- 在获准的本任务原生进程实验环境中重跑：`%TEMP%\delveforge-m5f1-geb_fyet`，13/14；
  broker 逃逸已复现；一轮“Job 归零后已知进程应立即 signal”断言失败。保留完整原始结果：
  [exploratory-job-zero-gap.json](../../tools/spikes/m5-f1/evidence/exploratory-job-zero-gap.json)。
- 后续区分局部 Job 观察与真实进程退出，增加已知 writer 句柄的有界 join，保留即时等待值，
  增加真实的新 operation 替换交错，并加入自然退出场景。没有将计数归零放宽成停止证明。
- 交付版本 15/15；早期结果不累加到最终测试数量。四次可到目标阶段的 broker 实验均复现
  Job 之外持续写入，其中最后一次来自交付 Worktree 路径。

## 6. 可以确认与不能确认

本机已观察：命名 gate 可串行遵守协议的同会话后端；原子 JOB_LIST、禁用 breakaway 和
KILL_ON_JOB_CLOSE 可约束所测试的普通 CreateProcess 子孙路径；持锁重查 operation 可拒绝
已被替换的旧请求。它们可成为未来实现的组成部分。

**不能保证完整停止所有潜在写者。** 仓库 Wrapper／插件／测试不是固定的可信小程序，可能
使用服务代创建等机制；当前应用也没有 OS 文件系统沙箱。Job 只覆盖它实际包含的进程。
不能通过要求用户“相信构建不会逃逸”把 NO-GO 改成 PASS。

此外未验证跨 Windows 登录会话／服务会话（原型用 Local 名字）、不同 SID／提升权限、
Job ACL／句柄复制攻击、所有服务／COM／任务计划等其他代创建途径、Java/JNI 集成、断电／
OS 崩溃后的持久性、任意外部编辑器写入、NTFS alias／reparse 路径身份和全部 Git／Maven
实现组合。没有通过本轮实验建立任意命令安全沙箱或 Provider exactly-once 保证。

## 7. 最小集成建议：尚未实施、不能据此开放写入

| 位置 | 建议职责／约束 |
| --- | --- |
| Application Port | 窄 `WorkspaceOperationOwnershipPort`，绑定已有 Plan／Step／WorkingCopy／首次 baseline／父 Retry／失败检查点与唯一 operation。返回作用域 guard 和分类型观察；不以 boolean `isStopped` 混淆 UNKNOWN、LEGACY、ACTIVE、局部 Job 为空和合格停止证明 |
| Infrastructure | 一个固定意图原生启动／监督实现，统一两套 ProcessBuilder 和直接文件写入的作用域；启动时原子 containment，无不受管 fallback。现有 Git 精确内容、属性和额外文件守卫不变 |
| 操作／持久化 | 未来新增协议版本、owner instance、operation／epoch、WorkingCopy 真实绑定、内核边界身份、生命周期／停止证据和诊断；PID 只作诊断。CAS 比较旧 operation 和完整历史；不得把新记录补造给旧执行 |
| 锁生命周期 | 从操作可能获得写能力之前开始，贯穿启动、子进程管理、Java 直接写入、真实停止／精确检查与终态 CAS。接管者从停止核对到使用结论始终保持同一互斥，期间再校验 checkpoint／epoch；不要在 Provider 等待返回后使用过期 guard |
| Windows 线程边界 | Mutex 所有权属于 OS 线程；原型在同一主线程 acquire/release。Java virtual thread／异步回调不能未经验证直接照搬；需固定平台线程的窄监督器或另经验证的 gate，不能假定 AutoCloseable 跨线程有效 |
| 失败关闭 | 缺记录、协议不支持、Job 打不开、查询失败、停止未知、身份变化均阻塞。没有合格停止证明就不给 Recovery capability；数据库记录和心跳不能代替 OS 隔离 |

核心缺口是 **受管执行边界必须封闭可写者集合**：可另行评估独立受限账号／ACL、合适的沙箱／
受控执行环境或其他真正阻止代创建写者访问副本的能力，并以本轮 broker writer 反例作为负向
验收。只禁止命令行中出现 WMI／PowerShell 或增加服务名黑名单没有证明作用。
本轮没有创建账号、更改 ACL／服务设置、增加生产依赖或实现沙箱。

## 8. 旧历史与下一 Slice／Decision Required

新机制不能追溯证明 97f587ae 及此前操作受管。既有 RECOVERY_REQUIRED 没有 ownership 事实时
继续阻塞，不从 PID、重启、普通锁、HEAD 或人工勾选补造 StopProof；其他遗留 UNKNOWN 按既定
边界保留。不能为了迁移给旧行写入假的 owner、已停止状态或 Recovery 授权。

下一最小 Slice 建议先做 **F1b 封闭写入边界 Spike**，不是业务 F2/F3：

1. 裁定受支持部署／执行信任边界：是否允许为执行引入独立身份或沙箱；是否包括不同登录
   会话及已有外部写者。原约束保持，不以缩小验证动作范围或默认可信构建替代安全证明。
2. 选一种隔离方案，实测原子启动、broker 代创建无法继续写、父／监督器异常终止、真实进程
   退出／文件写入停息，以及持锁接管竞态。未通过就继续 NO-GO。
3. 通过并经设计批准后，再写 ADR／Architecture 并集成窄 Port、持久化操作事实和所有入口。
   本轮没有将候选技术提升为长期架构决定。

真正待裁定：生产隔离技术与部署边界、旧版无证明记录的后续处置流程，以及新机制无法取得
停止证明时的人工诊断能力。已经确定的独立 Recovery 授权、原始 baseline、遗留 UNKNOWN
阻塞与不自动重放不再重新请求裁定。本轮停止，不自动实施 F1b、F2 或 F3。

## 9. 实际验证命令与证据

在指定 Worktree 中：

```powershell
python tools/spikes/m5-f1/ownership_spike.py run
$spikeNativeExit = $LASTEXITCODE
# 2 = 所有实验断言成立，且负向反例确认 NO-GO；1 = 实验失败；0 不代表本轮结果。
if ($spikeNativeExit -ne 2) { throw 'Unexpected spike result' }
```

最终实验耗时约 9.64 秒，15 个场景；原生 Python exit=2，检查预期退出码的 PowerShell wrapper
exit=0。不可将 wrapper exit=0 报为机制 GO。默认受限环境拒绝 CIM 时应报告环境限制，不跳过
或 mock broker 反例；本轮在允许原生进程实验的环境中实际到达并复现该阶段。

- 最终临时根：`%TEMP%\delveforge-m5f1-xkyaigrx`。
- 最终完整控制台：`%TEMP%\m5f1-final-63136846cfb24cea8ff579d79e676200.log`。
- 最终原型 SHA-256：`502c320f1963fc774f55cc0bc213c3728f81c8f02cdadf30ec7fa5261c329d74`。
- `final-results.json` 保存精确场景值／耗时、源码 hash 和平台，不依赖报告摘要重建结果。
- 未运行 Maven verify 或 frontend test/typecheck/build：本轮没有生产后端或前端变更；没有把
  历史 M0～M5-E PASS 当作新机制验证，也没有声称本原型已接入 Java／SQLite／REST。
- 最终提交只含本报告、原型及两份原始结果；Commit SHA 见 Git 提交及最终交付消息，避免
  把提交自身 SHA 写入被该提交包含的文件形成自引用。

- 提交前实际检查：原型 Python compile 语法检查、源码 SHA 与最终证据一致性、15/15 场景断言汇总、`git diff --cached --check` 均通过。只读 CIM 核对匹配本原型的遗留测试进程为 0。
