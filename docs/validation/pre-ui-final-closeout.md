# Pre-UI Final Closeout

记录日期：2026-10-11。M0–M4 业务开发结束，下一项为 Task G UI / UX Polish；
M5 Repair / Retry / Recovery 继续 DEFERRED。本次只整合已经完成的提交、校正文档和准备交接。
没有调用 Provider、执行 Evolution Step、启动用户数据库或修改已有 WorkingCopy。

## 1. 代码整合

整合前 main 为 `864361bdd00c8ad90390d938b8d081c07bd31525`，也是最新修复分支的
merge-base。`git merge --ff-only codex/final-planning-rejection-fix` 成功，main 快进到
`a2b722f2aa86da605a75485f00a8a2c64a10e301`；没有冲突、额外 merge commit、历史改写或 push。
随后提交的本记录与交接文档只改变文档，不是新的业务演化或测试执行。

已检查这八个提交的依赖顺序及 main 到修复分支的实际文件差异：

| 提交 | 纳入的能力 | 原验证记录 |
| --- | --- | --- |
| `bab279a` | Task D：原资产显式授权更新、authorizationRevision、重复登记保护、Evidence 展示 | [Task D](task-d-planning-authorization-ux.md) |
| `36beb37` | Task E：许可证声明、精确 Asset 授权导航、执行等待反馈、自定义 Root 与 existingRoots | [Task E](task-e-final-e2e-ux-polish.md) |
| `8b182ac` | Task F：Application 项目上下文、资源目录与旧 Plan 只读投影 | [Task F](task-f-project-workspace-history-recovery.md) |
| `a6b1355` | Task F：Vue 工作台、新流程与安全历史恢复 | 同上 |
| `d39adb5` | 持久化 flowId，项目间 Direction / Plan 生命周期隔离 | [F-Fix](task-f-fix-project-scope-replanning.md) |
| `6549161` | 显式后续规划轮次、旧计划历史导航；不覆盖原 planId | 同上 |
| `ee15646` | 项目导航软删除与恢复，保留业务对象和磁盘代码 | [删除与恢复](task-f-fix-project-deletion-recovery.md) |
| `a2b722f` | Planning 拒绝分类、版本预检、前端请求上下文隔离与结果未知处理 | [Final Fix](final-planning-rejection-fix.md) |

差异限于上述实现、测试、配置与文档；没有合入演示 WorkingCopy 的 Candidate B 或临时日志。
`frontend/design/task-g/` 的六张 PNG 仍是未跟踪的用户素材；前后 SHA-256 核对一致，未加入提交。
旧开发分支、历史报告与外部证据保留。

## 2. 现在能够完成的业务流程

- M0：Java 21 / Maven 多模块后端、SQLite / Flyway、REST 和 AI / Workspace Ports。
- M1：连续需求探索、六维画像与 Evidence、同版本审阅就绪记录、显式审阅和确认；
  授权本地 Git 仓库的只读、固定 revision 分析。
- M2：真实候选方向与依据，用户显式 Select / Reject；每个已知 flowId 最多一个 SELECTED。
- M3：严格接受 Planning Proposal，展示 Current / Target / Reuse / Steps / Criteria，
  Prepare 隔离副本并激活 Plan，单独确认 Step。授权、归属及版本不合规在相应边界拒绝。
- M4：Step 专属理解 → CodingProposal 校验 → 仅副本内修改 → Candidate → Maven / diff 验证；
  验证通过后 Step SUCCEEDED、lastVerifiedRevision 才推进。不会自动执行下一步。

工作台按明确 ID 恢复持久化对象；共享画像、资产与分析不代表共享方向作用域。
同流程换方向仍废弃旧方向关联的 PROPOSED / ACTIVE Plan；新规划使用关联的后续上下文，
不覆盖旧副本或把旧 B 猜作新分析基线。软删除只隐藏导航，恢复不复活 SUPERSEDED 终态。
相关规则已经在 [DOMAIN_MODEL](../DOMAIN_MODEL.md)、[ARCHITECTURE](../ARCHITECTURE.md)
及 ADR-0007/0008/0009 中表达；本次不改变这些规则。

## 3. 两次真实 Verified Evolution：证据与边界

| 案例 | 实际 A → B | 已保存的实际验证 |
| --- | --- | --- |
| DelveForge 自举，2026-10-10 | `47ca4487210505529d6f4b4082052530c33c33d3` → `6dfdc121fe2f61f1542d060fe56718ea6c3bb57e` | 两个文件：Python 文件名角色提示及三个新增测试；BUILD / 完整 TEST 1346 项 / DIFF_CHECK PASS；Step SUCCEEDED，lastVerified=B |
| GS REST 外部仓库，用户真实验收 | `e38e4a1cc1f54c036d40dd111ab50d103b154c06` → `c822cc384234342a95f5239f2b4ce060002f0d15` | 用户报告新增八个 Java 文件；保存的结果证明 BUILD 与两个 FOCUSED_TEST 各 5 项 PASS，Step 1 SUCCEEDED，其余三步当时 PENDING_CONFIRMATION |

第一例的完整 Plan、代码 diff、失败链和 API / XML 引用在
[M4 真实案例](m4-real-provider-verified-evolution-smoke.md)；原外部记录位于
`E:\df-m4-smoke\20261010-m4-provider\report.md`。成功执行器为
`9efee44518f9831cf8a06651db567e2bdf15c577`，使用非默认 SSE / timeout / token / response 预算。
维护分支的 1376 项 verify 与 Candidate 的 1346 项生产 TEST 是两个源码状态，不能合并计数。
维护修复早已通过 `bf949db` 合入；Candidate 的业务补丁没有作为开发分支合入 main。

第二例的原资产为 `E:\develop\local_repository\gs-rest-service`。基线 A 未被 Evolution 修改。
[Task F §4](task-f-project-workspace-history-recovery.md#4-实际-gs-rest-历史与离线联通证据)
记录了原库只读核对和一致副本 API 回读：1/4 成功、B、真实检查结果及重启后恢复。
本机原证据为 `logs/task-f-existing-history-readonly.log`、
`logs/task-f-offline-history-api.json`、`logs/task-f-offline-restart-proxy.json`。
这些后续回读不是第二次执行或重新验收；该例未留存可绑定执行器制品 SHA 的证据，
不能声称它由最新 `a2b722f` 执行。后来 SUPERSEDED 的历史 Plan 仍按真实终态展示，不恢复 ACTIVE。

两例都只证明当时指定 Step 的受控成功，不证明整个四步计划已完成、任意仓库可靠性、
默认配置成功率或 OS 安全隔离。原始未保存的聊天、Prompt / response 和 CodingProposal
不能恢复；事后示例不冒充模型原文。

## 4. 最新确定性验证

本次快进没有冲突，后续只有文档修改，因此复用最新修复的实际验证，不重复重型构建。
本次重新核对日志、Surefire XML 汇总及已打包 JAR 时间；没有将“重新检查”写成“重新运行”。

| 验证 | 结果 | 本机证据（忽略文件，保留原位置） |
| --- | --- | --- |
| `mvnw.cmd verify`，2026-10-11 20:47:24 | 1474 项，0 failure / error / skipped；9 分 9 秒，全部模块与打包 SUCCESS | `logs/final-planning-verify.log`；各模块 `target/surefire-reports/TEST-*.xml` |
| 模块计数 | Domain 220 / Application 761 / Infrastructure 275 / App 218 | 同上，合计 1474 |
| 规划聚焦回归 | 67 项 PASS；后续 scope 回归 11 项 PASS | `logs/final-planning-focused-final.log` / `logs/final-planning-scope-final.log` |
| 前端 Vitest | 85 项 / 7 文件 PASS | `logs/final-planning-frontend-final.log` |
| `pnpm typecheck` / `pnpm build` | PASS，Vite 48 modules | `logs/final-planning-typecheck.log` / `logs/final-planning-build.log` |

测试使用确定性 Provider 替身、测试数据库及受控 Git/Maven 夹具。
**最新 correctness 修复之后没有重新执行完整付费 M1–M4 E2E**；上述结果不能替代该手动验收。

本次文档检查：新增说明中的 PowerShell 六段均通过语法解析；归档代码在独立临时 SQLite
WAL 夹具上验证一致备份、integrity_check、拒绝覆盖及源内容不变。112 个本地 Markdown
文件/目录链接目标存在。没有执行这些示例中的真实库操作或后端启动。

## 5. 交付限制与 Task G 入口

- 当前自动验证面向授权、受信任、小型 Java/Maven Git 仓库；根目录 Wrapper、JDK 21、
  已缓存的离线依赖、可验证的测试选择器和支持的 Criteria 是实际使用条件。
- `m4-local` 是明确可启用的历史成功预算，所有 AI 阶段共用；不是最优默认值。
  默认非流式 / 较小预算没有同等真实成功证明，长请求与 Provider 成本仍需用户考虑。
- AI Planning 可能返回违反 CurrentState、TargetState、Evidence 等规则的提案并被拒绝；
  不放宽严格校验，不静默付费重试。部分旧失败仍 UNKNOWN，见原报告。
- M5 未实现。项目恢复、目录允许列表和删除恢复都不是 Candidate 修复、回滚或任务取消。
  READY 可能仍处于提案准备，结果未知时先查询，不能靠重发 execute 恢复。
- Windows Maven 插件和测试在宿主执行，WorkingCopy 不是 OS 沙箱。
- 数据库迁移仍按 Flyway 正常进行；本次未启动用户库。V16 多 SELECTED 作用域不能直接
  用旧单流程程序读取，旧版本回退须按当时一致备份及匹配代码单独处理。

数据库归档和新库切换由用户决定，步骤见 [本地数据环境切换](../LOCAL_DATA_ENVIRONMENTS.md)。
UI 实施范围、文件结构和不可改变的操作语义见 [Task G 技术交接](../UI_TASK_G_HANDOFF.md)。
后端业务开发到此停止；Task G 尚未实施。
