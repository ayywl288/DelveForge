package com.ayywl.delveforge.application.repositoryanalysis.asset;

import com.ayywl.delveforge.domain.asset.AssetAuthorizationConflictException;
import com.ayywl.delveforge.domain.asset.AssetEvolutionNotAllowedException;
import com.ayywl.delveforge.domain.asset.AssetUsagePolicy;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.asset.SoftwareAssetSource;
import com.ayywl.delveforge.domain.asset.SoftwareAssetType;
import com.ayywl.delveforge.domain.asset.UsageAuthorization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateSoftwareAssetAuthorizationUseCaseTest {
    private final InMemorySoftwareAssetRepository assets = new InMemorySoftwareAssetRepository();
    private final UpdateSoftwareAssetAuthorizationUseCase useCase = new UpdateSoftwareAssetAuthorizationUseCase(assets);
    private final SoftwareAssetId id = new SoftwareAssetId("existing");

    private SoftwareAsset seed() {
        SoftwareAsset original = SoftwareAsset.create(id, SoftwareAssetType.GIT_REPOSITORY,
                SoftwareAssetSource.USER_SPECIFIED, "E:/projects/demo", true, null, UsageAuthorization.UNCLEAR);
        assets.save(original);
        return original;
    }

    @Test
    void explicitUpdatePreservesIdentityAndDoesNotMutateLoadedOriginal() {
        SoftwareAsset original = seed();
        original.requireAnalysisAllowed();
        assertThrows(AssetEvolutionNotAllowedException.class,
                () -> new AssetUsagePolicy().requireEvolutionBaseAllowed(original));
        SoftwareAsset updated = useCase.update(id, 0, true, "Code: Apache-2.0; prose: separate terms",
                UsageAuthorization.ALLOWED);
        assertEquals(original.id(), updated.id());
        assertEquals(original.location(), updated.location());
        assertEquals(1, updated.authorizationRevision());
        assertEquals(0, original.authorizationRevision());
        assertTrue(original.licenseInfo().isEmpty());
        assertEquals(UsageAuthorization.UNCLEAR, original.usageAuthorization());
        new AssetUsagePolicy().requireEvolutionBaseAllowed(updated);
    }

    @Test
    void staleAndInvalidSubmissionsLeaveAllAuthorizationUntouched() {
        SoftwareAsset original = seed();
        assertThrows(AssetAuthorizationConflictException.class,
                () -> useCase.update(id, 1, true, "MIT", UsageAuthorization.ALLOWED));
        assertThrows(IllegalArgumentException.class, () -> useCase.update(id, 0, true, "MIT", null));
        assertThrows(IllegalArgumentException.class, () -> useCase.update(id, 0, true, " ", UsageAuthorization.ALLOWED));
        assertSame(original, assets.findById(id).orElseThrow());
    }

    @Test
    void denialAndUnknownLicenseRemainExplicitlyRepresentable() {
        seed();
        SoftwareAsset denied = useCase.update(id, 0, false, null, UsageAuthorization.DENIED);
        assertFalse(denied.readPermissionAllowed());
        assertTrue(denied.licenseInfo().isEmpty());
        assertEquals(UsageAuthorization.DENIED, denied.usageAuthorization());
        assertThrows(AssetEvolutionNotAllowedException.class,
                () -> new AssetUsagePolicy().requireEvolutionBaseAllowed(denied));
    }
}
