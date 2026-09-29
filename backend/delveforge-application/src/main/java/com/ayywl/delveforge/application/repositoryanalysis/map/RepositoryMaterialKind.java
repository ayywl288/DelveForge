package com.ayywl.delveforge.application.repositoryanalysis.map;

/**
 * 一个文件属于哪一类材料。
 *
 * <p>它回答的是「这个文件是什么」，不是「它有多重要」。后者由候选路由与后续阶段决定
 * （见 {@link RepositoryCandidateLane}）——分类与路由是两个独立的问题。
 *
 * <h2>这是 Application 层的启发式判断，不是领域规则</h2>
 *
 * <p>取值只由路径、文件名与扩展名推导，不看内容。因此它一定会误判，问题只在于误判的
 * 后果是否可接受：分类错误会改变一个文件进入哪个候选组，但**不会让文件从 Map 中消失**。
 * 这是刻意设计的——M1 的层级筛选之所以造成系统性盲区，正是因为它让文件直接不可见。
 *
 * <p>出现归不进任何一类的文件时使用 {@link #OTHER}，不要为了「看起来整齐」把不确定的
 * 文件塞进一个语义不符的类别。
 */
public enum RepositoryMaterialKind {

    /** 业务或库的源代码。测试代码单独见 {@link #TEST_CODE}。 */
    SOURCE_CODE,

    /** 测试代码与测试定义（含 {@code .jmx} 这类测试计划）。 */
    TEST_CODE,

    /** 工程与构建元数据：说明这是什么工程、依赖什么、怎么构建。 */
    BUILD_METADATA,

    /** 运行时与框架配置。 */
    CONFIGURATION,

    /** 数据库 schema、DDL 与迁移脚本。 */
    DATA_SCHEMA,

    /** 文档：说明、报告、计划、导读。 */
    DOCUMENTATION,

    /** 脚本与自动化：构建、压测、运维、一次性工具。 */
    SCRIPT_AUTOMATION,

    /** 部署与运行时编排：容器、编排文件、CI 工作流、基础设施定义。 */
    DEPLOYMENT,

    /**
     * 生成物与第三方依赖：构建输出目录、依赖目录、锁文件、压缩/打包产物。
     *
     * <p>它们仍然出现在 Map 中（Map 表示完整的已提交树），但不会被当作业务源码候选。
     */
    GENERATED_VENDOR,

    /** 其余内容：数据样本、无扩展名文件、映射表未覆盖的类型。 */
    OTHER
}
