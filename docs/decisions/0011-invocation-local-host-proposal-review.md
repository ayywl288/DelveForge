# ADR-0011：调用内的可选宿主机提案审查

Status: Accepted

Date: 2026-10-19

## Context

真实 E2E 中，验收代理必须先检查模型生成代码的宿主机执行风险。旧验收中继扣住 Provider
SSE 结束标记等待审查，导致审查时间计入 Provider 的 90 秒空闲期限；首次 Repair 已因此在
准备阶段失败并保留 EXECUTING。将中继审查限制为 60 秒后，又出现明确的 READY 准备失败。
这不是 Maven 或业务失败；反复重新调用模型不能解决审查与传输期限耦合。

## Decision

在 Application 严格校验出调用内 ValidatedExecutionProposal 后，允许通过只读的
ExecutionProposalReviewPort 执行可选宿主机技术审查；审查前后重新核对领域与 Workspace
资格。Provider 已完整返回，原 execute/prepare 调用继续持有同一对象，不跨请求存储提案，
不接受客户端回传可信对象，不新增 Proposal approval Aggregate 或持久化授权状态。

此审查只能额外否决，不能替代 Step/Repair/Retry 的既有授权、改变 Criteria、证明业务成功
或跳过执行前 revalidation。正常 execute 仍在审查之后保存 EXECUTING，再请求 Workspace
写入。Repair 的既有授权/检查点顺序不变；本改动不恢复旧 EXECUTING 或实现 Recovery。

默认关闭，保留原行为。显式配置时，Infrastructure 仅允许直接访问指定的 IPv4 loopback
HTTP 端点，禁止代理、重定向、用户信息和任意外部主机。连接超时 5 秒、读取超时 300 秒，
请求/响应有字节上限，响应必须明确批准并回显精确补丁 SHA-256；拒绝、异常或不匹配失败关闭。
请求不包含 Provider 凭据，不记录源码或响应正文到普通应用日志。

## Alternatives and Consequences

不通过增加 Provider idle/absolute timeout、伪造心跳或重放响应解决问题。跨调用保存可执行
提案或新增一次性执行引用会改变现有调用内边界，本轮不采用。验收中继恢复原始响应透传，
宿主机审查在另一项只读技术交互中完成，Provider 调用与费用仍按真实物理请求记录。

代价是显式启用该能力的调用增加一个有界外部检查点；审查期间资格可能变化，因此成功
审查后的再次 revalidation 为强制条件。审查不是安全沙箱，也不能证明旧进程已失去写能力。
