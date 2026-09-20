package com.ayywl.delveforge.application.repositoryanalysis.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.asset.SoftwareAssetSource;
import com.ayywl.delveforge.domain.asset.SoftwareAssetType;
import com.ayywl.delveforge.domain.asset.UsageAuthorization;
import org.junit.jupiter.api.Test;

class GetSoftwareAssetUseCaseTest {

    private static final SoftwareAssetId ASSET_ID = new SoftwareAssetId("software-asset-1");

    private static final String LOCATION = "E:/projects/legacy-tool";

    private final InMemorySoftwareAssetRepository repository = new InMemorySoftwareAssetRepository();

    private final GetSoftwareAssetUseCase useCase = new GetSoftwareAssetUseCase(repository);

    @Test
    void returnsStoredAsset() {
        SoftwareAsset stored = SoftwareAsset.reconstitute(
                ASSET_ID,
                SoftwareAssetType.GIT_REPOSITORY,
                SoftwareAssetSource.USER_SPECIFIED,
                LOCATION,
                true,
                "MIT",
                UsageAuthorization.DENIED);
        repository.save(stored);

        SoftwareAsset loaded = useCase.get(ASSET_ID);

        assertSame(stored, loaded);
        assertEquals(ASSET_ID, loaded.id());
        assertEquals(SoftwareAssetType.GIT_REPOSITORY, loaded.type());
        assertEquals(SoftwareAssetSource.USER_SPECIFIED, loaded.source());
        assertEquals(LOCATION, loaded.location());
        assertEquals(UsageAuthorization.DENIED, loaded.usageAuthorization());
    }

    @Test
    void failsWhenAssetDoesNotExist() {
        assertThrows(SoftwareAssetNotFoundException.class,
                () -> useCase.get(new SoftwareAssetId("unknown-asset")));
    }

    @Test
    void rejectsMissingRepository() {
        assertThrows(IllegalArgumentException.class, () -> new GetSoftwareAssetUseCase(null));
    }

    /**
     * 读取只返回元数据，不因为读取动作而改变任何授权事实。
     */
    @Test
    void doesNotChangeAuthorizationFactsWhenRead() {
        repository.save(SoftwareAsset.reconstitute(
                ASSET_ID,
                SoftwareAssetType.GIT_REPOSITORY,
                SoftwareAssetSource.USER_SPECIFIED,
                LOCATION,
                false,
                null,
                UsageAuthorization.UNCLEAR));

        SoftwareAsset loaded = useCase.get(ASSET_ID);

        assertFalse(loaded.readPermissionAllowed());
        assertEquals(UsageAuthorization.UNCLEAR, loaded.usageAuthorization());
    }
}
