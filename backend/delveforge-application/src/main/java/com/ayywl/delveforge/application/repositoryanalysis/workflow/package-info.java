/**
 * Analyze Repository 流程：把只读 Workspace、AI 提议与 Repository Profile 串成一条链路。
 *
 * <pre>
 * SoftwareAsset → 只读 Workspace → analyzedRevision → 材料 → AI 提议
 *     → RepositoryProfile → 保存
 * </pre>
 *
 * <p>本包只依赖 {@link com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort}：
 * Repository Analysis 对原始软件资产保持只读（RULE-ARCH-010、RULE-DOM-005），
 * 因此这里不存在任何修改能力，也不直接执行 Git / Shell / 文件系统操作。
 *
 * <p>{@code RepositoryAnalysisMaterialPolicy} 描述的是当前 Application 层的 MVP 选材策略，
 * 不是领域规则：领域模型只要求分析针对确定的软件状态，没有规定读多少。
 */
package com.ayywl.delveforge.application.repositoryanalysis.workflow;
