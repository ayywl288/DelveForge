# M5-C — User Rollback

日期：2026-10-13（Asia/Shanghai）。范围：VERIFICATION_FAILED 的独立授权 User Rollback。
完整 M5 尚未完成，不包含自动 Execution Failure Rollback、FAILED Retry 或 Recovery。

**Gate：PASS / 本地待审查。** 最终后端 1,563 项、前端 145 项及构建通过；不代表 OS 崩溃恢复、
真实用户数据库升级或真实 Provider E2E 已经验证。

## 1. Environment / Contract

- Worktree：`C:\Users\28226\.codex\worktrees\m5-lab\DelveForge`；分支：`feature/m5-lab`。
- 起点：M5-B `ed659c12c1847a7f27b63d2638e897b3c8abd11a`，开始时干净。
- 已读取当前 AGENTS、PRODUCT、DOMAIN_MODEL、ARCHITECTURE、ROADMAP、ADR-0001、M5-A/B 报告与实际代码。
- 依据 DOMAIN_MODEL §6.5、§8.13、INV-W05/06、INV-V06/07/08，首次 baseline 固定，精确恢复后才可 FAILED。
- 未操作真实业务数据库、历史 WorkingCopy、原始软件资产或 Smoke 证据；没有付费 Provider 调用。
  UUID 测试数据库 / Git Fixture 在本 Worktree 的 target 内，短临时根为本轮新建的 `E:\dmc-1013-2ba6`。
- main、其未提交文件与既有 Release Tag 不在写入范围；不 Push、不合并，不清理其他 Worktree 或用户目录。

契约差距是缺少 User Rollback 的授权、状态转换、窄 Workspace 能力与事务检查点；
不需要重设计 M5。额外用户文件的清理没有授权，本版保守拒绝并保留。

## 2. Changes / Reason

| 区域 | 关键实现 | 行为 |
|---|---|---|
| Domain | RollbackAuthorization / RollbackResult、EvolutionStep、EvolutionPlan、WorkingCopy、StepExecutionPolicy | 固定原 baseline，独立授权；保留当前失败结果及所有 Repair 快照；FAILED 或 RECOVERY_REQUIRED |
| Application | RollbackEvolutionStepUseCase、ExecutionPreparationState | 先保存授权与 ROLLING_BACK，再恢复；核对实际结果及完整检查点，不依赖 AI Gateway |
| Workspace | WorkspaceRollbackPort、AuthorizedStepRollback、GitWorkspaceAdapter、WorkspaceCommandRunner | 托管隔离副本，精确 commit；非强制 checkout、禁止覆盖 ignored 文件；本次进程终止的有界等待 |
| Persistence | V19、Step Mapper / Repository / DO、SqliteStepExecutionCommitAdapter | nullable 授权 / 结果槽；重放完整 Domain 转移，原子 CAS Step / WorkingCopy，旧历史不改写 |
| REST / UI | 原执行控制器、DTO、client / session、EvolutionView | 独立 checkbox，捕获 Candidate / baseline；结果与授权 ID 对应，断线只 GET，不重放 |
| 文档 | Domain、Architecture、Roadmap、ADR-0001 refinement | 记录有限能力、技术选择与用户裁定；完整 M5 不标 DONE |

请求为 `POST /api/evolution-plans/{planId}/steps/{stepId}/rollback`：

```json
{"expectedCandidateRevision":"<完整当前 Candidate>","expectedBaselineRevision":"<原 Step 首次 baseline>","authorizationId":"<本次新 UUID>"}
```

首次 Step Confirmation、旧 Repair ID、旧 Candidate 或其他 baseline 都不能替代本次授权。
授权绑定原 Step，先原子进入 ROLLING_BACK；baseline 和 lastVerified 不改变。
复用已有资产、Plan / Flow、前序 Step、WorkingCopy 绑定、隔离和真实 Candidate 守卫。
Rollback 不依赖当前标准的执行适配资格，也不要求 Provider 或重跑验收测试：它恢复既有可信基线，
不把未满足的当前 Step 标为成功。FAILED 继续阻止后续 Step，本 Slice 没有 Retry 入口。

Git 采用禁用 hooks 的非强制 detached checkout，使用 `--no-overwrite-ignore`，不用 reset --hard / clean。
只放弃 Candidate 中已跟踪的 Step 改动；授权前脏树或额外文件拒绝，授权后发现则保存阻塞事实。
精确恢复还拒绝 replacement refs / graft，并显式 `--no-replace-objects`，不删改这些元数据。
任务自有真实 Git 夹具已复现：替换视图下 HEAD 等于 A、Git status 干净，但工作树仍是 Candidate；
原始 A blob 是 baseline。这证明仅核对 SHA / 默认 Git 视图不足，不能据此伪造恢复成功。
证据为 `m5c-object-view-proof.log`，夹具位于短临时根的 `object-view-proof`。

只有实际精确恢复、状态重查和成功事务都完成，才记录 FAILED 与 current=baseline；lastVerified 不推进。
恢复异常、未知结果、恢复后用户修改、权限撤销或成功检查点失败时，只提交 RECOVERY_REQUIRED 安全事实。
`blockRollback` 不授予外部能力、不允许新的代码操作；仍比较旧授权、结果、Repair 历史、绑定与 copy 依据，
但权限撤销不能阻止已授权操作的诊断落盘。若成功和阻塞事务都不可写，保留完整 ROLLING_BACK 并返回错误。
不重跑 Git、不执行补偿。诊断保存阶段、可用的 Git exit code / observed revision 或异常类型，不存原始输出。

RECOVERY_REQUIRED 下 currentRevision 是最后已记录 Candidate，不能声称未知物理现场仍在该 revision；
即使 observedRevision=baseline，也不能单独证明完整恢复。失败 Execution / Verification 不被结果槽替换，
此前 Repair 快照及授权继续保留。V19 只加槽，不回填虚构授权或转换旧状态。

## 3. Verification / Actual Results

最终全量命令：

```powershell
mvnw.cmd -o "-Djava.io.tmpdir=E:\dmc-1013-2ba6" verify
```

执行 shell 使用 `MAVEN_OPTS=-Xmx512m`，移除该 shell 的 Provider key 与 SPRING_APPLICATION_JSON，
最终完整回归将该 shell 的 TEMP / TMP 设置为上述 Slice 自有短临时根，不改写用户全局配置。
早期 JUnit 临时目录也由本轮测试独立分配，未清理其他用户目录。
所有 Maven 子仓库均为本轮已知 Fixture，离线运行，没有不受控外部仓库构建。

| 检查 | 实际结果 |
|---|---|
| 最终后端全量 verify | PASS，1,563 项 / 141 XML，0 failures / errors / skipped；四模块构建与打包成功 |
| `pnpm test` | PASS，10 文件 / 145 项；m5c-frontend-test-complete.log |
| `pnpm typecheck` | PASS；m5c-typecheck-final.log |
| `pnpm build` | PASS；m5c-frontend-build.log |
| `git diff --check` | PASS |

最终日志 `m5c-verify-complete.log`，2026-10-13 19:39:26 完成，耗时 20:28。
Surefire XML 与日志实际核对：Domain 228 / 12 XML，Application 774 / 65 XML，
Infrastructure 287 / 35 XML，App 274 / 29 XML。全量包含 27 个 Rollback API 测试实例，
不与聚焦、单例或先前代码状态的结果相加。

**M0–M4、M5-A/B 确定性回归：PASS。** 包括模块依赖、Verified Success、Step 确认、资产授权、
WorkingCopy 隔离、Candidate / 可信 revision、标准预检、Unknown / in-flight 保护、
Verification Failure / Repair 分类、并发检查点及历史数据读取。未重跑历史付费 Smoke。

最终回归已覆盖场景：

- 多次 Repair 再 FAIL 后恢复最初 baseline，完整旧失败及授权不被覆盖；后续 Step 仍阻止。
- 较后 Step 恢复自己的首次 baseline，而非 WorkingCopy.sourceRevision（纯 Domain 测试）。
- 真实 Git 的已跟踪 Candidate 新增文件被移除、删除文件恢复；全工作树字节等于副本最初快照。
- 旧 Candidate、错误 baseline、缺少 UUID、旧 Repair ID、重复请求及并发请求拒绝；只调用一次恢复能力。
- EXECUTING / VERIFYING 未知状态不能被当作明确 FAIL 来授权 Rollback。
- 额外 untracked / ignored 文件、用户修改在授权前拒绝，授权后阻塞；内容保留，没有清理。
- 真实 Git index.lock / HEAD.lock 故障与 replacement / graft 视图阻塞，后者元数据保持不变。
- Port 注入未知结果、中断、伪造“成功”返回；真实检查不接受假恢复，中断标志在安全落盘后恢复。
- 恢复后用户修改或撤权阻止成功，仍能保存安全阻塞事实；不重复恢复。
- SQLite 授权、成功、所有终态检查点故障注入：事务整体回滚，加载对象不被污染；不暴露半个检查点。
- 新 Spring 上下文、DataSource / Flyway / Repository 对同一测试库回读 FAILED、RECOVERY_REQUIRED、
  ROLLING_BACK 与全部授权 / 失败 / Repair 历史；启动不补偿或改判，没有 Git 重放。
- V18 → V19 隔离迁移保留旧状态、baseline、Execution / Verification / Repair 原文字节，新增槽为 null。
  原文哨兵只证明迁移不重写，不冒充有效 Domain 记录；有效记录回读由 API 场景证明。
- 前端独立确认、Candidate / baseline 漂移禁用、旧 FAIL GET 保留等待标记，断线 / 重启只查询。

聚焦命令选择 StepExecutionLifecycleTest、ExecuteEvolutionStepUseCaseTest、RollbackMigrationTest、
EvolutionStepExecutionApiIntegrationTest#rollback*，使用同一 -o / 临时根及
`-Dsurefire.failIfNoSpecifiedTests=false`。数量与全量不能相加。
首次多 Repair 成功场景连同领域 / 迁移共 20 项 PASS（m5c-first.log）；最终新增 / 删除文件单例
PASS（m5c-addition-final.log），既有规划 API 装配的 7 项也通过。
`m5c-termination-object-view-final.log` 聚焦 PASS：8 项 VerificationOutcomeTest、11 项真实 Git /
Rollback 故障，共 19 项，0 failures / errors / skipped；耗时 02:37，19:16:54 完成。

## 4. Development Failures

- 首轮前端 145 项中 1 failure：新增隐藏 dialog 放到原执行 dialog 前，旧测试选择了它的 checkbox；
  恢复原执行 dialog 顺序，最终 145 项通过。没有删掉或放宽首次执行授权测试。
- 类型检查提示响应 step 可能为 undefined；补显式判空，typecheck / build 通过。
- 首轮聚焦 63 项中 1 failure / 1 error：空 template 克隆没有 .git/info，ignored 夹具写入失败；
  仅在夹具创建该目录，没有放宽生产清洁守卫。
- 补充聚焦 67 项中 1 failure：新删除文件夹具遗漏 declaredAffectedPaths，生产校验正确返回 422；
  修正 fake 提案的路径集合，保留严格补丁校验。
- 随后 8 项检查中新增文件场景 1 failure：原资产 LF 与副本初始 CRLF 被当成相同字节；
  使用副本最初字节作为恢复期待，不修改 Git EOL 配置或校验规则，单例通过。
- 对象视图守卫加入前，全量 1,561 项 PASS（m5c-verify.log，18:49:07，19:19）。
  该结果不能作为新增 replacement / graft 守卫的最终证据，不与最终全量相加。
- 加入对象视图守卫后的全量，在 Infrastructure 的 VerificationOutcomeTest 出现 1 error，
  原因是 JUnit 临时目录清理被 Windows 文件锁拒绝；Domain / Application 全部通过，App 未执行。
  只等待 ProcessHandle 的首轮修正仍有同类 timeout 清理 error。最终改为实际 Process.waitFor 加
  本次 descendants 的总计 5 秒有界等待，并保留中断标志；终止无法确认继续抛未知结果，不返回 FAIL facts。
  同时在该 shell 使用本 Slice TEMP / TMP，聚焦 19 项通过。未单独识别全部 OS 文件锁持有者，
  不把 Windows 所有锁争用都声称为已定位或已消除；没有跳过测试或放宽未知结果断言。

## 5. Risks / Decision Required

**本轮用户裁定：** 重启读到残留 ROLLING_BACK 保持原检查点，阻止新的 Execution、Repair、Rollback；
没有 Recovery 请求入口。不自动重放、不推断旧调用已停止，不改判 FAILED 或 RECOVERY_REQUIRED。
正常调用中能保存的失败 / 未确认结果仍按本轮契约进入 RECOVERY_REQUIRED。

**后续 Recovery Slice 的 Decision Required：** 旧调用停止的证据、实例所有权、未决结果判定及允许的
Recovery 授权范围。该事项不阻塞本轮已裁定的 User Rollback，不在本轮设计或实现。

- 数据库同时拒绝成功与阻塞事务时只能保留 ROLLING_BACK；未伪造已持久化的失败或成功。
- 重启验证是新应用上下文 / 新连接回读，不是 OS 强杀、断电或真实服务进程崩溃实验。
  Port 的未知 / 中断注入与真实 Git 锁 / 对象视图故障分开，不互相替代。
- 未测试真实用户数据升级、真实付费 Provider、真实浏览器手动验收、OS 崩溃 / 断电 / 磁盘损坏。
- 不提供额外用户文件清理，不实现自动 Execution Failure Rollback、Retry、Recovery、后台任务或下一 Slice。

本轮日志 m5c-*.log、Surefire XML、Fixture 与构建产物不进入提交。
本报告与实现同一提交交付；最终 Commit SHA 见交付回复，可由本 Worktree 的 git log -1 查询。
