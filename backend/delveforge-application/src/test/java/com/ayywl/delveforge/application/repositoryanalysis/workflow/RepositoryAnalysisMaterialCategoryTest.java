package com.ayywl.delveforge.application.repositoryanalysis.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 验证材料分类：只依据路径，结果稳定。
 *
 * <p>分类只影响文件在轮转里的排队位置，不影响它是否可读，因此这里只锁定几条关键判断
 * （尤其是「按文件名优先于扩展名」这一类容易搞错的地方）。
 */
class RepositoryAnalysisMaterialCategoryTest {

    @Test
    void classifiesProjectDescriptorsAsBuildMetadata() {
        assertEquals(RepositoryAnalysisMaterialCategory.BUILD_METADATA,
                RepositoryAnalysisMaterialCategory.of("pom.xml"));
        assertEquals(RepositoryAnalysisMaterialCategory.BUILD_METADATA,
                RepositoryAnalysisMaterialCategory.of("backend/module/package.json"));
        assertEquals(RepositoryAnalysisMaterialCategory.BUILD_METADATA,
                RepositoryAnalysisMaterialCategory.of("Makefile"));
    }

    /**
     * {@code pom.xml} 是构建元数据，而不是 {@code .xml} 配置——按文件名优先判断。
     */
    @Test
    void prefersFileNameOverExtension() {
        assertEquals(RepositoryAnalysisMaterialCategory.BUILD_METADATA,
                RepositoryAnalysisMaterialCategory.of("pom.xml"));
        assertEquals(RepositoryAnalysisMaterialCategory.CONFIGURATION,
                RepositoryAnalysisMaterialCategory.of("src/main/resources/spring.xml"));
    }

    @Test
    void classifiesByExtension() {
        assertEquals(RepositoryAnalysisMaterialCategory.SOURCE_CODE,
                RepositoryAnalysisMaterialCategory.of("src/main/java/com/example/App.java"));
        assertEquals(RepositoryAnalysisMaterialCategory.SOURCE_CODE,
                RepositoryAnalysisMaterialCategory.of("src/main/resources/seckill.lua"));
        assertEquals(RepositoryAnalysisMaterialCategory.CONFIGURATION,
                RepositoryAnalysisMaterialCategory.of("src/main/resources/application.yaml"));
        assertEquals(RepositoryAnalysisMaterialCategory.DOCUMENTATION,
                RepositoryAnalysisMaterialCategory.of("docs/README.md"));
        assertEquals(RepositoryAnalysisMaterialCategory.SCRIPT,
                RepositoryAnalysisMaterialCategory.of("jmeter/run_v2.bat"));
    }

    /**
     * 大小写不敏感，未知类型归入 OTHER：分类不追求准确，只保证稳定。
     */
    @Test
    void isCaseInsensitiveAndFallsBackToOther() {
        assertEquals(RepositoryAnalysisMaterialCategory.SOURCE_CODE,
                RepositoryAnalysisMaterialCategory.of("src/App.JAVA"));
        assertEquals(RepositoryAnalysisMaterialCategory.OTHER,
                RepositoryAnalysisMaterialCategory.of("jmeter/shop_ids.csv"));
        assertEquals(RepositoryAnalysisMaterialCategory.OTHER,
                RepositoryAnalysisMaterialCategory.of(".gitignore"));
        assertEquals(RepositoryAnalysisMaterialCategory.OTHER,
                RepositoryAnalysisMaterialCategory.of("LICENSE"));
    }
}
