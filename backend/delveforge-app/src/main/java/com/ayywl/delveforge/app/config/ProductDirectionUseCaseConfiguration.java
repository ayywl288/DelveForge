package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.application.opportunitydiscovery.direction.DirectionDiscoveryExtraction;
import com.ayywl.delveforge.application.opportunitydiscovery.direction.DiscoverProductDirectionsUseCase;
import com.ayywl.delveforge.application.opportunitydiscovery.direction.GetProductDirectionUseCase;
import com.ayywl.delveforge.application.opportunitydiscovery.direction.RejectProductDirectionUseCase;
import com.ayywl.delveforge.application.opportunitydiscovery.direction.SelectProductDirectionUseCase;
import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.EvolutionPlanRepository;
import com.ayywl.delveforge.application.port.persistence.EvolutionLifecycleCommitPort;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.domain.direction.ProductDirectionDiscoveryService;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Product Direction Use Case 的依赖装配。
 *
 * <p>Use Case 由 Application 拥有，但不依赖 Spring——Application 模块不引入任何 Spring
 * 依赖，因此它们不能通过 {@code @Component} 被发现。装配放在 Composition Root 完成
 * （RULE-ARCH-005），Application 侧保持框架无关。
 *
 * <p>{@link UserProfileRepository}、{@link RepositoryProfileRepository} 与
 * {@link ProductDirectionRepository} 由 Infrastructure 的 Persistence Adapter 提供；
 * {@link AiGateway} 由 Infrastructure 的 Provider Adapter 提供；
 * {@link ObjectMapper} 由 Spring Boot 的 Jackson 自动装配提供。这些都是已经由 Spring
 * 管理的 Bean，这里只消费它们，不重复声明。
 *
 * <p>{@link DirectionDiscoveryExtraction} 与 {@link ProductDirectionDiscoveryService}
 * 是 Discovery 链路内部的协作单元，被 {@link DiscoverProductDirectionsUseCase} 与
 * 读取端点共用，因此也在这里装配——它们同样不依赖 Spring。
 *
 * <h2>方向标识由服务端生成</h2>
 *
 * <p>{@link ProductDirectionDiscoveryService} 只声明「要一个新的标识」
 * （{@code Supplier<ProductDirectionId>}），具体策略由 Composition Root 给出：
 *
 * <pre>
 * new ProductDirectionId(UUID.randomUUID().toString())
 * </pre>
 *
 * <p>这与项目现有的 Entity 标识生成方式一致——{@code SoftwareAssetId}、
 * {@code UserProfileId} 与 {@code RepositoryProfileId} 都在各自的 Use Case 里用
 * 同样的 UUID 字符串生成，因此这里不引入第二套标识约定，也没有新增 ID 框架。
 *
 * <p>标识是服务端事实：它不出现在任何请求体里，客户端无法指定（见
 * {@code ProductDirectionDiscoveryRequest}）。同一批内的唯一性由
 * {@code ProductDirectionDiscoveryService} 自己防御，不依赖生成器保证。
 */
@Configuration(proxyBeanMethods = false)
public class ProductDirectionUseCaseConfiguration {

    @Bean
    public ProductDirectionDiscoveryService productDirectionDiscoveryService() {
        return new ProductDirectionDiscoveryService(
                () -> new ProductDirectionId(UUID.randomUUID().toString()));
    }

    @Bean
    public DirectionDiscoveryExtraction directionDiscoveryExtraction(
            AiGateway aiGateway, ObjectMapper objectMapper) {
        return new DirectionDiscoveryExtraction(aiGateway, objectMapper);
    }

    @Bean
    public DiscoverProductDirectionsUseCase discoverProductDirectionsUseCase(
            UserProfileRepository userProfileRepository,
            RepositoryProfileRepository repositoryProfileRepository,
            DirectionDiscoveryExtraction directionDiscoveryExtraction,
            ProductDirectionDiscoveryService productDirectionDiscoveryService,
            ProductDirectionRepository productDirectionRepository) {
        return new DiscoverProductDirectionsUseCase(
                userProfileRepository,
                repositoryProfileRepository,
                directionDiscoveryExtraction,
                productDirectionDiscoveryService,
                productDirectionRepository);
    }

    @Bean
    public GetProductDirectionUseCase getProductDirectionUseCase(
            ProductDirectionRepository productDirectionRepository) {
        return new GetProductDirectionUseCase(productDirectionRepository);
    }

    /**
     * 用户明确选择方向的入口（INV-D07）。
     *
     * <p>它只被 Interface 层的显式请求调用。发现流程、AI 解析与启动装配都不装配它到任何
     * 自动路径上：方向生成之后仍然全部是 {@code CANDIDATE}。
     */
    @Bean
    public SelectProductDirectionUseCase selectProductDirectionUseCase(
            ProductDirectionRepository productDirectionRepository, EvolutionPlanRepository plans,
            EvolutionLifecycleCommitPort commits) {
        return new SelectProductDirectionUseCase(productDirectionRepository, plans, commits);
    }

    /** 用户明确拒绝方向的入口。 */
    @Bean
    public RejectProductDirectionUseCase rejectProductDirectionUseCase(
            ProductDirectionRepository productDirectionRepository) {
        return new RejectProductDirectionUseCase(productDirectionRepository);
    }
}
