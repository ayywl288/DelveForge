# ADR-0015: 可执行 Step 与不完整模型响应纠正

Status: Accepted for 2026-10-25 autonomous FinTrack stabilization.

## 2026-11-09：支持思考模式的工具对话

默认 Coding Agent 使用 Thinking High。此决定替代下文为早期消息合同关闭 thinking 的处理。Spring AI 1.1.8 的高层 ChatModel 解码思考内容，却在重新构造 Assistant 消息时丢弃该字段。Infrastructure 直接使用同版本的 `DeepSeekApi`，其 `ChatCompletionMessage` 同时支持 `reasoning_content`、可见内容及工具调用。HTTP 拦截器补充该版本没有类型字段的模式参数，并继续负责最终字节预算和有限重试。Application 是唯一工具派发者。

携带工具时，所有保留的 Assistant 消息均重放模型原有的思考内容，包括仅回复文字后收到平台纠正的轮次。截断响应整轮丢弃，平台说明使用 User 消息。压缩按完整交互组移除思考和结果，仅投影有界事实。思考内容只驻留活跃 Run 内存。

执行前从所选方向引用的画像版本、已接受 Plan 和历史仓库快照读取解释性上下文。`authorizedStep` 和可信基线继续决定唯一修改范围。验证和配置见 [Agent 思考模式与启动上下文](../validation/agent-thinking-context-20261109.md)。

## 2026-11-06：纠正次数只限制连续响应失败

旧计数在整个 Run 累加；回归证明三次分散的截断或结束格式错误，即使每次之间都成功修改源码，
仍在第三次出错时终止。按用户移除不必要停止条件的要求，有效 Reply 清空连续截断计数，
正常工具响应或合法完成声明清空连续完成格式计数。三次连续同类协议失败仍停止，
截断内容仍全部丢弃，不执行半个工具请求；终态、授权、调用用量和 Candidate/Delivery 语义不变。
此决定替代下文“同一 Run 内最多两次”的累计解释，不放宽 Provider 协议解析或输出材料边界。

## 问题与证据

真实 Run 82a89bba 在 28 次只读工具后以 RESPONSE_TRUNCATED/LENGTH 终止，无补丁或 Candidate。原计划的第一步仅确定契约，代码和测试延期；提示没有明确要求每步交付可运行增量。
适配器正确拒绝截断工具参数，编排却立即终止整次尝试。
DeepSeek 当前默认 thinking，工具会话要求回传 reasoning_content；现有 Application 消息没有此提供者字段，Spring AI 1.1.8 options 没有显式 thinking 控制。这是可复现的协议配置缺口，不证明历史截断正文的具体组成。

## 决定

- 规划保留原 schema、Parser/Resolver/Domain 与用户确认。提示要求代码与验证同一步交付，分析并入实现，优先少量纵向切片，不采用自然语言关键词黑名单代替领域审阅。
- 现有 Infrastructure adapter 通过 RestClient 补充官方 thinking.type=disabled，与现有非 reasoning 消息合同匹配；模型/endpoint 可配置，响应上限不变，无 SDK 升级或新依赖。
- LENGTH 内容及全部工具请求仍在 adapter 边界丢弃。Application 同一活跃 Run 内最多两次记录失败后重新请求较小动作，保留此前成功工具，不拼接半个 JSON，不重放成功补丁，不重启或跨 Run 续接对话。
- 其它结构错误、传输失败、未知工具结果、安全拒绝及 Delivery 恢复边界不扩大。取消、资格/基线重查、显式请求预算与墙钟仍生效。
- Run.modelCalls/tokens 统计正常返回回复；所有尝试看 MODEL_REQUEST，失败响应的 reportedTokens 单列 MODEL_FAILED。MODEL_RESPONSE_RECOVERY 不是领域成功或授权。
- 每轮只提供系统计算的探索/补丁/检查进度，不设任意探索停止阈值。压缩保留有界路径、行范围、实际修改/检查与失败事实；工具材料仍是不可信数据。

## 替代、代价与验证

单纯增加 token、丢弃错误或执行部分参数不能解决问题。保存完整提供者 reasoning 会扩展消息与隐私责任，此处选择现有合同支持的模式。
有限纠正可能增加付费调用，故保留用量记录和原预算，重复截断到界仍失败。规划提示不保证语义质量，正常审阅仍不可省略。
不新增 Domain 状态、数据库迁移、Workspace 权限或 Delivery 能力；配置模型须支持非 thinking 工具协议。
回归覆盖实际 HTTP 字段、截断不执行、成功工具不重放、纠正上限、原预算、其它失败不重试及 UI 非终态恢复。最终真实结果必须单独验证。

Source: [DeepSeek thinking protocol](https://api-docs.deepseek.com/guides/thinking_mode/).

## 同方向显式重规划

DOMAIN_MODEL.md §5.3 / §10.6 已允许同一 Selected Direction 的新 Plan 替代旧 Plan；现有 planning-rounds 入口却只接受 SUPERSEDED Plan，无法替换不适合执行的 ACTIVE Plan。
本次沿用入口和项目版本 CAS，Application 用领域 supersede() 形成隔离候选，ProjectContextRepository 增加明确的跨对象原子提交重载。Infrastructure 在写锁内核对旧 Plan/WorkingCopy、活动 Run/Delivery 和恢复状态，再一并写入失效状态与后继上下文；插入失败全部回滚。旧 Plan、Step、WorkingCopy 和历史结果不改写，Direction 保持 SELECTED。无迁移、新 Aggregate 或新依赖。
UI 要求明确确认；新轮次本身不调用 AI，不自动生成计划或授权执行。后续仍从原始资产及所选 RepositoryProfile 正常准备新副本，不复用未验证 Agent 内容。

## 精确原文编辑复用原写入边界

新版真实 Run b422067b 最终分页核对 12 次补丁请求中只有 1 次成功；保留的现场只有一个 44 行、尚未完成的方法文件。多次 TOOL_ERROR 的具体原因未留存，不能断言全部为行数错误；另有明确 CONTEXT_MISMATCH / INVALID_DIFF。原文阅读后仍反复失败，没有进入验证，因此通过取消 API 停止付费循环。
新增 edit_file(path, old_text, new_text)：既有文件只允许唯一精确原文片段替换；old_text 为空只用于不存在的新文件。Infrastructure 只读当前受控快照，从实际字节生成完整标准 diff，再复用 inspect_patch、原文及新增内容凭据检查、Git apply --check / apply、快照、Candidate 与 Delivery 全链。不直接写文件、不做模糊匹配、不推断或恢复半个模型输出。完整 diff 仍受原补丁大小预算约束；较大文件可继续使用原 apply_patch。
该变化只扩展 Application 给模型的工具，不修改 Python 协议 v2、镜像、权限、数据库或模块依赖。只读补丁解析器的四种已知固定 ValueError 另映射到 INVALID_DIFF 及安全枚举，复用原有重复/三次未解决冲突停止机制，避免通用 TOOL_ERROR 绕过纠错计数。未知错误与写入不确定性不推断为可恢复冲突。

## 新增测试内容的凭据拒绝

Run 6df36a31 已经六次成功精确编辑并通过真实编译，但新增集成测试内容触发 PATCH_CREDENTIALS/PATCH_CONTENT，尚未写入即终止。具体被识别的文本未留存，不推测是哪个值。严格策略继续拒绝凭据字段赋值（包括占位/表达式），不增加测试目录例外。
通过只读检查确认的新补丁内容拒绝现在使用明确的写入前事实。Application 复用原来整个 Run 共一次的合法替代机会，返回固定规则/阶段及未写入事实，要求保留测试要求、改用框架隔离身份或运行时输入；第二次触发任一写入前凭据规则仍终止。未知、写入后、来源不明确的拒绝不恢复。不修改 RepositorySecretPolicy，也不将脱敏文本写回。

## 新内容拒绝的安全定位

Run 2bd2a7cb 在相同集成测试提案上两次触发 PATCH_CREDENTIALS，原一次修正机制按预期终止，未产生 Candidate/Delivery。17 次模型请求、30 次工具、291231 报告 tokens；旧提案正文未留存，不能推断具体字段和值。只有规则名的反馈不足以稳定指导合法修正。
当前补充只定位模型自己提交的 new_text / patch 输入，最多八个一基行号；不记录原文、值、命中片段、变量名或指纹。拒绝结论仍来自原完整补丁检测；逐行检查只辅助定位，多行匹配保留首个变化位置，定位不能放行写入。原文凭据拒绝不返回位置。整个 Run 共一次修正、重复拒绝终止、未知阶段不恢复均不变。
