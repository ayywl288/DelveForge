# 验证记录入口

这些记录描述各次验证时的代码、环境和结果，不代表当前版本自动获得同样结论。
产品、架构、领域规则与开发优先级分别以 `docs/` 下四份核心文档为准。

- 最新界面与指南呈现：[最终细化](final-agent-ui-polish-20261108.md)；前置的安全与界面回归：[材料收集与 Run 呈现](agent-run-quickstart-refinement-20261108.md)、[Review Fix](agent-run-quickstart-review-fix-20261108.md)。
- 当前完成事实与独立 Quick Start 的实现验证：[工作流收敛](final-agent-workflow-20261108.md)。以上记录均未运行新的付费模型或真实 AgentRun。
- 2026-11-04 历史真实 MVP 验收：[验收报告](real-mvp-product-acceptance-20261104.md)。功能交付有证据，但独立验收晚于自动 Delivery，当次严格流程验收仍为 FAIL。
- 该次交付的固定证据：[证据摘要](real-mvp-acceptance-20261104/evidence.json)、[冻结 Candidate](real-mvp-acceptance-20261104/frozen-candidate.json)、[验收约定](real-mvp-acceptance-20261104/acceptance-contract.json)。
- 本地分支、文件与 Docker 收尾：[清理记录](local-cleanup-20261105.md)。
- 最近的工具错误处理修正：[读取工具错误恢复](read-tool-error-recovery-20261104.md)。
- 随机全栈试验及失败轨迹：[最终记录](final-random-fullstack-20261104.md)、[恢复执行](random-fullstack-resumption-20261104.md)、[补丁冲突恢复](patch-conflict-resumption-20261104.md)。
- 安全与完整性决策依据：[准入边界](safe-admission-boundary-20261102.md)、[源码保护与执行](source-protection-execution-20261104.md)、[基线感知诊断](baseline-aware-evolution-diagnosis-20261104.md)。
- 构建与前端证据演进：[原生构建预检](native-build-preflight-20261103.md)、[脚本执行](sandbox-script-evolution-20261103.md)、[早期前端检查](frontend-checks-initial-20261102.md)、[真实 HomeOps 验收](authentic-homeops-product-20261103.md)。

其余日期化报告、JSON 与上述记录一起保留，供复核不同试验和失败原因使用；不能因报告较旧而推定其证据已失效。
