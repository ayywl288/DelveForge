package com.ayywl.delveforge.app.config;
import com.ayywl.delveforge.application.evolution.planning.*;
import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.domain.asset.AssetUsagePolicy;
import com.ayywl.delveforge.domain.evolution.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class EvolutionPlanningUseCaseConfiguration {
    @Bean
    public EvolutionPlanningService evolutionPlanningService() {
        return new EvolutionPlanningService(new AssetUsagePolicy(),
                () -> new EvolutionPlanId(UUID.randomUUID().toString()),
                () -> new EvolutionStepId(UUID.randomUUID().toString()));
    }
    @Bean
    public EvolutionPlanningExtraction evolutionPlanningExtraction(AiGateway gateway, ObjectMapper mapper) {
        return new EvolutionPlanningExtraction(gateway, mapper);
    }
    @Bean
    public GenerateEvolutionPlanUseCase generateEvolutionPlanUseCase(ProductDirectionRepository directions,
            SoftwareAssetRepository assets, RepositoryProfileRepository profiles,
            EvolutionPlanningExtraction extraction, EvolutionPlanningService service, EvolutionPlanRepository plans) {
        return new GenerateEvolutionPlanUseCase(directions, assets, profiles, extraction, service, plans);
    }
    @Bean
    public GetEvolutionPlanUseCase getEvolutionPlanUseCase(EvolutionPlanRepository plans) {
        return new GetEvolutionPlanUseCase(plans);
    }
}
