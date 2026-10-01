/**
 * 读取规划：把 Repository Map 与 Scout 的查看计划，在各自的预算内转成「接下来读哪些文件」。
 *
 * <pre>
 * RepositoryMaterialBudget   一组预算（maxFiles / maxFileBytes / maxTotalBytes）
 *
 * RepositoryReadPlanner      规划器：两条通道各自轮转选取
 * RepositoryReadPlan         结果：有序的基础材料 + 有序去重的定向源码 + 跳过诊断
 * SkippedReadCandidate         一条跳过诊断
 * RepositoryReadSkipReason     跳过原因
 * </pre>
 *
 * <h2>两条通道</h2>
 *
 * <pre>
 * Foundation      材料类别轮转，任何一类不能独占预算；不使用 AI
 * 定向源码        聚焦区域轮转，保持 Scout 给的优先级；跨区域重复只读一次
 * </pre>
 *
 * <p>两条通道的预算互相独立，选取顺序也各自保留——那就是后续读取的顺序。
 *
 * <h2>它在整条链路里的位置</h2>
 *
 * <p>本包是 ADR-0004 第二与第三阶段之间的过渡。它**尚未接入**
 * {@code AnalyzeRepositoryUseCase}：现有 Repository Analysis 的材料收集与选材策略
 * 没有任何变化。
 *
 * <pre>
 * Repository Map → 候选路由 → Scout → 查看计划 → 读取规划（本包） → 定向读取（后续）
 * </pre>
 *
 * <h2>本包不做什么</h2>
 *
 * <pre>
 * 不读取文件内容        取舍只依据列目录得到的 blob 大小
 * 不持有 Workspace      因此没有任何读取入口，也就无法误用
 * 不调用 AI             Foundation 的选取完全是确定性的
 * 不截断 / 不分块        放不下就是不读，不送半份内容给模型
 * 不持久化              计划是一次分析内的中间结果，也不进入 Domain
 * </pre>
 */
package com.ayywl.delveforge.application.repositoryanalysis.readplan;
