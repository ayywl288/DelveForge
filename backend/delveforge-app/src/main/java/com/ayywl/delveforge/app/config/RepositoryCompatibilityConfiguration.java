package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.workspace.RepositoryCompatibilityPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.repositoryanalysis.compatibility.GetRepositoryCompatibilityUseCase;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RepositoryCompatibilityConfiguration {
    @Bean
    GetRepositoryCompatibilityUseCase getRepositoryCompatibilityUseCase(SoftwareAssetRepository assets,
            WorkspaceReadPort read, RepositoryCompatibilityPort compatibility) {
        return new GetRepositoryCompatibilityUseCase(assets, read, compatibility);
    }
}
