package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.application.port.persistence.EvolutionPlanRepository;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.ProjectContextRepository;
import com.ayywl.delveforge.application.port.persistence.ProjectHistoryIndex;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.port.persistence.WorkingCopyRepository;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyStorageInspection;
import com.ayywl.delveforge.application.project.ProjectWorkspaceService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class ProjectWorkspaceConfiguration {
    @Bean
    public ProjectWorkspaceService projectWorkspaceService(ProjectContextRepository contexts, ProjectHistoryIndex index,
            UserProfileRepository users, SoftwareAssetRepository assets, RepositoryProfileRepository repositories,
            ProductDirectionRepository directions, EvolutionPlanRepository plans, WorkingCopyRepository copies,
            WorkingCopyStorageInspection storage) {
        return new ProjectWorkspaceService(contexts, index, users, assets, repositories, directions, plans, copies,
                storage, Clock.systemUTC());
    }
}
