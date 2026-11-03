# ADR-0012: Autonomous Agent Sandbox and Frozen Candidate

Status: Accepted for Task 1 implementation; runtime acceptance is recorded separately.

Date: 2026-10-21

用户本次 Task 1 明确替代旧 M4/M5 的一次性 CodingProposal 执行设计。旧文档中的冻结范围、
不使用 Spring AI、直接在 WorkingCopy 修改和单独 Repair/Retry 授权不约束新 Agent 路径。
这不是对旧流程恢复安全边界的放宽。

## Decision

### 2026-11-02：Maven 准备超时诊断和校验缓存

超时仍以 TIME_LIMIT 失败，不转为测试成功或释放未验证状态。项目进程停止后、清理容器前，
读取有界 Maven 阶段及可信代理的下载/重试/缓存指标和 OOM 标记；单个诊断保留有限历史，不记录 URL、凭据、响应体或进程环境。
指标文件只在可信代理的临时目录，不进入项目、公共工件缓存或 Agent 工具权限。
已校验匹配的 SHA-1 与工件同样原子发布，避免 Maven 请求校验文件时重复下载；摘要不匹配保持拒绝。
固定源、校验、网络和依赖隔离未改变。产品默认时限不变；有测量依据的验证参数调整单独记录，不作为冷缓存兼容性或全套测试通过的承诺。

### 2026-11-02：通用兼容性修正与检查声明

协议 v5 在现有 Port 内增加只读检查声明。省略前端目录时只在唯一 Vue/Vite 工程下自动选择；多工程必须明确指定。
声明与实际安装、执行和业务验收分开，不扩大 Step 权限。Vue JavaScript 工程不要求 TypeScript，Maven-only 不要求前端元数据。
基础工具组合识别为固定参数数组，覆盖官方模板包装、单层 Vitest 转调和有界 Vite heap 参数，不解释任意 shell。
root-only pnpm YAML 中的安装构建许可可识别但不传给安装器；多包 workspace、未知设置及 hooks 仍拒绝。

旧 registry 锁允许规范 SHA-1/256/384/512 SRI，继续由真实安装器核对摘要，并限制 HTTPS npm registry 来源及冻结安装。
SHA-1 不提供现代抗碰撞或发布者真实性保证；这是兼容历史锁文件的明确取舍，不把依赖视为可信代码。
Git 固定版本及 SHA-256 源快照、隔离网络、禁用安装脚本、离线执行及只读依赖仍共同约束执行。
不支持缺失/错误摘要、任意 URL、本地依赖或关闭完整性验证。相比仅接受 SHA-512，此选择扩大历史锁兼容面，
代价是接受上游历史摘要的强度；不额外引入远程信任服务或项目例外。

### 2026-11-02：现有隔离模式扩展到 npm/pnpm 前端检查

本节记录初次 v4 决定；其中单锁、workspace 与脚本范围已由 ADR-0021/0022 的 v8 原生准备和隔离执行扩展。
当前操作范围以 [LOCAL_SETUP](../LOCAL_SETUP.md#agent-build-preflight) 为准，下面保留当时取舍。

用户授权在同一 Agent 工具协议增加 `run_frontend_checks`，没有新 Agent 框架或通用 shell。
固定 Node/npm/pnpm，独立联网准备目录只接收筛选后的 manifest 与冻结锁；关闭生命周期脚本与 pnpm hooks。
仅固定 registry 代理可达；检查脚本只在新源副本、无网络容器中执行，依赖卷只读，Maven 缓存只读。
同一 Run 可在 manifest/锁文件不变时复用准备结果；项目执行者不能写入该卷。其他源码副作用拒绝，生成树仅在检查副本分类。
完整 source snapshot 继续覆盖所有源码，最终 Candidate 不接收依赖和输出，正式写入仍仅由 Delivery 执行。

Application 共用检查证据规则区分类型、工程及目标；前端失败不由 Maven 或不同目标成功清除。
v2 证据保持旧 Maven 记录可读。技术检查不构成产品验收，不增加领域状态或隐含授权。
产品上下文只从已保存 TargetState 投影，并与唯一授权 Step 分开作为净化数据传递。

相较让 Node 在 Agent 权威卷安装或执行脚本，一次性副本和分离网络增加复制及容器成本，
但无需放宽原快照和 Delivery 合同。相比一次性开放通用包管理器，明确支持固定 npm/pnpm、公共 registry、
标准 Vue/Vite/vue-tsc/Vitest scripts；workspace、私有源、安装期构建和其他配置拒绝，代价是兼容范围有限。
镜像增加 Node/npm/pnpm 和 YAML 安全解析器；未改变模块依赖或持久化技术。协议升到 v4，启动器核对脚本及镜像 ID。
详细行为和验证命令见 [受控前端检查](../LOCAL_SETUP.md#agent-build-preflight)。

- Domain 维护 AgentRun 与不可变 Candidate 事实；Application 拥有逐轮模型调用与工具调度。
- Infrastructure 使用发布的 Spring AI 1.1.x，关闭内部工具执行；保留 M1–M3 的文本 AiGateway。
- 每次 Run 使用独立 Docker Linux 容器和源副本，正式 WorkingCopy 只读提供可信 A。
- Maven 由镜像提供。执行容器无网络；依赖准备容器只能通过内部网络访问受控 Central 镜像代理。
  代理只取得受信任上游 artifact，并维护共享缓存；项目只能写每 Run 私有 Maven repository。
- Candidate 是实际源文件形成的 A→B diff，必须以独立 index 重放得到相同 B 后事务性保存到 SQLite。
- Run 成功只说明 Candidate 已冻结；不改变 Step 状态、WorkingCopy revision 或正式 Git commit。
- 活跃 Run 的持久化占用阻止重复启动及旧执行流冲突。重启不恢复对话，先处理残留资源再释放占用。
- 2026-10-22 验收修正：进入 FINALIZING 时原子保存材料保留意图，失败终态及再次重启均保持；
  只有成功冻结或明确 NO_CHANGES 解除，释放占用不能抹除保留事实。V23 向前修复旧版仍占用的最终化截面。
- 同 Step / baseline 已成功冻结后，预约事务拒绝新编码 Run，保留既有 Candidate 供 Task 2 使用。
- 所有可执行项目代码的容器显式清空 HTTP/HTTPS/FTP/ALL/NO_PROXY 大小写变量；可信 Central 代理的出站配置独立。

## Alternatives and trade-offs

继续旧 proposal 流程不能实现文件工具驱动的探索、测试与修复。直接修改 WorkingCopy 不能满足
候选生成与 Delivery 分离。容器内再建 Agent 服务会复制编排职责。全量源 Bundle 会重复存储。
Docker 增加运行前提；内部网络与代理增加少量基础设施，但允许冷缓存和 POM 新依赖而不开放任意出站。
第一版只支持有界文本源、受控 JDK 镜像和公开 Central 依赖；不承诺任意 Git/filter/JDK/私库兼容。

## Task 2

Delivery 消费已冻结 Candidate，不重跑 Agent。正式应用、接受验证、Git/SQLite Delivery 恢复、
旧 UI/路由清理和 SSE 均不属于 Task 1。

## 2026-10-24：修改前凭据拒绝的有限反馈

整文件凭据拒绝继续保留。Docker 适配器只在只读 inspect_patch 后、apply_patch 前的
SOURCE_CREDENTIALS/PATCH_SOURCE 检查点显式保证未开始修改；不能仅凭通用失败代码或阶段推断。
Application 每 Run 最多一次返回固定、脱敏的失败工具反馈，让模型保留该文件并选择其他实现路径。
该结果按原工具 identity 缓存，再次不同调用触发即终止；没有自动重打补丁或取消既有预算。
记录失败、应用后捕获失败及未知结果仍失败关闭。Candidate/Delivery 的成功条件不变。

选择这一局部方案是为了让无副作用的保守拒绝有一次换方案机会，同时不引入秘密重建或差异映射。
继续一律终止更简单，但会使正确保护阻止整个尝试；直接允许局部编辑则需要另行证明秘密跨度、
原始字节保持、前置校验和多文件变更失败原子性。当前未实现后者，代价是仍无法编辑这些文件，
且一次额外模型选择消耗既有预算。设计依据与剩余限制见
调查记录（Git History）。

## 2026-10-24：次数遥测与证据驱动的冲突修正

用户明确决定模型/工具累计次数不作为默认任意停止阈值。默认两个次数预算为 0，保留正数显式预算、
原 int/Long API 与溢出失败；墙钟、上下文、输出、资源、文件与授权约束保持。外部调用仍计费，Run 不可续跑。
不以放大常量替代产品决定，也不放开框架自动重试。

修改前 Git 检查失败可以反馈修正；实际应用后未知必须终止。模型获得固定安全原因、目标和重新读取提示，
Git 自由文本不再出站。重复相同失败补丁在执行前停止；最多三个未解决冲突，只有真实应用成功才重置。
直接比较有界内存文本，不增加持久化敏感材料或指纹机制。与一律失败相比允许有证据的修正；与仅取消次数
上限相比避免已观测的空转；代价是复杂但有可能修正的补丁也可能需要用户显式启动新尝试。
单个 Run 的身份/结果缓存仍持续到 Run 结束，墙钟而非默认累计次数约束其生命周期。


## 2026-10-24：实际镜像工具协议检查

Java 在准备阶段验证实际固定 image ID 的 `sandbox.py` 返回 `delveforge-sandbox` 整数版本 1，
位置在源码传入和 initialize 之前，因此也在首次模型请求及补丁操作之前。v1 拥有明确的
修改前冲突证据、安全诊断及应用阶段未知/应用后捕获分类；不兼容工具以
SANDBOX_PROTOCOL_INCOMPATIBLE 停止，不猜测旧返回值的无修改语义。

这在现有 Workspace/Docker adapter 内实现，不改变 Application/Domain、Candidate 或 Delivery 合同。
选择固定版本而非通用能力协商，是因为当前只有这一组必需语义；未来不兼容变更必须同步消费者和工具。
镜像身份固定避免运行中 tag 漂移，握手避免新 Java 配旧脚本；二者不替代对受控镜像构建来源的信任。
Java 适配器不自动构建或重启应用；正常启动的镜像准备由下述启动器完成。

## 2026-10-24：启动器自动准备可信镜像

Windows 启动器在启动后端前检查 Linux Engine API 1.48+，按可信构建目录内容生成带协议/JDK/输入哈希
的镜像标签，缺失时自动构建 17/21 两个环境。实际工具内容哈希、JDK 和协议核验成功后，才把固定 ID
显式传给新 Java 进程。缓存启动仍核验；输入变化选择新标签；已有不兼容标签拒绝且不覆盖。
高级覆盖仍验证实际内容，缺失 Docker 时仅显式关闭 Agent 才允许探索模式。
与手动构建相比减少首次启动遗漏和旧工具误用；代价是首次启动需要 Docker 构建网络和等待时间。
不改变 Workspace 能力、Agent/Candidate/Delivery 合同或 Engine 配置，不安装 Docker、清理用户资源。


## 2026-10-27：正式 Maven 检查隔离与有限运行期数据兼容

原始 Taskarium 基线的 SpringBootTest 正常打开版本化 H2 文件后修改其内容，旧 `run_checks`
虽然取得真实的一项通过报告，却会污染 Agent 源副本并正确拒绝该快照。仅隔离在线依赖准备不够。

当前选择在既有 Docker adapter 中给每次正式检查创建一次性 `check-source` 卷和无网络容器，
权威 Agent 源码只读提供输入，只有 Run 私有 Maven 缓存继续复用。检查结果须绑定复制前的完整源码
snapshot，宿主再次核对权威源码未变化；完整 snapshot 仍包含二进制资源和数据库初始字节。
生成报告先清理再读取，临时副本和全部写入者在返回前销毁，Candidate 从权威源码独立捕获。

不是所有副本变化都可接受。当前只识别已有 `.mv.db` 文件的 H2 MVStore 头部标识，允许其在副本
中的内容变化，并把相对路径、类别和前后哈希作为 `runtimeChanges` 保存在检查与 Candidate 证据中。
删除、模式变化、新增材料及所有其他非生成物变化仍拒绝。没有永久路径忽略，也不修改目标应用配置。
这支持已证实的嵌入式 H2 运行活动；不宣称支持任意数据库或任意测试副作用。

相比只读执行全项目，此选择允许数据库测试；相比放弃快照检查或丢弃任意写入，它仍能发现源码和配置
完整性违规，并保留被允许的精确数据变化。代价是每次复制和重新构建，以及其他格式仍可能需要另行
证据与明确支持决策。属于 Infrastructure 执行策略补充，不改变领域授权、Candidate、Delivery 或可信 revision。


### 2026-10-27：拒绝诊断补充

Step 4 的历史临时副本已清理，旧拒绝只含规则与阶段，不能还原实际变化文件。因此保留上述接受边界，
不按猜测扩大 H2 或其他文件允许范围。新 `CHECK_MUTATED_SOURCE` 可附最多 8 项相对路径、类型及前后
mode/SHA-256，意外变化优先，并记录总数、截断与前后 snapshot。Application 再次过滤受保护和疑似秘密路径，
保留隐藏数量；历史或无效元数据按不可用处理。原拒绝仍终止 Run，诊断不含源码、配置内容、测试输出或异常原文。
这是现有故障元数据的有界补充，不改变测试、Candidate 或 Delivery 的验证规则。


### 2026-10-27：既有 H2 trace 日志追加

实际 Step 4 Run 的新诊断定位到已有 `database/taskarium_db.trace.db` 内容变化。其前像与可信 C3 完全相同。
无源码改动的 C3 一次性副本上，真实 H2 2.2.224 的错误查询可复现正常日志追加及旧规则的拒绝。
历史后像已清理，不能逐字重建；以下规则对后续每次实际追加独立核对，不按猜测放行历史状态。

只在正式检查副本中，对已有、与已识别 MVStore 数据库同名的 `.trace.db`，核对原 H2 日志头、原长度和前缀 SHA-256。
仅当原字节完整保留、模式不变且追加内容具有 H2 日志头时接受，记录 `H2_TRACE_APPEND` 的路径及前后摘要。
不返回日志正文；新增文件、覆写/截断、删除、模式及其他源码变化仍拒绝。
权威源码继续只读、前后完整核对，测试绑定执行前 snapshot，日志副作用不进入 Candidate。
这是对已复现运行期文件格式的支持补充，不扩大到任意日志路径或任意测试写入。

### 2026-10-27：只读补丁预演失败可修正

实际 Run 的末次 apply_patch 在 requirePatchContent 调用 requireToolSuccess 处以 TOOL_ERROR 终止；
该调用针对只读 inspect_patch，实际应用尚未开始。具体旧异常正文未保存，不反推其输入。
真实 Docker 通用夹具复现：不闭合引号的 diff --git 头产生额外解析错误，原四项消息匹配没有接纳，导致 Run 被提前终止。

适配器仅在 inspect_patch 只读返回 TOOL_ERROR 的位置返回 PATCH_CONFLICT、CHECK_FAILED、
mutationNotStarted=true，诊断使用固定 INSPECTION_UNAVAILABLE；已知语法仍有原固定类别。
不输出异常正文，也不宣称输入只是语法错误；反复失败沿用现有相同补丁和三次未解决冲突停止规则。
后续写入仍必须重新获得有效预演、凭据检查和完整前后像。策略拒绝、协议不可信及实际应用后结果不明仍终止。
这是失败阶段的修正，不允许任意异常成为可重试写入，也不改变 Candidate/Delivery 合同。

### 2026-10-27：原生预演冲突的失败外壳

后续实际 Run 仍在 requireToolSuccess:492 终止。进一步检查发现：Python CLI 对预演的
PATCH_CONFLICT 返回 `ok=false`，分类和 mutationNotStarted 位于 result；适配器却要求 ok=true 才接纳冲突。
因此正常上下文冲突被送入 requirePatchContent，因顶层没有 classification 而降成 TOOL_ERROR。
真实 Docker 使用有效但上下文过期的补丁复现相同路径；此缺陷与上节未知解析错误的处理相邻，但不被其顶层错误分支覆盖。
旧 Run 的具体补丁与响应体仍无法重建，不伪造历史冲突事件。

适配器接纳当前协议明确的布尔 ok=false、内层 PATCH_CONFLICT 和布尔 mutationNotStarted=true，
直接返回该只读失败结果，让既有冲突修正/停止规则处理；不进入凭据见证或实际应用。
没有明确未写入标记的结果继续失败关闭。Python v3 协议、写入前后像和未知应用结果规则保持。
