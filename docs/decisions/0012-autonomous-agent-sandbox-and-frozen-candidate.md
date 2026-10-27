# ADR-0012: Autonomous Agent Sandbox and Frozen Candidate

Status: Accepted for Task 1 implementation; runtime acceptance is recorded separately.

Date: 2026-10-21

用户本次 Task 1 明确替代旧 M4/M5 的一次性 CodingProposal 执行设计。旧文档中的冻结范围、
不使用 Spring AI、直接在 WorkingCopy 修改和单独 Repair/Retry 授权不约束新 Agent 路径。
这不是对旧流程恢复安全边界的放宽。

## Decision

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
[调查记录](../validation/activity-monitor-source-credentials.md)。

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
