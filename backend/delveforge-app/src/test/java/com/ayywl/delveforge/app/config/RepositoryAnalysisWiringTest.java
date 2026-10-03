package com.ayywl.delveforge.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapBuilder;
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryReadExecutor;
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryReadPlanner;
import com.ayywl.delveforge.application.repositoryanalysis.region.RepositoryBranchScoutRunner;
import com.ayywl.delveforge.application.repositoryanalysis.region.RepositoryRegionNavigator;
import com.ayywl.delveforge.application.repositoryanalysis.region.RepositoryRegionScoutExtraction;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryScoutExtraction;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;
import com.ayywl.delveforge.application.repositoryanalysis.workflow.AnalyzeRepositoryUseCase;
import com.ayywl.delveforge.application.repositoryanalysis.workflow.RepositoryAnalysisMaterialCollector;
import com.ayywl.delveforge.application.repositoryanalysis.workflow.RepositoryUnderstanding;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 验证 Repository Analysis 的装配：新的理解链路在，旧的选材策略不在。
 *
 * <p>这是一个**结构**断言，与「跑一次分析看读到哪些文件」互补：后者证明行为，
 * 这里证明组成。两者都需要——只靠行为验证，一段不再被调用的旧代码可以安静地留在装配里，
 * 等到某天有人「顺手」把它接回去。
 */
@SpringBootTest
class RepositoryAnalysisWiringTest {

    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "delveforge.db");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE_FILE::toString);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RepositoryAnalysisProperties properties;

    @Test
    void wiresTheRepositoryUnderstandingChain() {
        assertNotNull(context.getBean(RepositoryMapBuilder.class));
        assertNotNull(context.getBean(RepositoryScoutExtraction.class));
        assertNotNull(context.getBean(RepositoryReadPlanner.class));
        assertNotNull(context.getBean(RepositoryReadExecutor.class));
        assertNotNull(context.getBean(RepositoryUnderstanding.class));
        assertNotNull(context.getBean(AnalyzeRepositoryUseCase.class));
    }

    /**
     * 分层 Scout 的组件同样在装配里：Region Scout、导航器、分支执行器。
     *
     * <p>只靠行为验证无法发现「这段代码根本没被接上」——它只会在真实遇到超大仓库时才暴露。
     */
    @Test
    void wiresTheHierarchicalScoutChain() {
        assertNotNull(context.getBean(RepositoryRegionScoutExtraction.class));
        assertNotNull(context.getBean(RepositoryRegionNavigator.class));
        assertNotNull(context.getBean(RepositoryBranchScoutRunner.class));
    }

    /**
     * 「flat 目录放不放得下」与「一个分支的目录放不放得下」必须是同一个门槛。
     *
     * <p>两者若取成不同的值，分层可能交出一个超过了 flat 上限、却在分支上限之内的目录——
     * 「交给模型的文件目录不会超过这个上限」这条保证就断了。这里把这条装配约束钉死。
     */
    @Test
    void usesTheSameCatalogByteLimitForBothScoutPaths() {
        assertEquals(properties.scout().maxCatalogBytes(),
                context.getBean(RepositoryRegionNavigator.class).maxFileCatalogBytes(),
                "两条 Scout 路径的文件目录门槛必须同源");
    }

    /**
     * Region 分层导航的守卫同样来自配置，且与 application.yml 一致。
     *
     * <p>Region 目录字节是**目录层**的上限，与文件目录上限是两件事，因此是独立的配置键。
     */
    @Test
    void bindsRegionNavigationGuardsFromApplicationYml() {
        assertEquals(65_536, properties.region().maxCatalogBytes());
        assertEquals(6, properties.region().maxSelectedRegions());
        assertEquals(8, properties.region().maxRoundsPerBranch());
        assertEquals(12, properties.region().maxScoutCalls());
        assertEquals(18, properties.scoutCalls().maxTotal());
    }

    /**
     * 凭据政策的 Bean 在装配里（ADR-0006）。
     *
     * <p>两个执行点（读取规划器与理解阶段）共用同一个实例：本用例钉住它存在，
     * 端到端「没有金丝雀离开本机」由接口层的用例证明。
     */
    @Test
    void wiresTheRepositorySecretPolicy() {
        RepositorySecretPolicy policy = context.getBean(RepositorySecretPolicy.class);

        assertNotNull(policy);
        assertTrue(policy instanceof DeterministicRepositorySecretPolicy,
                "生产装配用的是规则写死在代码里的实现: " + policy.getClass().getName());
    }

    /**
     * 旧的确定性选材策略不再是一个 Bean。
     *
     * <p>它仍然留在代码库里（M1 复盘与对照会引用），但没有任何 Bean 装配它，
     * 因此生产路径上不可能走回它，也不存在「新路径失败就退回旧路径」的降级。
     */
    @Test
    void doesNotWireTheOldMaterialSelectionPolicy() {
        assertTrue(context.getBeansOfType(RepositoryAnalysisMaterialCollector.class).isEmpty(),
                "旧的选材收集器不应再被装配");
    }

    /**
     * 运行时上限来自配置，且默认值与 application.yml 一致。
     *
     * <p>默认值放在配置里、不放在代码里兜底：配置缺失时启动即失败，
     * 而不是静默使用一个意料之外的上限。
     */
    @Test
    void bindsRound2DefaultsFromApplicationYml() {
        assertEquals(65_536, properties.scout().maxCatalogBytes());

        // 基础材料通道未改动：A/B 标定只调整了定向源码通道。
        assertEquals(12, properties.foundation().maxFiles());
        assertEquals(32_768, properties.foundation().maxFileBytes());
        assertEquals(98_304, properties.foundation().maxTotalBytes());
    }

    /**
     * 定向源码通道的取值来自 A/B 标定，且文件数与总量必须同时成立。
     *
     * <p>只改文件数、不改总量会让文件数成为空头承诺：18 那一档里 memos 正是因为先撞上
     * 总量上限而只取到 16 个。因此这里同时钉住两个数字，并断言总量确实容得下文件数
     * （按观测到的每文件体量估算的下界）。
     */
    @Test
    void bindsTheCalibratedTargetedSourceBudgetFromApplicationYml() {
        assertEquals(24, properties.targetedSource().maxFiles());
        assertEquals(65_536, properties.targetedSource().maxFileBytes());
        assertEquals(229_376, properties.targetedSource().maxTotalBytes());

        assertTrue(properties.targetedSource().maxTotalBytes() > 24 * 8_000,
                "总量必须容得下 24 个文件，否则文件数只是理论值");
    }
}
