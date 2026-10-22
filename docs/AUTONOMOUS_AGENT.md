# Autonomous Coding Agent — Task 1

本路径以 [ADR-0012](decisions/0012-autonomous-agent-sandbox-and-frozen-candidate.md) 为设计依据。
运行证据独立记录在 [验收报告](validation/task1-autonomous-agent.md)。Candidate 冻结不等于正式 Delivery。

## Ownership and invocation

- Domain：`agent/AgentRunState` 与不可变 `Candidate`，保留现有 Step 确认、顺序、资产授权和可信基线规则。
- Application：`agent/CodingAgentService` 独立管理历史、模型请求、工具校验/派发、调用身份、预算、取消与事件。
  不依赖 Spring AI、Docker、JDBC；通过 Model/Sandbox/Store Port 访问外部能力。
- Infrastructure：Spring AI **1.1.8** DeepSeek adapter、Docker CLI、容器工具、受控 Central 代理、Git 捕获/重放与 SQLite。
- App：配置、生命周期装配和本机 REST。既有 M1–M3 文本 AiGateway 保留。

只使用五个模型工具：`list_files`、`search_code`、`read_file`、`apply_patch`、`run_checks`。
工具参数是结构化 JSON；没有 shell、Docker、任意 Maven 参数或 Delivery 工具。
`apply_patch` 接受标准 unified diff。`run_checks` 支持 compile/test/package/verify、directory、module 与 test selector。
目录可选择仓库内嵌套 Maven reactor，不要求仓库根 POM，不使用项目 Wrapper。
误选无 POM 的目录在 Maven 启动前返回 INVALID_ARGUMENTS，允许纠正 directory 后继续；
执行已开始后的报告缺失、损坏或未知结果仍保持未决，不能按参数错误忽略。

API（默认仅监听 127.0.0.1；勿把本机开发接口暴露到不受信任网络）：

```text
POST /api/evolution-plans/{planId}/steps/{stepId}/agent-runs
     optional body: {"targetJdk":17}
GET  /api/agent-runs/{runId}
GET  /api/agent-runs/{runId}/events?after=0&limit=100
POST /api/agent-runs/{runId}/cancel
GET  /api/agent-candidates/{candidateId}
GET  /api/agent-candidates/{candidateId}/diff
```

Start 返回 202 与 Run identity。此前 Step 必须已明确确认并处于 READY。失败/取消/中断后可重新调用 Start；
新 Run 使用新沙箱，不续接旧对话。同一 Step/baseline 已冻结成功 Candidate 后不重新编码，交给 Task 2。
查询事件使用数据库全局递增 sequence，按 runId 筛选，after 为严格大于游标；分页最多 200 条。
事件包含实际转换、工具身份、分类、snapshot、测试计数，不保存完整源码/patch/提示词或模型自由文本。

## Environment

需要可访问的 Linux Docker Engine **API 1.48+**（显式 gateway priority 用于隔离准备网络）。
不依赖 WSL、Docker Desktop 内部路径或宿主机 Maven/Python。Python 只存在于受控工具镜像。
DelveForge 自身仍以 Java 21 和 Maven Wrapper 构建。

在仓库根建立可信工具镜像：

```powershell
docker build -t delveforge-agent:task1-jdk21 backend/delveforge-infrastructure/src/main/resources/agent
docker build --build-arg BASE_IMAGE=maven:3.9.9-eclipse-temurin-17 -t delveforge-agent:task1-jdk17 backend/delveforge-infrastructure/src/main/resources/agent
```

API key 从既有 `DEEPSEEK_API_KEY` 提供。模型/endpoint 沿用 `delveforge.ai.deepseek`。
Spring AI 直接 ChatModel 调用显式设置 `internalToolExecutionEnabled(false)`，Callback 不可执行；
框架重试关闭，工具只由 Application 按 call identity 执行一次。
提供者未返回 usage 时保留 unknown，不编造 token 计数。

`delveforge.agent.enabled=false` 可以关闭此开发能力。主要配置位于 application.yml：

| 配置 | 默认 |
| --- | --- |
| sandbox.root | data/agent-runs |
| sandbox.docker | docker |
| sandbox.images | 17 / 21 的上述本地镜像 |
| sandbox.default-jdk | 21 |
| sandbox.cache-volume | delveforge-maven-central-cache |
| sandbox.owner | delveforge-local-agent |
| sandbox.memory / cpus / pids | 2g / 2 / 256 |
| response-tokens | 8192 |

可单独覆盖 `delveforge.agent.limits.*`：max-model-calls=60、max-tool-calls=160、max-tokens=240000、
max-context-chars=180000、max-output-bytes=24000、max-patch-bytes=131072、max-file-bytes=262144、
max-candidate-bytes=2097152、max-files=10000、wall-seconds=1800、check-seconds=180、dependency-retries=2。
在线准备单次上限为 min(wall-seconds, 3×check-seconds)，仍受整个 Run 的 wall deadline 约束。
工具读行默认 120，最多 300；搜索最多 40 处，每段最多 400 字符；文件枚举分页最多 200。
基线复制前先检查 Git blob 声明大小、文件数及路径；当前原始基线/传输总上限为 16 MiB，
单文件仍受 max-file-bytes 约束。超限明确失败，不先把大型对象复制到宿主机再拒绝。
模型上下文按完整 assistant/tool 组压缩，保留系统、Step、历史结果摘要和近期组；仍超限则失败。
压缩摘要和持久化事件保留检查 goal、directory、module 与 selectedTest，避免丢失测试覆盖范围。

JDK 从标准 compiler release/target、java.version 与简单属性引用保守推断。
只跟随根 POM 声明的 reactor modules，不把测试资源中的 POM 夹具作为环境准入条件。
显式 targetJdk 直接选择受控运行环境；compiler 的 source/target/release 是语言级别，
例如 release=8 可在支持该 release 的受控 JDK 21 中编译，不要求安装或强制切换到 JDK 8。
版本矛盾或无法解析时要求显式 targetJdk；不重写 POM。镜像未配置则给出 JDK_UNSUPPORTED。
只声明已在验收报告实际测试过的 JDK，不承诺所有 Java/toolchain/profile/私有 parent 组合。

## Isolation and dependency preparation

正式 WorkingCopy 先通过既有只读 inspection 核对隔离、干净状态与可信 A。
只读读取 A 的 commit/tree/blob，并以受控 pack 传入运行专属临时 bare repository；不调用源 upload-pack/hook。
A 的源文件流入独立 Docker source volume，不复制无关 Git 历史。
正式 WorkingCopy 和原始 SoftwareAsset 都不挂载到容器。

工程容器使用 uid 11000、只读根、cap-drop ALL、no-new-privileges、CPU/memory/PID 限制、无 Docker socket、
`network=none`、有界可写 tmpfs 与专属 workspace/Maven volumes。显式清除大小写代理环境变量，
不传入模型 key、宿主机 Maven settings 或 host credentials。
每次 Maven 检查后 stop/start 整个容器，终止仍存活的后台子进程，再允许下一次操作。
每 Run 把配置镜像 tag 解析并固定为 image ID，捕获容器也使用同一 ID；不会受中途重建 tag 影响。
SANDBOX_READY 事件保存实际 container/image identity、JDK、Engine 报告的 CPU/memory/PID 与网络/只读约束。

共享缓存是 Docker-managed volume，不属于用户仓库。每 Run 独立可写 `/m2` 从该缓存复制初始化。
项目和 Maven 插件不能挂载/写入共享缓存。默认 Maven 始终 offline。
缺依赖时由系统运行另一个受限准备容器：它只有 Docker internal 网络，并强制使用 isolated gateway mode，
避免普通 internal bridge 地址仍可访问宿主服务；创建后检查实际网络选项，唯一出站通道是可信 artifact proxy。
该区别见 [Docker gateway modes](https://docs.docker.com/engine/network/port-publishing/#gateway-modes)。
镜像 settings 统一镜像项目声明的仓库；即使插件绕过 settings，也没有任意外网路由。

可信代理同时接 internal 与受控出站网络，只提供固定 `https://repo.maven.apache.org/maven2/` 路径 GET：
无 CONNECT、任意上游 URL 或重定向；构件最多 64 MiB；TLS 验证开启；下载及独立 SHA-1 checksum 校验后
原子发布共享缓存。校验不通过不发布。网络瞬态失败最多重试 5 次。可信代理可使用 Engine 配置的出站代理，
项目执行环境不能继承该代理环境。共享缓存不从项目构建的 local install 内容回灌。
受限网络无法建立时失败关闭，不降级为让项目容器连外网。
在线准备前后比较源码 snapshot，不接受准备过程修改源码；在线构建结果不作测试证据，必须再离线检查。

初始只支持公开 Central 构件。私库、认证、任意仓库、外部数据库服务或平台专用工具不承诺可用。
环境/依赖失败、编译失败、测试失败与构建配置失败分别报告。

## Source and Candidate correctness

项目、seed、在线依赖准备与最终捕获容器统一显式清空 HTTP_PROXY、HTTPS_PROXY、FTP_PROXY、
ALL_PROXY、NO_PROXY 及对应小写形式，防止 Docker 客户端 config.json 自动注入代理凭据。
这独立于宿主进程环境过滤，也不替代网络隔离；可信 Central 代理保留单独许可的出站配置。
Docker 自动注入机制见 [Docker 官方说明](https://docs.docker.com/engine/cli/proxy/)。

模型可见文本逐字段经过既有 RepositorySecretPolicy；路径凭据规则在访问前执行。
被净化的 read_file 内容明确标记 sanitized/不可编辑，不冒充原文；相应编辑拒绝。
正则策略只能保护已识别模式，不保证识别所有拆分/编码或未知格式的秘密。

最终 stop 所有项目容器，再用新容器以只读源挂载捕获实际文件。
Git 原始 blob 与 100644/100755 mode 构成完整 B；受支持的新增、修改、删除均纳入，包括被 gitignore 忽略的 Java 源。
不根据旧预测文件名单或模型 patch 历史选择交付文件。生成的 target 目录不交付；baseline 中已跟踪 target 则明确拒绝。
target 以外的生成样式文件变化（如 log/class/jar/tmp）拒绝并报告歧义。
只把 Maven 工程根下的 target 识别为构建输出；Java 包路径中的 target 是普通源码目录，
其中合法的 ignored/untracked Java 文件也纳入 Candidate。target 同时像 module 与构建目录时明确报告歧义。

diff 使用固定 Git 参数、raw UTF-8 bytes、无 textconv/external diff、禁用 rename detection/full-index。
在独立 index 中 read-tree(A) → apply --cached(D) → write-tree，必须精确等于 B。
CRLF/LF 由 raw blob 保留，不通过宿主机 checkout 自动转换。
symlink/submodule/special file、case-fold 冲突、Git metadata、凭据载体、Windows 保留别名、
不支持的 encoding、attributes filter/working-tree-encoding/export 转换均明确拒绝。
可接受普通 text/eol attributes，但不运行自定义 clean/smudge filter。

测试报告与 path/mode/content-hash snapshot 关联。源码变化后旧证据标 stale。
清理旧 Surefire/Failsafe 报告后才检查；Maven exit=0 不单独证明运行了测试。
报告缺失或 tests=0 准确标 NOT_TESTED；失败需后续合适的完整检查解决，不能以模型自述消除。
证据同时保存规范化的 Maven directory；当前保守策略只以同一工程根、无 module/test selector 的 test/verify
完整重验清除该工程失败，package 仍可执行并保留实际报告；
不推断其他目录或父 reactor 的检查覆盖它，避免无关工程通过掩盖已知失败。
没有有意义变化则 NO_CHANGES，不产生成功 Candidate。

## Persistence, restart and Task 2 contract

V22 增加 agent_run、agent_event、agent_candidate。单 WorkingCopy occupied partial unique index 与
旧执行/WorkingCopy 更新触发器共同拒绝冲突。start 事务重查 READY、ACTIVE、授权、先前 Step 与可信基线。
同一 Step / baseline 已有 SUCCEEDED Run 时，预约事务直接返回 RUN_CONFLICT，不创建 Run、worker 或模型请求；
原成功唯一索引保留为最终一致性守卫。失败/取消/中断可按现有授权重试，新授权基线不受历史 Candidate 阻塞。
应用以数据库旁的独占文件锁防止第二实例错误中断活跃 Run。资源名称包含 Run UUID，并检查 owner label。
清理只操作本次资源，不 prune，不清空共享 cache，不触及用户已有容器。

Candidate 字段：id、runId、baseline（commit A）、expectedTree（tree B）、diff（SQLite UTF-8 BLOB）、
sha256（diff bytes）、changedPaths、testEvidence、createdAt。diff 有配置大小上限；没有额外全源码 Bundle。
进入 FINALIZING 的短事务先设置 retained=true，再允许最终化产生唯一可恢复材料；失败终态保持该标记。
Candidate insert、Run SUCCEEDED 与 retained=false 在同一短事务提交；失败回滚后保留意图仍为 true。
数据库触发器拒绝 Candidate UPDATE/DELETE；重新读取会验证 diff SHA-256。
最终化或冻结失败时保留停止的卷与 run 目录（candidate.diff / metadata 存在时保留），状态带 retained。
清理不能证实停止时保留 occupied 并记录 CLEANUP_BLOCKED，阻止新执行。
重启先证实 owned 容器停止，活跃 Run 标 INTERRUPTED，再重新读取数据库的 retained 决定是否清理；
协调自身再次中断也不会丢失保留意图。release 不能把已持久化的 true 改成 false。
只有确认 NO_CHANGES 或成功冻结可解除该意图；普通未进入最终化的失败正常清理。
V22 已用于本地运行与独立验收，保持原迁移校验和；V23 为仍占用且有 FINALIZING 历史的旧崩溃截面补回保留标记，
排除已冻结成功与明确 NO_CHANGES。迁移不能恢复已被旧版本删除的文件；本轮只在测试数据库执行迁移。
重启不续接对话、不覆盖成功 Candidate。

Task 2 通过 AgentStore.candidate(id) 或候选 API 消费上述冻结合同，不必重新调用模型或依赖存活沙箱。
Delivery 应重新核对正式 WorkingCopy 精确位于 A，校验 diff hash，并独立应用/比较 B。
正式 commit、WorkingCopy 信任推进、Step 成功、完整 Git/SQLite Delivery 恢复和前端 SSE 尚未实现。
