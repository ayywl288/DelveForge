# M5 独立复审 Findings M5-003 / M5-004 修复

日期：2026-10-14（Asia/Shanghai）。基线：`dbf8e2b488381575af1ac130bac85cc069fb839e`。
Worktree：`C:\Users\28226\.codex\worktrees\m5-lab\DelveForge`，`feature/m5-lab`，开始时干净。
验证进行中；最终 Gate / 全量统计在完成后记录。本轮只修复这两项，不重构 Verification 或开始新 Slice。

## 1. Contract / Isolation

读取当前 AGENTS、PRODUCT、DOMAIN_MODEL §8.13、ARCHITECTURE、ROADMAP、ADR-0001、原修复报告与代码。
授权前异常拒绝、授权后未确认的 Rollback 保存 RECOVERY_REQUIRED；Repair 未知结果保留 EXECUTING / VERIFYING，
不推断失败或自动重放。首次 baseline、授权身份、历史、CAS 和 ROLLING_BACK 重启裁定没有改变。

本轮短临时根：`C:\dmrf2-1014-b184`。所有 Git / SQLite Fixture 均为本次新建隔离目录和数据库。
只运行仓库已知测试和其自有 Maven Fixture，使用 Wrapper / 离线缓存和 fake Provider；没有付费调用。
没有访问真实业务数据库、历史 WorkingCopy 或 Smoke 证据，不写 main、原工作目录、Release Tag 或其他 Worktree。
没有清理旧测试根；不 Push、不合并。

## 2. M5-003 — 同名 driver 绕过精确证明

### Executed reproduction

真实 Git 夹具：Candidate 仅修改 changed.txt；unchanged.txt 被等长篡改，mtime 恢复，普通 status / diff 漏报。
`.gitattributes` 指定 `filter=unset` 或 `filter=unspecified`，本夹具 clean 命令输出固定的可信内容，
并在本夹具 `.git/filter-ran` 留下副作用。命令不访问真实用户文件或 Provider。

基线旧实现 4 项全部失败：两个 driver × 授权前共享检查 / 已授权 restoreBaseline。
旧校验接受伪造内容，marker 实际产生；Rollback 还能返回成功并令 HEAD 指向 baseline，而原物理篡改仍保留。
这是实际 Git 复现，未 mock hash / status。证据：m5-review2-git-red.log。

### Root cause / Fix

`check-attr` 指定属性时，把真实缺省 / 禁用状态与同名字符串 driver 打印为相同文本。
旧代码把这些字符串视为安全，随后不带 --no-filters 的 hash-object 调用 clean，信任了 filter 的输出。

- `check-attr --all` 按属性存在性拒绝 filter / ident / working-tree-encoding，不解释哨兵字符串。
  显式禁用也保守拒绝；真正未声明的属性不产生条目。
- 实际文件读取使用 `hash-object --no-filters`，结构上取消 clean 能力；不写对象或真实 index。
- 同一属性守卫在 checkout 前还检查整个原始 baseline：即使 Candidate 删除了旧 filter 属性，
  也不能先运行目标 smudge 再在 POSTCHECK 拒绝。该拒绝走既有授权后 RECOVERY_REQUIRED 路径。
- 需要 EOL 等价时，只接受已索引 LF、实际 CRLF / mixed 和受支持 text / auto / eol / autocrlf 描述。
  EOL / config 按最多 32 个文件的批次读取，每个实际文件仍独立计算完整 identity；不是 stat 缓存。
  有界读取最多 1 MiB 的实际字节，仅移除 CRLF 中的 CR，计算完整 Git blob identity；NUL、未知配置、
  超预算或不匹配失败关闭，不把转换后的任意 filter 输出当作证据。
- tracked 内容 / index / mode / stage 独立证明后，额外文件用 ls-files 枚举。移除该校验路径的
  status 和 Rollback 末尾工作树 diff，防止它们另行调用 clean。保留隐藏 index 标志、隔离、路径、
  Git 替换视图、额外文件保留，以及 POSIX 上 Git filemode 启用时的实际可执行位检查。

安全依据参考 Git 官方 [hash-object](https://git-scm.com/docs/git-hash-object) 的 no-filters 契约、
[ls-files](https://git-scm.com/docs/git-ls-files) 的只读 EOL 字段和 [gitattributes](https://git-scm.com/docs/gitattributes)。
当前技术适配范围不是新的业务授权规则。

### Regression

真实 Git 测试重跑原 M5-001 的 stat 漏报、普通恢复、LF / CRLF、二进制、转换拒绝，并加入同名 driver、
仅 baseline 存在的 smudge、eol 单独指定 / text=auto / core.autocrlf=true / input。
API / SQLite 覆盖 M4 Execute、M5 Repair / Rollback 授权前拒绝，无 Provider / 新授权 / filter 副作用；
Repair 授权后注入保持 EXECUTING 与授权历史；Rollback 授权后注入保持 RECOVERY_REQUIRED，保留实际内容。
成功判定、原始 baseline、额外用户文件保留、重复 / 并发与未知结果保护继续回归。

## 3. M5-004 — 被淘汰 GET 清理等待标记

### Executed reproduction

Repair / Rollback 的 POST 返回明确业务错误后，错误处理 GET A 尚未完成；新 GET B 启动并将返回
本次已保存的 EXECUTING / ROLLING_BACK 授权。GET A 先返回旧失败 Candidate，refreshPlan 丢弃它，
却正常返回 undefined；旧 catch 将 checked=true，使用先前缓存的 VERIFICATION_FAILED 清除 receipt。

两个前端红色用例均失败：预期本次授权 marker，实际 undefined；39 项未选中，不是禁用测试。
证据：m5-review2-ui-red.log。mock API 有意控制两个真实 Promise 的交错，不用固定 sleep 伪造完成顺序。

### Fix / Regression

refreshPlan 明确返回已采纳的 Plan / WorkingCopy 快照及查询版本，淘汰 / 无目标 / disposed 返回 null。
错误清理还要求版本和当前对象身份仍一致；网络完成不再等同于可信结果被采纳。
执行前 GET 未被采纳则在写 marker / POST 前停止。成功 POST 后也不再无条件清理，等待匹配的已采纳终态。
既有 Candidate / baseline / 本次授权未保存检查保持，未知结果不清理、不自动重发。

新增覆盖 Repair / Rollback 交错拒绝、旧 GET 先完成 / 新 GET 先完成、顺序 GET、已保存未决授权、
匹配新终态清理及过期执行前查询禁止 POST。原五类资产拒绝、GET 失败、网络中断与重启等待规则继续回归。

## 4. Changed files / Responsibility

- `GitWorkspaceAdapter`：共享可信内容检查、无 filter 的 blob 证明、baseline checkout 前属性检查。
- `GitRollbackContentIntegrationTest`：真实 Git stat / converter / EOL / baseline 夹具。
- `EvolutionStepExecutionApiIntegrationTest`：真实 WorkingCopy / SQLite 和 fake Provider 的授权检查点注入。
- `frontend/src/state/session.ts` / `frontend/tests/session.test.ts`：GET 采纳凭据、最新版本守卫和交错回归。
- ARCHITECTURE、原 M5-001/002 报告及本报告：更新技术边界与复审证据。

未改 Domain / Application 状态转移、Port、API payload、Flyway、模块依赖、Provider 或 Verification 策略。

## 5. Commands / Actual results

后端命令设置 TEMP / TMP / java.io.tmpdir 为本任务短根，MAVEN_OPTS=-Xmx512m。
完整 verify 的新进程环境删除 Provider key / SPRING_APPLICATION_JSON；不输出凭据。
命令均从 Worktree 根运行，前端三项从 frontend 运行。

| 实际检查 | 命令 / 参数 | 结果 |
| --- | --- | --- |
| 原漏洞真实 Git 红色复现 | `GitRollbackContentIntegrationTest` 两个 sentinelNamed 方法，修复前运行 | 4 failures / 0 errors / 0 skipped；旧校验实际执行 filter |
| 原 GET 红色复现 | session 的 Repair / Rollback 被丢弃 GET 两个用例，修复前运行 | 2 failed；39 项未选中 |
| 初版 Git 定向 | `mvnw.cmd -o -Djava.io.tmpdir=C:\dmrf2-1014-b184 -pl backend/delveforge-infrastructure -am -Dtest=GitRollbackContentIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 13 passed；后续新增配置由最终 verify 重跑 |
| API / SQLite 定向故障注入 | `mvnw.cmd -o -Djava.io.tmpdir=C:\dmrf2-1014-b184 -pl backend/delveforge-app -am '-Dtest=EvolutionStepExecutionApiIntegrationTest#*Sentinel*+rollbackFailureOrUnknownBlocksWithoutDeletingUserFiles' -Dsurefire.failIfNoSpecifiedTests=false test` | 23 / 0 failures / 0 errors / 0 skipped；BUILD SUCCESS，28:22，12:50:55 完成 |
| baseline-only filter 定向 | `mvnw.cmd -o -Djava.io.tmpdir=C:\dmrf2-1014-b184 -pl backend/delveforge-infrastructure -am '-Dtest=GitRollbackContentIntegrationTest#baselineOnlyFilterCannotRunDuringCheckoutBeforeFailedPostcheck' -Dsurefire.failIfNoSpecifiedTests=false test` | 2 passed；25.873 s，12:48:27 完成 |
| 首次全后端 | `mvnw.cmd -o -Djava.io.tmpdir=C:\dmrf2-1014-b184 verify` | 1597 / 1 failure / 0 errors / 0 skipped；BUILD FAILURE，02:07 h，15:00:42 完成 |
| Git 批量读取修正 | 上述 GitRollbackContentIntegrationTest 定向命令 | 21 passed；02:42，15:16:03 完成 |
| 原并发断言重跑 | `mvnw.cmd -o -Djava.io.tmpdir=C:\dmrf2-1014-b184 -pl backend/delveforge-app -am '-Dtest=EvolutionStepExecutionApiIntegrationTest#repairConcurrentRequestCannotReuseAuthorizationOrStartAnotherProviderCall+rollbackConcurrentRequestAndRepairCannotReuseGrantedAuthorization' -Dsurefire.failIfNoSpecifiedTests=false test` | 2 / 0 failures / 0 errors / 0 skipped；02:15，15:23:23 完成 |
| 最终全后端 | `mvnw.cmd -o -Djava.io.tmpdir=C:\dmrf2-1014-b184 verify` | 待最终结果 |
| 前端全量 | `pnpm test` | 10 files / 155 passed，22.31 s，12:35:47 开始 |
| 前端类型 | `pnpm typecheck` | PASS，退出 0 |
| 前端构建 | `pnpm build` | PASS，56 modules；Vite 796 ms，退出 0 |

本次全量 verify 已重跑 `GitRollbackContentIntegrationTest`：21 / 0 failures / 0 errors / 0 skipped，155.8 s。
不把定向测试和全量重复相加，最终总数只统计本次 verify 的新 Surefire XML。

首次全量唯一失败：`repairConcurrentRequestCannotReuseAuthorizationOrStartAnotherProviderCall:1176`
等待 Provider 检查点的 15 秒断言超时（expected true / actual false）。新证明对每个 CRLF 文件分别
读取 EOL / autocrlf，进程启动成本累积。改为每批一次 EOL 描述和 config 查询，仍逐文件读完整字节
并匹配可信 blob；没有修改 15 / 30 / 60 秒测试屏障或任何授权、重复请求、并发断言。
修正后原 Repair / Rollback 两项并发均通过；不把定向结果代替最终完整 verify。
批量读取初次编译漏导入 HashMap（15:12:33 BUILD FAILURE）；已补齐 JDK import，21 项真实 Git 重跑通过。
POSIX mode 守卫读取 OWNER_EXECUTE 位，避免把当前进程的访问能力作为 Git mode；Windows 不执行此分支。

开发迭代：快照初版使用原始 JSON 身份而 Vue ref 保存代理，导致原 session 回归失败；已改为采纳对象身份。
最后一次加入内部 freshness 检查导致循环返回类型推导（TS7023 / TS2502）；补齐显式 AdoptedPlanSnapshot 类型，
完整前端 test / typecheck / build 已重新通过。
新增换行 Fixture 初版在授权后手动改 CRLF 而未发布 index，6 项在真实 Git CHECKOUT 被拒绝，
19 项运行中 0 failures / 6 errors，证明非强制 checkout 没有伪造成功。另建独立 Git 最小夹具确认
checkout / update-index --refresh 都报告需要更新；Fixture 改为模拟正常 Candidate 发布的 git add，
保留全部真实内容与恢复成功断言，生产校验器没有加入 index 刷新或强制覆盖。
baseline-only 夹具初版删除 filter 后改为 -text，但沿用旧 autocrlf 的物理 CRLF，21 项中 2 errors
在内容检查先行拒绝；已使该新 Candidate 的真实字节与 blob 匹配。两项定向及最终 21 项回归均通过。
另一个新只读 GET 测试缺少 POST spy 初始化；已补齐，保留原断言，没有删除测试或放宽未知状态保护。

## 6. Risks / Unverified / Decision Required

- 实测 Windows / Git 2.53.0.windows.2；没有其他 OS、真实用户数据升级、付费 Provider 或浏览器手工 E2E。
- EOL 等价适配有明确条件与 1 MiB 快照预算；不是通用编码 / filter / ident 解释器。无法证明时拒绝。
- 整树读取增加 I/O；没有大型真实用户仓库性能基准。
- 没有验证恶意外部进程持续改写文件 / Git 配置、OS 崩溃、断电或磁盘损坏；不是宿主文件系统沙箱。
- SQLite 重启为新上下文 / 连接回读；不声称 OS 崩溃恢复。ROLLING_BACK 所有权 / 停止判据继续留待 Recovery。
- 无新的阻塞性业务 Decision Required。本轮完成后停止，等待独立复审。

m5-review2-*.log、Surefire XML、Fixture、数据库与构建产物不提交。
本报告与实现同一提交交付；最终 Commit SHA 见交付回复及本 Worktree git log -1。
