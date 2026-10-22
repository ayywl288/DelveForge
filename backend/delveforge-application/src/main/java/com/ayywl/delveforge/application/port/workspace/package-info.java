/**
 * Application 拥有的本地代码操作边界（RULE-ARCH-009）。
 *
 * <p>Repository Analysis 仅依赖 WorkspaceReadPort；Evolution 通过独立的 provisioning、inspection
 * 和 CandidateDeliveryPort 使用受控副本能力。Delivery 消费冻结结果，模型不拥有正式 Git 写入权。
 * 构建和测试在 AgentSandboxPort 隔离执行，不由业务模块直接操作宿主文件或 Shell。
 */
package com.ayywl.delveforge.application.port.workspace;
