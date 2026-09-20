/**
 * Workspace Gateway 的本地能力 Adapter：Git、Filesystem、Shell、Build / Test。
 *
 * <pre>
 * GitWorkspaceAdapter    WorkspaceReadPort 的本地 Git 实现（只读）
 * </pre>
 *
 * <p>所有本地代码操作必须经过此边界（RULE-ARCH-009）。
 *
 * <p>只读能力与代码修改能力在 Application 层是两个独立 Port（ADR-0001）。
 * 本包当前只实现只读的 {@code WorkspaceReadPort}——Repository Analysis 需要的就是它；
 * {@code WorkspaceMutationPort} 的实现等 Evolution Execution（M3 / M4）出现真实调用方时再补，
 * 不提前为它建立没有调用方的实现。
 */
package com.ayywl.delveforge.infrastructure.workspace;
