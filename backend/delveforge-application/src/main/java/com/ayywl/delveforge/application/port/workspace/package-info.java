/**
 * Workspace Gateway 抽象。
 *
 * <p>业务模块不得直接执行 Git / Shell / Build / Test 或直接操作本地文件系统，
 * 相关能力统一通过本包提供（RULE-ARCH-009）。
 *
 * <p>Workspace Gateway 在本包中按 capability 拆分为两个 Port：
 *
 * <pre>
 * WorkspaceReadPort       只读能力，Repository Analysis 等只读流程可依赖
 * WorkspaceMutationPort   代码修改能力，MVP 中仅 Evolution Execution 可依赖
 * </pre>
 *
 * <p>拆分的目的不是引入权限框架，而是让 RULE-ARCH-010 / RULE-ARCH-011 在类型层面成立：
 * 只读流程的依赖中根本不出现修改能力。
 *
 * <p>本包当前只定义能够合理确定的最小操作集（文件树、读文件、HEAD revision、写文件）。
 * {@code DOMAIN_MODEL.md} §12.13 列出的 Shell / Build / Test / Diff 属于 Workspace
 * 的最终职责范围，将在对应 Use Case 出现时按真实需求补充，不提前预设签名。
 */
package com.ayywl.delveforge.application.port.workspace;
