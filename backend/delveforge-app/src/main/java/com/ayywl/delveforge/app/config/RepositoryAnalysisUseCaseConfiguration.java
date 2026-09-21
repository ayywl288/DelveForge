package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositoryAnalysisExtraction;
import com.ayywl.delveforge.application.repositoryanalysis.workflow.AnalyzeRepositoryUseCase;
import com.ayywl.delveforge.application.repositoryanalysis.workflow.RepositoryAnalysisMaterialCollector;
import com.ayywl.delveforge.application.repositoryanalysis.workflow.RepositoryAnalysisMaterialPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Repository Analysis Use Case 的依赖装配。
 *
 * <p>Use Case 与选材策略由 Application 拥有，但不依赖 Spring，因此装配放在
 * Composition Root 完成（RULE-ARCH-005）。
 *
 * <p>本流程只需要只读能力：注入的是 {@link WorkspaceReadPort}，不是
 * {@code WorkspaceMutationPort}（RULE-ARCH-010、ADR-0001）。写入能力在这个流程里
 * 于类型层面就不存在。
 *
 * <p>选材策略当前固定为 {@link RepositoryAnalysisMaterialPolicy#mvpDefault()}：
 * 它是 MVP 的实现选择而非领域规则，暂时不作为配置项暴露；出现按仓库调整的真实需求时，
 * 再按 AGENTS.md §8.9 引入配置绑定。
 */
@Configuration(proxyBeanMethods = false)
public class RepositoryAnalysisUseCaseConfiguration {

    @Bean
    public RepositoryAnalysisMaterialCollector repositoryAnalysisMaterialCollector(
            WorkspaceReadPort workspaceReadPort) {
        return new RepositoryAnalysisMaterialCollector(
                workspaceReadPort, RepositoryAnalysisMaterialPolicy.mvpDefault());
    }

    @Bean
    public RepositoryAnalysisExtraction repositoryAnalysisExtraction(
            AiGateway aiGateway, ObjectMapper objectMapper) {
        return new RepositoryAnalysisExtraction(aiGateway, objectMapper);
    }

    @Bean
    public AnalyzeRepositoryUseCase analyzeRepositoryUseCase(
            SoftwareAssetRepository softwareAssetRepository,
            WorkspaceReadPort workspaceReadPort,
            RepositoryAnalysisMaterialCollector repositoryAnalysisMaterialCollector,
            RepositoryAnalysisExtraction repositoryAnalysisExtraction,
            RepositoryProfileRepository repositoryProfileRepository) {
        return new AnalyzeRepositoryUseCase(
                softwareAssetRepository,
                workspaceReadPort,
                repositoryAnalysisMaterialCollector,
                repositoryAnalysisExtraction,
                repositoryProfileRepository);
    }
}
