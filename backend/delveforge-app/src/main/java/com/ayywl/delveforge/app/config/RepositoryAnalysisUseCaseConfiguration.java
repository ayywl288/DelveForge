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
import com.ayywl.delveforge.application.repositoryanalysis.region.RepositoryBranchScoutRunner;
import com.ayywl.delveforge.application.repositoryanalysis.region.RepositoryRegionNavigator;
import com.ayywl.delveforge.application.repositoryanalysis.region.RepositoryRegionScoutExtraction;
import com.ayywl.delveforge.application.repositoryanalysis.scout.FileCatalogPayload;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;
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
 * <h2>分析链路（ADR-0004 / ADR-0005）</h2>
 *
 * <pre>
 * RepositoryMapBuilder            完整已提交树 → 描述符
 * RepositoryScoutExtraction       描述符 → File Scout 指出「去哪里看」
 * RepositoryRegionScoutExtraction 目录层 → Region Scout 指出「往哪个目录看」
 * RepositoryRegionNavigator       flat 目录超出预算时递归下降 → 有序终态文件组
 * RepositoryBranchScoutRunner     逐终态组跑分支本地 File Scout → 保序轮转合并
 * RepositorySecretPolicy          凭据政策：读取之前的路径排除 + 交给模型之前的内容净化
 * RepositoryReadPlanner           两条通道各自轮转 → 读取计划（第一个执行点在这里）
 * RepositoryReadExecutor          按计划真实读取 → 材料
 * RepositoryUnderstanding         把上面这些串起来（第二个执行点在它末尾），
 *                                 并守住流程前置条件与路径选择
 * </pre>
 *
 * <h2>整次分析的 Scout 调用总数同时交给导航器与分支执行器</h2>
 *
 * <p>分层下降与分支 File Scout 合并计入同一个上限（ADR-0005）。两个执行阶段都必须
 * 在**每次调用之前**判定它：只交给后者，超限的那几次 Region 调用就已经发生了，
 * 而失败收不回已经付出的调用。
 *
 * <h2>两个字节门槛是同一个值</h2>
 *
 * <p>{@code RepositoryUnderstanding} 用它判断「flat 目录放不放得下」，
 * {@link com.ayywl.delveforge.application.repositoryanalysis.region.RepositoryRegionNavigator}
 * 用它判断「一个分支的目录放不放得下」。两者都取自 {@code scout.max-catalog-bytes}：
 * 若取成两个值，分层就可能交出一个超过了 flat 上限、却在分支上限之内的目录，
 * 「交给模型的文件目录不会超过这个上限」这条保证就断了。
 *
 * <p>Region Scout 的目录层上限（{@code region.max-catalog-bytes}）是**另一件事**：
 * 它约束的是目录描述符载荷，不是文件目录载荷，因此是独立的配置键。
 *
 * <h2>序列化入口只有一个实现</h2>
 *
 * <p>{@code FileCatalogPayload} 在这里被交给导航器，File Scout 内部也用它。
 * 两处是同一个类的两个无状态实例，不是两份实现：停止条件量到的字节与 File Scout 真正
 * 发出的载荷因此必然一致。
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
    public RepositoryRegionScoutExtraction repositoryRegionScoutExtraction(
            AiGateway aiGateway, ObjectMapper objectMapper,
            RepositoryAnalysisProperties properties) {
        return new RepositoryRegionScoutExtraction(
                aiGateway, objectMapper, properties.region().toLimits());
    }

    @Bean
    public RepositoryRegionNavigator repositoryRegionNavigator(
            RepositoryRegionScoutExtraction repositoryRegionScoutExtraction,
            ObjectMapper objectMapper,
            RepositoryAnalysisProperties properties) {
        return new RepositoryRegionNavigator(
                repositoryRegionScoutExtraction,
                new FileCatalogPayload(objectMapper),
                properties.scout().maxCatalogBytes(),
                properties.region().toRecursionBudget(),
                properties.scoutCalls().toBudget());
    }

    @Bean
    public RepositoryBranchScoutRunner repositoryBranchScoutRunner(
            RepositoryScoutExtraction repositoryScoutExtraction,
            RepositoryAnalysisProperties properties) {
        return new RepositoryBranchScoutRunner(
                repositoryScoutExtraction,
                properties.region().toRecursionBudget(),
                properties.scoutCalls().toBudget());
    }

    /**
     * 凭据政策：**一条**政策，两个执行点共用同一个实例（ADR-0006）。
     *
     * <p>规则写死在代码里，不来自配置——可配置就等于可以被静默放宽，而那次分析看起来仍然正常。
     */
    @Bean
    public RepositorySecretPolicy repositorySecretPolicy() {
        return new DeterministicRepositorySecretPolicy();
    }

    @Bean
    public RepositoryReadPlanner repositoryReadPlanner(
            RepositoryAnalysisProperties properties,
            RepositorySecretPolicy repositorySecretPolicy) {
        return new RepositoryReadPlanner(
                properties.foundation().toBudget(),
                properties.targetedSource().toBudget(),
                repositorySecretPolicy);
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
            RepositoryRegionNavigator repositoryRegionNavigator,
            RepositoryBranchScoutRunner repositoryBranchScoutRunner,
            RepositoryReadPlanner repositoryReadPlanner,
            RepositoryReadExecutor repositoryReadExecutor,
            RepositorySecretPolicy repositorySecretPolicy,
            RepositoryAnalysisProperties properties) {
        return new RepositoryUnderstanding(
                repositoryMapBuilder,
                repositoryScoutExtraction,
                repositoryRegionNavigator,
                repositoryBranchScoutRunner,
                repositoryReadPlanner,
                repositoryReadExecutor,
                repositorySecretPolicy,
                properties.scoutCalls().toBudget(),
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
