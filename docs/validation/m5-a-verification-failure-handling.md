# M5-A — Confirmed Verification Failure Handling

日期：2026-10-13（Asia/Shanghai）。范围：独立实验分支的 Verification FAIL Vertical Slice。

**M5-A：PASS，可审查的本地实现。完整 M5 尚未完成，下一 Slice 不自动开始。**

## 1. 环境与契约差距

- Worktree：`C:\Users\28226\.codex\worktrees\m5-lab\DelveForge`。
- 分支：`feature/m5-lab`。
- 基线：最新远端 main `474375d8f9f2dcd822037fa85c5e197198c4e5cf`，经 HTTPS fetch 核对。
- main 工作目录的已跟踪文件没有变化；既有 `frontend/design/` 未跟踪目录原样保留。
- 未 Push / 合并、未修改 Release Tag，未读取或改写真实业务数据库、Workspace、历史 WorkingCopy 或外部演示证据。
- SQLite 测试数据位于本 Worktree 的 `target/`，Git / 进程测试使用任务拥有的临时目录；短临时根为
  `E:\dm5a-1013-67c29`。Provider 使用 fake、MockRestServiceServer 或本机测试服务，没有付费调用。

以当前 AGENTS、PRODUCT、ARCHITECTURE、DOMAIN_MODEL、ROADMAP、ADR-0001 的 M4 refinement、
M4 验证及 correctness 报告为依据。DOMAIN_MODEL §6.5 / §8.11 与 INV-V02–V04 已明确失败语义，
没有需要修改的授权或领域不变量。原实现已有枚举及阻塞守卫，缺少失败转移、结果恢复和事务提交分支。
原 VerificationFacts 的布尔值还混合了实际失败、未执行和证据不足，需要保守区分。

先修改真实 Diff FAIL 的 API 回归预期，旧代码实际返回 409，测试报
`Status expected:<200> but was:<409>`（1 项失败），复现缺口后才实现。

## 2. Changes / Reason

| 区域 | 关键类 / 文件 | 实现 |
|---|---|---|
| Domain | `EvolutionPlan`、`EvolutionStep`、`VerificationResult` | 同一 Candidate 的明确失败转移及完整历史恢复；失败不推进软件信任 |
| Application | `ExecuteEvolutionStepUseCase`、`VerificationFacts` | 保留三个检查点；最终检查点根据真实 PASS / FAIL 分支，未知或无效事实继续拒绝 |
| Persistence | `SqliteStepExecutionCommitAdapter` | 重放 Domain 失败操作，再沿用 Step / WorkingCopy 原子 CAS；无需 schema 迁移 |
| Workspace | `GitWorkspaceVerificationAdapter` | 返回 PASSED / FAILED / NOT_RUN / INCONCLUSIVE；读取有界新报告和失败测试身份，排除异常退出 |
| REST / UI | 既有 execute / GET DTO、`types.ts`、`EvolutionView.vue` | 保存 FAIL 后原端点返回 200 与失败 Plan；展示保留 Candidate、可信基线及未执行动作，保留阻塞入口 |
| 文档 | `ARCHITECTURE.md`、`DOMAIN_MODEL.md`、`ROADMAP.md` | 标明 M5-A 实验能力与剩余 M5 边界；不将完整 M5 标为 DONE |

明确 FAIL 的最终状态是：Plan ACTIVE、Step VERIFICATION_FAILED、WorkingCopy EVOLVING，
baseline=A、current=B、lastVerified=A。ExecutionResult 与失败 VerificationResult 同时可读；
B 的真实 Git Commit / Diff 保留，没有 reset、回滚或二次代码修改。
下一 Step 的确认、执行准备和执行，以及原 Step 的重复执行均被既有资格守卫拒绝。

只有具体动作的确认失败参与失败列表，提前停止后的其余动作记录为 NOT_RUN。
判定采用有限技术证据：Git Diff 检查负向退出及输出；Maven 正常结束的编译失败；
正常 Maven 测试失败及本动作新报告中的失败事实。FOCUSED_TEST 还必须匹配失败类 / 逻辑方法。
Maven BUILD FAILURE 字样或任意非零退出本身不足以证明验证 FAIL。
能力不足、缺报告、测试子进程崩溃、超时、读取失败、中断、错误 Candidate / 动作和持久化失败
仍保留最后完整检查点，不补造 VerificationResult、FAILED、回滚或恢复事实。

旧成功 JSON 没有 outcome 时恢复为 PASSED，历史标准不重新获得执行资格；旧负值恢复为
INCONCLUSIVE，不制造确认失败。实际 SQLite / API 回归证明历史 JSON 读取后原文没有被重写。

## 3. Verification

第一轮聚焦测试：62 项通过；收紧进程崩溃与负向证据后的补充聚焦测试：39 项通过。
二者来自不同测试选择，不能相加当作最终全量测试数。

聚焦命令均为 Maven Wrapper offline `test`，显式 `-Dtest` 与
`-Dsurefire.failIfNoSpecifiedTests=false`，并使用上述独立临时根。
第一轮选取 StepExecutionLifecycleTest、VerificationEvidenceTest、ExecuteEvolutionStepUseCaseTest、
VerificationOutcomeTest、StepResultJsonCompatibilityTest 与四个 API 方法（含参数化事务失败）。
补充选取 VerificationOutcomeTest、FocusedTestEvidenceTest、VerificationReportBudgetTest、
GitEvolutionExecutionIntegrationTest 的真实失败方法，以及真实 Maven FAIL、编译失败、
子进程崩溃和并发依据变化 API 方法。

| 命令 / 检查 | 实际结果 |
|---|---|
| `mvnw.cmd -o "-Djava.io.tmpdir=E:\dm5a-1013-67c29" verify` | PASS，1514 项、139 XML；0 failures / errors / skipped；四模块构建及打包成功 |
| `pnpm install --frozen-lockfile` | PASS；offline 缓存缺包后按原锁文件下载，锁文件无改动 |
| `pnpm test` | PASS，10 个测试文件、138 项，含明确失败响应和未执行动作显示 |
| `pnpm typecheck` | PASS |
| `pnpm build` | PASS |
| `git diff --check` | PASS |

回归覆盖包括 M4 Verified Success、Step 确认及资产授权、WorkingCopy 隔离、Candidate / 可信
revision 区分、UNSUPPORTED Criteria 预检、unknown / in-flight 阻塞、失败检查点原子回滚、
授权 / Direction / revision / Step 结果并发变化拒绝和历史 schema / JSON 读取。
**M0–M4 确定性回归：PASS。** 没有重跑真实 Provider Smoke，不将本次自动化结果作为新付费 E2E 证据。
全量于 2026-10-13 15:41:20 完成，Maven 报告耗时 09:57。
XML 实际核对：Domain 223 / 12 XML，Application 774 / 65 XML，Infrastructure 285 / 33 XML，
App 232 / 29 XML，各模块均 0 failures / errors / skipped。四项总计 1514，不与聚焦结果相加。

日志在 Worktree 的 `m5a-red.log`、`m5a-focused.log`、`m5a-safety.log`、`m5a-verify.log` 与
`m5a-frontend-*.log`；最终各模块 XML 在 `backend/<module>/target/surefire-reports/`。
这些构建产物不进入提交，也不包含真实业务执行现场。

## 4. Risks / Decision Required / Follow-ups

- 无阻塞本 Slice 的 Decision Required；业务失败状态及 Candidate 保留语义来自既有领域契约。
- 未认证的 Maven 负向结果仍可能保留 VERIFYING，不能将其当作已知 FAIL 或安全恢复。
  本版只识别上述有限负向证据；未实现通用构建工具失败分类。
- Unknown / EXECUTING / VERIFYING 的历史未决状态没有自动恢复入口；不能从本提交推断其已结束或已回滚。
- 真实 Provider 的可靠性和生产数据升级未执行；兼容性通过隔离旧格式 / 迁移夹具验证。
- Repair、Retry、Rollback、Recovery、完整失败审计与下一 Slice 留待单独授权，本轮完成后停止。

本报告与实现一同保存在本地实验提交；Commit SHA 见交付回复。
