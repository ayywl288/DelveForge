package com.ayywl.delveforge.application.repositoryanalysis.map;

/**
 * 一个描述符将来可能进入哪个候选组。
 *
 * <h2>分类与路由是两件事</h2>
 *
 * <pre>
 * 分类  这个文件是什么          RepositoryMaterialKind / RepositoryRoleHint
 * 路由  它将来可能怎么被选中    本类型
 * </pre>
 *
 * <p>分开的理由是它们会独立变化：新增一种材料类别不应该改变某个已有类别进入哪一组；
 * 调整选取策略也不应该改变对文件的描述。把两者压成一个枚举，会让每次策略调整都看起来
 * 像一次「重新分类」。
 *
 * <p>本类型只表达**分组**，不表达预算、顺序、数量与优先级——那些属于后续阶段的选取策略，
 * 当前不存在。因此这里没有任何「取前 N 个」之类的语义。
 *
 * <h2>这是当前阶段的分组，不是领域规则</h2>
 *
 * <p>它由 ADR-0004 决定的两个阶段推出：
 *
 * <pre>
 * Foundation Material   回答「这是什么工程」——元数据、配置、文档、脚本、部署、数据模型
 * Scout 源码候选        回答「它实现过什么」——非生成的业务源码，交给后续的 Scout 指认
 * NONE                  当前不进入任何一组
 * </pre>
 */
public enum RepositoryCandidateLane {

    /** 基础材料候选：规模小、可稳定取全，用来建立对仓库工程形态的认识。 */
    FOUNDATION,

    /**
     * 未来 Scout 的源码候选：非生成的业务源码。
     *
     * <p>它们数量最多、内容最需要被取舍，因此不能整体读进来，而要先被指认再定向读取
     * （ADR-0004）。
     */
    SCOUT_SOURCE,

    /**
     * 当前不进入任何候选组。
     *
     * <pre>
     * GENERATED_VENDOR  生成物与依赖：不是这个仓库写的代码
     * TEST_CODE         测试代码与测试定义：回答的是「怎么测」，不是「产品实现什么」
     * </pre>
     *
     * <p>把 {@code TEST_CODE} 放在这里是**当前选择**，不是结论：测试确实能反映业务行为，
     * 是否让它们进入 Scout 候选应当由后续阶段的真实证据决定。
     *
     * <p>当前排除的理由只是「Scout 要找的是产品实现，测试回答的是怎么测」，与数量无关。
     * 真实验证仓库在 139 个已提交 blob 上是 12 个测试文件/测试计划对 84 个源码候选
     * （见 {@code RealRepositoryShapeTest}），因此**不存在**「测试会把候选集稀释」这类
     * 数量层面的依据。
     */
    NONE;

    /**
     * 判断一个描述符进入哪一组。
     *
     * <p>规则刻意保持最小，只区分「这是什么材料」与「是不是业务源码」两件事：
     *
     * <pre>
     * 生成物 / 测试            → NONE
     * 非源码材料               → FOUNDATION
     * 源码且角色恰好是 CONFIG_BOOTSTRAP → FOUNDATION
     * 其余源码                 → SCOUT_SOURCE
     * </pre>
     *
     * <p>「恰好是 CONFIG_BOOTSTRAP」按集合判断：一个类如果同时是配置类**又**带有
     * 业务角色提示（例如既在 {@code config/} 下又叫 {@code *Service}），说明它更像业务代码，
     * 因此进入 {@link #SCOUT_SOURCE}。判据是角色提示集合本身，不是其中是否包含某一项——
     * 后者会让「同时是配置类和服务类」这种情形在两条规则下都成立。
     *
     * @param entry 待判断的描述符，不得为 {@code null}
     * @return 该描述符所属的候选组
     */
    public static RepositoryCandidateLane of(RepositoryMapEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("判断候选组必须指定 entry");
        }
        if (entry.materialKind() == RepositoryMaterialKind.GENERATED_VENDOR
                || entry.materialKind() == RepositoryMaterialKind.TEST_CODE) {
            return NONE;
        }
        if (entry.materialKind() != RepositoryMaterialKind.SOURCE_CODE) {
            return FOUNDATION;
        }
        if (entry.roleHints().size() == 1
                && entry.hasRoleHint(RepositoryRoleHint.CONFIG_BOOTSTRAP)) {
            return FOUNDATION;
        }
        return SCOUT_SOURCE;
    }
}
