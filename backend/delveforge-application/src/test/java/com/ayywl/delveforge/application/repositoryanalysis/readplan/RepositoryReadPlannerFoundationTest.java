package com.ayywl.delveforge.application.repositoryanalysis.readplan;

import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.APPLICATION_YAML;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.DOCS_API;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.DOCS_GUIDE;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.MVC_CONFIG;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.POM;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.README;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.SCHEMA;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.SCRIPT;
import static com.ayywl.delveforge.application.repositoryanalysis.readplan.ReadPlanFixtures.SHOP_TEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryCandidateLane;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 Foundation 通道：只取基础材料，按材料类别轮转。
 */
class RepositoryReadPlannerFoundationTest {

    private static final RepositoryMaterialBudget GENEROUS =
            new RepositoryMaterialBudget(50, 10_000, 100_000);

    private final RepositoryMap map = ReadPlanFixtures.map();

    @Test
    void selectsOnlyFoundationEntries() {
        RepositoryReadPlan plan = plan(GENEROUS);

        for (RepositoryMapEntry entry : plan.foundationEntries()) {
            assertEquals(RepositoryCandidateLane.FOUNDATION,
                    RepositoryCandidateLane.of(entry),
                    "Foundation 通道不该选中其它候选组: " + entry.relativePath());
        }
        assertFalse(paths(plan.foundationEntries()).contains(
                        ReadPlanFixtures.entry(map, SHOP_TEST).relativePath()),
                "NONE 组的文件不得进入 Foundation");
    }

    /**
     * 类别轮转：每一轮从每个类别各取一个，类别顺序取枚举声明顺序。
     *
     * <p>文档有三个、其它类别各一个。如果按数量或路径排序，文档会先占满前面的位置；
     * 轮转保证的是每一类都有机会。
     */
    @Test
    void rotatesAcrossMaterialCategories() {
        RepositoryReadPlan plan = plan(GENEROUS);

        assertEquals(List.of(
                        MVC_CONFIG,       // SOURCE_CODE（配置与启动装配类）
                        POM,              // BUILD_METADATA
                        APPLICATION_YAML, // CONFIGURATION
                        SCHEMA,           // DATA_SCHEMA
                        README,           // DOCUMENTATION
                        SCRIPT,           // SCRIPT_AUTOMATION
                        DOCS_API,         // 第二轮：文档剩下的
                        DOCS_GUIDE),
                refs(plan));
    }

    /**
     * 数量最多的那一类不能独占预算。
     *
     * <p>三个文档只占一个名额，其余名额留给别的类别——这正是 M1 轮转要解决的问题
     * （一个文档很多、源码很少的仓库，不该只分析出文档）。
     */
    @Test
    void oneLargeCategoryCannotMonopolizeTheBudget() {
        RepositoryReadPlan plan = plan(new RepositoryMaterialBudget(3, 10_000, 100_000));

        assertEquals(List.of(MVC_CONFIG, POM, APPLICATION_YAML), refs(plan));
    }

    /**
     * 类别内按相对路径升序，因此同一份输入永远得到同一份顺序。
     */
    @Test
    void ordersCandidatesInsideACategoryByPath() {
        RepositoryReadPlan plan = plan(new RepositoryMaterialBudget(8, 10_000, 100_000));

        List<Integer> refs = refs(plan);
        assertTrue(refs.indexOf(README) < refs.indexOf(DOCS_API), "README.md 应排在 docs/api.md 之前");
        assertTrue(refs.indexOf(DOCS_API) < refs.indexOf(DOCS_GUIDE), "docs/api.md 应排在 docs/guide.md 之前");
    }

    @Test
    void isDeterministicForTheSameInputs() {
        assertEquals(plan(GENEROUS).foundationEntries(), plan(GENEROUS).foundationEntries());
    }

    /**
     * 计划里的是 Map 里那些描述符本身，不是按值相等的副本。
     *
     * <p>后续读取要拿真实路径与 revision，而不是一个碰巧内容相同的对象。
     */
    @Test
    void keepsTheVeryMapEntries() {
        RepositoryReadPlan plan = plan(GENEROUS);

        assertSame(ReadPlanFixtures.entry(map, MVC_CONFIG), plan.foundationEntries().get(0));
    }

    /**
     * 两条通道的预算互不影响：Foundation 只允许读一个，定向源码仍然按自己的预算读满。
     */
    @Test
    void foundationBudgetDoesNotConstrainTargetedSource() {
        RepositoryReadPlan plan = new RepositoryReadPlanner(
                new RepositoryMaterialBudget(1, 10_000, 100_000),
                new RepositoryMaterialBudget(50, 10_000, 100_000))
                .plan(map, ReadPlanFixtures.fullPlan(map));

        assertEquals(1, plan.foundationEntries().size());
        assertEquals(6, plan.targetedSourceEntries().size(),
                "定向源码应当按自己的预算读满，不受 Foundation 影响");
    }

    private RepositoryReadPlan plan(RepositoryMaterialBudget budget) {
        return new RepositoryReadPlanner(budget, GENEROUS)
                .plan(map, ReadPlanFixtures.fullPlan(map));
    }

    private static List<Integer> refs(RepositoryReadPlan plan) {
        return plan.foundationEntries().stream().map(entry -> positionOf(entry)).toList();
    }

    private static int positionOf(RepositoryMapEntry entry) {
        return Integer.parseInt(entry.reference().value().substring("RF-".length()));
    }

    private static List<String> paths(List<RepositoryMapEntry> entries) {
        return entries.stream().map(RepositoryMapEntry::relativePath).toList();
    }
}
