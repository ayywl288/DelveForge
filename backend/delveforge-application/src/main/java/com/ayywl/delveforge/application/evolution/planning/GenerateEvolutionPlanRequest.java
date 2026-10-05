package com.ayywl.delveforge.application.evolution.planning;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
public record GenerateEvolutionPlanRequest(ProductDirectionId productDirectionId,
        SoftwareAssetId baseAssetId, RepositoryProfileId baseRepositoryProfileId) {
    public GenerateEvolutionPlanRequest {
        if (productDirectionId == null || baseAssetId == null || baseRepositoryProfileId == null)
            throw new IllegalArgumentException("All three planning basis identities are required");
    }
}
