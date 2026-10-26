package com.ayywl.delveforge.application.evolution.planning;

import com.ayywl.delveforge.application.project.ProjectScopeBasis;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;

public record GenerateEvolutionPlanRequest(ProductDirectionId productDirectionId,
        SoftwareAssetId baseAssetId, RepositoryProfileId baseRepositoryProfileId, ProjectScopeBasis project, String planningNotes) {
    public GenerateEvolutionPlanRequest(ProductDirectionId productDirectionId, SoftwareAssetId baseAssetId,
            RepositoryProfileId baseRepositoryProfileId, ProjectScopeBasis project) {
        this(productDirectionId, baseAssetId, baseRepositoryProfileId, project, null);
    }

    public GenerateEvolutionPlanRequest(ProductDirectionId productDirectionId, SoftwareAssetId baseAssetId, RepositoryProfileId baseRepositoryProfileId) {
        this(productDirectionId, baseAssetId, baseRepositoryProfileId, null);
    }

    public GenerateEvolutionPlanRequest {
        if (productDirectionId == null || baseAssetId == null || baseRepositoryProfileId == null) {
            throw new IllegalArgumentException("All three planning basis identities are required");
        }
    }
}
