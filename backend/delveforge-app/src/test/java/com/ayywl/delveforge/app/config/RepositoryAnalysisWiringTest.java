package com.ayywl.delveforge.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapBuilder;
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryReadExecutor;
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryReadPlanner;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryScoutExtraction;
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

        assertEquals(12, properties.foundation().maxFiles());
        assertEquals(32_768, properties.foundation().maxFileBytes());
        assertEquals(98_304, properties.foundation().maxTotalBytes());

        assertEquals(18, properties.targetedSource().maxFiles());
        assertEquals(65_536, properties.targetedSource().maxFileBytes());
        assertEquals(163_840, properties.targetedSource().maxTotalBytes());
    }
}
