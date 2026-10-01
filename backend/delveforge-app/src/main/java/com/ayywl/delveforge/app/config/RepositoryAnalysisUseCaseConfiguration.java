package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositoryAnalysisExtraction;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapBuilder;
import com.ayywl.delveforge.application.repositoryanalysis.profile.GetRepositoryProfileUseCase;
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryReadExecutor;
import com.ayywl.delveforge.application.repositoryanalysis.readplan.RepositoryReadPlanner;
import com.ayywl.delveforge.application.repositoryanalysis.scout.RepositoryScoutExtraction;
import com.ayywl.delveforge.application.repositoryanalysis.workflow.AnalyzeRepositoryUseCase;
import com.ayywl.delveforge.application.repositoryanalysis.workflow.RepositoryUnderstanding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Repository Analysis Use Case 的依赖装配。
 *
 * <p>Use Case 与协作单元由 Application 拥有，但不依赖 Spring，因此装配放在 Composition Root
 * 完成（RULE-ARCH-005）。
 *
 * <p>本流程只需要只读能力：注入的是 {@link WorkspaceReadPort}，不是
 * {@code WorkspaceMutationPort}（RULE-ARCH-010、ADR-0001）。写入能力在这个流程里
 * 于类型层面就不存在。
 *
 * <h2>分析链路（ADR-0004）</h2>
 *
 * <pre>
 * RepositoryMapBuilder      完整已提交树 → 描述符
 * RepositoryScoutExtraction 描述符 → Scanner 指出「去哪里看」
 * RepositoryReadPlanner     两条通道各自轮转 → 读取计划
 * RepositoryReadExecutor    按计划真实读取 → 材料
 * RepositoryUnderstanding   把上面四步串起来，并守住流程前置条件
 * </pre>
 *
 * <h2>旧的确定性选材策略不再装配</h2>
 *
 * <p>{@code RepositoryAnalysisMaterialCollector} 与 {@code RepositoryAnalysisMaterialPolicy}
 * 曾是 M1 的生产选材路径，现在**不再**出现在这条链路上：新的分析链路完全由上面的四个组件
 * 构成。它们暂时保留在代码库里（M1 复盘与对照仍会引用），但没有任何 Bean 装配它们，
 * 也不存在「新路径失败就退回旧路径」的降级——那会让一次分析走的是哪条路径变得不可见。
 *
 * <p>两条通道的预算来自 {@link RepositoryAnalysisProperties}，不在这里写死数值。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RepositoryAnalysisProperties.class)
public class RepositoryAnalysisUseCaseConfiguration {

    @Bean
    public RepositoryMapBuilder repositoryMapBuilder(WorkspaceReadPort workspaceReadPort) {
        return new RepositoryMapBuilder(workspaceReadPort);
    }

    @Bean
    public RepositoryScoutExtraction repositoryScoutExtraction(
            AiGateway aiGateway, ObjectMapper objectMapper) {
        return new RepositoryScoutExtraction(aiGateway, objectMapper);
    }

    @Bean
    public RepositoryReadPlanner repositoryReadPlanner(
            RepositoryAnalysisProperties properties) {
        return new RepositoryReadPlanner(
                properties.foundation().toBudget(),
                properties.targetedSource().toBudget());
    }

    @Bean
    public RepositoryReadExecutor repositoryReadExecutor(
            WorkspaceReadPort workspaceReadPort, RepositoryAnalysisProperties properties) {
        return new RepositoryReadExecutor(
                workspaceReadPort,
                properties.foundation().toBudget(),
                properties.targetedSource().toBudget());
    }

    @Bean
    public RepositoryUnderstanding repositoryUnderstanding(
            RepositoryMapBuilder repositoryMapBuilder,
            RepositoryScoutExtraction repositoryScoutExtraction,
            RepositoryReadPlanner repositoryReadPlanner,
            RepositoryReadExecutor repositoryReadExecutor,
            RepositoryAnalysisProperties properties) {
        return new RepositoryUnderstanding(
                repositoryMapBuilder,
                repositoryScoutExtraction,
                repositoryReadPlanner,
                repositoryReadExecutor,
                properties.scout().maxCatalogBytes());
    }

    @Bean
    public RepositoryAnalysisExtraction repositoryAnalysisExtraction(
            AiGateway aiGateway, ObjectMapper objectMapper) {
        return new RepositoryAnalysisExtraction(aiGateway, objectMapper);
    }

    @Bean
    public GetRepositoryProfileUseCase getRepositoryProfileUseCase(
            RepositoryProfileRepository repositoryProfileRepository) {
        return new GetRepositoryProfileUseCase(repositoryProfileRepository);
    }

    @Bean
    public AnalyzeRepositoryUseCase analyzeRepositoryUseCase(
            SoftwareAssetRepository softwareAssetRepository,
            WorkspaceReadPort workspaceReadPort,
            RepositoryUnderstanding repositoryUnderstanding,
            RepositoryAnalysisExtraction repositoryAnalysisExtraction,
            RepositoryProfileRepository repositoryProfileRepository) {
        return new AnalyzeRepositoryUseCase(
                softwareAssetRepository,
                workspaceReadPort,
                repositoryUnderstanding,
                repositoryAnalysisExtraction,
                repositoryProfileRepository);
    }
}
