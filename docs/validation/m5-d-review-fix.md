# M5-D 独立审查 Findings M5-005 / M5-006 修复

日期：2026-10-14（Asia/Shanghai）。起点：`f4e4c799f881010692914b6acf297f5638dd8539`。
Worktree：`C:\Users\28226\.codex\worktrees\m5-lab\DelveForge`；分支 `feature/m5-lab`；开始时干净。
范围：仅修复两项 Finding 及属性来源守卫的直接依赖。最终提交 SHA 见交付回复及 `git log -1`。
实现者自验 Gate：PASS；独立复审待执行。

## 1. Contract / Isolation

读取当前 AGENTS、PRODUCT、ARCHITECTURE、DOMAIN_MODEL §8.13、ROADMAP、ADR-0001、M5 修复报告及
M5-D 实现报告，并检查实际 Git Adapter、UseCase、API 与测试。明确执行失败与 UNKNOWN 分类、原始 baseline、
独立 User Rollback 授权、Repair 历史、精确内容证明、SQLite CAS / 检查点及未决重启规则均不改变。
没有修改 Verification 架构、数据库 schema、Domain 状态、REST 协议或前端产品行为。

任务自有短临时根：`C:\dm5fix-1015-b6d2`（名称不是执行日期）。Git 与 SQLite Fixture 由本次测试新建，
使用 Wrapper / 离线缓存及 fake AI。没有付费 Provider、真实业务数据、历史 WorkingCopy 或 Smoke 操作。
没有修改 main、其他 Worktree、Release Tag 或原始 Software Asset；不 Push、不合并、不清理旧临时根。

## 2. M5-005 — 恢复期间的 Git 属性来源变化

### 红色复现（实际执行）

基线 tracked `rules.txt` 包含 `*.txt filter=unset` 或 `filter=unspecified`；Candidate / 未发布补丁将其变为
无 filter 的 inert 规则。配置 `core.attributesFile` 指向该 tracked 文件；测试 filter 只向本夹具
`.git/filter-ran` 写 marker。clean 使用普通透传；process 启动即留下 marker 并关闭，故意不完成协议。

恢复前，两侧 `--attr-source=<revision>` 查询都仍读当前 inert 外部文件。恢复改变这个文件后，下一条
Git 操作重新加载外部属性，成为不同的转换上下文。此复现没有 mock Git 的属性、checkout 或内容证明。

在未修改生产代码的基线执行新增 8 项：两个 driver × clean/process × User/Automatic，全部失败。
Automatic 的 4 项均实际产生 marker：UNPUBLISHED_RESTORE 改变 rules.txt 后，CHECKOUT 启动 filter。
User 的 4 项预检没有拒绝，已改变 HEAD / tracked 内容后才在 POSTCHECK 拒绝；本组没有观察到 marker。
两种现象分别记录，不能把 User 的事后拒绝当作危险操作前的保护。证据：`m5d-review-git-red.log`。

### 根因与修复

`--attr-source` 固定仓库内属性文件的对象来源，不冻结系统、全局、`core.attributesFile` 和
`.git/info/attributes`。原检查只判断当前生效的属性值，没有证明外部来源在全部恢复命令期间稳定。
官方实现的属性栈同时加载这些来源，参见 [Git v2.53.0 attr.c](https://github.com/git/git/blob/v2.53.0/attr.c)
及 [gitattributes](https://git-scm.com/docs/gitattributes)。

- `requireRepositoryAttributeSources` 在共享物理检查及 `restoreBaseline` 的 ATTRIBUTE_SOURCES 阶段，
  在任何写入前拒绝有效的非空 core.attributesFile，包括相对 / 绝对 / include / 空白路径值。
  配置只读查询使用 NUL 分隔，不把 trim 后为空、unset 或 unspecified 当作安全路径。
- `.git/info/attributes` 不属于固定 revision，当前版本即使 inert 也拒绝；info 目录的链接 / 路径别名
  或读取不确定也阻塞。不会删除这些文件或改写用户配置。
- 每条共享有界 Git 命令固定 `-c core.attributesFile=`，避免恢复 tracked include 配置后重新启用来源。
  清除外部 GIT_* 后设置内部 `GIT_ATTR_NOSYSTEM=1`，排除默认全局 / 系统属性。
  实际子进程的默认 XDG 属性夹具与内部环境标志分别验证；未改写宿主系统属性文件。
- 固定版本 `.gitattributes` 继续检查；另读取其 immutable blob，保守拒绝 dormant filter / ident /
  working-tree-encoding 定义与自定义宏，防止中间属性视图激活未被单版本查询发现的转换。
- 原生 text / eol / autocrlf 的 LF/CRLF 支持、hash-object --no-filters 全树字节证明、index/mode/stage、
  精确首次 baseline、非强制 checkout、额外用户文件保留规则不变。当前技术范围不支持外部属性文件冻结
  或自定义宏；依赖它们时 fail closed，不能自动回退为未证明的转换。

M5-C User Rollback 与 M5-D Automatic Rollback 共同调用 restoreBaseline；修复在共享入口，
也作用于 M4 Execute / M5 Repair 的 requireTrustedState。授权前拒绝不保存新授权、不调用 AI；
授权后通过现有错误处理保存 RECOVERY_REQUIRED，不产生虚假成功。数据库不可写仍保留最后完整检查点。

主要文件：GitWorkspaceAdapter.java、WorkspaceCommandRunner.java、GitRollbackContentIntegrationTest.java、
GitEvolutionExecutionIntegrationTest.java、WorkspaceCommandRunnerTest.java、EvolutionStepExecutionApiIntegrationTest.java。
ARCHITECTURE 同步当前技术支持范围，未引入新的长期业务语义或依赖。

## 3. M5-006 — UNKNOWN 注入的目标阶段

原测试对所有 mutationGitResult 无条件返回未知退出码，第一次调用在 EXPECTED_TREE，实际没有到 APPLY/PUBLISH。
此次仅调整测试：私有预期 tree、apply --check 及前序实际命令正常执行，只有指定 APPLY 或 update-ref/PUBLISH
返回未知退出结果。两个阶段 × 137 / 139 / -1073741819 / 无 fatal 标记的 128，共 8 项。

显式断言预检完成、真实 APPLY 到达、目标注入命中。APPLY 场景实际应用补丁后模拟结果丢失；PUBLISH 场景
保留已应用但未发布的树。断言异常不是 ConfirmedExecutionFailureException，Step 保持 EXECUTING，
无失败 / Candidate / 自动恢复结果，HEAD 和 lastVerifiedRevision 保持原值，物理修改保留。
API 的 UNKNOWN 故障回归另外确认没有 restoreBaseline 调用；没有把 Adapter 层断言冒充完整 UseCase 证据。
没有更改生产代码的确认失败分类，也没有自动重新提交执行、Repair 或 Rollback。

## 4. 实际命令与结果

所有 backend 命令前设置本任务 TEMP/TMP、MAVEN_OPTS=-Xmx512m，并清除测试子进程中的 Provider 凭据别名。
以下均从实验 Worktree 根目录运行，前端命令从 frontend 运行。

```powershell
.\mvnw.cmd -o '-Djava.io.tmpdir=C:\dm5fix-1015-b6d2' -pl backend/delveforge-infrastructure -am '-Dtest=GitRollbackContentIntegrationTest#mutableExternalAttributeSourceBlocksBeforeAuthorizationAndUserRestore,GitEvolutionExecutionIntegrationTest#mutableExternalAttributeSourceCannotRunDuringAutomaticRestore' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

红色复现：8 tests / 8 failures / 0 errors；BUILD FAILURE，63 s，21:20:00+08 完成。
首次编写新增测试时发生 API 方法签名及异常类型编译错误，已修正。第一次修复回归剩余 4 个失败来自
Windows 合法 CRLF 与测试硬编码 LF 的差异；改成完整比较操作前实际内容，而非归一化或放宽守卫。

```powershell
.\mvnw.cmd -o '-Djava.io.tmpdir=C:\dm5fix-1015-b6d2' -pl backend/delveforge-app -am '-Dtest=GitRollbackContentIntegrationTest,GitEvolutionExecutionIntegrationTest#mutableExternalAttributeSourceCannotRunDuringAutomaticRestore+unrecognizedProcessExitCannotBecomeConfirmedExecutionFailure,WorkspaceCommandRunnerTest,EvolutionStepExecutionApiIntegrationTest#externalAttributesAreRejectedBeforeExecutionRepairOrRollbackAuthorization+m5dRealExecutionRejectionPreservesEvidenceHistoryAndHonorsExactRollback+rollbackFailureOrUnknownBlocksWithoutDeletingUserFiles+m5dUnknownMutationNeverStartsAutomaticRollback' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

针对性回归：81 项，0 failure/error/skipped；BUILD SUCCESS，20:05 min，22:09:24+08 完成。
其中 Infrastructure 50 项（Git Execution 12、Git Rollback 33、Runner 5），API/SQLite 31 项。
证据：`m5d-review-focused.log`。此轮在最终空白路径 / 默认全局来源 / 合法路径模式增补前执行；
最终代码状态由以下来源补充检查与完整 verify 另行验证，不混合成单次测试计数。

```powershell
.\mvnw.cmd -o '-Djava.io.tmpdir=C:\dm5fix-1015-b6d2' -pl backend/delveforge-infrastructure -am '-Dtest=GitRollbackContentIntegrationTest#externalAttributeAliasesAreRejectedWithoutChangingConfiguration+defaultGlobalAttributesAreExcludedFromTheActualControlledGitProcesses' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

最终来源补充验证：6 项，0 failure/error/skipped；BUILD SUCCESS，30.594 s，21:56:27+08 完成。
证据：`m5d-review-source-check.log`。其版本包括 NUL 解析、空白路径与默认全局属性子进程测试。

```powershell
.\mvnw.cmd -o '-Djava.io.tmpdir=C:\dm5fix-1015-b6d2' verify
pnpm test
pnpm typecheck
pnpm build
```

最终源码完整 verify：BUILD SUCCESS / exit 0，22:11:17+08 开始，23:22:04+08 完成，01:10 h。
所有模块及 Spring Boot repackage 均成功。汇总 143 份本轮新生成 XML，1,661 tests / 0 failure / 0 error /
0 skipped；逐文件检查修改时间不早于本轮起点，没有引用旧 XML 补足数量。证据：`m5d-review-verify.log`。

| Module | Tests | XML | Failure / Error / Skipped |
| --- | ---: | ---: | --- |
| Domain | 231 | 12 | 0 / 0 / 0 |
| Application | 777 | 65 | 0 / 0 / 0 |
| Infrastructure | 346 | 37 | 0 / 0 / 0 |
| App | 307 | 29 | 0 / 0 / 0 |
| Total | 1661 | 143 | 0 / 0 / 0 |

最终 Git Execution 39 项（387.383 s）、Git Rollback Content 37 项（162.361 s）、Runner 5 项（0.815 s）、
Execution API 106 项（3404.237 s）全部通过。M0–M4、M5-A/B/C/D 的已有确定性自动回归 PASS：
M4 Verified Success、资产权限与 Step 确认、WorkingCopy 隔离、可信 Revision、Unsupported Criteria 预检、
Verification FAIL / Repair 历史、精确首次 baseline、额外文件保留、未知与 in-flight 阻塞、重复 / 并发请求、
SQLite 检查点故障、历史迁移和新 ApplicationContext 的状态回读均包含在本轮全量。
没有删除、禁用旧测试或放宽旧安全断言；本结论不包含付费 Provider / 历史 Smoke 的重新执行。


前端：161 tests / 10 files，0 failure；10.79 s，21:50:20+08 开始。typecheck 与 build exit 0；
56 modules，511 ms。证据：`m5d-review-frontend.log`。前端源码未改动，未做手工浏览器 E2E。

## 5. 风险 / 未验证 / Decision Required

- 实测 Windows、Git 2.53.0.windows.2、Java 21；未实测其他 OS / Git 版本、真实业务库升级或付费 Provider。
- 真实 Git 与 SQLite 故障注入 / 新上下文回读不是 JVM 被强杀、系统断电或磁盘损坏实验，不能作 OS 崩溃保证。
- 当前保守技术支持范围拒绝非空配置属性文件、info/attributes、自定义宏与 dormant 外部转换规则。
  全局 / 系统属性不参与受控转换；原生仓库 LF/CRLF 保留。没有实现外部属性来源冻结或通用转换支持。
- 未验证持续恶意并发改写 .git/info / tracked 文件、宿主文件系统或 Git 可执行程序；这不是 OS 沙箱。
  原有实际内容 / index / 路径 / 额外文件守卫与检查点边界继续生效。
- 无新增阻塞性业务 Decision Required。重启后未决执行 / Rollback 的停止判据和所有权仍待后续 Recovery
  Slice 裁定，当前继续保留原检查点、阻塞、不重放；不扩展本轮。

日志、XML、临时目录和数据库不提交。完成后只在 feature/m5-lab 本地 Commit，保持干净，等待独立复审。
