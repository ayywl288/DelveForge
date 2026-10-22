package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.application.evolution.preparation.ConfirmEvolutionStepUseCase;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationState;
import com.ayywl.delveforge.application.evolution.query.GetStepFinalChangesUseCase;
import com.ayywl.delveforge.application.port.persistence.EvolutionPlanRepository;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.SoftwareAssetRepository;
import com.ayywl.delveforge.application.port.persistence.StepConfirmationCommitPort;
import com.ayywl.delveforge.application.port.persistence.WorkingCopyRepository;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyInspectionPort;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyTreeDiffPort;
import com.ayywl.delveforge.domain.evolution.StepExecutionPolicy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配明确确认与只读资格；编码和 Delivery 由 CodingAgentConfiguration 组合。 */
@Configuration(proxyBeanMethods = false)
public class ExecutionPreparationUseCaseConfiguration {
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
    public GetStepFinalChangesUseCase getStepFinalChangesUseCase(EvolutionPlanRepository plans,
            WorkingCopyRepository copies, SoftwareAssetRepository assets, WorkingCopyTreeDiffPort trees) {
        return new GetStepFinalChangesUseCase(plans, copies, assets, trees);
    }
}
