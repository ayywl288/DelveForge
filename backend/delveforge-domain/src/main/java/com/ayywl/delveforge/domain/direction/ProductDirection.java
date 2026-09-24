package com.ayywl.delveforge.domain.direction;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;
import java.util.ArrayList;
import java.util.List;

/**
 * Product Direction Aggregate Root（DOMAIN_MODEL.md §3.5、§11.6）。
 *
 * <p>Product Direction 是系统结合 User Profile 与一个或多个 Repository Profile 后
 * 发现的候选产品方向，回答：
 *
 * <pre>
 * 针对当前用户
 * 可以基于哪些已有软件能力
 * 演化出什么产品
 * 以及为什么这个方向值得考虑
 * </pre>
 *
 * <p>它是「值得做什么」的候选答案，而不是「具体应该怎么改代码」的实施计划。
 *
 * <p>本类当前只承载 Aggregate 自身的领域语义：
 *
 * <pre>
 * 保存方向本身的内容与其分析来源
 * §6.2 定义的全部状态转换
 * </pre>
 *
 * <p>不包含：方向如何被发现（属于 AI 与 Application）、方向之间的排序或评分、
 * 以及 §8.4 中「每次生成 3–5 个方向」这类跨方向的数量约束。后者作用于一组方向，
 * 不属于任何单个 Aggregate。
 *
 * <h2>生命周期与状态转换</h2>
 *
 * <p>创建出来的方向天然处于 {@link ProductDirectionStatus#CANDIDATE}（§8.4），
 * 此后只能沿 §6.2 定义的三条边上移动：
 *
 * <pre>
 * CANDIDATE --select()-----&gt; SELECTED
 * CANDIDATE --reject()-----&gt; REJECTED
 * SELECTED  --supersede()--&gt; SUPERSEDED
 * </pre>
 *
 * <p>§6.2 没有为 {@code REJECTED} 或 {@code SUPERSEDED} 定义出边，也没有定义
 * 自环，因此这些组合一律被拒绝。本 Aggregate 不提供通用的 setStatus：
 * 状态只能通过这些带语义的操作改变（AGENTS.md §8.6、RULE-DOM-002）。
 *
 * <p>这些操作只判断「该转移在当前状态下是否被允许」。是否真的由用户发起，
 * 属于 Application 的显式请求，不由领域模型推测（INV-D07、RULE-DOM-004）：
 * 领域层不提供任何「系统认为匹配度最高所以自动选中」的入口。
 *
 * <h2>状态变化不影响分析依据</h2>
 *
 * <p>方向进入 SELECTED 或 SUPERSEDED 后，仍然必须回答它当初是凭什么被推荐的，
 * 因此状态变化不得丢失或改写生成该方向时的分析依据（§10.5、INV-D02）。
 * 这一点不靠约定，而是靠本类的字段划分：除 {@code status} 外全部字段不可变，
 * 内容集合在创建时即固化，Aggregate 没有任何可以改写它们的操作。
 *
 * <p>REJECTED 与 SUPERSEDED 的方向同样保留：它们的历史解释能力不因被放弃而消失
 * （§10.5、RULE-DOM-007）。
 *
 * <h2>与其他 Aggregate 的关系</h2>
 *
 * <p>它通过身份引用分析来源，不拥有 User Profile、Repository Profile 或 Software Asset
 * （§11.6）：
 *
 * <pre>
 * userProfileId + userProfileRevision   → User Profile Aggregate（INV-D01、INV-D02）
 * repositoryProfileIds                  → Repository Profile Aggregate（INV-D05）
 * candidateAssetIds                     → Software Asset Aggregate
 * </pre>
 *
 * <p>{@code userProfileRevision} 记录的是生成该方向时所依据的版本，它不随后续
 * User Profile 更新而变化（INV-D02）——本类是不可变的，也不订阅任何外部变化。
 *
 * <h2>INV-D09 不属于本 Aggregate</h2>
 *
 * <p>INV-D09 规定的是「系统全局最多一个当前 SELECTED Product Direction」，
 * 以及切换方向时原方向必须同批进入 SUPERSEDED。这是一条跨 Aggregate invariant：
 * 它约束的是一组 Product Direction 与其它 Aggregate 的协作，而不是任何一个
 * Product Direction 自身的合法性。因此本 Aggregate 只保护自己的状态机——
 * 它允许一个处于 SELECTED 的方向被 supersede，但既不检查、也无法检查
 * 当前是否已经存在另一个 SELECTED 方向。该约束由 Application 在同一个
 * Use Case 内协调完成。
 *
 * <h2>当前实现选择</h2>
 *
 * <p>以下都是本 Task 的实现选择，不是 DOMAIN_MODEL.md 的规定：
 *
 * <pre>
 * 哪些内容必填
 *     §3.5 把 title / problem / targetProduct / userFit / differentiation /
 *     technicalValue / estimatedComplexity 列为该方向表达的核心内容，§8.4 也要求
 *     每个生成的方向都给出这些内容，因此它们必须非空白。risks 允许为空：
 *     一个真实存在的方向可能确实没有已识别的主要风险。
 *
 * candidateAssetIds 不得为空
 *     同样不是实现选择：INV-D10 要求每个方向至少标识一个 Candidate Software Asset。
 *     但 INV-D10 的另一半——这些资产必须来自该方向所引用 Repository Profiles 对应的
 *     Software Assets——本 Aggregate 只校验前一半：后一半需要结合 Repository Profile
 *     Aggregate 才能判断，单个 Aggregate 的状态不足以完成它（§12.3 Case 1）。
 *
 *     这并不意味着它落到了 Application 身上。按 §12.3 Case 3 与 §12.4，
 *     「结合多个 Aggregate 才能形成的领域生成与校验」属于 Domain Service，
 *     即 ProductDirectionDiscoveryService：Application 加载 User Profile 与
 *     Repository Profile 并协调调用，由该 Domain Service 校验资产与输入
 *     Repository Profiles 的对应关系（§12.12）。本 Aggregate 因此不会拒绝与该方向
 *     Repository Profile 依据不相符的 assetId——「单个 Aggregate 不负责」不等于
 *     「Domain Layer 不负责」，只是执行这条校验的位置在 Domain Service。
 *
 * repositoryProfileIds 与 evidence 不得为空
 *     这两条不是实现选择，而是 Invariant 的直接编码：INV-D05 要求方向必须能追溯到
 *     至少一个明确的 Repository Profile；INV-D06 要求关键判断具有可追溯 Evidence，
 *     一个没有任何 Evidence 的方向无法满足它。领域模型没有规定具体条数，
 *     因此这里只要求至少一条。
 *
 * userProfileRevision 必须为正数
 *     INV-D01 要求方向能追溯到确定的 {@code UserProfileId + revision}，
 *     而 User Profile 的 revision 自 1 开始（§10.3），0 或负数不对应任何版本。
 *
 * 不提供 reconstitute
 *     本 Task 只提供创建与状态转换。恢复已保存状态（含 SELECTED / SUPERSEDED）
 *     的入口属于 Persistence 设计，届时需要同时回答「恢复出的状态是否绕过状态机」，
 *     因此不在此处提前给出。
 * </pre>
 *
 * <p>不包含：方向如何被生成、如何被排序展示、选择后的 Evolution Planning、
 * 以及本 Aggregate 的持久化与历史保留。
 */
public class ProductDirection {

    private final ProductDirectionId id;
    private final UserProfileId userProfileId;
    private final int userProfileRevision;
    private final List<RepositoryProfileId> repositoryProfileIds;
    private final String title;
    private final String problem;
    private final String targetProduct;
    private final String userFit;
    private final List<SoftwareAssetId> candidateAssetIds;
    private final String differentiation;
    private final String technicalValue;
    private final String estimatedComplexity;
    private final List<String> risks;
    private final List<Evidence> evidence;

    private ProductDirectionStatus status;

    /**
     * 创建一个新的候选 Product Direction。
     *
     * <p>新方向一律进入 {@link ProductDirectionStatus#CANDIDATE}（§8.4）：系统可以主动
     * 生成和推荐方向，但不能在生成时就替用户做出选择（INV-D07）。
     *
     * <p>本方法只固化调用方已经确定的内容，不判断这些内容是否正确、是否足够有说服力：
     * AI 输出不会因为进入了这个构造函数就成为可信领域状态，它必须先经过 Application
     * 的解析与校验（RULE-DOM-003）。因此这里的校验只覆盖领域上不可缺失的部分——
     * 分析来源的可追溯性与内容的存在性，不涉及质量。
     *
     * @param id                    方向标识，不得为 {@code null}
     * @param userProfileId         生成该方向所依据的 User Profile，不得为 {@code null}（INV-D01）
     * @param userProfileRevision   所依据的 User Profile 版本，不得小于 1（INV-D01、INV-D08）
     * @param repositoryProfileIds  生成该方向所依据的 Repository Profile；不得为
     *                              {@code null} 或空（INV-D05），元素不得为 {@code null}
     * @param title                 方向的简短名称，不得为 {@code null} 或空白
     * @param problem               希望解决的核心问题或需求，不得为 {@code null} 或空白
     * @param targetProduct         候选产品的大致目标形态，不得为 {@code null} 或空白
     * @param userFit               与 User Profile 的主要匹配点，不得为 {@code null} 或空白
     * @param candidateAssetIds     可以用于实现该方向的 Software Asset；不得为 {@code null}
     *                              或空（INV-D10），元素不得为 {@code null}
     * @param differentiation       与原项目或常见方案相比的主要差异，不得为 {@code null} 或空白
     * @param technicalValue        可以体现或获得的技术价值，不得为 {@code null} 或空白
     * @param estimatedComplexity   对整体演化成本的粗粒度判断，不得为 {@code null} 或空白
     * @param risks                 当前已知主要风险；不得为 {@code null}，可以为空，
     *                              元素不得为 {@code null} 或空白
     * @param evidence              支撑该方向的领域依据；不得为 {@code null} 或空（INV-D06），
     *                              元素不得为 {@code null}
     * @throws IllegalArgumentException 任一参数不满足上述约束
     */
    public static ProductDirection create(
            ProductDirectionId id,
            UserProfileId userProfileId,
            int userProfileRevision,
            List<RepositoryProfileId> repositoryProfileIds,
            String title,
            String problem,
            String targetProduct,
            String userFit,
            List<SoftwareAssetId> candidateAssetIds,
            String differentiation,
            String technicalValue,
            String estimatedComplexity,
            List<String> risks,
            List<Evidence> evidence) {

        if (id == null) {
            throw new IllegalArgumentException("Product Direction 必须指定 id");
        }
        if (userProfileId == null) {
            throw new IllegalArgumentException("Product Direction 必须指定 userProfileId");
        }
        if (userProfileRevision < 1) {
            throw new IllegalArgumentException(
                    "Product Direction 的 userProfileRevision 必须是确定的版本: "
                            + userProfileRevision);
        }

        List<RepositoryProfileId> normalizedRepositoryProfileIds =
                normalizeReferences(repositoryProfileIds, "repositoryProfileIds");
        if (normalizedRepositoryProfileIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Product Direction 必须至少引用一个 Repository Profile（INV-D05）");
        }

        String normalizedTitle = requireText(title, "title");
        String normalizedProblem = requireText(problem, "problem");
        String normalizedTargetProduct = requireText(targetProduct, "targetProduct");
        String normalizedUserFit = requireText(userFit, "userFit");
        String normalizedDifferentiation = requireText(differentiation, "differentiation");
        String normalizedTechnicalValue = requireText(technicalValue, "technicalValue");
        String normalizedEstimatedComplexity =
                requireText(estimatedComplexity, "estimatedComplexity");

        List<SoftwareAssetId> normalizedCandidateAssetIds =
                normalizeReferences(candidateAssetIds, "candidateAssetIds");
        if (normalizedCandidateAssetIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Product Direction 必须至少标识一个 Candidate Software Asset（INV-D10）");
        }

        List<String> normalizedRisks = normalizeSection(risks, "risks");
        List<Evidence> normalizedEvidence = normalizeEvidence(evidence);
        if (normalizedEvidence.isEmpty()) {
            throw new IllegalArgumentException(
                    "Product Direction 必须至少给出一条 Evidence（INV-D06）");
        }

        return new ProductDirection(
                id,
                userProfileId,
                userProfileRevision,
                normalizedRepositoryProfileIds,
                normalizedTitle,
                normalizedProblem,
                normalizedTargetProduct,
                normalizedUserFit,
                normalizedCandidateAssetIds,
                normalizedDifferentiation,
                normalizedTechnicalValue,
                normalizedEstimatedComplexity,
                normalizedRisks,
                normalizedEvidence);
    }

    private ProductDirection(
            ProductDirectionId id,
            UserProfileId userProfileId,
            int userProfileRevision,
            List<RepositoryProfileId> repositoryProfileIds,
            String title,
            String problem,
            String targetProduct,
            String userFit,
            List<SoftwareAssetId> candidateAssetIds,
            String differentiation,
            String technicalValue,
            String estimatedComplexity,
            List<String> risks,
            List<Evidence> evidence) {

        this.id = id;
        this.userProfileId = userProfileId;
        this.userProfileRevision = userProfileRevision;
        this.repositoryProfileIds = repositoryProfileIds;
        this.title = title;
        this.problem = problem;
        this.targetProduct = targetProduct;
        this.userFit = userFit;
        this.candidateAssetIds = candidateAssetIds;
        this.differentiation = differentiation;
        this.technicalValue = technicalValue;
        this.estimatedComplexity = estimatedComplexity;
        this.risks = risks;
        this.evidence = evidence;
        this.status = ProductDirectionStatus.CANDIDATE;
    }

    /**
     * 用户明确选择该方向作为后续 Evolution Planning 的目标方向
     * （DOMAIN_MODEL.md §6.2 的「CANDIDATE → SELECTED：User selects」、§8.5）。
     *
     * <p>只有 {@code CANDIDATE} 是这条转移的合法起点：§6.2 里没有为 REJECTED 或
     * SUPERSEDED 定义回到 SELECTED 的边，因此这里拒绝，而不是静默忽略。
     *
     * <p>本方法只负责这条状态转移是否被允许。选择必须来自用户的明确行为
     * （INV-D07、RULE-DOM-004），「用户是否真的做出这个选择」属于 Application 的
     * 显式请求，不由领域模型推测。方向生成的先后顺序、推荐理由的强弱、
     * 以及当前是否已经存在另一个 SELECTED 方向（INV-D09），都不影响本方法——
     * 前者是跨方向的信息，后者是跨 Aggregate 的一致性，均不在本 Aggregate 内判定。
     *
     * <p>状态变化不改写任何内容：进入 SELECTED 之后，方向仍然报告它生成时的
     * 分析来源与推荐依据（§10.5）。
     *
     * @throws ProductDirectionStateException 当前状态不是 {@code CANDIDATE}
     */
    public void select() {
        if (status != ProductDirectionStatus.CANDIDATE) {
            throw new ProductDirectionStateException(
                    "Product Direction 当前状态不允许被选择: " + status);
        }
        this.status = ProductDirectionStatus.SELECTED;
    }

    /**
     * 用户明确不选择该方向（DOMAIN_MODEL.md §6.2 的
     * 「CANDIDATE → REJECTED：User rejects」）。
     *
     * <p>只有 {@code CANDIDATE} 是这条转移的合法起点。REJECTED 的方向不进入
     * Evolution Planning（§6.2），但它作为历史方向保留下来，内容与依据都不删除
     * （§10.5）。
     *
     * <p>与 {@link #select()} 相同，本方法只判断转移是否被允许；拒绝行为本身来自
     * 用户的明确操作。
     *
     * @throws ProductDirectionStateException 当前状态不是 {@code CANDIDATE}
     */
    public void reject() {
        if (status != ProductDirectionStatus.CANDIDATE) {
            throw new ProductDirectionStateException(
                    "Product Direction 当前状态不允许被拒绝: " + status);
        }
        this.status = ProductDirectionStatus.REJECTED;
    }

    /**
     * 该方向曾经被用户选择，但用户已切换到其他方向，因此被取代
     * （DOMAIN_MODEL.md §6.2 的「SELECTED → SUPERSEDED：User switches direction」）。
     *
     * <p>只有 {@code SELECTED} 是这条转移的合法起点：SUPERSEDED 表示「曾经被选择」，
     * 一个从未被选择过的方向不存在这条语义，因此 CANDIDATE / REJECTED 一律拒绝。
     *
     * <p>被取代不删除任何内容：已经基于该方向创建的历史 Evolution Plan 不因此消失
     * （§6.2），方向本身也仍然保留其分析来源与推荐依据（§10.5、RULE-DOM-007）。
     *
     * <p>INV-D09 要求用户选择新方向时，原方向必须在同一次选择操作中进入
     * SUPERSEDED。这个「同一次操作」由 Application 协调：本方法只把当前方向
     * 置为 SUPERSEDED，不负责选择新方向，也不检查是否存在新方向。
     *
     * @throws ProductDirectionStateException 当前状态不是 {@code SELECTED}
     */
    public void supersede() {
        if (status != ProductDirectionStatus.SELECTED) {
            throw new ProductDirectionStateException(
                    "Product Direction 当前状态不允许被取代: " + status);
        }
        this.status = ProductDirectionStatus.SUPERSEDED;
    }

    public ProductDirectionId id() {
        return id;
    }

    /**
     * 生成该方向所依据的 User Profile。跨 Aggregate 引用，只保留身份。
     *
     * <p>它与 {@link #userProfileRevision()} 一起构成该方向在用户侧的追溯点。
     */
    public UserProfileId userProfileId() {
        return userProfileId;
    }

    /**
     * 生成该方向时所依据的 User Profile 版本。
     *
     * <p>它是一个历史事实：User Profile 之后如何更新都不会改变这个取值（INV-D02）。
     */
    public int userProfileRevision() {
        return userProfileRevision;
    }

    /**
     * 生成该方向所依据的 Repository Profile（INV-D05）。跨 Aggregate 引用，只保留身份。
     */
    public List<RepositoryProfileId> repositoryProfileIds() {
        return repositoryProfileIds;
    }

    public String title() {
        return title;
    }

    /** 希望解决的核心问题或需求。 */
    public String problem() {
        return problem;
    }

    /** 候选产品的大致目标形态。 */
    public String targetProduct() {
        return targetProduct;
    }

    /** 与 User Profile 的主要匹配点。 */
    public String userFit() {
        return userFit;
    }

    /**
     * 可以用于实现该方向的 Software Asset。跨 Aggregate 引用，只保留身份。
     *
     * <p>至少有一个（INV-D10）：一个没有可利用资产的「方向」不是可实施的方向。
     * 但本 Aggregate 不校验这些资产是否真的来自 {@link #repositoryProfileIds()} 所对应的
     * Software Asset——那需要结合 Repository Profile Aggregate，属于跨 Aggregate 的
     * 领域校验，由 {@code ProductDirectionDiscoveryService}（Domain Service，§12.4）承担。
     *
     * <p>它表达的是「可能可用」，不代表其中任何一个已经成为 Evolution Plan 的
     * Base Software Asset——后者在进入 Evolution Planning 时才确定。
     */
    public List<SoftwareAssetId> candidateAssetIds() {
        return candidateAssetIds;
    }

    /** 与原项目或常见方案相比的主要差异。 */
    public String differentiation() {
        return differentiation;
    }

    /** 可以体现或获得的技术价值。 */
    public String technicalValue() {
        return technicalValue;
    }

    /** 对整体演化成本的粗粒度判断。 */
    public String estimatedComplexity() {
        return estimatedComplexity;
    }

    /** 当前已知主要风险。 */
    public List<String> risks() {
        return risks;
    }

    /** 支撑该方向中重要判断的依据。 */
    public List<Evidence> evidence() {
        return evidence;
    }

    public ProductDirectionStatus status() {
        return status;
    }

    /**
     * 校验并固化一段必填文本。
     *
     * <p>这些内容是方向能够被理解与讨论的最低限度：一个没有说明问题的方向无法
     * 被用户判断，也无法与其它方向比较。
     */
    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Product Direction 必须给出 " + fieldName);
        }
        return value;
    }

    /**
     * 校验并固化一段结构化文本内容。
     *
     * <p>与 {@code UserProfile}、{@code RepositoryProfile} 的同类处理保持一致：
     * 返回的列表不可修改，也不与调用方传入的列表共享状态，
     * 使内容在创建之后不再可能被外部改动。
     */
    private static List<String> normalizeSection(List<String> values, String sectionName) {
        if (values == null) {
            throw new IllegalArgumentException(
                    "Product Direction 的 " + sectionName + " 不能为 null");
        }
        List<String> normalized = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "Product Direction 的 " + sectionName + " 不能包含空值");
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    /**
     * 校验并固化一组跨 Aggregate 的 Identity 引用。
     *
     * <p>引用只保存身份本身；被引用的 Aggregate 是否仍然存在、是否已经变化，
     * 由上层结合各自 Aggregate 判断，本类不代它做决定。
     */
    private static <T> List<T> normalizeReferences(List<T> values, String fieldName) {
        if (values == null) {
            throw new IllegalArgumentException(
                    "Product Direction 的 " + fieldName + " 不能为 null");
        }
        List<T> normalized = new ArrayList<>(values.size());
        for (T value : values) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "Product Direction 的 " + fieldName + " 不能包含 null");
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    /** 校验并固化一组 Evidence，使创建出的集合不可再由外部修改。 */
    private static List<Evidence> normalizeEvidence(List<Evidence> evidence) {
        if (evidence == null) {
            throw new IllegalArgumentException("Product Direction 的 evidence 不能为 null");
        }
        List<Evidence> normalized = new ArrayList<>(evidence.size());
        for (Evidence item : evidence) {
            if (item == null) {
                throw new IllegalArgumentException(
                        "Product Direction 的 evidence 不能包含 null");
            }
            normalized.add(item);
        }
        return List.copyOf(normalized);
    }
}
