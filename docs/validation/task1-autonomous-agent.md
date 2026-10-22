# Task 1 — Autonomous Coding Agent validation

Date: 2026-10-22 (Asia/Shanghai)

Status: **All three corrections verified; Git checkpoint pending. Task 2 not started.**

## 2026-10-22 independent findings and focused corrections

独立验收的原始结论为 **FAIL**：FTP 客户端代理泄漏、最终化材料保留的崩溃窗口，以及冻结后仍可重复启动编码。
原始报告与三个复现保留在本地 `data/task1-independent-evidence/`，未改写历史结论。
独立审查随后运行的完整旧快照为 2032 tests / 162 XML，2030 passed、2 Provider skips、exit=0；
绿色旧矩阵未覆盖这三个缺陷，不作为本轮修复证明。

| Finding | Correction | Permanent regression |
| --- | --- | --- |
| FTP proxy credentials | baseRun 显式清空 FTP_PROXY / ftp_proxy，与其余八个代理变量一致；覆盖项目、seed、在线准备、捕获容器，可信 Central 代理出站独立 | DockerProxyIsolationTest：隔离临时客户端配置、虚构口令、真实注入对照、实际四种生产容器的 env / network / security inspect；专用空 cache 下下载依赖、离线测试、实际 Candidate；资源销毁 |
| Retention crash window | FINALIZING 与 retained=true 原子提交；失败终态保留，只有成功冻结同事务或 NO_CHANGES 可解除；release 不清除 true；协调重新读数据库 | SqliteAgentStoreTest + DockerRetentionRecoveryTest：真实 Candidate INSERT 故障、终态提交后的崩溃截面、stop 未确认阻塞、协调 release 再次中断、INTERRUPTED 提交后再次中断、再重启保留实际 diff/metadata/卷；正常成功及普通失败清理 |
| Duplicate coding | reserve 的 BEGIN IMMEDIATE 事务内排除同 Step / baseline 的 SUCCEEDED Run，返回 RUN_CONFLICT；保留成功唯一索引 | 生产 SQLite 并发预约、Run 行数不增、Candidate 重载不变；服务 Start 在任何 worker/sandbox/model 工作前拒绝；失败重试与新授权基线仍可预约 |

V22 已用于本地运行和独立验收，不修改其历史 checksum；V23 向前补回旧版仍占用且有最终化历史的崩溃截面保留意图。
生产 Flyway V22→V23、重复迁移，以及 NO_CHANGES 不保留无用资源均有永久测试。
测试中的中断是确定性持久化截面和故障注入，**没有声称真实强杀 OS 进程**。
Candidate 字段、A / diff / expectedTree / SHA-256 合同、正式 WorkingCopy、信任 revision、Step 状态与 Task 2 边界未改变。

初次新增回归先复现保留标记和重复预约缺陷；引擎恢复后，FTP 永久回归也实际 RED。
首轮组合测试有夹具 SQL 列名错误，以及将无效假 HTTP 代理施加于可信代理而导致上游失败；
修正夹具后，假配置仅施加于不可信项目路径，可信 Central 代理保留实际许可出站配置。
后续冷缓存回归经历有界 TLS 重试后通过，不跳过依赖准备、不把旧复现探针的零退出码算作修复通过。

本轮聚焦矩阵 **25 tests / 4 XML，25 passed，0 failures/errors/skips，exit=0**。
其中 SQLite 9、编排 11、真实代理隔离 1、真实 Docker 重启保留 4；全部本轮新增回归实际执行。
冻结快照为 `data/r3`，668 个输入逐文件 SHA-256 与工作区一致；无关 8 个草稿另有哈希清单。
完整后端 `mvnw.cmd verify -Dmaven.jar.forceCreation=true` 在该冻结快照串行执行，
于 2026-10-22 14:36:05 +08:00 结束，**BUILD SUCCESS / exit=0**。
实际汇总 **2042 tests / 164 XML：2040 passed、2 skipped、0 failures/errors**；
Domain 240、Application 832、Infrastructure 557（其中 2 skipped）、App 413。
旧 EvolutionStepExecutionApiIntegrationTest 的 143 个场景全部通过。

门控明确设置为 `DELVEFORGE_AGENT_DOCKER=true`、`DELVEFORGE_AGENT_COMPATIBILITY=true`、
`DELVEFORGE_AGENT_LIVE=false`、`DELVEFORGE_AGENT_E2E=false`。
两项跳过分别为 SpringAiAgentModelLiveTest、CodingAgentLiveTest，本轮未调用真实 Provider。
真实 Docker 工具、代理冷缓存准备、重启保留及 JDK/公开 Maven reactor 兼容性测试均实际执行；
公开副本固定为 spring-guides/gs-multi-module @ d88a2b721bda3798a6a934987157498e66da06c5。
测试结束后 668 个构建输入与工作区再次逐文件核对一致，无新增/缺失输入；8 个无关草稿哈希不变。
Docker 回归结束后的检查无测试容器、网络或私有卷残留，仅保留设计上共享的测试依赖 cache。

本轮证据保存在忽略目录 `data/task1-remediation-evidence/`：`focused-final/`、`full-final/`、
`complete-verify.log`、`complete-verify.exit`、`source-manifest.json`、`drift.json`、
`docker-resource-audit.json`；不提交数据库、凭据、测试输出或缓存。
Git checkpoint 建立后在此记录实际 identity；原独立 FAIL 报告保持不变。

## Historical 2026-10-21 implementation evidence

以下保留原实现轮的执行范围；该轮 READY 声明已被 2026-10-22 独立 FAIL 否定，不能单独作为当前就绪依据。

最终主工作区核心 verify：38 tests / 8 XML reports，0 failures/errors/skips，所有后端模块构建成功。
真实模型闭环：12 次请求 / 14 次工具调用，经历测试失败、编译失败、自主修复，最后 5 项项目测试通过。
初轮全量执行 2027 项，有 47 个旧读取上下文的实例锁错误；配置适配后的 66 项补验全部通过，
按 XML testcase identity 核对，原 47 个失败场景全部覆盖，无遗漏。
**没有在最终修改后重复运行整套全量；不把这些不同范围相加，也不把初轮全量标成 PASS。**

## Scope

实现与 Task 2 合同见 [实现说明](../AUTONOMOUS_AGENT.md) 和 [ADR-0012](../decisions/0012-autonomous-agent-sandbox-and-frozen-candidate.md)。
本次不修改真实业务数据库、历史 WorkingCopy、正式 Git revision 或 Step 成功状态，不发布或推送。
测试使用独立临时 SQLite、受控临时 Git 仓库、专属 Docker 资源及公开工程的独立副本。
原有未跟踪 `frontend/design/`、`grilling/` 保留。

## Executed evidence

| Mechanism | Result | Evidence and precise scope |
| --- | --- | --- |
| Docker Engine | PASS | Windows host, Linux Engine 29.4.0/API 1.54；受限非 root 容器实际启动、退出 |
| Spring AI 1.1.8 native Tool Calling | PASS | SpringAiAgentModelLiveTest，1 test，真实 DeepSeek；结构化 read_file 请求、Application 执行一次、回传 nonce、模型结束 |
| Final working-tree core verify | PASS | 38 tests，8 XML reports，0 failures/errors/skips；最终 663 个输入文件 SHA-256 已记录并与测试结束后的工作区核对；所有后端模块编译/打包成功 |
| Real Docker finalizer and tools | PASS | DockerAgentSandboxTest：12 tests；冷缓存、POM 新依赖、真实 Git 重放、取消、报告边界、跨工程失败范围、二进制删除与 source budget |
| SQLite / orchestration / JDK | PASS | SQLite 5、编排 10、JDK 推断 4 tests；SQLite migration/transactions 与独立 JVM Candidate reload；编排中的 model/sandbox 为 doubles |
| Instance ownership | PASS | 1 test：同 JVM 冲突、独立 JVM 冲突、关闭后独立 JVM 重取及再次排他；实际 FileChannel/native lock，不以 mock 代替 |
| HTTP / Spring composition | PASS | 4 tests；真实随机端口查询、事件、取消与 404；完整 Spring 装配。没有冒充真实用户规划链路的模型调用 |
| Real model + Docker + SQLite | PASS | 最新 CodingAgentLiveTest：12 model requests、14 tool calls；实际测试失败→修改→编译失败→自主修复→5 tests pass；完整 diff 重放、SQLite reload、正式副本及 SQL 信任元数据不变 |
| Public Spring Boot reactor / Java 17 | PASS | spring-guides/gs-multi-module @ d88a2b721bda3798a6a934987157498e66da06c5；先误选无 POM 的根目录，收到未执行 Maven 的 INVALID_ARGUMENTS 后改选 complete；原样 baseline 2 tests pass；随后沙箱内两个模块新增源码、重测并捕获/重放 Candidate；正式副本不变 |
| Java 8 source level / JDK 21 | PASS | release=8 夹具在受控 JDK 21 compile 成功；未改 POM；准确记录 NOT_TESTED |
| Initial full backend verify | FAILED, findings closed by scoped rerun | 2027 tests，47 errors、0 assertion failures、2 Provider-gated skips；47 项均为旧读取上下文的实例锁冲突；全部 XML 已归档 |
| Legacy affected-method rerun | PASS | 全部 14 个受影响方法的 66 个参数场景，0 failures/errors/skips；原 47 个错误 testcase 全部匹配；未删改原领域、历史、回滚、权限断言 |

真实模型测试只发送程序在临时目录内生成的 Counter 示例及其工具结果；未发送 DelveForge 源码、
公开仓库内容或真实用户 WorkingCopy。领域对象是真实 Domain 类型，Planning/Asset read repositories 与
正式副本 inspection 使用测试替身；Docker、文件修改、Maven、Git finalization 与 SQLite 冻结/重载真实执行。
这证明 Agent core 的真实运行，不是完整 M1–M3 用户操作流程的 Provider 验收。

最新成功模型 Run：`bea0b752-7d39-47ff-bd66-ce87b9ae6152`。
Candidate：`f196bc0a-85f3-4de9-9e92-a9fab87fde80`，2 changed paths。
Expected tree：`233a70542c6ad3ac7f1e7fe885bd079256c1f30c`。
Diff SHA-256：`6fcc3a2286e1159d3a086a1e645ac0ef8ab8a0b43c9421c6045b00fd2dbff51b`。
最终 snapshot：`27155d1368b7fe043ddb5bf1d84b5eb037a6f02b2ebd192b2186824d9144a855`。
较早的测试失败与编译失败记录 stale；最后 5 项测试绑定最终 snapshot，directory=`.`，2 个实际测试报告。
这些 identity 来自本次真实运行的安全摘要，不是模型自述；测试临时资源不作为正式用户 Candidate 留存。

## Reproduction

本地输出位于忽略目录 `data/`，不含 API key：

```text
data/task1-model-live.log          初始真实 native Tool Calling proof
data/task1-agent-live-final.log    最新真实模型自主编码与 Candidate proof
data/task1-current-focused-verify.log  前一快照的 37-test verify / build
data/task1-evidence/final-core/    最终 8 个 XML、metrics.json、663-file source-manifest.json
data/task1-evidence/live-final/    最新真实模型测试 XML，1 test PASS
data/task1-final-snapshot-path.txt 独立源码快照的绝对路径
data/task1-legacy-snapshot-path.txt  短路径验证副本的绝对路径
data/task1-complete-verify.log     串行独立执行的全后端回归
data/task1-evidence/full-initial/  初轮完整 XML 与 metrics：2027 tests / 47 errors
data/task1-final-core-verify.log   主工作区最终核心矩阵与构建
data/task1-legacy-recheck.log      全部曾失败方法及其参数场景的独立重验
data/task1-evidence/legacy-recheck/ 66 项 XML、原失败 47/47 覆盖核对、该轮实际 source manifest
data/task1-context-red.log         修复前压缩丢失检查范围的真实回归失败
data/task1-nested-preflight-red.log 修复前无 POM 根目录的实际错误分类失败
data/task1-first-full-verify.log   第一轮完整回归，公开副本换行检查失败，未宣称 PASS
```

命令通过仓库 Maven Wrapper 执行：

```powershell
# 普通确定性后端验证（live/Docker tests 各自有环境 gate）
.\mvnw.cmd verify

# 真 Docker 与公开工程验收；先取得报告指定的固定 revision 独立副本。
$env:DELVEFORGE_AGENT_DOCKER='true'
$env:DELVEFORGE_AGENT_COMPATIBILITY='true'
.\mvnw.cmd verify '-Ddelveforge.compatibility.repository=<absolute-path-to-clean-pinned-copy>'

# 仅临时自建示例的真实 Provider 测试；key 从外部环境提供。
$env:DELVEFORGE_AGENT_E2E='true'
.\mvnw.cmd -pl backend/delveforge-infrastructure -am test '-Dtest=CodingAgentLiveTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

不得把 gated/跳过的测试算作实际通过。最终统计与早期 focused counts 不相加。
旧流程补验使用启动时冻结的短副本；与最终源码的差异仅为随后修复的 sandbox 预检查及其兼容性测试
三个文件，不进入旧流程调用路径，已由最终主工作区 38 项实际 Docker 矩阵覆盖。
两轮实际 manifest 与差异列表分别保留，不把旧副本冒充最终全部源码的执行证据。

## Failures investigated and corrected

- 首次 sandbox Docker API 访问受本机执行权限限制；获准受控访问后验证实际 Engine。
- Docker 29 的缺失资源文本为小写；修正存在性判断，仍要求独立确认 Engine 可达。
- 只读 rootfs 拒绝 docker cp；改为 bounded stdin 流入专属临时区，没有放宽 rootfs 或挂载正式源码。
- Maven native library 需要可执行 tmpfs；使用独立有界 `/tmp`，仍保留 nosuid、非 root、CPU/memory/PID 限制。
- 可信代理双网络的默认路由与 TLS 握手失败：明确 gateway priority，固定 Central 上游、TLS 验证、
  checksum 校验、原子缓存发布，并复用 TLS 连接及进行有界瞬态重试。未给工程容器开放外网。
- bare Git 删除不能依赖 worktree；改用 NUL-delimited index-info，真实 diff 重放已覆盖新增/修改/删除。
- 第一轮真实模型修正了代码且通过测试，但结束消息格式不合法；增加最多两次格式纠正，
  不把自然语言“完成”视为成功。后续真实 Run 冻结成功。
- 自动审批最初拒绝合并运行 live Provider，担忧真实仓库源码外传；核对并限定为临时生成示例后，
  单独的低风险 live 测试获准执行。没有把拒绝的真实项目源码传输换成间接操作。
- 早期模型测试的关闭动作打断清理；修正为不打断已终态的清理，并加入回归。
  已核对 owner/run labels 与挂载，清理 `1e716df7…` 和 `a83daebf…` 两次早期 Run
  遗留的已退出容器及各自两个专属卷，未触碰其他资源或共享缓存。
- 公开仓库默认 Windows checkout 的 CRLF 与测试中的 raw Git 检查配置不同。
  从相同固定 Git 对象创建独立 raw checkout 后测试，不重写 POM/源码、不削弱断言。
- 报告边界增加：全部 skipped 不是执行测试；Maven exit=0 不能覆盖报告失败；矛盾计数拒绝，
  不可用报告及零测试不能解除已知失败。
- 曾误将两次 Maven 验证重叠执行，JAR 被另一进程重建导致类加载失败；相关日志不作为通过证据。
  后续同一构建目录严格串行。最后新增修复使用 662 个源码文件的独立快照、独立输出目录验证，
  不重写长回归正在使用的 JAR；记录逐文件 SHA-256 并在结束时与工作区复核。
  只有独立退出码与报告一致的运行纳入结果，不相加重叠范围的测试计数。
- `clean verify` 在旧 `target/` 验证副本的只读 Git 对象上清理失败，尚未进入测试；未删除该历史目录。
  后续使用 `verify -Dmaven.jar.forceCreation=true` 重新构建并运行，清理失败不冒充测试通过。
- 依赖准备网络增加 isolated gateway mode，并检查 Engine 的实际选项，避免普通 internal bridge 的宿主服务访问。
  机制依据见 [Docker 官方文档](https://docs.docker.com/engine/network/port-publishing/#gateway-modes)。
- 修复报告枚举变量覆盖 Maven directory；测试证据保存实际规范化工程根。
  只有同一工程根的完整重验可解除其失败，真实 Docker 验证了无关子工程通过不能掩盖已知失败。
- 强制触发多轮上下文压缩的回归先复现“检查范围丢失”，再确认修复后 goal/directory/module/selectedTest
  保留在模型摘要与持久化事件中；未以放大上下文预算替代修复。
- 全量中的 47 个错误均来自旧 M5 测试在主上下文存活时启动第二个读取上下文。
  只在四个辅助读取上下文设置 `agent.enabled=false`，不关闭主上下文 Agent，不删除或削弱领域/历史断言。
  新增实际同 JVM/跨 JVM 排他与关闭后重取测试；同 JVM 在打开第二个 channel 前拒绝同文件/别名，
  避免失败构造泄漏句柄或误释放已有原生锁。生产排他边界保持有效。
- 深层独立快照中的旧 Git 夹具在 Windows 路径上限处失败，66 项未进入业务断言；该轮不算验证通过。
  使用内容相同的短路径 `data/t1v` 重验，不修改全局 Git/系统设置。逐文件哈希清单覆盖最终 663 个输入。
- 无 POM 根目录的检查错误曾被当成“执行结果未知”，导致改选正确子目录并通过测试后仍无法冻结。
  回归先复现错误，再修正为明确未执行 Maven 的参数错误；未知执行/损坏报告的阻断规则保持不变。
  固定公开 reactor 实际验证了误选→纠正→双模块变更→Candidate 重放的全过程。

## Boundaries and remaining verification

最终清理核对：本任务 owner 下没有容器、网络或 Run 私有卷残留，只保留可复用的共享 Maven cache。
JDK 17/21 两镜像的脚本与最终源码哈希一致；`git diff --check` 通过。
新增/修改源码、38 份本次日志与 179 份归档 XML 未发现本次 key 的字面量；key 未写入配置或容器。

已实际使用 Java 21 与 Java 17 的受控 Maven 镜像；未在 Linux/macOS 宿主机独立运行。
私有 artifact repositories、需要外部服务的测试、所有历史/未来 JDK、Git SHA-256 object format、
自定义 Git filters、symlink/submodule/special files 不在初版已验证支持集合。
公开仓库测试为 Sandbox/Maven 兼容性，不冒充真实 Provider 在该公开仓库的编码验收。
正则秘密净化仅覆盖已识别形式。正式接口是本机开发能力，保持 loopback 信任边界。

Task 2 未实现：正式 WorkingCopy Delivery、正式 commit、信任 revision 推进、Step SUCCEEDED、
完整 Git/SQLite Delivery 恢复、旧流程清理及完整前端/SSE。

上述未验证平台与明确不支持的环境不冒充通过；Task 1 的核心验收项已具备实际证据，
Task 2 可以消费冻结 Candidate 合同，尚未启动正式交付。
