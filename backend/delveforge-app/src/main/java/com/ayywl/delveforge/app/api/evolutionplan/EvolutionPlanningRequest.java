package com.ayywl.delveforge.app.api.evolutionplan;

/** Only the basis is client-supplied; planning content/state/IDs are server facts. */
public record EvolutionPlanningRequest(String productDirectionId, String baseAssetId, String baseRepositoryProfileId) {
    public EvolutionPlanningRequest {
        if (productDirectionId == null || productDirectionId.isBlank()
                || baseAssetId == null || baseAssetId.isBlank()
                || baseRepositoryProfileId == null || baseRepositoryProfileId.isBlank())
            throw new IllegalArgumentException("All planning basis identities are required");
    }
}
