package com.ayywl.delveforge.application.repositoryanalysis.map;

/**
 * 一个源文件**可能**在结构中扮演的角色。
 *
 * <h2>它是提示，不是结论</h2>
 *
 * <p>与 {@link RepositoryMaterialKind} 的区别是粒度：material kind 说「这是什么材料」，
 * role hint 只在源码内部进一步说「它大概是哪一层」。两者可以组合，例如一个 Java 配置类：
 *
 * <pre>
 * SOURCE_CODE + CONFIG_BOOTSTRAP
 * </pre>
 *
 * <p>推导只依据路径段与文件名的命名惯例（{@code controller/}、{@code *Service.java}…）。
 * 命名惯例可以被违反，因此这里**允许不确定**：归不进任何一类时使用 {@link #UNKNOWN}，
 * 而不是猜一个看起来更体面的角色。
 *
 * <p>这些提示只影响后续阶段从哪里开始找，不构成对文件内容的断言。一个文件是不是
 * 应用服务，只有它的内容被真正读进来、并经过解析与校验之后才能说。
 *
 * <p>一个文件可以同时带多个提示（例如一个既在 {@code service/} 下、文件名又以
 * {@code Mapper} 结尾的类）。{@link #UNKNOWN} 是例外：它表示「没有别的提示」，
 * 因此不会与其它提示同时出现。
 */
public enum RepositoryRoleHint {

    /** 对外接口入口：controller / api / resource / endpoint 一类。 */
    API_ENTRY,

    /** 应用服务或用例：service / usecase / manager 一类。 */
    APPLICATION_SERVICE,

    /** 领域或数据模型：domain / model / entity / dto / vo 一类。 */
    DOMAIN_MODEL,

    /** 持久化：mapper / repository / dao 一类。 */
    PERSISTENCE,

    /** 与外部系统交互：client / gateway / producer / consumer / listener / adapter 一类。 */
    INTEGRATION,

    /** 配置与启动装配：config / bootstrap / 应用入口类。 */
    CONFIG_BOOTSTRAP,

    /** 通用工具与支持代码：util / helper / support 一类。 */
    UTILITY,

    /** 没有可依据的命名信号。它不是「没有角色」，而是「从这里看不出来」。 */
    UNKNOWN
}
