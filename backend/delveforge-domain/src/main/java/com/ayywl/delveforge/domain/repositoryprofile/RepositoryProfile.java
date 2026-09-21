package com.ayywl.delveforge.domain.repositoryprofile;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import java.util.ArrayList;
import java.util.List;

/**
 * Repository Profile Aggregate Root（DOMAIN_MODEL.md §3.3、§11.5）。
 *
 * <p>Repository Profile 是系统分析 Repository 后形成的结构化描述，表达：
 *
 * <pre>
 * 某个 Software Asset
 * @ 某个确定的 analyzedRevision
 * → 系统在那次分析中理解到了什么
 * </pre>
 *
 * <p>它不保存源码副本，也不决定该 Repository 应该演化成什么产品。
 *
 * <h2>Snapshot 语义</h2>
 *
 * <p>一次分析对应一个 Profile，分析结果不随后续变化而更新（INV-D04、§10.4）：
 *
 * <pre>
 * Software Asset A @ revision-1   →  Repository Profile P1
 * Software Asset A @ revision-2   →  Repository Profile P2（新快照）
 *
 * P2 的产生不修改 P1：历史 Product Direction 或 Evolution Plan 可能仍然引用 P1
 * </pre>
 *
 * <p>因此本 Aggregate 不提供任何修改入口，也没有 status 或 revision：
 * §6 说明 Repository Profile 不定义状态机，本 Task 也不为它引入
 * REVIEWING / CONFIRMED 一类未定义的生命周期。需要新的分析结果时创建新的 Profile。
 *
 * <p>「不被隐式更新」不是靠约定，而是靠本类没有任何可以改写已有内容的操作：
 * 全部字段不可变，内容集合在创建时即固化。
 *
 * <h2>与其他 Aggregate 的关系</h2>
 *
 * <p>它通过 {@link SoftwareAssetId} 引用 Software Asset，不包含 Software Asset；
 * Software Asset 也不拥有 Repository Profile（§11.4、§11.5）。两者是跨 Aggregate
 * 关系，通过身份建立：
 *
 * <pre>
 * Repository Profile P1 → assetId = A
 * Repository Profile P2 → assetId = A
 * </pre>
 *
 * <h2>当前实现选择</h2>
 *
 * <p>以下都是本 Task 的实现选择，不是 DOMAIN_MODEL.md 的规定：
 *
 * <pre>
 * 不可变 + 无修改入口
 *     本 Task 只提供创建与读取。§8.3 的 Analyze Repository 会「为关键分析结论建立
 *     Evidence」，因此 Evidence 在创建时一并给出，而不是事后追加。
 *
 * purpose 必填，其余分析内容可以为空
 *     §3.3 把 purpose 列为该快照表达的核心内容（这个项目主要用途是什么）；
 *     一个没有用途描述的快照无法回答它描述的是什么。而 modules / capabilities /
 *     limitations 等可能确实为空（例如一个很小的仓库），因此只要求非 null。
 *
 * analyzedRevision 只要求非空白，不规定格式
 *     §3.3 明确它「可以是 Git Commit、快照标识或其他能够稳定定位代码状态的机制」，
 *     因此领域层不把它约束成某一种标识形式。
 * </pre>
 *
 * <p>不包含：Repository 的访问（只读访问由 Workspace 承担）、分析如何产生这些内容
 * （属于 AI 与 Application）、Profile 的持久化与历史保留。
 */
public class RepositoryProfile {

    private final RepositoryProfileId id;
    private final SoftwareAssetId assetId;
    private final String analyzedRevision;
    private final String purpose;
    private final List<String> techStack;
    private final List<String> modules;
    private final List<String> capabilities;
    private final List<String> reusableAssets;
    private final List<String> limitations;
    private final List<String> risks;
    private final List<Evidence> evidence;

    /**
     * 创建一次 Repository 分析的结果快照。
     *
     * <p>本方法只固化调用方已经确定的分析结论，不判断这些结论是否正确、是否完整：
     * AI 输出不会因为进入了这个构造函数就成为可信领域状态，它必须先经过
     * Application 的解析与校验（RULE-DOM-003）。
     *
     * @param id                Profile 标识，不得为 {@code null}
     * @param assetId           被分析的 Software Asset，不得为 {@code null}（INV-D03）
     * @param analyzedRevision  本次分析对应的软件状态，不得为 {@code null} 或空白（INV-D03）；
     *                          其表现形式由具体机制决定，领域模型不规定格式
     * @param purpose           该 Repository 当前解决的问题或主要用途，不得为 {@code null} 或空白
     * @param techStack         主要技术栈；不得为 {@code null}，可以为空
     * @param modules           主要业务或技术模块；不得为 {@code null}，可以为空
     * @param capabilities      当前已经具备的核心能力；不得为 {@code null}，可以为空
     * @param reusableAssets    具有直接复用或演化价值的能力、模块或实现；不得为 {@code null}，可以为空
     * @param limitations       当前项目的重要限制；不得为 {@code null}，可以为空
     * @param risks             对后续演化可能产生影响的技术风险；不得为 {@code null}，可以为空
     * @param evidence          支撑本 Profile 判断的依据；不得为 {@code null}，可以为空
     * @throws IllegalArgumentException 任一必填参数缺失或取值不合法
     */
    public static RepositoryProfile create(
            RepositoryProfileId id,
            SoftwareAssetId assetId,
            String analyzedRevision,
            String purpose,
            List<String> techStack,
            List<String> modules,
            List<String> capabilities,
            List<String> reusableAssets,
            List<String> limitations,
            List<String> risks,
            List<Evidence> evidence) {

        if (id == null) {
            throw new IllegalArgumentException("Repository Profile 必须指定 id");
        }
        if (assetId == null) {
            throw new IllegalArgumentException("Repository Profile 必须指定 assetId");
        }
        if (analyzedRevision == null || analyzedRevision.isBlank()) {
            throw new IllegalArgumentException("Repository Profile 必须指定 analyzedRevision");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("Repository Profile 必须给出 purpose");
        }

        return new RepositoryProfile(
                id,
                assetId,
                analyzedRevision,
                purpose,
                normalizeSection(techStack, "techStack"),
                normalizeSection(modules, "modules"),
                normalizeSection(capabilities, "capabilities"),
                normalizeSection(reusableAssets, "reusableAssets"),
                normalizeSection(limitations, "limitations"),
                normalizeSection(risks, "risks"),
                normalizeEvidence(evidence));
    }

    private RepositoryProfile(
            RepositoryProfileId id,
            SoftwareAssetId assetId,
            String analyzedRevision,
            String purpose,
            List<String> techStack,
            List<String> modules,
            List<String> capabilities,
            List<String> reusableAssets,
            List<String> limitations,
            List<String> risks,
            List<Evidence> evidence) {

        this.id = id;
        this.assetId = assetId;
        this.analyzedRevision = analyzedRevision;
        this.purpose = purpose;
        this.techStack = techStack;
        this.modules = modules;
        this.capabilities = capabilities;
        this.reusableAssets = reusableAssets;
        this.limitations = limitations;
        this.risks = risks;
        this.evidence = evidence;
    }

    public RepositoryProfileId id() {
        return id;
    }

    /** 本次分析针对的 Software Asset。跨 Aggregate 引用，只保留身份。 */
    public SoftwareAssetId assetId() {
        return assetId;
    }

    /**
     * 本次分析对应的软件状态。
     *
     * <p>它与本 Profile 的分析内容一起构成一个不可分割的快照：内容描述的就是这个
     * revision，两者不得分别变化（§10.4）。
     */
    public String analyzedRevision() {
        return analyzedRevision;
    }

    public String purpose() {
        return purpose;
    }

    public List<String> techStack() {
        return techStack;
    }

    public List<String> modules() {
        return modules;
    }

    public List<String> capabilities() {
        return capabilities;
    }

    public List<String> reusableAssets() {
        return reusableAssets;
    }

    public List<String> limitations() {
        return limitations;
    }

    public List<String> risks() {
        return risks;
    }

    /** 支撑本 Profile 中重要判断的依据。 */
    public List<Evidence> evidence() {
        return evidence;
    }

    /**
     * 校验并固化一段结构化内容。
     *
     * <p>与 {@code UserProfile} 的同类处理保持一致：返回的列表不可修改，也不与调用方
     * 传入的列表共享状态，使 Profile 的内容在创建之后不再可能被外部改动——
     * 这正是 Snapshot 语义要求的。
     *
     * <p>当前两个 Aggregate 各自持有一份同样的校验。等到第三个 Aggregate 也需要它时，
     * 再考虑为它找一个明确的语义归属，而不是现在提前建一个通用工具。
     */
    private static List<String> normalizeSection(List<String> values, String sectionName) {
        if (values == null) {
            throw new IllegalArgumentException(
                    "Repository Profile 的 " + sectionName + " 不能为 null");
        }
        List<String> normalized = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "Repository Profile 的 " + sectionName + " 不能包含空值");
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    /** 校验并固化一组 Evidence，使创建出的集合不可再由外部修改。 */
    private static List<Evidence> normalizeEvidence(List<Evidence> evidence) {
        if (evidence == null) {
            throw new IllegalArgumentException("Repository Profile 的 evidence 不能为 null");
        }
        List<Evidence> normalized = new ArrayList<>(evidence.size());
        for (Evidence item : evidence) {
            if (item == null) {
                throw new IllegalArgumentException(
                        "Repository Profile 的 evidence 不能包含 null");
            }
            normalized.add(item);
        }
        return List.copyOf(normalized);
    }
}
