# ADR-0010：技术验证与独立产品业务验收分层

Status: Accepted

Date: 2026-10-19

## Context

Three-Bundle 首轮的 Spring REST 计划把真实 HTTP 业务效果保存为 UNSUPPORTED。
M4 只有四类受控技术证据，不能合法地满足该标准。BUILD / TEST / DIFF_CHECK 通过也
不足以证明产品实际可用。用户明确批准 D1-A，要求两层都成为产品交付的强制关卡。

## Decision

新计划的 Direction、TargetState 与 Step Goals / scope / plannedChanges 必须完整保留业务要求。
Step 内技术 Criteria 只证明技术事实；独立验收方在编码前冻结业务合同，绑定真实所选方向、
计划、用例版本和内容哈希，从最终精确 Verified Revision 独立构建、启动及执行真实 HTTP / 数据断言。
最终报告同时包含技术成功、业务成功与产品可用性；任何一层失败或无法验证都不能交付。

独立合同当前由验收工具维护，不新增 Aggregate、API、数据库 schema 或生命周期状态。
当前 DelveForge 的 Step / Plan SUCCEEDED 仍只代表既有技术事实，不证明独立关卡已通过。
业务验收结果不得回写或制造 Step 成功；合同不能仅由被测模型生成或自评。

既有 Plan、UNSUPPORTED、结果与 revision 原样保留。用户要求进入正式 Step Criterion 的
业务效果仍为 UNSUPPORTED，没有可信 adapter 就拒绝 mutation。不得把旧标准删除或替换为技术标准。

“最终必须启动并通过真实 HTTP / 数据验收”属于强制产品交付关卡，本身不表示要求
将该项放入 Step verificationCriteria；Direction 的 userFit、risks 或依据中出现这类要求
也不能推断为 Step 内证明。只有明确指定进入 Step 正式 Criterion 的要求才适用上述
UNSUPPORTED 边界。两类要求都必须保留，不能把独立产品验收省略为可选事项。

## Alternatives and Consequences

正式增加受控 HTTP 证据 adapter 可将业务事实纳入领域，但需要新的证据来源、边界、
revision 绑定、持久化和失败语义设计，超出本轮最小修复。放宽 UNSUPPORTED 或让模型
声称 PASS 均不可接受。

分层方案保持当前受控执行规则，代价是产品内部状态不证明业务完成；调用方必须明确披露
并执行独立关卡。提示约束不保证模型正确，实际新计划仍须审查业务覆盖与可执行性。
这不实现 Recovery、宿主机隔离升级、通用自然语言证明或发布能力。
