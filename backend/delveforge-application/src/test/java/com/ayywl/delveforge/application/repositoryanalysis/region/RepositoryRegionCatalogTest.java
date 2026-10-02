package com.ayywl.delveforge.application.repositoryanalysis.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Region 目录：编号的作用域、可判定性，以及越界/外来引用的处理。 */
class RepositoryRegionCatalogTest {

    private static String scopeOf(RepositoryRegionReference reference) {
        // RR-<scope>-<position>
        String[] parts = reference.value().split("-");
        return parts[1];
    }

    @Test
    void assignsScopedReferencesByPosition() {
        RepositoryRegionCatalog catalog = RegionFixtures.catalog();

        assertEquals(3, catalog.size());
        assertEquals(List.of("src", "svc", "web"),
                catalog.regions().stream().map(RepositoryRegion::pathPrefix).toList());
        assertEquals(RegionFixtures.REVISION, catalog.analyzedRevision());

        String scope = scopeOf(catalog.entries().get(0).reference());
        for (int i = 0; i < catalog.size(); i++) {
            RepositoryRegionReference reference = catalog.entries().get(i).reference();
            assertTrue(RepositoryRegionReference.isWellFormed(reference.value()),
                    "引用必须符合 RR-<scope>-<position>: " + reference.value());
            assertEquals(scope, scopeOf(reference), "同一次调用的引用共享同一个作用域");
            assertTrue(reference.value().endsWith("-" + (i + 1)),
                    "位置编号按顺序分配: " + reference.value());
        }
    }

    @Test
    void scopeIsDeterministicForTheSameContent() {
        RepositoryRegionCatalog first = RegionFixtures.catalog();
        RepositoryRegionCatalog second = RepositoryRegionCatalog.of(
                RegionFixtures.REVISION, RegionFixtures.tree().rootRegions());

        assertEquals(scopeOf(first.entries().get(0).reference()),
                scopeOf(second.entries().get(0).reference()),
                "同一份内容派生同一个作用域（引用因此可复现）");
    }

    @Test
    void scopeDiffersWhenContentDiffers() {
        RepositoryRegionCatalog threeRegions = RegionFixtures.catalog();
        RepositoryRegionCatalog oneRegion = RepositoryRegionCatalog.of(
                RegionFixtures.REVISION, threeRegions.regions().subList(0, 1));

        assertNotEquals(scopeOf(threeRegions.entries().get(0).reference()),
                scopeOf(oneRegion.entries().get(0).reference()),
                "内容不同的目录作用域必须不同，否则外来引用会被误认成本次调用");
    }

    @Test
    void resolvesReferencesBackToRegions() {
        RepositoryRegionCatalog catalog = RegionFixtures.catalog();

        assertEquals("src", catalog.find(catalog.entries().get(0).reference())
                .orElseThrow().pathPrefix());
        assertEquals("web", catalog.find(catalog.entries().get(2).reference())
                .orElseThrow().pathPrefix());
    }

    @Test
    void referenceFromAnotherCatalogResolvesToEmpty() {
        RepositoryRegionCatalog other = RepositoryRegionCatalog.of(
                RegionFixtures.REVISION,
                List.of(RegionFixtures.tree().region("svc").orElseThrow(),
                        RegionFixtures.tree().region("web").orElseThrow()));

        assertTrue(RegionFixtures.catalog().find(other.entries().get(0).reference()).isEmpty(),
                "另一份目录的第一个区域不能在本目录里解析成功");
    }

    @Test
    void unknownReferenceAndNullResolveToEmpty() {
        RepositoryRegionCatalog catalog = RegionFixtures.catalog();

        assertTrue(catalog.find(new RepositoryRegionReference("RR-00000000-99")).isEmpty());
        assertTrue(catalog.find(new RepositoryRegionReference("RR-1")).isEmpty(),
                "裸序号不是本版本的引用形式");
        assertTrue(catalog.find(null).isEmpty());
    }

    @Test
    void rejectsDuplicatePathPrefix() {
        RepositoryRegion src = RegionFixtures.tree().rootRegions().get(0);

        assertThrows(IllegalArgumentException.class,
                () -> RepositoryRegionCatalog.of(RegionFixtures.REVISION, List.of(src, src)),
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
