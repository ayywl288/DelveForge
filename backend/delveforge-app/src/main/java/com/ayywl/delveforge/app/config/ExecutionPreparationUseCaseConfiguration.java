package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.application.evolution.preparation.*;
import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.application.port.workspace.*;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;
import com.ayywl.delveforge.domain.evolution.StepExecutionPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExecutionPreparationProperties.class)
public class ExecutionPreparationUseCaseConfiguration {
    @Bean
    public com.ayywl.delveforge.application.evolution.execution.ExecuteEvolutionStepUseCase executeEvolutionStepUseCase(
            PrepareEvolutionStepUseCase preparation, ExecutionPreparationState state, StepExecutionPolicy policy,
            StepExecutionCommitPort commits, WorkspaceMutationPort mutation, WorkspaceVerificationPort verification) {
        return new com.ayywl.delveforge.application.evolution.execution.ExecuteEvolutionStepUseCase(
                preparation, state, policy, commits, mutation, verification);
    }

    @Bean
    public StepExecutionPolicy stepExecutionPolicy() {
        return new StepExecutionPolicy();
    }

    @Bean
    public ExecutionPreparationState executionPreparationState(EvolutionPlanRepository plans,
            WorkingCopyRepository copies, SoftwareAssetRepository assets, ProductDirectionRepository directions,
            WorkingCopyInspectionPort inspection, StepExecutionPolicy policy) {
        return new ExecutionPreparationState(plans, copies, assets, directions, inspection, policy);
    }

    @Bean
    public ConfirmEvolutionStepUseCase confirmEvolutionStepUseCase(ExecutionPreparationState state,
            StepConfirmationCommitPort commits, StepExecutionPolicy policy) {
        return new ConfirmEvolutionStepUseCase(state, commits, policy);
    }

    @Bean
    public StepRepositoryUnderstanding stepRepositoryUnderstanding(WorkspaceReadPort read, AiGateway ai,
            ObjectMapper mapper, RepositorySecretPolicy secrets, ExecutionPreparationProperties preparation,
            RepositoryAnalysisProperties repository) {
        return new StepRepositoryUnderstanding(read, ai, mapper, secrets, preparation.toLimits(),
                repository.foundation().toBudget(), repository.targetedSource().toBudget(),
                repository.region().toLimits(), repository.region().toRecursionBudget(),
                repository.scout().maxCatalogBytes());
    }

    @Bean
    public PrepareEvolutionStepUseCase prepareEvolutionStepUseCase(ExecutionPreparationState state,
            StepRepositoryUnderstanding understanding, ObjectMapper mapper, RepositorySecretPolicy secrets,
            WorkingCopyInspectionPort inspection, ExecutionPreparationProperties properties) {
        ExecutionPreparationLimits limits = properties.toLimits();
        return new PrepareEvolutionStepUseCase(state, understanding, new CodingProposalParser(mapper, limits),
                new CodingProposalValidator(secrets, inspection, limits), mapper, limits);
    }
}
