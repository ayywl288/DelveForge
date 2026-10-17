# M5-B — Verification Failure Candidate Repair

日期：2026-10-13（Asia/Shanghai）。范围：独立授权 Repair 的 Vertical Slice，完整 M5 尚未完成。

**Gate：PASS / 本地待审查。** M5-B 实现及本轮确定性回归通过；不代表付费 Provider E2E、
真实用户数据库升级或 OS 崩溃恢复已经验证。

## 1. Environment / Contract

- Worktree：`C:\Users\28226\.codex\worktrees\m5-lab\DelveForge`。
- 分支：`feature/m5-lab`；起点：M5-A `3f21bca9b338a7ea7453e2539e38b3739a32f788`，开始时干净。
- 没有切换、修改、合并或 Push main，也没有移动 Release Tag 或操作原工作目录的未提交文件。
- 没有使用真实用户数据库、历史 WorkingCopy、原软件资产或 Smoke 证据。所有写入性验证均在
  本轮 UUID 测试库、测试 Git Fixture 与短临时根 `E:\dmb-1013-91fd` 中完成。
- Provider 为 fake、MockRestServiceServer 或本机测试服务；没有付费调用。

已读取 AGENTS、PRODUCT、ARCHITECTURE、DOMAIN_MODEL、ROADMAP、ADR-0001、M5-A 报告和实际源码。
DOMAIN_MODEL §6.5 / §8.12、INV-W06、INV-E08、INV-V05 明确原 Step、固定 baseline、新授权和重新验证。
ROADMAP IDEA-008 的完整 Attempt Entity 仍暂缓；本轮只使用 Step 内部 Value Object 保存必要事实。
本 Slice 不引入用户 Rollback、FAILED Retry、Recovery 或通用后台任务。

## 2. Changes / Reason

| 区域 | 关键实现 | 行为 |
|---|---|---|
| Domain | `RepairAuthorization`、`EvolutionStep`、`EvolutionPlan`、`StepExecutionPolicy`、`WorkingCopy` | 追加授权及旧失败快照；VERIFICATION_FAILED → EXECUTING；baseline 不重设，修改起点与可信基线区分 |
| Application | `ExecuteEvolutionStepUseCase.repair`、`PrepareEvolutionStepUseCase.prepareRepair`、`ExecutionPreparationState` | Provider 前原子保存新授权；从当前 Candidate 只读准备，所有关键外部能力前后重查 |
| Workspace | `AuthorizedStepExecution.fromRepair`、`GitWorkspaceAdapter` | 只修改绑定副本，从 B 创建一个子 Candidate C；保留原资产隔离与实际路径 / tree / HEAD 守卫 |
| Verification | 同一执行编排及既有验证适配器 | 保留 M5-A 的 PASS / FAILED / NOT_RUN / INCONCLUSIVE 分类；验证 A..C 和全部标准 |
| Persistence | V18、`SqliteStepExecutionCommitAdapter`、Step Mapper / Repository | 默认空授权历史；原子 CAS 包括旧历史，重放完整领域转移并拒绝历史替换 |
| REST / UI | 原控制器新增 `/repair`、DTO / client / session / `EvolutionView.vue` | 独立费用与范围确认、Candidate revision 绑定、历史展示；断线 / 刷新只查询，不重放请求 |
| 文档 | Domain、Architecture、Roadmap、ADR-0001 refinement | 记录本轮有限能力及技术选择，完整 M5 不标 DONE |

Repair 请求为 `POST /api/evolution-plans/{planId}/steps/{stepId}/repair`，请求体：

```json
{"expectedCandidateRevision":"<当前完整 Candidate commit>","authorizationId":"<本次独立 UUID>"}
```

这个请求明确授权本次 Repair；首次 Step Confirmation 不能替代它。UUID 是当前协议的去重身份，
不是资产权限或通用授权 token。同一 Step 的旧授权 ID、旧 Candidate 或未知 / in-flight 状态均拒绝。

调用先提交授权与失败快照，清空当前结果槽并进入 EXECUTING，随后才允许本次 Provider 调用。
Repair 仍属于原 Step，scope / Criteria 保留，baseline=A、lastVerified=A 不变，从真实 Candidate B 修改。
完成 Execution 后持久化 C 并进入 VERIFYING；PASS 才将 lastVerified 推进到 C，明确 FAIL 保留 C。
异常保留最后完整检查点，不能据此推断已回滚、仍在运行或可以重放。

每次授权归档其所依据的完整 ExecutionResult / VerificationResult；初次 B 失败、再次 C 失败及
各次授权均可从正常 GET 读取。最新成功结果保留在当前结果槽。快照是不可修改的 Value Object 列表，
不是完整 Attempt / 事件框架；没有回填以前不存在的授权事实。

本版保守技术选择：此前动作及定向测试身份全部重跑，再加入新提案意图，按 action / target 合并
标准索引；继续受既有动作、时间、输出、报告和上下文预算限制。新增测试不能掩盖旧失败。
DIFF_CHECK 比较 A..C，BUILD / TEST 在完整 C 上运行，不把 B..C 的 Repair Diff 当作完整软件验证。

## 3. Verified Facts

最终聚焦命令：

```powershell
mvnw.cmd -o "-Djava.io.tmpdir=E:\dmb-1013-91fd" `
  "-Dtest=StepExecutionLifecycleTest,ExecuteEvolutionStepUseCaseTest,RepairAuthorizationMigrationTest,EvolutionStepExecutionApiIntegrationTest#repair*" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

**PASS：54 项，0 failures / errors / skipped。** Domain 15、Application 23、Migration 1、Repair API 15。
原日志 `m5b-focused-final.log`，Maven 耗时 05:06，2026-10-13 16:58:31 完成。
这些选择包括旧回归，不能把 54 当作新增测试数量，也不与全量结果相加。

已实际验证：

- 真实 Git 的失败 B → Repair PASS C；原资产快照不变，C 的父提交为 B，baseline / source 保持 A。
- Repair 再 FAIL C → 独立再授权 → PASS D，B / C 失败及两次授权均保留。
- B..C 的 Diff 无检查错误而 A..C 仍失败，证明未只验证新增 Repair Diff。
- 新选择的通过测试不能掩盖此前 `ReportTest#report` 的失败；旧动作仍执行，新动作明确 NOT_RUN。
- 缺少授权字段、首次 execute 授权复用、旧 revision、重复授权 ID 被拒绝，拒绝前无 Provider 调用。
- 一个请求阻塞在 fake Provider 时，并发第二请求冲突；只有一次授权，未开始第二次 Provider 流程。
- 资产授权撤销、脏 Candidate、失效 Plan 均在授权 / Provider 前拒绝。
- SQLite 授权、Candidate、成功检查点故障注入：数据库保留此前完整检查点，加载对象不被污染。
- Provider 故障后保留授权和 EXECUTING；验证 Port 的 timeout / process-crash / interrupted 注入后
  保留 C、VERIFYING 与旧失败快照，新的 Repair 请求不能重放。
- 新 Spring 应用上下文、DataSource / Flyway / Repository 从本轮同一测试库启动，读回 VERIFYING、
  Candidate、完整授权 / 失败历史及原 lastVerified。这是独立应用上下文启动回读，不冒充 OS 级崩溃重启实验。
- V17 → V18 隔离迁移只增加 `[]` 历史，保留旧状态、baseline 和结果原文字节。正常 API 覆盖有效领域记录回读。
- 前端独立 Repair checkbox、Candidate 漂移禁用、旧 FAIL 查询不清除新等待标记；断线后刷新只 GET。

| 命令 | 实际结果 |
|---|---|
| `mvnw.cmd -o "-Djava.io.tmpdir=E:\dmb-1013-91fd" verify` | PASS，1,532 项，0 failures / errors / skipped；四模块构建成功 |
| `pnpm test` | PASS，10 文件 / 141 项 |
| `pnpm typecheck` | PASS |
| `pnpm build` | PASS |
| `git diff --check` | PASS |

全量日志 `m5b-verify.log`，耗时 14:42，2026-10-13 17:15:40 完成。执行 shell 使用
`MAVEN_OPTS=-Xmx512m`，移除该 shell 的 Provider key / `SPRING_APPLICATION_JSON` 环境变量；
没有修改用户配置。按最终 Surefire XML 与本轮日志核对，Domain 225、Application 774、
Infrastructure 286、App 247，共 140 个测试报告。聚焦及全量数量不能相加。

**M0–M4 / M5-A：本轮全量确定性回归 PASS。** 包括模块依赖约束、既有 Verified Success、
Step 确认、资产授权、WorkingCopy 隔离、可信 revision、标准预检、Unknown / in-flight 阻塞、
验证失败分类和历史持久化测试。没有重跑历史付费 Smoke，不将历史证据当作本轮 E2E。
前端日志为 `m5b-frontend-test.log`、`m5b-typecheck.log`、`m5b-frontend-build.log`。

## 4. Failures During Development

以下均为本轮测试 / 构建事实，没有修改生产错误分类或删掉相关断言来通过：

1. 初次新增夹具重复设定 Mock 调用了旧 answer 的 null 参数，1 error；改用 doAnswer 重设。
2. 修正夹具后，旧 API 对 `/repair` 实际返回 404，新增测试 1 failure，复现缺少入口。
3. 初轮编译用了项目没有引入的 Jakarta Validation 注解，编译失败；改用既有 DTO 构造校验，没有加依赖。
4. 聚焦测试 3 failures：错误期待 Workspace 异常为 502，实际既有契约为 500；保留生产分类，修正预期。
5. 前端新增用例 3 failures：夹具错误使用 COMPLETED Plan、选择了未打开的旧对话框 checkbox；
   改成真实 ACTIVE Repair 状态并选择打开的对话框。既有生命周期及授权守卫保持不变。

## 5. Risks / Unverified / Decision Required

- **Decision Required：无阻塞本 Slice 的新业务决策。** 原领域规则决定 Repair 状态转移与固定 baseline；
  授权 ID 协议、必要 VO 快照、动作合并及授权先提交的顺序是本轮明确记录的实现选择。
- Provider / 提案失败发生在授权后时，仍停在 EXECUTING；本 Slice 没有解除该检查点或重新授权的入口。
  这不表示 Provider 仍运行、代码已失败回滚或可以复用授权。
- 新验证动作与此前动作合并后仍受既有预算限制；超预算关闭，不自动拆分、放宽标准或重试。
- 未做真实付费 Provider、真实业务数据升级、真实浏览器手动验收、OS 进程强杀 / 断电恢复或磁盘损坏实验。
  Repair 异常通过 Port 注入验证；既有真实进程异常分类由全量 M5-A / Workspace 回归验证，二者分开。
- 没有实现 User Rollback、FAILED Retry、Recovery、通用任务或完整 Attempt History；不自动进入 M5-C。

本轮日志为 Worktree 下 `m5b-*.log`，完整 XML 为各模块 `target/surefire-reports/`；构建产物不进入提交。
本报告随实现本地提交交付，最终 Commit SHA 见交付回复。
