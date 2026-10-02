package com.ayywl.delveforge.application.repositoryanalysis.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Region 目录与它的 invocation-local 编号。 */
class RepositoryRegionCatalogTest {

    @Test
    void assignsInvocationLocalReferencesByPosition() {
        RepositoryRegionCatalog catalog = RegionFixtures.catalog();

        assertEquals(3, catalog.size());
        assertEquals(List.of("RR-1", "RR-2", "RR-3"),
                catalog.entries().stream()
                        .map(entry -> entry.reference().value()).toList());
        assertEquals(List.of("src", "svc", "web"),
                catalog.regions().stream().map(RepositoryRegion::pathPrefix).toList());
        assertEquals(RegionFixtures.REVISION, catalog.analyzedRevision());
    }

    @Test
    void resolvesReferencesBackToRegions() {
        RepositoryRegionCatalog catalog = RegionFixtures.catalog();

        assertEquals("src", catalog.find(RepositoryRegionReference.of(1)).orElseThrow()
                .pathPrefix());
        assertEquals("web", catalog.find(RepositoryRegionReference.of(3)).orElseThrow()
                .pathPrefix());
    }

    @Test
    void unknownReferenceAndNullResolveToEmpty() {
        RepositoryRegionCatalog catalog = RegionFixtures.catalog();

        assertTrue(catalog.find(RepositoryRegionReference.of(4)).isEmpty(),
                "越界编号不是本次提供的区域");
        assertTrue(catalog.find(new RepositoryRegionReference("RR-99")).isEmpty());
        assertTrue(catalog.find(null).isEmpty());
    }

    @Test
    void rejectsDuplicatePathPrefix() {
        RepositoryRegion src = RegionFixtures.tree().rootRegions().get(0);
        RepositoryRegion samePrefix = RegionFixtures.tree().rootRegions().get(0);

        assertThrows(IllegalArgumentException.class,
                () -> RepositoryRegionCatalog.of(RegionFixtures.REVISION,
                        List.of(src, samePrefix)),
                "重复目录前缀会让编号指向哪个区域不可判定");
    }

    @Test
    void rejectsInvalidArguments() {
        List<RepositoryRegion> regions = RegionFixtures.tree().rootRegions();

        assertThrows(IllegalArgumentException.class,
                () -> RepositoryRegionCatalog.of(null, regions));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryRegionCatalog.of("  ", regions));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryRegionCatalog.of(RegionFixtures.REVISION, null));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryRegionCatalog.of(RegionFixtures.REVISION, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryRegionCatalog.of(RegionFixtures.REVISION,
                        java.util.Arrays.asList(regions.get(0), null)));
    }

    @Test
    void entryAndRegionOrdersAgree() {
        RepositoryRegionCatalog catalog = RegionFixtures.catalog();

        for (int i = 0; i < catalog.size(); i++) {
            assertEquals(catalog.regions().get(i), catalog.entries().get(i).region());
        }
    }
}
