# M4 — Windows Local Functional Smoke

**Status:** Validation Record / **Not Source of Truth**

**Date:** 2026-10-08（UTC+08:00）

**Verdict:** **M4 REAL SMOKE: BLOCKED**

本次按用户更新的边界做受控 Windows 本地功能尝试，不使用 Docker、VM、容器或沙箱框架。
应用启动成功，但首次 M1 AI 请求被诊断转发工具拒绝，未到达真实 Provider。
因此没有完成 M1→M4，也没有证明真实演化成功；不能归因于模型质量或 M4 执行正确性。
此前 `m4-real-provider-self-hosting-smoke.md` 保留为不同安全前提下的历史检查记录。

## 1. 凭据隔离修复与验证

- 固定实现 / 源码 revision A：`9eac80c5683c1d8eb5e8be08f3148dce5df2cc61`。
- `WorkspaceCommandRunner` 启动受控 Git / Maven 进程前移除 DeepSeek key 的环境变量别名，
  以及可能携带完整凭据配置的 `SPRING_APPLICATION_JSON`。
- `GitWorkspaceAdapter` 独立的读取 / provisioning 进程也使用同一凭据过滤。
  过滤只改变子进程环境，不改变父应用的 Provider 配置；原有验证与环境注入限制保留。
- 回归覆盖配置别名、父环境不变，以及实际父子 Java 进程：`parent=true;child=false`。
  使用非秘密 sentinel，不使用真实 key 做自动化测试。
- 定向 `WorkspaceCommandRunnerTest`：4 tests，0 failures / errors / skipped。
- 完整 `mvnw.cmd verify`：**1310 tests，0 failures / errors / skipped，BUILD SUCCESS**，耗时 07:55。
  Domain / Application / Infrastructure / App 分别为 216 / 713 / 212 / 169。
  这是修复的确定性验证，**不是 smoke 中的 Candidate 验证**。
- 未修改 Domain、AI prompt、Git mutation、验证证据规则或生命周期。

## 2. 固定制品与运行环境

- 原 SoftwareAsset 目标：`E:\develop\DelveForge`，起始 HEAD=A，工作树干净；
  未发现 assume-unchanged / skip-worktree 标志。
- JDK：`E:\develop\jdks\jdk-21.0.12.1`；现有 Maven Wrapper 3.9.14。
- 固定 JAR 来自上述完整验证，复制到仓库外 `artifact/app.jar` 后启动，不从 WorkingCopy 运行应用。
- JAR SHA-256：`472003c81c91711fc90e04a2d48b9e1824114a5e947bf3450efa4ea4f8e07629`。
- 全新独立目录：`E:\df-m4-smoke\20261008-local1`。
  SQLite=`data/smoke.db`，Workspace=`workspaces`，日志=`logs`，证据=`evidence`，临时文件=`tmp`。
- 应用仅绑定 `127.0.0.1:18784`；启动成功，PID 50212。停止后无本次服务监听。
- Disposable key 通过不回显输入进入启动器内存，再传给应用环境；未放进命令参数或配置文件。
- 模型保持配置默认 `deepseek-flash`，单次调用 timeout=60s。
- 验证覆盖仅使用已授权预算：

```text
--delveforge.workspace.verification.max-test-reports=160
--delveforge.workspace.verification.action-timeout=900s
--delveforge.workspace.verification.invocation-timeout=1200s
--delveforge.workspace.verification.max-output-bytes=8388608
```

M2 / M4 材料、Scout、扩展与 Proposal 预算保持当前配置；未更改测试选择、POM、Wrapper 或信任规则。

## 3. 实际 API 流程与阻断位置

1. `POST /api/user-profiles` → **201**，约 0.132s。
   Profile=`c297b95d-d008-4b1f-8c78-c30f47381e4e`，`EXPLORING / revision=1`。
2. `POST /api/user-profiles/{id}/discovery-turn` → **502**，约 0.085s，
   `EXTERNAL_CAPABILITY_UNAVAILABLE`。
   输入如实说明 Java / Agent 学习、维护者授权与本次小型仓库分析改进目标。
   候选是 `RepositoryPathClassifier` 的 Python 下划线文件名角色提示及正负回归测试，
   要求完整 Maven TEST，不预制 Step、Criteria、Proposal 或验证结果。
3. 只读 `GET /api/user-profiles/{id}` → **200**。
   Profile 仍为 `EXPLORING / revision=1`，全部内容与 evidence 为空。

为记录真实执行中使用的 Proposal 与选材路径，本次使用仓库外的透明本地诊断转发，
准备原样转发到 DeepSeek HTTPS，不修改请求或模型响应、不制造输出、不做重试。
**工具存在传输兼容性错误**：只接受 `Content-Length`，没有处理 chunked 请求体。
Spring/JDK 发出的 JSON 请求使用 `Transfer-Encoding: chunked`，被工具前置长度检查拒绝，
应用将该 HTTP 错误映射为上述 502。

停止后，用相同缓存 Spring/JDK 库和非秘密 sentinel 做完全本地诊断，确认请求路径为
`/chat/completions`、`Content-Length=null`、`Transfer-Encoding=chunked`，
拒绝产生的异常类型与本次日志一致。该诊断不访问 Provider、不调用产品 API，也不重试 smoke。
上游调用计数为 **0**；没有真实模型输出可供评价。

本次按一次有界尝试的限制停止，没有修正转发后重新运行，没有 Repair / Retry / Rollback。
后续独立尝试需要先离线验证诊断工具的 HTTP framing，或直接使用已存在的 Provider HTTPS 配置；
不需要修改产品验证规则。

## 4. 状态、完整性与保留证据

- 数据库只存在 1 个 UserProfile；SoftwareAsset、RepositoryProfile、ProductDirection、
  EvolutionPlan、EvolutionStep、WorkingCopy 均为 **0**。
- Workspace 目录为空；没有执行 `/confirm`、`/execution-preparation`、`/execute`。
- 未产生 Candidate B，没有 M4 三个持久化检查点、ExecutionResult、VerificationResult、
  smoke Surefire 报告或 A→B diff。`SUCCEEDED` 与可信 revision 提升均未发生。
- 应用被停止；启动器总耗时约 183.801s。记录的 exit=1 来自 Windows 显式终止，
  不是应用启动崩溃。
- **报告写入前**的前后快照证明：HEAD=A、工作树均干净；578 个受控文件 SHA-256 完全一致；
  `.git/HEAD`、index、config 的 SHA-256 完全一致。报告的后续提交属于授权记录，不是演化修改。
- 保留脱敏 API 请求 / 响应、应用日志、只读 DB、运行配置、前后快照、传输诊断与最终审计，
  均位于上述仓库外目录；不提交运行产物或 DB。
- 对保留的 21 个文本产物（含修复验证日志）扫描 Provider key 格式，无命中。
  本次未把 key 发送给 Provider，也未在文件、日志或报告中保留 key。

## 5. 剩余安全边界与结论

这是本机、维护者授权的功能尝试，**不是任意不可信仓库可安全执行的认证**。
凭据环境过滤不限制文件系统权限：仓库构建 / 测试若被执行，仍以当前 Windows 用户运行，
可能访问工作目录之外的私有文件、用户配置 / Maven cache、网络和同用户进程。
独立数据库、WorkingCopy、临时目录与固定 JAR 不能消除这些宿主权限风险。
本次实际未进入仓库构建 / 测试阶段，不能声称该风险已通过 smoke 得到隔离验证。

最小凭据继承修复已验证；真实端到端 smoke 被诊断工具阻断。
M4 保持 IN_PROGRESS，不冻结、不启动复盘或 M5。

**M4 REAL SMOKE: BLOCKED**
