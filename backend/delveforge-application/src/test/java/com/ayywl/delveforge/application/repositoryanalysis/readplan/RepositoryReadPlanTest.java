package com.ayywl.delveforge.application.repositoryanalysis.readplan;

import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.BLOG_CONTROLLER;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.MVC_CONFIG;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.REVISION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证读取计划自身的不变量与派生视图。
 */
class RepositoryReadPlanTest {

    private final RepositoryMap map = ReadPlanFixtures.map();

    @Test
    void keepsTheGivenOrderInBothLanes() {
        RepositoryReadPlan plan = RepositoryReadPlan.of(REVISION,
                List.of(entry(MVC_CONFIG)),
                List.of(entry(BLOG_CONTROLLER)),
                List.of());

        assertEquals(List.of(MVC_CONFIG), positionsOf(plan.foundationEntries()));
        assertEquals(List.of(BLOG_CONTROLLER), positionsOf(plan.targetedSourceEntries()));
        assertEquals(2, plan.size());
    }

    /**
     * 两条通道合起来不得出现同一个文件两次。
     *
     * <p>它们在路由上互斥，因此这条条件在正常路径下必然成立；在这里强制它，是为了让
     * 「唯一文件只读一次」由类型本身保证，而不是依赖规划器写对。
     */
    @Test
    void rejectsAFileAppearingInBothLanes() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RepositoryReadPlan.of(REVISION,
                        List.of(entry(MVC_CONFIG)),
                        List.of(entry(MVC_CONFIG)),
                        List.of()));

        assertTrue(failure.getMessage().contains("两次"), failure.getMessage());
    }

    @Test
    void rejectsDuplicateInsideOneLane() {
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryReadPlan.of(REVISION,
                        List.of(entry(MVC_CONFIG), entry(MVC_CONFIG)), List.of(), List.of()));
    }

    /**
     * 不同编号指向同一个路径时同样被拒绝。
     *
     * <p>ADR-0004 把「已解析 revision + 提交树相对路径」定义为稳定技术身份，
     * 因此两个不同的编号指向同一个路径，物理上仍然是同一个文件——放进去就会读两次。
     * 只检查编号唯一挡不住这种情况。
     */
    @Test
    void rejectsTwoEntriesPointingAtTheSamePath() {
        RepositoryMapEntry renamed = new RepositoryMapEntry(
                com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryFileReference.of(2),
                entry(MVC_CONFIG).relativePath(),
                entry(MVC_CONFIG).sizeInBytes(),
                entry(MVC_CONFIG).language(),
                entry(MVC_CONFIG).materialKind(),
                entry(MVC_CONFIG).roleHints());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RepositoryReadPlan.of(REVISION,
                        List.of(entry(MVC_CONFIG)), List.of(renamed), List.of()));

        assertTrue(failure.getMessage().contains("路径"), failure.getMessage());
    }

    @Test
    void rejectsMissingRevision() {
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryReadPlan.of(null, List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryReadPlan.of("  ", List.of(), List.of(), List.of()));
    }

    @Test
    void rejectsNullCollectionsAndElements() {
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryReadPlan.of(REVISION, null, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryReadPlan.of(REVISION, List.of(), null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryReadPlan.of(REVISION, List.of(), List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryReadPlan.of(REVISION, Arrays.asList(entry(MVC_CONFIG), null),
                        List.of(), List.of()));
    }

    /**
     * 一份空计划是合法的：某个 revision 上可能既没有基础材料也没有合适的源码。
     */
    @Test
    void allowsAnEmptyPlan() {
        RepositoryReadPlan plan = RepositoryReadPlan.of(REVISION, List.of(), List.of(), List.of());

        assertTrue(plan.isEmpty());
        assertEquals(0, plan.size());
        assertTrue(plan.entries().isEmpty());
        assertEquals(REVISION, plan.analyzedRevision());
    }

    /**
     * 合并视图是派生的：基础材料在前、定向源码在后，不需要再去重。
     */
    @Test
    void combinedViewConcatenatesBothLanes() {
        RepositoryReadPlan plan = RepositoryReadPlan.of(REVISION,
                List.of(entry(MVC_CONFIG)),
                List.of(entry(BLOG_CONTROLLER)),
                List.of());

        assertEquals(List.of(MVC_CONFIG, BLOG_CONTROLLER), positionsOf(plan.entries()));
    }

    @Test
    void collectionsAreImmutable() {
        RepositoryReadPlan plan = RepositoryReadPlan.of(REVISION,
                List.of(entry(MVC_CONFIG)), List.of(), List.of());

        assertThrows(UnsupportedOperationException.class,
                () -> plan.foundationEntries().add(entry(BLOG_CONTROLLER)));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.entries().add(entry(BLOG_CONTROLLER)));
    }

    @Test
    void skippedCandidatesAreKept() {
        SkippedReadCandidate skipped = new SkippedReadCandidate(
                entry(BLOG_CONTROLLER), RepositoryReadSkipReason.SELECTED_BUT_TOO_LARGE);

        RepositoryReadPlan plan = RepositoryReadPlan.of(REVISION,
                List.of(), List.of(), List.of(skipped));

        assertEquals(List.of(skipped), plan.skippedCandidates());
        assertThrows(IllegalArgumentException.class,
                () -> new SkippedReadCandidate(null, RepositoryReadSkipReason.SELECTED_BUT_TOO_LARGE));
        assertThrows(IllegalArgumentException.class,
                () -> new SkippedReadCandidate(entry(BLOG_CONTROLLER), null));
    }

    private RepositoryMapEntry entry(int position) {
        return ReadPlanFixtures.entry(map, position);
    }

    private static List<Integer> positionsOf(List<RepositoryMapEntry> entries) {
        return entries.stream()
                .map(entry -> Integer.parseInt(entry.reference().value().substring("RF-".length())))
                .toList();
    }
}
