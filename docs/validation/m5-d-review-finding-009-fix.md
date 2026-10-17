# M5-D 独立复审修复：M5-009 UTF-8 BOM 属性解析绕过

## 1. 范围与基线

- Worktree：`C:\Users\28226\.codex\worktrees\m5-lab\DelveForge`。
- 分支：`feature/m5-lab`；起点 `5f778afa9fc904f6f8d1eb3511fd4633c2cb1ccd`，开始时工作树干净。
- 仅修复 BOM 及同一属性解析安全边界的直接问题，不改 Verification 架构、不实现 Retry / Recovery。
- 依据当前 AGENTS、PRODUCT、ARCHITECTURE、DOMAIN_MODEL §8.13、ROADMAP 和 ADR-0001；
  保留独立授权、原始 baseline、真实字节证明、额外文件保护、历史及未知检查点，不新增领域状态或 schema。
- 本轮 Git / API / SQLite 测试均使用任务自有目录。进程临时根 `C:\dm5b-1015-f42c` 在确认不存在后创建；
  API 的 UUID 目录和 state.db 位于实验 Worktree 的 ignored target，Provider 使用测试替身。
  未操作 main、其他 Worktree、真实业务数据库、历史 WorkingCopy、原始软件资产或旧 Smoke 证据；不 Push、不合并。

## 2. 根因及真实 Git 红色复现

真实工具为 Git `2.53.0.windows.2`、Java `21.0.12.1`。测试先在原生产实现上运行。
Git 可以将首行 UTF-8 BOM 视作语法前缀，旧检查器却把 U+FEFF 留在首 pattern。
尤其是 BOM + 引号 pattern 后紧贴 `filter=unset`，会使旧扫描器把整个首字段当作普通 pattern，漏掉 filter token；
BOM + `[attr]custom` 也会躲过首字段宏识别。不能因为最终状态阻塞就声称 filter 从未执行。

| 原生产代码上的运行 | 实际结果 | 证据与边界 |
| --- | --- | --- |
| `m5d-009-red.log` | 34 tests，26 failures，0 errors，0 skipped | 首轮根/嵌套 BOM 规则及恢复中间状态；6 个嵌套自动恢复夹具因 Diff 排序提前拒绝，不能计为安全证明 |
| `m5d-009-red-final.log` | 45 tests，42 failures，0 errors，0 skipped | 修正夹具 Diff 排序后重跑；24 个恢复中间状态全部暴露旧拒绝/写入边界问题，另含文本及属性值分隔用例 |
| `m5d-009-proposal-red.log` | 17 tests，17 failures，0 errors，0 skipped | 12 个初次 Execution / Repair 新增 BOM 属性提案和 5 种原始编码；根目录 clean/process 在初次和 Repair 共 4 个用例实际写出 `filter-ran` marker |

最后一组的真实 marker 是本轮执行副作用证据（用例 1、2、7、8）。其他 smudge、嵌套或恢复中间状态
没有观察到 marker，不能把其错误接受/写入一概描述为执行了 filter。直接规则中旧代码已拒绝的控制组也保留。

红色测试使用的 filter 命令只向 Fixture 自有 `.git/filter-ran` 写 marker，或写 marker 后退出；
没有任意第三方代码、网络或付费 Provider。根/嵌套 `git check-attr` 独立验证 Git 的实际解释：
根宏确实生效；Git 明确不允许嵌套文件定义宏，测试断言该诊断，未声称嵌套宏已经激活。
恢复中间状态夹具的 Candidate 属性是惰性注释，baseline 的父/子规则在端点视图无生效 filter；
仍要求在 `BASELINE_ATTRIBUTES` 阶段拒绝整个 baseline，并检查原 HEAD、物理内容、属性和 marker。

红色命令（从 Worktree 根；以下均离线 Maven，设置本轮 TEMP/TMP、MAVEN_OPTS=-Xmx512m 并清除 Provider 凭据别名）：

```powershell
.\mvnw.cmd -o '-Djava.io.tmpdir=C:\dm5b-1015-f42c' -pl backend/delveforge-infrastructure -am '-Dtest=GitAttributeSafetyIntegrationTest#bomDefinitionsAreRejectedBySharedGuardAndUserRollbackBeforeMutation+bomRulesCannotExecuteFiltersInIntermediateUserOrAutomaticRestoration' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -o '-Djava.io.tmpdir=C:\dm5b-1015-f42c' -pl backend/delveforge-infrastructure -am '-Dtest=GitAttributeSafetyIntegrationTest#bomDefinitionsAreRejectedBySharedGuardAndUserRollbackBeforeMutation+bomRulesCannotExecuteFiltersInIntermediateUserOrAutomaticRestoration+ambiguousAttributeTextFailsClosedEvenWithoutAnActiveConversion+conversionNameCannotBeHiddenByNonGitLineSeparatorsInItsValue' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -o '-Djava.io.tmpdir=C:\dm5b-1015-f42c' -pl backend/delveforge-infrastructure -am '-Dtest=GitEvolutionExecutionIntegrationTest#bomProposalIsRejectedBeforeAnyActualApplyOrPublication,GitAttributeSafetyIntegrationTest#nonUtf8AttributeBlobsAreRejectedWithoutConversion' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

## 3. 最小修复与有限安全检查

`GitWorkspaceAdapter.requireSupportedAttributes` 对不可变属性 blob 先执行 `requireSafeAttributeText`，
再运行原首字段/属性 token 安全检查。采用保守拒绝，不实现 BOM 兼容解释器。

| 边界 | 当前处理与测试 |
| --- | --- |
| BOM | U+FEFF 在任何位置均拒绝，包括看似注释、原生 text/eol、直接 filter、引号紧邻 token、真实宏；根/嵌套均覆盖 |
| 编码 | U+FFFD、NUL 及其他 C0/DEL 拒绝；真实 UTF-16LE/BE、UTF-32LE/BE 与非法 UTF-8 blob 回归。合法 UTF-8 编码的 U+FFFD 同样保守拒绝 |
| 行/字段分隔 | 保留 LF、CRLF、ASCII TAB/空格；裸 CR 与不支持控制字符拒绝，避免 Java lines 与 Git 行读取差异 |
| 属性值 | 按首个 `=` 取属性名，移除单个 `-` / `!` 前缀后拒绝 filter/ident/working-tree-encoding；不再使用值上的正则 dot。真实 U+0085/U+2028/U+2029 值回归防止 dormant 规则漏检 |
| 引号/转义/宏 | 沿用首 pattern 的 C 引号与三位八进制处理，未闭合/未知转义失败关闭；继续检查闭引号后紧贴的 token。真实宏拒绝，`docs/[attr]*.txt text eol=lf` 等合法 glob 仍接受 |
| 属性来源 | 沿用 M5-005/007 的外部来源、大小写、tree/index/物理路径守卫；不新增外部转换支持 |
| 实际内容 | 保留 `hash-object --no-filters` 与本地 LF/CRLF 等价证明，未退回 stat/status/index 缓存判断 |

上述首字段语法检查参考 [Git v2.53.0 attr.c](https://github.com/git/git/blob/v2.53.0/attr.c)；
BOM 的实际行为由本轮真实 Git Fixture 证明。本轮是有界检查，不宣称实现完整 Git 属性解析器。

新增 `requireSafeTreeAttributes` 复用整树检查：

1. User / Automatic Rollback 均在 `BASELINE_ATTRIBUTES` 检查原始 baseline 的全部属性，先于 restore / checkout。
2. `applyCandidate` 在调用私有 index 的 `write-tree` 后增加 `EXPECTED_ATTRIBUTES`，检查整个预期 tree，
   先于真实 `apply --check --index`、Apply 和 `update-ref`。因此新增的 BOM 属性也不能先写入再拒绝。
3. 初次 Execution / Repair 的新增提案测试明确断言已构造预期 tree，而真实 Apply 检查、Apply、发布均未到达；
   HEAD、文件、可信 Revision、原始仓库保持不变，clean/process/smudge marker 不存在。
4. 此类拒绝使用既有 WorkingCopyBasisChangedException，保留已保存的 EXECUTING 未决事实；
   不伪造 Confirmed Execution Failure，不触发自动回滚。恢复阶段的拒绝沿用 RECOVERY_REQUIRED / 未决检查点规则。

未改 Domain / Application 生产代码、持久化格式、REST/UI 协议、依赖或模块边界。
私有预期 tree 是本次提案检查对象，不是 Candidate / Verified State；可能产生未引用 Git 对象，但不发布 Revision。

## 4. API / SQLite 与事实边界

新增真实 API 场景包括：旧 READY 检查点中已有 BOM、授权前物理属性变化拒绝、Repair 授权后变化、
初次/Repair 提案新增 BOM、User / Automatic Rollback 授权后变化、所有最终 SQLite 检查点失败。
检查独立授权与此前失败历史保留、可信 Revision 不推进、无自动重放/重试、重开 SQLite 读取一致。
最终结果不能落盘时保持 ROLLING_BACK，不擅自改判成功或 RECOVERY_REQUIRED。

Port 层 Repair Fixture 重建的是既有失败事实，并非声称首次技术验证实际失败；目标变更仍使用真实 Git。
API 层使用真实失败 Candidate；另一个旧 READY 场景仅在本任务数据库种入历史确认事实，以验证升级后共享守卫，
没有在生产代码跳过确认。授权前物理 BOM 变化同时受既有真实内容守卫保护，不能仅凭该场景声称测试到了 immutable BOM 解析；
不可变 blob 与恢复中间状态由独立真实 Git 用例覆盖。

## 5. 最终验证

首轮绿色尝试 `m5d-009-git-green.log` 实际为 103 tests / 12 failures / 0 errors /
0 skipped：91 个属性用例通过，12 个新提案用例已被新守卫拒绝，但测试错误期待 WorkspaceException，
实际为既有 WorkingCopyBasisChangedException。修正为精确业务异常断言并补充目标阶段断言，未放宽安全断言。

随后从根执行的针对性命令：

```powershell
.\mvnw.cmd -o '-Djava.io.tmpdir=C:\dm5b-1015-f42c' -pl backend/delveforge-app -am '-Dtest=GitAttributeSafetyIntegrationTest,GitEvolutionExecutionIntegrationTest#bomProposalIsRejectedBeforeAnyActualApplyOrPublication,EvolutionStepExecutionApiIntegrationTest#bomInHistoricalReadyCheckpointBlocksExecutionBeforeProvider+bomPhysicalChangeBlocksBeforeExecutionRepairOrRollbackAuthorization+repairBomAfterGrantKeepsExecutingCheckpoint+bomProposalRetainsAuthorizedCheckpointBeforeActualMutation+rollbackSqliteFailureNeverExposesPartialCheckpointOrFalseSuccess' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

实际 BUILD SUCCESS / exit 0，2026-10-15 17:02:42+08 完成，11:02 min：
91 项属性 + 12 项初次/Repair 提案 + 11 项 API/SQLite，共 114 tests / 0 failures / 0 errors / 0 skipped。
证据 `m5d-009-targeted-green.log`。故障触发器和重开 SQLite 的断言均通过。

最终完整后端命令（从根执行，仅此一轮完整 verify，没有终止、重启或并行启动另一轮）：

```powershell
.\mvnw.cmd -o '-Djava.io.tmpdir=C:\dm5b-1015-f42c' verify
```

本轮 **BUILD SUCCESS / exit 0**，包含全部模块 JAR 与 Spring Boot repackage。
开始 `2026-10-15T17:02:55.2522186+08:00`，结束 `2026-10-15T18:18:02.3143776+08:00`，
Maven 总计 `01:15 h`。证据为 `m5d-009-full-verify.log` 及任务临时根中的 verify-start/end/exit 标记。
逐份解析四模块全部 144 份 Surefire XML，确认每份 mtime 均不早于本轮开始，未发现旧报告、失败、错误或跳过项：

| 模块 | 报告数 | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| Domain | 12 | 231 | 0 | 0 | 0 |
| Application | 65 | 777 | 0 | 0 | 0 |
| Infrastructure | 38 | 449 | 0 | 0 | 0 |
| App | 29 | 322 | 0 | 0 | 0 |
| 合计 | 144 | 1,779 | 0 | 0 | 0 |

完整运行子集：GitAttributeSafetyIntegrationTest 91 项（290.820 s）、
GitEvolutionExecutionIntegrationTest 51 项（418.333 s）、GitRollbackContentIntegrationTest 37 项（140.815 s）、
EvolutionStepExecutionApiIntegrationTest 121 项（3,423.611 s）。上述子集与 114 项局部运行均不另行累加。
M0–M4、M5-A/B/C/D 现有自动化回归通过，覆盖 Verified Success、资产权限与独立授权、
Step / WorkingCopy 绑定、原始 baseline 与 Candidate / lastVerifiedRevision、UNSUPPORTED 预检、
失败历史、并发/重复请求、UNKNOWN / RECOVERY_REQUIRED 阻塞、精确恢复与额外文件保护、
SQLite 检查点及历史兼容、重启事实回读。没有用真实用户数据库或付费 Provider 验证。

API 长测试执行期间曾做只读诊断：18:01–18:03 两次进程/线程采样显示测试 JVM 与 Git 子进程仍在推进，
日志增长，未报告 Java 死锁；App 的长测试类 XML 当时尚未更新，不能据此计入旧的 11 项局部结果。
该类最终于 18:16:05 生成本轮 121 项全通过报告，完整 verify 于 18:18:02 正常结束。
没有因执行时间长而修改断言、放宽安全约束、终止或重启测试。

前端在 frontend 实际执行 `pnpm test`、`pnpm typecheck`、`pnpm build`：
10 files / 161 tests 全通过；类型检查通过；生产构建 56 modules，通过。
本轮 2026-10-15 15:52 开始，证据为 `m5d-009-frontend-test.log`、
`m5d-009-frontend-typecheck.log`、`m5d-009-frontend-build.log`。仓库无 lint script，未虚构该检查。

## 6. 剩余风险与 Decision Required

- 未测试电源故障、OS/内核崩溃、Linux/macOS、SMB 或非本地文件系统；阶段注入不等于任意崩溃证明。
- 没有与任意外部写者之间的 OS 级原子隔离；保留既有阶段复核与 UNKNOWN 阻塞，不宣称消除所有 TOCTOU 窗口。
- BOM、解码替换字符和不支持的控制字符会使仓库保守拒绝，即使其规则看似无害；这是当前技术支持范围，
  未增加新的安全转换能力。未用真实用户库或付费 Provider 验证。
- 重启残留 EXECUTING / ROLLING_BACK 的调用所有权与终止判定仍留给后续 Recovery Slice，保持检查点并禁止重放。
- 本轮没有新增业务 Decision Required，不自动开始下一 Slice。
- Git/API 集成测试执行效率按用户要求留到完整 M5 结束后单独评估；本轮未进行性能重构。

## 7. 关键文件

- `backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/workspace/GitWorkspaceAdapter.java`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/workspace/GitAttributeSafetyIntegrationTest.java`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/workspace/GitEvolutionExecutionIntegrationTest.java`
- `backend/delveforge-app/src/test/java/com/ayywl/delveforge/app/api/evolutionplan/EvolutionStepExecutionApiIntegrationTest.java`
- `docs/ARCHITECTURE.md` 与本报告。

本报告随最终本地修复 Commit 提交；精确 SHA 由最终交付与 Git 记录给出，避免自引用 SHA。
