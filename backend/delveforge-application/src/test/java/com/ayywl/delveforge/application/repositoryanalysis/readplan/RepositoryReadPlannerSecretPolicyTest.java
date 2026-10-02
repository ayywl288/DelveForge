package com.ayywl.delveforge.application.repositoryanalysis.readplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryFileReference;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryPathClassifier;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;
import com.ayywl.delveforge.application.repositoryanalysis.secret.SanitizedRepositoryMaterial;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 凭据政策的**第一个执行点**：读取之前的路径排除（ADR-0006）。
 *
 * <p>这里验证的是规划器有没有把政策用在两条通道上，以及被排除的候选是否像承诺的那样
 * 既不被读、也不占名额。政策自身的规则由
 * {@code DeterministicRepositorySecretPolicyTest} 覆盖。
 */
class RepositoryReadPlannerSecretPolicyTest {

    private static final String ENV = ".env";
    private static final String APPLICATION_YML = "application.yml";
    private static final String POM = "pom.xml";
    private static final String API = "src/main/java/com/x/Api.java";

    private static final RepositoryMaterialBudget GENEROUS =
            new RepositoryMaterialBudget(50, 10_000, 100_000);

    private final RepositorySecretPolicy realPolicy = new DeterministicRepositorySecretPolicy();

    /**
     * <pre>
     * RF-1  .env              CONFIGURATION  FOUNDATION   ← 按路径升序排在 application.yml 之前
     * RF-2  application.yml   CONFIGURATION  FOUNDATION
     * RF-3  pom.xml           BUILD_METADATA FOUNDATION
     * RF-4  src/…/Api.java    SOURCE_CODE    SCOUT_SOURCE
     * </pre>
     */
    private static RepositoryMap map() {
        return RepositoryMap.of("secret-rev-1", List.of(
                entry(1, ENV),
                entry(2, APPLICATION_YML),
                entry(3, POM),
                entry(4, API)));
    }

    /** 一次以 {@code Api.java} 为定向源码的规划。 */
    private RepositoryReadPlan plan(RepositoryMap map, RepositorySecretPolicy policy,
                                    RepositoryMaterialBudget budget) {
        return new RepositoryReadPlanner(budget, budget, policy).plan(map,
                ReadPlanFixtures.plan(map, ReadPlanFixtures.area(map, "入口", 4)));
    }

    // ------------------------------------------------------------------ 基础材料通道

    @Test
    void excludedCredentialFileNeverEntersThePlan() {
        RepositoryMap map = map();

        RepositoryReadPlan plan = plan(map, realPolicy, GENEROUS);

        assertFalse(paths(plan.entries()).contains(ENV),
                "被排除的凭据文件不得进入读取计划: " + paths(plan.entries()));
        assertTrue(paths(plan.foundationEntries()).contains(APPLICATION_YML),
                "同一类的其它配置照常被选中: " + paths(plan.foundationEntries()));
    }

    @Test
    void recordsTheExclusionAsADiagnostic() {
        RepositoryMap map = map();

        RepositoryReadPlan plan = plan(map, realPolicy, GENEROUS);

        assertEquals(List.of(ENV), excludedPaths(plan),
                "应当记下被排除的是哪一个——但只有路径，这一层从来没读过它的内容");
    }

    /**
     * 被排除的文件**不占名额**。
     *
     * <p>{@code .env} 按路径升序排在 {@code application.yml} 前面。若它到轮转时才被丢掉，
     * 就会先占掉唯一的名额，让一次本来能分析出配置的分析变成「什么都没选到」——
     * 那意味着「仓库里多了一个凭据文件」变成了「分析少看了一个正常文件」。
     */
    @Test
    void excludedFileDoesNotConsumeAnAdmissionSlot() {
        RepositoryMap map = map();
        RepositoryMaterialBudget twoFiles = new RepositoryMaterialBudget(2, 10_000, 100_000);

        RepositoryReadPlan plan = plan(map, realPolicy, twoFiles);

        // 不排除时，CONFIGURATION 这一类里 .env 排在 application.yml 之前，第二个名额会是 .env。
        assertEquals(List.of(POM, APPLICATION_YML), paths(plan.foundationEntries()),
                "名额应当留给安全的候选：被排除的 .env 不参与轮转");
    }

    /** 排除不影响其余候选的顺序：留下的还是原来那个顺序。 */
    @Test
    void keepsTheOrderOfTheRemainingCandidates() {
        RepositoryMap map = map();

        RepositoryReadPlan plan = plan(map, realPolicy, GENEROUS);

        assertEquals(List.of(POM, APPLICATION_YML), paths(plan.foundationEntries()),
                "基础材料按「类别顺序 + 组内路径升序」，去掉 .env 之后其余相对顺序不变");
    }

    // ------------------------------------------------------------------ 定向源码通道

    /**
     * 定向源码通道也走同一条政策。
     *
     * <p>用替身政策而不是真实规则：真实规则命中的都是「按定义就是凭据载体」的路径，
     * 而它们不是源码（因此进不了这条通道）。这里要证明的是**规划器把政策用在了这条通道上**，
     * 而不是政策认出了什么——后者由政策自己的用例覆盖。
     */
    @Test
    void targetedLaneHonorsTheSamePolicy() {
        RepositoryMap map = map();

        RepositoryReadPlan plan = plan(map, excludingPath(API), GENEROUS);

        assertFalse(paths(plan.targetedSourceEntries()).contains(API),
                "定向源码通道必须同样过政策");
        assertEquals(List.of(API), excludedPaths(plan), "排除同样要留下诊断");
    }

    /** 分层路径交回的候选流也过政策：入口不同，政策同一个。 */
    @Test
    void candidateStreamAlsoHonorsThePolicy() {
        RepositoryMap map = map();

        RepositoryReadPlan plan = new RepositoryReadPlanner(
                GENEROUS, GENEROUS, excludingPath(API))
                .plan(map, RepositoryTargetedSourceCandidates.of(
                        map.analyzedRevision(), List.of(entryOf(map, 4))));

        assertFalse(paths(plan.entries()).contains(API));
        assertTrue(paths(plan.foundationEntries()).contains(POM),
                "基础材料通道不受影响，照常按类别轮转");
    }

    // ------------------------------------------------------------------ 辅助

    private static List<String> paths(List<RepositoryMapEntry> entries) {
        return entries.stream().map(RepositoryMapEntry::relativePath).toList();
    }

    private static List<String> excludedPaths(RepositoryReadPlan plan) {
        return plan.skippedCandidates().stream()
                .filter(candidate ->
                        candidate.reason() == RepositoryReadSkipReason.EXCLUDED_BY_SECRET_POLICY)
                .map(candidate -> candidate.entry().relativePath())
                .toList();
    }

    /** 只排除一个指定路径的替身政策：用来验证「规划器把政策用在哪条通道上」。 */
    private static RepositorySecretPolicy excludingPath(String excluded) {
        return new RepositorySecretPolicy() {

            @Override
            public boolean excludes(String relativePath) {
                return excluded.equals(relativePath);
            }

            @Override
            public SanitizedRepositoryMaterial sanitize(List<RepositorySourceFile> files) {
                return new SanitizedRepositoryMaterial(files, 0);
            }
        };
    }

    private static RepositoryMapEntry entryOf(RepositoryMap map, int position) {
        return map.find(RepositoryFileReference.of(position)).orElseThrow();
    }

    private static RepositoryMapEntry entry(int position, String relativePath) {
        RepositoryPathClassifier.Classification classification =
                RepositoryPathClassifier.classify(relativePath);
        return new RepositoryMapEntry(
                RepositoryFileReference.of(position),
                relativePath,
                100,
                classification.language(),
                classification.materialKind(),
                classification.roleHints());
    }
}
