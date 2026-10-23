# Autonomous Coding Agent and Delivery

本路径以 [ADR-0012](decisions/0012-autonomous-agent-sandbox-and-frozen-candidate.md) 为设计依据。
Task 1 历史证据记录在 [核心验收](validation/task1-autonomous-agent.md)。Candidate 冻结不等于正式 Delivery。
正式交付依据 [ADR-0013](decisions/0013-frozen-candidate-delivery.md)；当前实现证据以
[Task 2 验收的 2026-10-23 审查修复](validation/task2-autonomous-evolution.md) 为准。

## Local startup and upgrade

DelveForge 自身要求 **JDK 21、Git CLI**；前端要求 **Node.js `^20.19.0 || >=22.12.0`、pnpm 12.9.1**。
项目自带 Maven Wrapper。Agent 另需下节的 Linux Docker Engine 与可信镜像；目标项目支持的受控 JDK 是 **17/21**，
不是把 DelveForge 自身切换到 Java 17。正式 Delivery 原生边界支持 Windows 和 Linux x86-64；其它平台、
缺失原生 API 或不支持的路径/文件类型均失败关闭。完整后端证据来自 Windows，Linux 仅有交付矩阵补验。

### Existing database: back up before first start

应用启动时自动运行 Flyway，目前到 **V25**。V22/V23 增加 Agent/Candidate 与保留意图修复；
V24 增加 Delivery 日志；V25 将有旧执行事实的原始 Step 行归档到 `legacy_evolution_execution`，
将相关 Plan 标为 SUPERSEDED、其 Steps 标为 CANCELLED。它不把旧成功转换为新 Delivery，
不删除原始仓库、WorkingCopy 文件、画像、分析或冻结 Candidate。未经旧执行的规划保留。

已有数据的首次升级应按以下顺序手动进行；本次开发验证不执行真实用户库升级：

1. 核对没有未决操作，正常停止后端及其执行资源；未知占用或材料仍需先人工核对，不能清标记绕过。
2. 备份实际配置的 SQLite 数据库，使用 SQLite `Connection.backup` 等一致备份 API，验证备份可打开且
   `PRAGMA integrity_check` 为 `ok`。不要只复制可能仍有 WAL 的主 `.db` 文件。
3. 在停止写入的状态下，完整备份所有托管 WorkingCopy roots（包括 `existing-roots`、隐藏 `.git`、
   未跟踪文件与恢复材料），并保留 Agent root 中的留存材料、旧配置和可回退的应用包。
4. 核对备份可恢复，再明确选择真实库启动。如先用副本试验升级，必须隔离数据库与所有路径绑定，
   不能启动仍指向原 WorkingCopy 的克隆库。数据库与代码副本必须成组保留，不能只回退一边。

正常重启复用所选数据库，不清库或搬移历史副本。旧 M4/M5 使用文档只作历史参考。

### Build and launch

从仓库根目录构建；Windows 可使用 `setup-delveforge.ps1` 交互启动。手动方式如下（PowerShell 7）：

```powershell
.\mvnw.cmd verify
# 已有上述产物时无需再次打包。首次试用请选择不存在的隔离数据库路径。
$localRunRoot = Join-Path $env:LOCALAPPDATA 'DelveForge\agent-local'
$env:DELVEFORGE_PERSISTENCE_DATABASE_FILE = Join-Path $localRunRoot 'application.db'
$env:DELVEFORGE_WORKSPACE_ROOT = Join-Path $localRunRoot 'workspaces'
$env:DELVEFORGE_AGENT_ROOT = Join-Path $localRunRoot 'agent-runs'
$env:DELVEFORGE_LOG_DIR = Join-Path $localRunRoot 'logs'
$env:DEEPSEEK_API_KEY = Read-Host 'DeepSeek API Key' -MaskInput
java -jar backend/delveforge-app/target/delveforge-app-0.1.0-SNAPSHOT.jar
```

另开终端运行前端：

```powershell
cd frontend
pnpm install --frozen-lockfile
pnpm dev
```

默认后端 `127.0.0.1:8080`，Vite `127.0.0.1:5173` 代理 `/api`。端口可用 `SERVER_PORT` 和前端的
`BACKEND_DEV_URL` 对应调整。默认库为仓库启动目录下 `data/delveforge.db`；默认副本为 `data/workspaces`。
模型和 endpoint 使用后端 `delveforge.ai.deepseek.model/base-url` 配置；API key 仅放后端进程环境，
不放前端、源码、日志或启动参数。停止后可移除该终端的 key 环境变量。正常启动不需要 `m4-local` profile。

这些是本机开发 HTTP 接口，**没有用户登录认证、多租户隔离或部署安全保证**。保持 loopback，
不要直接暴露到不受信任网络；`pnpm preview` 只有静态预览，没有开发 API 代理。

### Use an existing Java/Maven repository

在项目工作台创建流程，经 M1 审阅确认画像，M2 登记本地 Git 仓库并如实填写读取权限、许可证与使用授权，
完成只读分析并选择方向；M3 生成计划，准备独立 WorkingCopy 并激活。原仓库应已提交且干净，分析固定到 revision。
目标可为根或嵌套 Maven 工程；不要求目标仓库 Maven Wrapper，检查使用可信镜像内 Maven。
先审阅一个 Step 的 goal/scope/plannedChanges，明确确认，再在实施页启动 Agent；不会自动确认或启动后续 Step。
失败 Run 可显式创建新尝试；已有成功 Candidate 时消费其交付结果，不重新编码。

Run、Candidate、Delivery 与事件分别保存在所选 SQLite 的 `agent_run`、`agent_candidate`、
`candidate_delivery`、`agent_event` 中；冻结 diff 为数据库 BLOB，正式 commit 与源码在对应 WorkingCopy。
临时沙箱和保留材料位于配置的 Agent root 及该 Run 的 Docker 资源，不能当作可随意清理的缓存。
页面和下文 REST/SSE 可读取真实历史、测试范围、冻结差异与交付原因。
DELIVERY_BLOCKED 表示已知阻塞，可处理原因后显式重新核对；RECOVERY_REQUIRED 表示现场不能证明安全，
保留预约和材料，不 reset/clean。只有下文规定的 MUTATION_NOT_STARTED 冲突具备有限的显式重试路径。

普通复验为根目录 `.\mvnw.cmd verify` 与前端 `pnpm test`、`pnpm typecheck`、`pnpm build`，
没有独立 lint 脚本。Docker、公开仓库兼容性和 Provider 均有单独门控；具体命令及证据范围见
[Task 2 Reproduction](validation/task2-autonomous-evolution.md#reproduction)。普通验证的门控跳过不算真实通过。

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

可单独覆盖 `delveforge.agent.limits.*`：max-model-calls=60、max-tool-calls=160、
max-context-chars=180000、max-output-bytes=24000、max-patch-bytes=131072、max-file-bytes=262144、
max-candidate-bytes=2097152、max-files=10000、wall-seconds=1800、check-seconds=180、dependency-retries=2。
在线准备单次上限为 min(wall-seconds, 3×check-seconds)，仍受整个 Run 的 wall deadline 约束。
工具读行默认 120，最多 300；搜索最多 40 处，每段最多 400 字符；文件枚举分页最多 200。
基线复制前先检查 Git blob 声明大小、文件数及路径；当前原始基线/传输总上限为 16 MiB，
单文件仍受 max-file-bytes 约束。超限明确失败，不先把大型对象复制到宿主机再拒绝。
模型上下文按完整 assistant/tool 组压缩，保留系统、Step、历史结果摘要和近期组；仍超限则失败。
上下文压缩行为保持不变，摘要仍保留检查 goal、directory、module 与 selectedTest。
Candidate 的 testEvidence 继续保存实际测试范围；活动展示摘要只保留安全的 goal、directory、module。

累计预算配置 `delveforge.agent.limits.max-tokens` 已退役，不再因累计用量超过历史 240000 阈值而终止 Run。
各次响应报告的 tokens 仍累计并持久化；任一次缺少 usage 时总量保持 unknown，不补造数字。
`delveforge.agent.response-tokens` 和 M1–M3 AiGateway 的 `max-tokens` 仍控制各自响应生成，未作调整。
执行消耗外部 API 额度，可在界面监控已报告用量并取消。取消不是可恢复暂停，当前没有 checkpoint/resume；
提供者余额耗尽或其他失败仍走既有失败处理，充值不意味着旧 Run 可续跑，可能需要显式新建 Run。
上述调用次数、上下文、输出、时限、重试与沙箱资源限制继续生效。

### 活动展示与安全元数据

新 TOOL_STARTED / TOOL_FINISHED 摘要使用 `metadataVersion=1`，每次实际工具执行由服务端生成独立
UUID `toolCallId`，同一对事件共享此身份。模型原始 call ID 去重与工具执行顺序不变。
界面只合并同 Run、同工具、同身份且唯一、先开始后结束的事件；旧记录不迁移，也不按相邻位置猜测关联。
工具返回不等于代码修改成功，例如 `ok=true` 的 PATCH_CONFLICT 仍显示冲突；缺少结束事件显示待核对。

展示目标是经参数校验、既有 RepositorySecretPolicy 与沙箱路径规则保守子集筛选的仓库相对路径：
单路径最多 256 字符，禁止遍历、宿主绝对路径、受保护文件和敏感片段；实际访问继续由沙箱校验文件系统边界。
补丁只从成功执行后受控返回的已检查 paths 提取，最多 8 个安全路径，否则不显示文件名，不解析补丁正文。
展示摘要不保存原始参数、搜索查询、补丁、源码、提示词或任意工具输出，仅包含有界身份、范围、分类与实际数值。
既有事件大小上限、后端事件读取/安全诊断能力和历史记录保持有效；界面不再提供原始 JSON 或容器身份转储。

界面合并 Run 状态、实际用量与起止时间，日常展示不再重复 Run ID/baseline；API 与持久化记录仍保留完整身份。
时间线默认显示最近 40 项操作，可展开更早的已加载活动；原有最近 1000 条事件保留边界不变。
仅在读者位于底部时跟随新活动，向上浏览后可显式回到最新；清理受阻、恢复和保留材料警示继续可见。

新事件的 `invocationDurationNs` 来自 Application 在 Java→Sandbox Port 调用前后的单调时钟差，
包含受控检查和沙箱往返，不包含模型请求、输出净化或事件持久化。原 `durationMs` 继续表示 Python 工具内部时间。
界面优先显示调用总耗时；仅有旧内部计时时标注“内部”，零或不足一毫秒显示 `<1 ms`，没有计时则不补造。
没有实际调用沙箱的参数拒绝不记录调用耗时。

工具抛异常时补充相同关联身份的 TOOL_FINISHED，仅保存有界分类、计时与 `resultRecorded=false`，
不保存异常 message、参数或输出。它表示未取得完整工具结果，不能证明未产生修改；原异常仍终止 Run，
清理与占用协调继续走既有流程。若结束事件写入失败，不以记录失败替换原拒绝原因。
现有明确 INVALID_ARGUMENTS 反馈仍可由模型纠正；POLICY_REJECTED、执行结果未知与安全违规不因此转为可恢复成功。
安全过滤省略目标时可带 `targetState=withheld`，只显示“目标已隐藏”；历史或缺失元数据显示“目标未记录”。
允许的点目录、点文件及路径中的普通空格可以展示，秘密和受保护路径仍经过服务端政策及沙箱检查。

已完成的 read_file/list_files/search_code 按连续范围分组，每组至少 3 次、最多 8 次文件操作及 16 项活动；
可包含其间已结束的模型请求，展开后按原始顺序显示各自身份、结果和时间，不把它们合成一次执行。
失败、不完整、补丁、检查、最终化、Delivery 与当前最后一项不收进普通探索组；活动中的项始终可见。
分组只处理当前显示范围，默认折叠且不创建隐藏子行，查看更早活动不改变已加载历史边界。
新活动以 220ms 轻微上移进入；只有持久化活动表明仍有请求时显示等待指示，终态和取消停止动画。
持久化事件比 Run 概览更新时会标明“运行概览待同步”；不会从 SSE 连接状态推断模型正在工作，
也不会从活动推断 Candidate 或 Delivery 成功。减少动态效果偏好会关闭进入、脉冲与等待点动画。

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

## Deterministic Delivery (Task 2)

成功 Run 清理并释放其预约后，系统发现冻结 Candidate，自动交给独立 CandidateDeliveryService。
同 Candidate 只有一个持久化 Delivery 身份，不重跑模型，也不需要沙箱继续存在。
INTENT 在任何正式 Git 写入前提交，候选 diff 在独立 index 重放；实际 index、完整 tracked 内容、
额外文件、路径、attributes 与 Git 安全现场均检查。普通 text/EOL 转换允许经过独立字节等价证明；
不执行项目自定义 filter、hook 或任意命令。commit-tree 使用 DelveForge 系统身份，parent=A/tree=B；
保存 C 后 update-ref 以 A 作旧值 CAS 发布。发布后重新证明 C 与文件，短 SQLite 事务才接受 Step。
此事务同时写 Step SUCCEEDED/baseline A/delivery C、WorkingCopy 两个 revision C、Delivery COMPLETED、
完成事件和释放预约。Candidate 不修改，NOT_TESTED 原样显示。

2026-10-23 安全修复后，Git 只在自有临时目录渲染 B，不 checkout 正式源码。正式写入需要
Windows 的目录/独占文件句柄，或 Linux x86-64 的 openat/statx/renameat2 能力；JNA 随应用打包，
能力不可用时拒绝写入。新增目标排他创建，旧内容在写入/删除边界再次核对，Linux 捕获旧条目保留
在 `.git/delveforge-captured-*`，`paths` 对应编号文件。源文件成功后才由 Git 原子发布 index。
未完成标记 `.git/delveforge-delivery-incomplete` 留存时，不允许仅凭 B 的外观接受成功；不自动清理。

重启先协调 Agent 占用与材料，再处理交付日志和未交付 Candidate：

| 实际现场 | 行为 |
| --- | --- |
| INTENT + 干净 A | 重新核对授权、重放并继续 |
| HEAD=A，index/文件完整等于 B，且无未完成标记或已记录的外部冲突 | 不再次应用；创建/复用已记录 C 后发布 |
| HEAD=已记录 C，parent=A/tree=B，文件完整 | 接受尚未提交的 SQLite 事实 |
| SQLite 已是 COMPLETED | 返回相同结果，不新增 commit |
| 干净 A 上的确定拒绝 | DELIVERY_BLOCKED，保留身份，用户可请求重新核对 |
| 部分 index/文件、额外外部修改、未知 ref/数据库 | RECOVERY_REQUIRED，保留现场和预约，不自动 reset/clean |
| MUTATION_NOT_STARTED 冲突已由用户处理，且显式重试重新完整证明干净 A | 重新核对授权后消费同一冻结 Candidate；不重新编码 |

已知 Git 结果但 SQLite 暂不可用时保留可恢复检查点与 DELIVERY_PENDING 事件；异常后先读回是否已经提交。
冻结结果的首次发现若不能预约，记录阻塞事件，本进程不无限自动重试该结果；明确请求仍会重新校验。
新 Step 的 goal/scope/plannedChanges 不再要求 M4/M5 VerificationStrategy 或精确文件/命令。
旧 execute、execution-preparation、repair、retry、rollback 路由及其编排已删除。
V25 归档旧执行行并停用相关 Plan；生产迁移应先备份数据库，现有代码副本不会由迁移删除或覆盖。

## API and persisted progress

新增接口：

```text
GET  /api/evolution-plans/{planId}/steps/{stepId}/agent-runs?limit=50&before={runId}
GET  /api/agent-runs/{runId}/stream?after=0
GET  /api/agent-candidates/{candidateId}/delivery
POST /api/agent-candidates/{candidateId}/delivery
```

显式交付请求只消费现有冻结结果，可重复调用。历史 Run 每页最多 100。
SSE 的 activity 事件 ID 是 SQLite 全局递增 sequence，查询仍按 Run 隔离；after/Last-Event-ID 为严格排除游标。
历史与后续事件始终来自相同有界数据库查询，每页 100，避免 replay/live 切换窗口。
心跳检测断开的连接，订阅结束释放资源；同应用最多 64 个订阅。
前端按事件 ID 去重，页面重开从持久化历史恢复；不估算百分比、思维过程或未知 token。
默认仅监听 loopback；开发接口不应暴露到不受信任网络。

Vue 实施页分别显示 Step、Run 与 Delivery；可明确开始、取消或启动新的失败尝试。
已有成功 Candidate 时不提供重复编码；可查看检查证据、changedPaths、冻结 diff 和正式结果文件列表。
恢复要求原始位置仍可读、托管根仍在配置许可内；不能用挪动路径或猜测来源绕过边界。

实际验收和限制见 [Task 2 验收](validation/task2-autonomous-evolution.md)。
