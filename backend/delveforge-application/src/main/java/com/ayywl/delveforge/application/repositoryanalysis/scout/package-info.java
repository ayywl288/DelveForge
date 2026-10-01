/**
 * Repository Scout：从源码候选的描述符得到一份「接下来去哪里看」的计划。
 *
 * <pre>
 * RepositoryScoutInputs         一次调用的输入：analyzedRevision + 源码候选描述符（无内容）
 *
 * AiRepositoryScoutProposal     AI 返回的不可信提议
 * AiRepositoryScoutFocusArea      其中一个查看区域
 * RepositoryScoutProposalParser   原始文本 → 严格解析（区域数量 / 标签 / 引用格式 / 区域内不重复）
 * RepositoryScoutProposalResolver 引用校验 → 换回真实描述符
 *
 * RepositoryInspectionPlan      可信结果：有序的查看区域
 * RepositoryInspectionArea        其中一个区域：标签 + 已解析的描述符
 *
 * RepositoryScoutExtraction     编排：输入 → AI → 解析 → 校验 → 计划
 * </pre>
 *
 * <h2>它在整条链路里的位置</h2>
 *
 * <p>本包是 ADR-0004 两阶段设计的第二阶段。它**尚未接入**
 * {@code AnalyzeRepositoryUseCase}：现有 Repository Analysis 的材料收集与选材策略
 * 没有任何变化。
 *
 * <pre>
 * Repository Map → 确定性候选路由 → LLM Scout（本包） → 校验引用 → 定向读取（后续）
 * </pre>
 *
 * <h2>语义边界</h2>
 *
 * <pre>
 * Scout                  where should we inspect?      去哪里看
 * Repository Analysis    what does it actually prove?  这些内容说明了什么
 * </pre>
 *
 * <p>本包的输出是**查看意图**，不是仓库事实。区域标签与分组不得被当作 RepositoryProfile
 * 的能力或依据：模型只见过路径，没见过代码。真正的结论只能来自内容被读进来之后的
 * Repository Analysis。
 *
 * <h2>本包不做什么</h2>
 *
 * <pre>
 * 不读取任何文件内容          输入只有描述符；计划也不触发读取
 * 不按计划去读文件            定向读取属于后续阶段
 * 不做预算 / 轮转 / 合并       Foundation 与定向源码各占多少、重复引用怎么合，都还没有消费者
 * 不做分层 Scout              当前清单规模（真实验证仓库约 84 条）不需要
 * 不接触持久化                计划不保存，也不进入 Domain
 * </pre>
 */
package com.ayywl.delveforge.application.repositoryanalysis.scout;
