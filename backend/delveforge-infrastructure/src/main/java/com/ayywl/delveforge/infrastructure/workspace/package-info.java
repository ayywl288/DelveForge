/**
 * Workspace 端口的 Git 与文件系统实现。
 *
 * <p>GitWorkspaceAdapter 实现只读 Repository Analysis、独立 WorkingCopy 准备与 inspection，
 * 并通过 CandidateDeliveryPort 实现确定性冻结结果交付。Application 通过各自能力端口访问，
 * 不把交付写能力提供给只读分析流程（RULE-ARCH-010 / RULE-ARCH-011）。
 */
package com.ayywl.delveforge.infrastructure.workspace;
