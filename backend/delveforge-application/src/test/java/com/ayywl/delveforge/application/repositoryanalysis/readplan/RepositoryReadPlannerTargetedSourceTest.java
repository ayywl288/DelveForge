package com.ayywl.delveforge.application.repositoryanalysis.readplan;

import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.BLOG_CONTROLLER;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.SHOP_CONTROLLER;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.SHOP_ENTITY;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.SHOP_MAPPER;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.SHOP_SERVICE_IMPL;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.VOUCHER_ORDER_SERVICE_IMPL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryCandidateLane;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryInspectionPlan;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证定向源码通道：按聚焦区域轮转、跨区域重复只读一次、按字节预算取舍。
 */
class RepositoryReadPlannerTargetedSourceTest {

    private static final RepositoryMaterialBudget GENEROUS =
            new RepositoryMaterialBudget(50, 10_000, 100_000);

    private final RepositoryMap map = ReadPlanFixtures.map();

    @Test
    void selectsOnlyScoutSourceEntries() {
        RepositoryReadPlan plan = plan(GENEROUS, ReadPlanFixtures.fullPlan(map));

        for (RepositoryMapEntry entry : plan.targetedSourceEntries()) {
            assertEquals(RepositoryCandidateLane.SCOUT_SOURCE,
                    RepositoryCandidateLane.of(entry),
                    "定向源码通道不该选中其它候选组: " + entry.relativePath());
        }
    }

    /**
     * 聚焦区域轮转：每一轮从每个区域各取一个，直到某个区域先取完。
     *
     * <pre>
     * A: 8  9  10
     * B: 11 12
     * C: 13
     * → 8 11 13 9 12 10
     * </pre>
     *
     * <p>区域顺序与区域内顺序都保持 Scout 给出的样子——顺序就是它表达的优先级。
     */
    @Test
    void rotatesAcrossFocusAreas() {
        RepositoryReadPlan plan = plan(GENEROUS, ReadPlanFixtures.plan(map,
                ReadPlanFixtures.area(map, "A", BLOG_CONTROLLER, SHOP_CONTROLLER, SHOP_ENTITY),
                ReadPlanFixtures.area(map, "B", SHOP_MAPPER, SHOP_SERVICE_IMPL),
                ReadPlanFixtures.area(map, "C", VOUCHER_ORDER_SERVICE_IMPL)));

        assertEquals(List.of(BLOG_CONTROLLER, SHOP_MAPPER, VOUCHER_ORDER_SERVICE_IMPL,
                        SHOP_CONTROLLER, SHOP_SERVICE_IMPL, SHOP_ENTITY),
                refs(plan));
    }

    // ---------------------------------------------------------------------
    // 跨区域重复
    // ---------------------------------------------------------------------

    /**
     * 同一个文件出现在多个区域里时，只选一次、只计一次。
     */
    @Test
    void selectsACrossAreaDuplicateOnlyOnce() {
        RepositoryReadPlan plan = plan(GENEROUS, ReadPlanFixtures.plan(map,
                ReadPlanFixtures.area(map, "A", BLOG_CONTROLLER, SHOP_ENTITY),
                ReadPlanFixtures.area(map, "B", BLOG_CONTROLLER, SHOP_SERVICE_IMPL),
                ReadPlanFixtures.area(map, "C", VOUCHER_ORDER_SERVICE_IMPL)));

        assertEquals(List.of(BLOG_CONTROLLER, SHOP_SERVICE_IMPL, VOUCHER_ORDER_SERVICE_IMPL,
                        SHOP_ENTITY),
                refs(plan));
        assertEquals(4, plan.targetedSourceEntries().size());
        assertEquals(1, refs(plan).stream().filter(ref -> ref == BLOG_CONTROLLER).count(),
                "跨区域重复只应出现一次");
    }

    /**
     * 重复不应该消耗掉一个区域本轮的贡献机会。
     *
     * <pre>
     * A: 8 9 10      B: 9 11      C: 12
     * 第一轮  A → 8     B → 9     C → 12
     * 第二轮  A → 9 是重复，跳过，继续在本区域内找到 10；B → 11
     * → 8 9 12 10 11
     * </pre>
     *
     * <p>如果重复只是让该区域这一轮空过，结果会少一个文件——那意味着「另一个区域也提到过它」
     * 反而让这次分析读得更少。
     */
    @Test
    void duplicateDoesNotConsumeTheAreaOpportunity() {
        RepositoryReadPlan plan = plan(GENEROUS, ReadPlanFixtures.plan(map,
                ReadPlanFixtures.area(map, "A", BLOG_CONTROLLER, SHOP_CONTROLLER, SHOP_ENTITY),
                ReadPlanFixtures.area(map, "B", SHOP_CONTROLLER, SHOP_MAPPER),
                ReadPlanFixtures.area(map, "C", SHOP_SERVICE_IMPL)));

        assertEquals(List.of(BLOG_CONTROLLER, SHOP_CONTROLLER, SHOP_SERVICE_IMPL,
                        SHOP_ENTITY, SHOP_MAPPER),
                refs(plan));
    }

    // ---------------------------------------------------------------------
    // 预算
    // ---------------------------------------------------------------------

    /**
     * {@code maxFiles} 约束的是**唯一文件数**，不是引用出现次数。
     *
     * <p>{@code RF-8} 被两个区域提到，但它只占一个名额，因此第二个名额可以留给别的文件。
     */
    @Test
    void maxFilesCountsUniqueFiles() {
        RepositoryReadPlan plan = plan(new RepositoryMaterialBudget(2, 10_000, 100_000),
                ReadPlanFixtures.plan(map,
                        ReadPlanFixtures.area(map, "A", BLOG_CONTROLLER, SHOP_ENTITY),
                        ReadPlanFixtures.area(map, "B", BLOG_CONTROLLER, SHOP_MAPPER),
                        ReadPlanFixtures.area(map, "C", SHOP_SERVICE_IMPL)));

        assertEquals(List.of(BLOG_CONTROLLER, SHOP_MAPPER), refs(plan));
    }

    /**
     * 超过单文件上限的候选被记录并跳过，同一区域里后面的候选照常参与。
     */
    @Test
    void recordsAnOversizedCandidateAndKeepsPlanning() {
        RepositoryReadPlan plan = plan(new RepositoryMaterialBudget(50, 150, 100_000),
                ReadPlanFixtures.plan(map,
                        ReadPlanFixtures.area(map, "A", BLOG_CONTROLLER, SHOP_CONTROLLER,
                                SHOP_ENTITY),
                        ReadPlanFixtures.area(map, "B", SHOP_MAPPER),
                        ReadPlanFixtures.area(map, "C", SHOP_SERVICE_IMPL)));

        assertEquals(List.of(BLOG_CONTROLLER, SHOP_MAPPER, SHOP_SERVICE_IMPL, SHOP_ENTITY),
                refs(plan), "跳过过大的 RF-9 之后，同一区域里的 RF-10 仍应被选中");
        assertEquals(1, plan.skippedCandidates().size());
        assertEquals(RepositoryReadSkipReason.SELECTED_BUT_TOO_LARGE,
                plan.skippedCandidates().get(0).reason());
        assertEquals(SHOP_CONTROLLER, positionOf(plan.skippedCandidates().get(0).entry()));
    }

    /**
     * 放不进剩余总量预算的候选被记录并跳过；同一区域里**更小的候选仍然可以被选中**。
     *
     * <pre>
     * 预算 250；尺寸 12=100 9=200 10=50 8=100 13=100
     * 第一轮  A → 12 (100)   B → 8 (200)   C → 13 放不下，跳过
     * 第二轮  A → 9 放不下，跳过，继续找到 10 (50) → 累计 250
     * </pre>
     */
    @Test
    void skipsCandidatesThatDoNotFitButStillTakesSmallerOnes() {
        // 单文件上限放到与总量相同，这样被跳过的原因只可能是「放不进剩余总量」，
        // 而不是「超过单文件上限」——两个原因是不同的诊断。
        RepositoryReadPlan plan = plan(new RepositoryMaterialBudget(50, 250, 250),
                ReadPlanFixtures.plan(map,
                        ReadPlanFixtures.area(map, "A", VOUCHER_ORDER_SERVICE_IMPL,
                                SHOP_CONTROLLER, SHOP_ENTITY),
                        ReadPlanFixtures.area(map, "B", BLOG_CONTROLLER),
                        ReadPlanFixtures.area(map, "C", SHOP_SERVICE_IMPL)));

        assertEquals(List.of(VOUCHER_ORDER_SERVICE_IMPL, BLOG_CONTROLLER, SHOP_ENTITY),
                refs(plan), "RF-9 放不下不代表同一区域里更小的 RF-10 也放不下");

        List<SkippedReadCandidate> skipped = plan.skippedCandidates();
        assertEquals(2, skipped.size());
        assertTrue(skipped.stream().allMatch(candidate ->
                        candidate.reason() == RepositoryReadSkipReason.EXCEEDS_REMAINING_TOTAL_BYTES),
                "两个候选都应是放不进剩余总量");
    }

    /**
     * 去重发生在字节记账**之前**：一个被重复提到的文件只计一次字节。
     *
     * <p>本例总量预算刚好等于该文件的大小。若重复引用被当成第二个候选去记账，
     * 它会因为超出剩余总量而被记一条 {@code EXCEEDS_REMAINING_TOTAL_BYTES}——
     * 那说明「同一个文件被提到两次」莫名其妙地把预算吃掉了。
     */
    @Test
    void countsBytesOnceForACrossAreaDuplicate() {
        int size = (int) ReadPlanFixtures.sizeOf(BLOG_CONTROLLER);

        RepositoryReadPlan plan = plan(new RepositoryMaterialBudget(50, size, size),
                ReadPlanFixtures.plan(map,
                        ReadPlanFixtures.area(map, "A", BLOG_CONTROLLER),
                        ReadPlanFixtures.area(map, "B", BLOG_CONTROLLER),
                        ReadPlanFixtures.area(map, "C", BLOG_CONTROLLER)));

        assertEquals(List.of(BLOG_CONTROLLER), refs(plan));
        assertTrue(plan.skippedCandidates().isEmpty(),
                "重复引用不得产生预算诊断: " + plan.skippedCandidates());
    }

    // ---------------------------------------------------------------------
    // 跳过的诊断：一个文件只有一条
    // ---------------------------------------------------------------------

    /**
     * 同一个过大文件被多个区域引用时，只留下**一条**诊断。
     *
     * <p>跳过是文件的属性，不是引用的属性。若只对选中的去重，一个被三个区域引用的大文件
     * 会留下三条一模一样的记录，把「跳过了几个文件」说成三倍。
     */
    @Test
    void recordsOneDiagnosticForAnOversizedFileReferencedBySeveralAreas() {
        RepositoryReadPlan plan = plan(new RepositoryMaterialBudget(50, 150, 100_000),
                ReadPlanFixtures.plan(map,
                        ReadPlanFixtures.area(map, "A", SHOP_CONTROLLER, BLOG_CONTROLLER),
                        ReadPlanFixtures.area(map, "B", SHOP_CONTROLLER),
                        ReadPlanFixtures.area(map, "C", SHOP_CONTROLLER, SHOP_ENTITY)));

        assertEquals(List.of(SHOP_CONTROLLER), skippedRefs(plan));
        assertEquals(RepositoryReadSkipReason.SELECTED_BUT_TOO_LARGE,
                plan.skippedCandidates().get(0).reason());
        assertEquals(List.of(BLOG_CONTROLLER, SHOP_ENTITY), refs(plan),
                "重复的过大文件不该占用各区域的贡献机会");
    }

    /**
     * 同一个放不进剩余总量的文件被多个区域引用时，同样只留一条诊断。
     */
    @Test
    void recordsOneDiagnosticForAFileThatCannotFitReferencedBySeveralAreas() {
        // 总量 250：RF-12(100) 与 RF-8(100) 先选中，剩下的 50 放不下 RF-9(200)
        RepositoryReadPlan plan = plan(new RepositoryMaterialBudget(50, 250, 250),
                ReadPlanFixtures.plan(map,
                        ReadPlanFixtures.area(map, "A", VOUCHER_ORDER_SERVICE_IMPL,
                                SHOP_CONTROLLER),
                        ReadPlanFixtures.area(map, "B", SHOP_CONTROLLER),
                        ReadPlanFixtures.area(map, "C", BLOG_CONTROLLER, SHOP_CONTROLLER)));

        assertEquals(List.of(SHOP_CONTROLLER), skippedRefs(plan));
        assertEquals(RepositoryReadSkipReason.EXCEEDS_REMAINING_TOTAL_BYTES,
                plan.skippedCandidates().get(0).reason());
        assertEquals(List.of(VOUCHER_ORDER_SERVICE_IMPL, BLOG_CONTROLLER), refs(plan));
    }

    // ---------------------------------------------------------------------
    // 分层 Scout 的有序候选流：同一份预算，只换输入形状
    // ---------------------------------------------------------------------

    /**
     * 候选流按**一条队列**处理：顺序就是考虑顺序，不会被轮转重新交织。
     *
     * <p>这条是 ADR-0005 的要求：分层合并出的保序轮转顺序必须原样成为定向源码的考虑顺序。
     */
    @Test
    void treatsTheOrderedCandidateStreamAsASingleLane() {
        RepositoryReadPlan plan = new RepositoryReadPlanner(GENEROUS, GENEROUS)
                .plan(map, candidates(
                        VOUCHER_ORDER_SERVICE_IMPL, BLOG_CONTROLLER, SHOP_ENTITY));

        assertEquals(List.of(VOUCHER_ORDER_SERVICE_IMPL, BLOG_CONTROLLER, SHOP_ENTITY),
                refs(plan));
    }

    /**
     * 候选流与查看计划共用**同一套**预算与跳过原因：分层只改候选从哪来，不改规划语义。
     */
    @Test
    void appliesTheSameBudgetAndSkipReasonsToTheCandidateStream() {
        RepositoryReadPlan plan = new RepositoryReadPlanner(
                GENEROUS, new RepositoryMaterialBudget(50, 150, 100_000))
                .plan(map, candidates(SHOP_CONTROLLER, BLOG_CONTROLLER, SHOP_ENTITY));

        assertEquals(List.of(BLOG_CONTROLLER, SHOP_ENTITY), refs(plan),
                "超过单文件上限的候选被跳过，后面的照常参与");
        assertEquals(1, plan.skippedCandidates().size());
        assertEquals(RepositoryReadSkipReason.SELECTED_BUT_TOO_LARGE,
                plan.skippedCandidates().get(0).reason());
    }

    /** 基础材料通道与候选流无关：两条通道仍旧各自独立。 */
    @Test
    void stillSelectsFoundationMaterialAlongsideTheCandidateStream() {
        RepositoryReadPlan plan = new RepositoryReadPlanner(GENEROUS, GENEROUS)
                .plan(map, candidates(BLOG_CONTROLLER));

        assertTrue(plan.foundationEntries().stream()
                        .anyMatch(entry -> positionOf(entry) == ReadPlanFixtures.POM),
                "基础材料仍按类别轮转选出: " + plan.foundationEntries());
    }

    /** 候选流的 revision 必须与本次 Map 一致。 */
    @Test
    void rejectsACandidateStreamFromAnotherRevision() {
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryReadPlanner(GENEROUS, GENEROUS).plan(map,
                        RepositoryTargetedSourceCandidates.of("other-revision",
                                List.of(ReadPlanFixtures.entry(map, BLOG_CONTROLLER)))));
    }

    /**
     * 候选必须是本次 Map 的**源码候选**：不属于这一组的文件混进来即拒绝。
     *
     * <p>用测试代码（{@code NONE} 组）而不是基础材料来构造：基础材料本来就有一条通道，
     * 混进候选流会**顺带**因为「同一个文件出现在两条通道」被拒绝，那样这条用例就不能证明
     * 「候选必须是源码候选」这件事。
     */
    @Test
    void rejectsACandidateThatIsNotAScoutSource() {
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryReadPlanner(GENEROUS, GENEROUS).plan(map,
                        candidates(ReadPlanFixtures.SHOP_TEST)));
    }

    /**
     * 编号存在于本次 Map，但指向的是**另一个文件**：候选不是由本次这张 Map 产生的。
     *
     * <p>只比较 revision 挡不住这种情形——两份 Map 的 revision 字符串可以一样。
     */
    @Test
    void rejectsACandidateStreamBuiltFromADifferentMapWithTheSameRevision() {
        RepositoryMap foreign = ReadPlanFixtures.foreignMapWithSameRevision();

        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryReadPlanner(GENEROUS, GENEROUS).plan(map,
                        RepositoryTargetedSourceCandidates.of(ReadPlanFixtures.REVISION,
                                List.of(ReadPlanFixtures.entry(foreign, BLOG_CONTROLLER)))));
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    /** 按编号顺序构造一条候选流；编号取自本次 {@link #map}。 */
    private RepositoryTargetedSourceCandidates candidates(int... positions) {
        List<RepositoryMapEntry> entries = new ArrayList<>(positions.length);
        for (int position : positions) {
            entries.add(ReadPlanFixtures.entry(map, position));
        }
        return RepositoryTargetedSourceCandidates.of(map.analyzedRevision(), entries);
    }

    private static List<Integer> skippedRefs(RepositoryReadPlan plan) {
        return plan.skippedCandidates().stream()
                .map(candidate -> positionOf(candidate.entry()))
                .toList();
    }

    private RepositoryReadPlan plan(RepositoryMaterialBudget targetedBudget,
                                    RepositoryInspectionPlan inspectionPlan) {
        return new RepositoryReadPlanner(GENEROUS, targetedBudget).plan(map, inspectionPlan);
    }

    private static List<Integer> refs(RepositoryReadPlan plan) {
        return plan.targetedSourceEntries().stream().map(entry -> positionOf(entry)).toList();
    }

    private static int positionOf(RepositoryMapEntry entry) {
        return Integer.parseInt(entry.reference().value().substring("RF-".length()));
    }
}
