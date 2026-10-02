package com.ayywl.delveforge.application.repositoryanalysis.readplan;

import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.BLOG_CONTROLLER;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.MVC_CONFIG;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.README;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.SHOP_CONTROLLER;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.SHOP_ENTITY;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.SHOP_MAPPER;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.SHOP_SERVICE_IMPL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryCandidateLane;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryInspectionPlan;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证规划的前提与结果完整性：同一 revision、真实描述符、不读内容、不调用 AI。
 */
class RepositoryReadPlannerIntegrityTest {

    /** 使用真实规则：这些用例验证的是规划本身，凭据政策在这里应当「什么都不排除」。 */
    private static final RepositorySecretPolicy SECRET_POLICY =
            new DeterministicRepositorySecretPolicy();

    private static final RepositoryMaterialBudget GENEROUS =
            new RepositoryMaterialBudget(50, 10_000, 100_000);

    private final RepositoryMap map = ReadPlanFixtures.map();

    /**
     * 两份输入必须来自同一个 revision。
     *
     * <p>混用会让计划把两个版本的路径与大小当成一份内容，而计划只会记录一个
     * {@code analyzedRevision}——那份记录就是假的。因此这里拒绝，而不是挑一个写进去。
     */
    @Test
    void rejectsInputsFromDifferentRevisions() {
        RepositoryMap otherRevision = ReadPlanFixtures.map("deadbeef");
        RepositoryInspectionPlan planFromOtherRevision =
                ReadPlanFixtures.fullPlan(otherRevision);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> planner().plan(map, planFromOtherRevision));

        assertTrue(failure.getMessage().contains(ReadPlanFixtures.REVISION)
                        && failure.getMessage().contains("deadbeef"),
                "错误信息应当给出两个 revision: " + failure.getMessage());
    }

    /**
     * revision 相同**不能**证明计划来自这张 Map。
     *
     * <p>revision 只是一个字符串。另一张 Map 完全可能声明同一个 revision 而内容不同——
     * 此时它的 {@code RF-8} 指向别的文件。只比较 revision 就会接受它，最后产出一份包含
     * 本次 Map 里根本不存在（或指向别处）的路径的计划。
     */
    @Test
    void rejectsAPlanProducedFromADifferentMapWithTheSameRevision() {
        RepositoryMap foreign = ReadPlanFixtures.foreignMapWithSameRevision();
        // 这张 Map 的 RF-8 / RF-9 与本次 Map 互换了，位数没有越界，两个也都是源码候选
        RepositoryInspectionPlan planFromForeignMap = ReadPlanFixtures.plan(foreign,
                ReadPlanFixtures.area(foreign, "A", 8, 10),
                ReadPlanFixtures.area(foreign, "B", 9),
                ReadPlanFixtures.area(foreign, "C", 11));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> planner().plan(map, planFromForeignMap));

        assertTrue(failure.getMessage().contains("不一致"),
                "应当指出描述符对不上: " + failure.getMessage());
    }

    @Test
    void rejectsAPlanReferencingAReferenceThisMapDoesNotHave() {
        RepositoryMap smaller = ReadPlanFixtures.subsetMap(ReadPlanFixtures.REVISION,
                BLOG_CONTROLLER, SHOP_CONTROLLER);
        RepositoryInspectionPlan planFromBiggerMap = ReadPlanFixtures.fullPlan(map);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> planner().plan(smaller, planFromBiggerMap));

        assertTrue(failure.getMessage().contains("不存在"),
                "应当指出编号在本次 Map 里不存在: " + failure.getMessage());
    }

    /**
     * 查看计划里出现非源码候选的条目同样被拒绝——它不该出现在那里，
     * 出现就说明这份计划不是本次引用校验的产物。
     */
    @Test
    void rejectsAPlanContainingAnEntryThatIsNotScoutSource() {
        RepositoryInspectionPlan planWithFoundationEntry = ReadPlanFixtures.plan(map,
                ReadPlanFixtures.area(map, "A", README),
                ReadPlanFixtures.area(map, "B", BLOG_CONTROLLER),
                ReadPlanFixtures.area(map, "C", SHOP_ENTITY));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> planner().plan(map, planWithFoundationEntry));

        assertTrue(failure.getMessage().contains("不是源码候选"),
                "应当指出它不属于源码候选: " + failure.getMessage());
    }

    @Test
    void recordsTheRevisionOfTheMapItPlannedFrom() {
        assertEquals(ReadPlanFixtures.REVISION,
                planner().plan(map, ReadPlanFixtures.fullPlan(map)).analyzedRevision());
    }

    /**
     * 计划里的每一条都是 Map 里那些描述符本身。
     *
     * <p>这是「模型输出中的路径永不进入读取」这条约束在读取前的最后一道：
     * 计划里的路径来自 Map，后续读取只认这些对象。
     */
    @Test
    void keepsTheVeryMapEntriesForBothLanes() {
        RepositoryReadPlan plan = planner().plan(map, ReadPlanFixtures.fullPlan(map));

        assertSame(ReadPlanFixtures.entry(map, MVC_CONFIG), plan.foundationEntries().get(0));
        assertSame(ReadPlanFixtures.entry(map, BLOG_CONTROLLER),
                plan.targetedSourceEntries().get(0));
    }

    /**
     * 规划不需要 Workspace，也不需要 AI。
     *
     * <p>这不是靠断言调用次数证明的，而是由类型本身保证：{@link RepositoryReadPlanner} 的
     * 协作者只有两个预算，它拿不到 {@code WorkspaceReadPort}，也拿不到 {@code AiGateway}，
     * 因此「读文件」与「调用模型」在这里根本表达不出来。本用例的作用是把这条性质钉在
     * 使用方式上——规划只需要 Map、查看计划与预算三样东西。
     */
    @Test
    void plansFromMetadataOnlyWithoutWorkspaceOrAiCapability() {
        RepositoryReadPlan plan = new RepositoryReadPlanner(GENEROUS, GENEROUS, SECRET_POLICY)
                .plan(map, ReadPlanFixtures.fullPlan(map));

        assertEquals(14, plan.size(), "Map 里 8 个基础材料 + 6 个定向源码，NONE 组不参与");
        for (RepositoryMapEntry entry : plan.entries()) {
            assertTrue(entry.sizeInBytes() > 0,
                    "取舍依据的是列目录得到的 blob 大小，而不是读到的内容: "
                            + entry.relativePath());
        }
    }

    /**
     * 合并视图是派生的：基础材料在前、定向源码在后，两条通道的来源仍然可分辨。
     */
    @Test
    void combinedViewKeepsLaneProvenanceDistinguishable() {
        RepositoryReadPlan plan = planner().plan(map, ReadPlanFixtures.fullPlan(map));

        List<RepositoryMapEntry> all = plan.entries();
        assertEquals(plan.size(), all.size());
        assertEquals(plan.foundationEntries(), all.subList(0, plan.foundationEntries().size()));
        assertEquals(List.of(RepositoryCandidateLane.FOUNDATION, RepositoryCandidateLane.SCOUT_SOURCE),
                all.stream().map(RepositoryCandidateLane::of).distinct().toList());
    }

    @Test
    void rejectsNullInputs() {
        // 定向源码有两种输入形状，null 必须指明是哪一种，否则重载无法判定
        assertThrows(IllegalArgumentException.class,
                () -> planner().plan(null, (RepositoryInspectionPlan) null));
        assertThrows(IllegalArgumentException.class,
                () -> planner().plan(map, (RepositoryInspectionPlan) null));
        assertThrows(IllegalArgumentException.class,
                () -> planner().plan(map, (RepositoryTargetedSourceCandidates) null));
        assertThrows(IllegalArgumentException.class,
                () -> planner().plan(null, ReadPlanFixtures.fullPlan(map)));
    }

    @Test
    void rejectsMissingBudgets() {
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryReadPlanner(null, GENEROUS, SECRET_POLICY));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryReadPlanner(GENEROUS, null, SECRET_POLICY));
    }

    /**
     * 一份没有基础材料的 Map 也能规划：定向源码照常选出，不因为没有基础材料而失败。
     *
     * <p>「没有任何源码候选」会在建立 Scout 输入时就被拒绝，但「没有任何基础材料」是
     * 完全正常的仓库形态，规划器不该因此失败。
     */
    @Test
    void toleratesAMapWithoutFoundationMaterial() {
        RepositoryMap sourceOnly = ReadPlanFixtures.subsetMap(ReadPlanFixtures.REVISION,
                BLOG_CONTROLLER, SHOP_CONTROLLER, SHOP_ENTITY, SHOP_MAPPER, SHOP_SERVICE_IMPL);

        // 重新编号之后，这五个文件是 RF-1…RF-5
        RepositoryReadPlan plan = planner().plan(sourceOnly, ReadPlanFixtures.plan(sourceOnly,
                ReadPlanFixtures.area(sourceOnly, "A", 1, 2),
                ReadPlanFixtures.area(sourceOnly, "B", 3),
                ReadPlanFixtures.area(sourceOnly, "C", 4)));

        assertTrue(plan.foundationEntries().isEmpty());
        // RF-5 没有被任何区域引用，因此不进计划——计划只包含 Scout 实际指出过的文件
        assertEquals(4, plan.targetedSourceEntries().size());
    }

    private RepositoryReadPlanner planner() {
        return new RepositoryReadPlanner(GENEROUS, GENEROUS, SECRET_POLICY);
    }
}
