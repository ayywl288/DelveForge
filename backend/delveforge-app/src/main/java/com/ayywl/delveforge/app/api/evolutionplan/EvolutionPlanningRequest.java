package com.ayywl.delveforge.app.api.evolutionplan;

/**
 * 客户端只指定规划依据；规划内容、状态与 Plan / Step 身份由服务端形成。
 */
public record EvolutionPlanningRequest(String productDirectionId, String baseAssetId, String baseRepositoryProfileId) {
    public EvolutionPlanningRequest {
        if (productDirectionId == null || productDirectionId.isBlank()
                || baseAssetId == null || baseAssetId.isBlank()
                || baseRepositoryProfileId == null || baseRepositoryProfileId.isBlank()) {
            throw new IllegalArgumentException("All planning basis identities are required");
        }
    }
}
