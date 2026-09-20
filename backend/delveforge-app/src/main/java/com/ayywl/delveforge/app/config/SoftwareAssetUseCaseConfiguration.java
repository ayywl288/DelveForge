package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.repositoryanalysis.asset.GetSoftwareAssetUseCase;
import com.ayywl.delveforge.application.repositoryanalysis.asset.RegisterSoftwareAssetUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Software Asset Use Case 的依赖装配。
 *
 * <p>Use Case 由 Application 拥有，但不依赖 Spring——Application 模块不引入任何
 * Spring 依赖，因此它们不能通过 {@code @Component} 被发现。装配放在 Composition Root
 * 完成（RULE-ARCH-005），Application 侧保持框架无关。
 *
 * <p>{@link SoftwareAssetRepository} 由 Infrastructure 的 Persistence Adapter
 * （{@code SqliteSoftwareAssetRepository}）提供。
 *
 * <p>登记 Software Asset 不需要 Workspace 与 AI Gateway：它只写元数据，
 * 不访问 Repository 本身。后续 Analysis 流程所需的读能力在对应 Use Case 装配时引入。
 */
@Configuration(proxyBeanMethods = false)
public class SoftwareAssetUseCaseConfiguration {

    @Bean
    public RegisterSoftwareAssetUseCase registerSoftwareAssetUseCase(
            SoftwareAssetRepository softwareAssetRepository) {
        return new RegisterSoftwareAssetUseCase(softwareAssetRepository);
    }

    @Bean
    public GetSoftwareAssetUseCase getSoftwareAssetUseCase(
            SoftwareAssetRepository softwareAssetRepository) {
        return new GetSoftwareAssetUseCase(softwareAssetRepository);
    }
}
