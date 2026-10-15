# M5-D 独立复审 Findings M5-007 / M5-008 修复

日期：2026-10-15（Asia/Shanghai）。基线：`bbd85e2f776bf35e62a7a20b9f6aff4afb66982d`。
Worktree：`C:\Users\28226\.codex\worktrees\m5-lab\DelveForge`；分支：`feature/m5-lab`；开始时干净。
最终 Commit SHA 见交付回复及包含本报告的 `git log -1`。
实现者自验 Gate：PASS；独立复审待执行，完整 M5 不因此标为完成。

## 1. 契约与隔离

已读取 AGENTS、PRODUCT、ARCHITECTURE、DOMAIN_MODEL §8.13、ROADMAP、ADR-0001、M5-A/B/C/D
及前轮修复报告，并核对 Git Adapter、授权编排、SQLite/API 与相关测试的实际代码。
仅改变属性来源安全检查及其直接测试、文档；不改变 Domain 状态、授权含义、数据库 schema、API 协议、
Verification 架构、UNKNOWN 分类或重启未决检查点规则，不开始 Retry / Recovery。

全部写入性验证使用任务新建短临时根 `C:\dm5r-1015-7a3c`、UUID Git Fixture 及测试专有 SQLite。
仅执行当前项目与受控夹具；使用离线 Maven 缓存和 fake AI，不调用真实付费 Provider。
不修改 main、Release Tag、原始 Software Asset、真实业务数据库、其他 Worktree、历史 WorkingCopy 或 Smoke
证据；不清理其他任务目录，不 Push、不合并。

## 2. M5-007：属性文件大小写绕过

### 红色复现（修改生产代码前实际执行）

真实 Windows Git Fixture 使用 `.GITATTRIBUTES` 和 `src/.GitAttributes`，并通过 `Files.isSameFile`
确认规范名 `.gitattributes` 实际命中同一物理文件。基线与 Candidate / 未发布 tree 都保留该规则，
在建立可恢复的真实 Git 状态后才配置测试 filter，避免夹具准备提前执行它。

矩阵：根 / 嵌套 × unset / unspecified × clean / process × User / Automatic，共 16 项。
clean 只透传并写本 Fixture 的 `.git/filter-ran`；process 写 marker 后退出，故意不完成协议。
Automatic 使用真实 update-ref 的 HEAD.lock 拒绝产生 PUBLISH 失败，再以真实 tree/index/blob 构造失败事实；
不会依赖旧缺陷的共享预检来创建新的执行授权，也不把未发布 tree 当作 Candidate。

16 项均产生 marker，旧守卫未在危险操作前拒绝。clean 场景错误恢复并返回成功；process 场景虽最终异常，
但 filter 已经执行。User 的 HEAD 与 Automatic 的物理文件副作用分别断言，不能把事后异常当作安全。

### 根因与修复

旧扫描只识别完全等于 `.gitattributes` 或以 `/.gitattributes` 结尾的对象路径。
Git 对象路径与 Windows 文件系统的大小写解释不同，固定 `--attr-source` 不能证明 checkout 的物理属性来源一致。

- `requireCanonicalAttributePaths` 在共享整树内容证明中检查固定 revision 的所有路径；真实 index 必须先与
  revision 的 mode / object / stage / 路径完全相等，所以 index 别名同样不能通过。
- 对 root 及所有 tracked 路径祖先目录枚举真实目录项。非规范大小写的属性文件、非普通文件或不确定目录
  身份保守拒绝，不用 Windows Path.equals、stat、status 或 diff 推断物理文件名。
- 原始 baseline 的完整路径在 BASELINE_ATTRIBUTES 同样检查；即使 Candidate 删除了属性文件，也在
  restore / checkout 之前拒绝目标别名。Automatic 的完整未发布 tree 继续经过同一共享内容证明。
- 不增加大小写属性文件兼容转换。无法统一证明 Git 对象、index 与物理路径解释时 fail closed。
  不删除、重命名或修复用户文件，不执行外部 filter，不回退为 Git stat/status 缓存判断。
- 既有外部属性来源限制、原生 LF/CRLF、无 filter 的整树实际字节证明、精确原始 baseline、额外文件保留、
  非强制 checkout 与 UNKNOWN / RECOVERY_REQUIRED 规则不变。

新增 Git 回归还覆盖：根与嵌套多种混合大小写；规范 tree/index 下的物理 case-only 改名；规范 tree/物理文件
下的 index 别名；仅 baseline 存在的别名。API 使用真实托管 Git 与隔离 SQLite，覆盖 Execute / Repair /
User Rollback 授权前拒绝（不保存授权、不调用 AI），以及两种 Rollback 授权后阻塞、可信 revision / 失败历史
保留与重启读回。16 项真实 Windows 副作用复现只在 Windows 运行；本机未跳过。

## 3. M5-008：合法 glob 与真实宏

### 红色复现

同一未修复基线的新增测试中，`docs/[attr]*.txt text eol=lf`、带引号的同一 pattern、单独 `[attr]` glob
共 3 项被错误拒绝。真实 Git check-attr 对前两项返回 eol=lf。
另一个反例 `"\133attr]custom" text eol=lf` 被 Git C 引号解码为真实宏，旧 contains 检查却放行。
普通、前导空白、双引号真实宏 3 项旧负测已通过。

合计首轮红色测试：23 tests / 17 failures / 3 errors / 0 skipped；BUILD FAILURE，2026-10-15
11:36:25+08 完成，Maven 总计 01:45 min。证据：`m5d-review3-red.log`（未提交的测试日志）。
失败不是 mock Git 结果；marker 是本任务 test filter 实际进程的副作用。

### 修复

按 [Git v2.53.0 attr.c](https://github.com/git/git/blob/v2.53.0/attr.c) 的 parse_attr_line 首字段语法，
读取 Git 空白分隔的 pattern，或先解码 C 引号（含三位八进制）再识别：只有首 pattern 以 `[attr]` 开头且
后缀非空时才按宏拒绝。中间字符组与恰好 `[attr]` 不再误判。无法安全解析的引号 / 转义失败关闭。
继续检查首字段后全部属性 token，包括引号结束后紧邻的属性，防止新解析器漏掉 dormant filter / ident /
working-tree-encoding。这里只判断安全支持范围，不替代 Git glob 匹配，也不引入新的转换能力。

正测包括带引号、无间隔属性 token 的合法 glob 与实际精确恢复；负测包括普通、空白、引号、八进制真实宏，
以及带空格 / 转义的 pattern 和不支持的转换定义。真实宏仍保守拒绝。

## 4. 实际验证

以下后端命令从实验 Worktree 根运行；前端命令从 frontend 运行。
每次后端调用设置本任务 TEMP/TMP、MAVEN_OPTS=-Xmx512m，并清除 Provider 凭据环境别名。
实际工具：Git 2.53.0.windows.2、Java 21.0.12.1、pnpm 12.9.1。

```powershell
.\mvnw.cmd -o '-Djava.io.tmpdir=C:\dm5r-1015-7a3c' -pl backend/delveforge-infrastructure -am '-Dtest=GitAttributeSafetyIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -o '-Djava.io.tmpdir=C:\dm5r-1015-7a3c' -pl backend/delveforge-infrastructure -am '-Dtest=GitAttributeSafetyIntegrationTest,GitRollbackContentIntegrationTest,GitEvolutionExecutionIntegrationTest,WorkspaceCommandRunnerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -o '-Djava.io.tmpdir=C:\dm5r-1015-7a3c' verify
pnpm test
pnpm typecheck
pnpm build
```

首条为上述红色复现。第二条实际通过 118 tests / 0 failures / 0 errors / 0 skipped，
2026-10-15 11:51:54+08 完成，09:31 min；四类分别为新属性测试 37、Git Execution 39、
Git Rollback Content 37、Command Runner 5。随后新增的 4 项 baseline-only 别名测试及 API 场景由最终
完整 verify 验证，不能计入这次 118 项结果。证据：`m5d-review3-git-green.log`。
第一次完整 verify 中，后补的 4 项 baseline-only 测试在准备 Candidate 的 git rm 阶段被真实 Git
以 local modifications 拒绝，没有进入目标守卫。改为显式删除本 Fixture 自有的 tracked 文件并用
update-index --force-remove 建立该测试 Candidate；这不是生产回滚清理或放宽断言。
同轮另有既有 VerificationOutcomeTest.realTimeoutDoesNotReturnFailedFacts 在 JUnit 清理临时根时
出现 Windows FileSystemException（目录仍被占用）；不是将 UNKNOWN 判为 FAILED 的断言失败，未改该测试
或超时生产逻辑。首轮完整运行于 12:03:34+08 结束，11:26 min，Infrastructure 387 tests / 0 failures /
5 errors / 0 skipped，App 未运行。日志 `m5d-review3-full-verify.log` 保留，不作为通过证据。
最终从仓库根重新运行完整 verify，**BUILD SUCCESS / exit 0**，包含 JAR 与 Spring Boot repackage。
开始 2026-10-15 12:03:49+08，结束 13:13:55+08，Maven 显示总计 01:10 h。
证据：`m5d-review3-full-verify-final.log` 及四模块的 Surefire XML；逐份核对全部 144 份报告的
mtime 不早于本轮开始时间，没有使用首轮或旧提交的 XML 充数。

| 模块 | 报告数 | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| Domain | 12 | 231 | 0 | 0 | 0 |
| Application | 65 | 777 | 0 | 0 | 0 |
| Infrastructure | 38 | 387 | 0 | 0 | 0 |
| App | 29 | 312 | 0 | 0 | 0 |
| 合计 | 144 | 1,707 | 0 | 0 | 0 |

最终新属性类 41 项全通过（包含后补的 4 项 baseline-only 场景）；Step Execution API 类 111 项全通过，
耗时 3,352 s。上述数字都是最终完整运行的子集，不与 118 项局部运行相加。
原有 M0–M4、M5-A/B/C/D 回归通过，包括 Verified Success、权限与独立授权、原始 baseline / Candidate /
lastVerifiedRevision、全部标准与 UNSUPPORTED 预检、Repair 历史、并发 / 重复请求、UNKNOWN 不自动恢复、
额外文件保留、精确内容恢复、SQLite 检查点及历史兼容、重启事实回读。未用真实用户库验证迁移。

前端已实际通过：10 files / 161 tests / 0 failures；typecheck 与 build exit 0，56 modules，2026-10-15
11:44 开始。没有 lint script，未虚构该检查。

## 5. 风险、事实边界与 Decision Required

- 以上红绿测试为受控真实 Git 与 SQLite/API 故障验证，不是电源故障、内核崩溃或任意文件系统的行为证明。
  未测试 Linux/macOS、Windows 目录启用大小写敏感模式、SMB / 非本地文件系统的实际 checkout 行为。
- 首轮既有超时测试的 Windows 临时目录占用在最终完整运行未复现；本轮未改其生产或测试逻辑，
  也未确定占用来源与底层释放时序，不宣称该既有清理问题已根治。
- 非规范大小写属性文件及无法证明的引号 / 路径转换当前保守拒绝；没有扩大支持范围来绕过拒绝。
- 不提供与任意外部写者之间的操作系统级原子隔离；已有多阶段复核与未知结果阻塞仍保留，不宣称消除所有
  外部并发修改窗口。测试中的变更是明确阶段注入，不能冒充任意并发时序或 OS 崩溃测试。
- 重启残留 ROLLING_BACK / EXECUTING 的所有权与终止判定仍属于后续 Recovery Slice，保持检查点、禁止重放。
  本轮没有新业务 Decision Required，也不替用户批准 Recovery。

## 6. 关键文件

- Infrastructure：[GitWorkspaceAdapter](../../backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/workspace/GitWorkspaceAdapter.java)：requireTrackedContent → requireCanonicalAttributePaths / requireSupportedAttributes → requireSafeAttributeRule；restoreBaseline 的 BASELINE_ATTRIBUTES 复用路径检查。
- 真实 Git：[GitAttributeSafetyIntegrationTest](../../backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/workspace/GitAttributeSafetyIntegrationTest.java)。
- API/SQLite：[EvolutionStepExecutionApiIntegrationTest](../../backend/delveforge-app/src/test/java/com/ayywl/delveforge/app/api/evolutionplan/EvolutionStepExecutionApiIntegrationTest.java)。
- 文档：[ARCHITECTURE](../ARCHITECTURE.md) 与本报告；未更改 Domain / Application 生产代码、依赖、持久化和前端产品代码。
