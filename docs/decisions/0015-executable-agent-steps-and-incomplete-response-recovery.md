# ADR-0015: 可执行 Step 与不完整模型响应纠正

Status: Accepted for 2026-10-25 autonomous FinTrack stabilization.

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
