# ADR-0008：稳定演化作用域与规划轮次

Status: Accepted

Date: 2026-10-11

## 问题、边界与决策

Task F 的多个项目可以复用画像、仓库与分析快照，但原 INV-D09 查询和 V7 索引仍限制
全局仅一个 SELECTED。项目 B 的明确选择会废弃项目 A 的 Direction 和可继续 Plan。
这不是前端过滤问题；需要显式演进单流程规则。本决策替代 ADR-0007 中保留全局选择的部分。

采用稳定的 `flowId` 持久化归属，不增加 Aggregate 或第二套状态机：

- 新项目的服务端身份也是初始 flowId。方向发现保存时核对上下文版本、画像身份、
  已关联分析及资产；在同一事务写入候选的不可变归属。导航列表本身不能制造归属。
- Direction 选择由目标方向的已保存 flowId 查找当前 SELECTED。客户端可提供上下文 ID
  和 expectedVersion，但不能提交 flowId；后端检查真实归属，不按画像、路径或时间查询。
- 每个已知 flowId 最多一个 SELECTED；旧方向与它的 PROPOSED / ACTIVE Plan 的
  SUPERSEDED 转换在一个 SQLite 事务中提交。状态 CAS、唯一索引和归属检查共同生效。
- Plan 从它的 Direction 继承 flowId。规划、激活、Step 确认与检查点提交仍验证绑定、
  授权与 revision，并要求 Plan / Direction 同属一个已知作用域。
- 无项目参数的方向发现把新候选保存到兼容的 `standalone` 作用域。旧选择接口仍按目标
  Direction 的实际归属限定替代范围，Planning 仍继承该归属；不是全局选择，也不是新的
  多用户访问控制。Web 正常操作发送上下文及其版本。未归属历史不自动并入 standalone。
- 一个导航上下文仍最多绑定一份不可替换的 Plan。重新规划创建同 flowId 的后续上下文，
  保存 previousContextId；原上下文、Plan、WorkingCopy 和检查结果保留。创建轮次不调用 AI。

## 历史数据与取舍

V16 增加 Direction / Plan / Context 的 flow_id、Context.previous_context_id 和作用域索引。
旧方向仅在一个上下文明确引用且画像身份一致时归属该上下文；多个上下文共同引用的方向
不猜归属。没有上下文的旧 Plan 按真实 Plan → Direction 链获得 `legacy-<Direction ID>`。
没有上述明确引用的旧方向保持 NULL、可读而不能选择或规划。

迁移不更改任何生命周期、授权、画像内容、Git revision 或结果。历史 SUPERSEDED 是终态，
本轮不提供自动回退；误伤也不能凭日志推断恢复 ACTIVE。可在已知作用域中显式选择剩余
CANDIDATE，然后建立新规划轮次；无候选时需用户显式请求新的发现，不自动付费。

相比删除全局唯一索引或按 UserProfile / Repository 过滤，稳定归属增加少量持久化字段，
但解决共享资源与跨项目并发的歧义。完整工作流引擎和终态恢复超出本任务。
迁移后不能直接运行旧版本：旧代码不理解多个 SELECTED，回滚必须恢复升级前的一致备份。
