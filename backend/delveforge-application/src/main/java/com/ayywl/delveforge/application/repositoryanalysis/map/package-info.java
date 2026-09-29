/**
 * Repository Map：一个已解析 revision 上完整已提交树的确定性描述。
 *
 * <pre>
 * RepositoryMapBuilder   从 Workspace 的列目录结果建立 Map（不读文件内容）
 * RepositoryMap          引用 → 描述符 的完整集合
 * RepositoryMapEntry     单个文件的描述符
 * RepositoryFileReference  本次 Map 内的短名（RF-1）
 *
 * RepositoryPathClassifier  路径 → 材料类别 / 语言 / 结构角色
 * RepositoryMaterialKind    这个文件是什么材料
 * RepositoryRoleHint        源码可能扮演的结构角色
 * RepositoryLanguage        按扩展名识别的语言
 * RepositoryCandidateLane   它将来可能进入哪个候选组
 * </pre>
 *
 * <h2>它在整条链路里的位置</h2>
 *
 * <p>本包是 ADR-0004 两阶段设计的第一阶段。当前它**尚未接入**
 * {@code AnalyzeRepositoryUseCase}：现有材料收集与选材策略没有任何变化。
 *
 * <pre>
 * Repository Map → 确定性候选路由 → （后续）LLM Scout → 校验引用 → 定向读取
 * </pre>
 *
 * <h2>边界</h2>
 *
 * <p>本包只做确定性的、纯函数式的判断，并且：
 *
 * <pre>
 * 不读文件内容        输入只有提交树的元数据
 * 不调用 AI           因此不产生成本，也不需要失败重试语义
 * 不接触持久化        Map 不被保存，也不进入 Domain
 * 不做排除性取舍      分类不改变文件是否出现在 Map 中，只改变它进入哪个候选组
 * </pre>
 */
package com.ayywl.delveforge.application.repositoryanalysis.map;
