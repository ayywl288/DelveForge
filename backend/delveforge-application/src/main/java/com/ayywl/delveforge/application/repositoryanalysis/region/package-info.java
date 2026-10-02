/**
 * Region 分层导航：当一次分析的 flat File Catalog 超出预算时，先在**目录层**缩小范围
 * （ADR-0005）。
 *
 * <pre>
 * RepositoryMap
 *         ↓  只取 SCOUT_SOURCE，按路径前缀聚合
 * RepositoryRegionTree            确定性 Region 视图（不读内容）
 *         ↓  取某一层的兄弟 Region
 * RepositoryRegionCatalog         一次调用的可选项 + invocation-local RR-* 编号
 *         ↓  Prompt（只有目录描述符）
 * RepositoryRegionScoutExtraction 调用 Region Scout → 严格解析 → 引用校验
 *         ↓
 * RepositoryRegionSelection       有序的分支优先级（导航提示，不是仓库事实）
 * </pre>
 *
 * <h2>Region 不是领域概念</h2>
 *
 * <p>Region 是本包内的临时导航元数据：没有身份、没有生命周期、不持久化、不进入 Domain。
 * 它只描述「某个目录前缀下有多少源码候选」，这些事实全部来自既有的 {@code RepositoryMap}。
 *
 * <h2>它只回答「往哪里看」</h2>
 *
 * <p>Region Scout 的输出是 inspection hint，不是 Repository fact（ADR-0005）。
 * 「这个目录实现了什么」只有在文件内容真的被读进来、并经过既有分析之后才成立。
 *
 * <h2>本包包含分层导航与分支 Scout 执行</h2>
 *
 * <pre>
 * RepositoryRegionNavigator      递归下降 → 有序终态组
 * RepositoryBranchScoutRunner    逐组跑一次分支本地 File Scout，再保序轮转合并
 * RepositoryFileCandidates       有序可信文件候选
 * </pre>
 *
 * <p>分支本地 File Scout 复用 {@code scout} 包的既有契约，不另写解析或提示词；
 * 每组用自己那份重新编号的目录，跨分支的引用互不可解析。
 *
 * <p>本包**不**读文件内容、不改材料预算、不接入 RepositoryReadPlanner——
 * 那些属于后续步骤。这里只回答「哪些文件、按什么顺序」。
 */
package com.ayywl.delveforge.application.repositoryanalysis.region;
