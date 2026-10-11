package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.repositoryanalysis.asset.GetSoftwareAssetUseCase;
import com.ayywl.delveforge.application.repositoryanalysis.asset.RegisterSoftwareAssetUseCase;
import com.ayywl.delveforge.application.repositoryanalysis.asset.UpdateSoftwareAssetAuthorizationUseCase;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition Root 装配元数据 Use Case；路径身份的只读实现由 Infrastructure 提供。 */
@Configuration(proxyBeanMethods = false)
public class SoftwareAssetUseCaseConfiguration {
    @Bean
    public UpdateSoftwareAssetAuthorizationUseCase updateSoftwareAssetAuthorizationUseCase(
            SoftwareAssetRepository softwareAssetRepository) {
        return new UpdateSoftwareAssetAuthorizationUseCase(softwareAssetRepository);
    }

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
