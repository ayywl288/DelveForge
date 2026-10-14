# M5-A/B/C Review Findings 修复

实施 / 验证：2026-10-13；报告与提交完成：2026-10-14（Asia/Shanghai）。范围：M5-001 / M5-002 两项 Important。
基线：`2af0b556eedc4ca3860ccc66756a333947112e3a`，`feature/m5-lab`，开始时干净。
Worktree：`C:\Users\28226\.codex\worktrees\m5-lab\DelveForge`。

**Gate：自测 PASS / 待独立复审。** 最终后端 1,575 项、前端 149 项及构建通过。
没有重构 Verification 架构或开展新 Slice；不代表生产数据、付费 Provider 或 OS 崩溃恢复已验证。

## 1. Contract / Isolation

已读取当前 AGENTS、PRODUCT、DOMAIN_MODEL、ARCHITECTURE、ROADMAP、ADR-0001、M5-C 报告和实际实现。
DOMAIN_MODEL §8.13 要求实际 HEAD、index 与工作树精确对应首次 baseline 后才允许 FAILED；
授权前异常拒绝，授权后未确认结果保持 RECOVERY_REQUIRED。ROLLING_BACK 重启保留、不重放的裁定不变。

所有 Git Fixture / SQLite 数据库使用本次测试新建的 UUID 目录；短临时根为任务自有
`E:\dmrf-1013-72e1`；最终全量改用本任务新建的 `C:\dmrf-1013-72e1`。每次 TEMP / TMP / java.io.tmpdir 均指向所列任务根。没有清理其他 Worktree / 历史目录，
没有访问真实业务数据库、历史 WorkingCopy 或 Smoke 证据，没有付费 Provider 调用。
main、原工作目录和 Release Tag 没有写入；不 Push、不合并。

## 2. M5-001 — Rollback 精确恢复误判

### Reproduction / Root cause

在真实独立 Git Fixture 中，Candidate 只修改 changed.txt。另一个 tracked unchanged.txt
在稳定 index 缓存建立后发生等长篡改；配置 `core.trustctime=false` / `core.checkstat=minimal`，
恢复旧 mtime。测试先断言真实 `git status --porcelain` 与普通 diff 均为空，再检查实际文件仍被篡改。

旧代码两个安全断言失败：requireTrustedState 未抛异常，restoreBaseline 未抛异常而返回成功。
红色结果：3 项，2 failures，0 errors / skipped；普通恢复对照通过。证据：m5-review-backend-red.log。
这是已执行的复现，不是 mock Git 或仅依据审查者陈述。

根因：HEAD / index 对象和 Git stat 缓存均可保持原样，普通 status / diff 不一定重新读取未修改文件。
仅检查 Candidate→baseline 的变化路径不能证明 baseline 中其余文件的真实内容。

### Fix

GitWorkspaceAdapter 的共享 requireTrustedState 增加整树内容证明：

1. 从不可替换的固定 revision 枚举全部 tracked blob，核对 index 的对象、mode、stage 和完整路径集合。
2. 全部实际路径复用隔离 / 链接 / 嵌套仓库检查；逐批重新读取文件并计算 Git blob identity，
   不依赖真实 index 的 stat 缓存，不写对象、不刷新或修复真实 index。
3. `--attr-source=<revision>` 固定属性来源，允许 Git 原生 text / EOL 转换。
   custom filter、ident、working-tree-encoding 缺少本版可靠物化证明时失败关闭；不运行过滤器命令或改写配置。
4. 沿用有界、环境隔离的 WorkspaceCommandRunner；不支持该 Git 能力或无法完整读取时失败关闭。

共享 guard 是 Rollback 授权前加载、Adapter 恢复前后和成功提交前检查的直接依赖；
M4 / Repair 的物理检查同步增强，Domain / API / 数据库状态机和并发 CAS 没有改动。
Git 的 EOL 等价由转换后的 blob 核对；本报告不声称任意过滤器或任意物理表示都能获得证明。

### Regression / Fault injection

- Infrastructure 真实 Git：缓存漏报的未改动文件、恢复前拒绝、checkout 后隐藏篡改、普通恢复、
  LF / CRLF 属性、二进制普通文件、过滤器不执行、未证明 ident / 编码转换拒绝。
- API / SQLite：授权前拒绝且授权和结果槽不落盘；授权后隐藏篡改进入 RECOVERY_REQUIRED；
  实际恢复后再次隐藏篡改也阻塞，不能提交虚假的 FAILED / current=baseline。
- 原有 M5-C 的额外文件保留、replacement / graft、防旧请求、并发、SQLite 检查点失败及重启回读继续回归。

## 3. M5-002 — 明确资产授权拒绝残留等待标记

### Reproduction / Root cause

旧 session 在 Repair 和 Rollback 返回 ASSET_READ_PERMISSION_REQUIRED、GET 回读同一失败
Candidate 且本次授权没有保存时，仍保留 receipt / localStorage 标记。
两条前端红色回归均失败：预期 null，实际为等待标记；2 failures，35 项未选中。
证据：m5-review-frontend-red.log。未选中来自聚焦选择，不是禁用测试。

根因：普通 Execute 白名单已有资产资格错误，但 Repair / Rollback 分支只列出
INVALID_REQUEST / CONFLICT / EXECUTION_CRITERIA_UNSUPPORTED。

### Fix / Regression

为失败处理分支补齐后端现有五类明确资产错误：ASSET_TYPE_UNSUPPORTED、ASSET_READ_PERMISSION_REQUIRED、
ASSET_USAGE_AUTHORIZATION_REQUIRED、ASSET_LICENSE_INFORMATION_REQUIRED、ASSET_AUTHORIZATION_CONFLICT。
不加入网络错误或泛化的服务器错误。

清理仍要求成功 GET：Step 为 VERIFICATION_FAILED；WorkingCopy 为 EVOLVING；copy、ExecutionResult、
VerificationResult 都对应请求 Candidate；lastVerified 与原 baseline 匹配；本次授权未保存。
Rollback 另核对请求 baseline，Repair 要求完整授权列表。只允许后续新的显式授权，不自动提交。

两条路径分别覆盖五类资产错误及新 UUID；GET 失败、copy / 结果 / baseline 漂移、已保存未决授权、
EXECUTING / ROLLING_BACK 和网络中断都保持等待、禁止重发。正常完成结果的既有 GET 清理规则保留。

## 4. Commands / Actual results

所有后端命令前设置 TEMP / TMP 为上述短根、MAVEN_OPTS=-Xmx512m；使用 Maven Wrapper 和离线缓存。

| 命令 | 实际结果 |
|---|---|
| `mvnw.cmd -o "-Djava.io.tmpdir=E:\dmrf-1013-72e1" -pl backend/delveforge-infrastructure -am -Dtest=GitRollbackContentIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS，9 项，0 failures / errors / skipped |
| `mvnw.cmd -o "-Djava.io.tmpdir=E:\dmrf-1013-72e1" -pl backend/delveforge-app -am "-Dtest=EvolutionStepExecutionApiIntegrationTest#rollback*" -Dsurefire.failIfNoSpecifiedTests=false test` | PASS，30 项，0 failures / errors / skipped；20:56:20 完成，11:43 |
| `mvnw.cmd -o "-Djava.io.tmpdir=C:\dmrf-1013-72e1" verify` | PASS，1,575 项 / 142 XML，0 failures / errors / skipped；四模块构建、打包及 Boot repackage 成功 |
| `pnpm test` | PASS，149 项 / 10 文件；20:44:34 开始，耗时 11.70s |
| `pnpm typecheck` | PASS |
| `pnpm build` | PASS，56 modules transformed |
| `git diff --check` | PASS |

后端红色复现使用上述 Infrastructure 聚焦命令；前端红色复现为
`pnpm test -- tests/session.test.ts -t '资产读取拒绝'`，仅选择初始两条复现测试。
最终前端完整测试包含扩展后的五类资产拒绝与未知状态回归，不将循环案例数量当作 Vitest 测试数量。

开发迭代还遇到夹具换行 / 初始化及 NUL 正则转义错误，已修正；新增转换拒绝测试一度因模板克隆
没有 .git/info 目录而出现 2 errors（m5-review-fixture-errors.log），补齐夹具目录后 9 项通过。
前端曾把已完成 VERIFICATION_FAILED + 匹配授权的结果错误作为“未决”夹具；已改为准确的
EXECUTING / ROLLING_BACK 授权检查点，保留正常完成结果的清理规则，最终 149 项通过。
没有删除 / 禁用测试、放宽内容断言或把未知结果转为明确失败。

首轮完整 verify：Domain / Application 通过，Infrastructure 296 项中 1 failure / 2 errors，App 未运行。
新增整树核验复用公开 requireSafePaths 时改变了原有 stagedDrift 故障注入调用序号，导致过早检测漂移、
异常类型断言不符。将共同路径检查提取为私有 inspectSafePaths，保留公开能力调用位置和原测试断言；
两项原 stagedDrift 参数回归及九项内容回归随后全部通过。

另两项错误是 VerificationOutcomeTest 在中断 / timeout 后的 JUnit 临时目录删除失败；
后续安全聚焦 19 项出现 1 个同类清理 error。未删除其他目录、改动进程测试或关闭 JUnit 清理。
新任务根 C:\dmrf-1013-72e1 下原 VerificationOutcomeTest 八项通过；最终完整 verify 在该根全部通过。
目录锁的完整原因尚未定位，不把一次成功声明为所有 Windows 锁争用已消除。
安全聚焦日志为 m5-review-safety-focused.log，进程聚焦为 m5-review-process-focused.log。

最终全量日志 m5-review-verify-final.log：2026-10-13 21:41:16 完成，33:25。
按本轮新生成的 Surefire XML 核对，无过期报告：Domain 228 / 12 XML、Application 774 / 65 XML、
Infrastructure 296 / 36 XML、App 277 / 29 XML，合计 1,575 / 142 XML。

**M0–M4 与 M5-A/B/C 确定性回归：PASS。** 包括 Verified Success、Step 确认、资产授权、WorkingCopy
隔离、revision 信任、UNSUPPORTED 预检、明确 FAIL / 未知结果区分、Repair 历史和授权、
Rollback 精确首次 baseline、额外文件保留、并发 / 重复请求、SQLite 检查点与新上下文重启回读。
完整套件包含本轮 30 项 Rollback API 实例、九项新增整树内容实例，以及既有 Execution / Repair 回归。
没有重跑付费 Smoke，也不把历史 E2E 证据算作本轮验证。


聚焦次数不与全量相加。完整日志为 m5-review-backend-red.log、m5-review-frontend-red.log、
m5-review-backend-focused.log、m5-review-api-focused.log、m5-review-verify-first-failed.log、m5-review-verify-final.log、
m5-review-frontend-all.log / typecheck.log / build.log。

## 5. Risks / Unverified / Decision Required

- 实测环境为 Windows / Git 2.53.0.windows.2。旧 Git 不支持 --attr-source 时失败关闭；未做其他 OS 实测。
- 支持证明的是普通 tracked blob 和 Git 原生 text / EOL；symlink / gitlink、custom filter、ident、
  working-tree-encoding 无可靠证明时拒绝。扩展物化能力不在本轮范围，不清理或改写用户配置。
- 整树重新读取比仅查询 stat 缓存增加 I/O 和进程开销；没有大型真实用户仓库性能基准。
- 验证中出现过 Windows 临时目录清理锁错误；完整原因未知，未改变断言或声称普遍消除。
- 文件系统与数据库仍不是一个事务；没有验证恶意外部进程持续并发写、OS 强杀、断电或磁盘损坏。
- SQLite 重启验证是新应用上下文 / 连接回读，不表示 OS 崩溃恢复已验证。
- 前端为模拟 API 自动化；未进行真实浏览器手工验收或付费 Provider E2E。
- 无新的阻塞性业务 Decision Required。原 ROLLING_BACK 所有权 / 停止判据仍留给后续 Recovery Slice。

本轮 m5-review-*.log、测试库、Fixture、Surefire XML 与构建产物不进入提交。
本报告与修复同一提交交付；最终 SHA 见交付回复及本 Worktree 的 git log -1。
