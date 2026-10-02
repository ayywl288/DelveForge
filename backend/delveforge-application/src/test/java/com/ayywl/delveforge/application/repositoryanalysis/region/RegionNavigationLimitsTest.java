package com.ayywl.delveforge.application.repositoryanalysis.region;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 导航守卫：Region Catalog 的字节上限与选择数量上限。 */
class RegionNavigationLimitsTest {

    @Test
    void acceptsPayloadWithinTheLimit() {
        RegionNavigationLimits limits = new RegionNavigationLimits(1_024, 6);

        assertDoesNotThrow(() -> limits.requireCatalogWithinLimit(1_024, "rev"));
        assertEquals(1_024, limits.maxCatalogBytes());
        assertEquals(6, limits.maxSelectedRegions());
    }

    @Test
    void failsClosedWhenPayloadExceedsTheLimit() {
        RegionNavigationLimits limits = new RegionNavigationLimits(1_024, 6);

        RepositoryRegionCatalogTooLargeException failure = assertThrows(
                RepositoryRegionCatalogTooLargeException.class,
                () -> limits.requireCatalogWithinLimit(1_025, "rev-abc"));

        assertTrue(failure.getMessage().contains("REGION_CATALOG_TOO_LARGE"),
                "失败原因必须有稳定标识");
        assertTrue(failure.getMessage().contains("rev-abc"),
                "失败信息应带上本次导航的 revision");
        assertTrue(failure.getMessage().contains("1025"),
                "失败信息应带上实际字节数，便于判断超了多少");
    }

    @Test
    void rejectsNegativePayloadAndInvalidLimits() {
        RegionNavigationLimits limits = new RegionNavigationLimits(1_024, 6);

        assertThrows(IllegalArgumentException.class,
                () -> limits.requireCatalogWithinLimit(-1, "rev"));
        assertThrows(IllegalArgumentException.class, () -> new RegionNavigationLimits(0, 6));
        assertThrows(IllegalArgumentException.class, () -> new RegionNavigationLimits(-5, 6));
        assertThrows(IllegalArgumentException.class, () -> new RegionNavigationLimits(1_024, 0));
    }

    @Test
    void guardAppliesToTheMeasuredRegionCatalogPayload() {
        RepositoryRegionScoutExtraction extraction = new RepositoryRegionScoutExtraction(
                new AiGateway() {
                    @Override
                    public String generate(AiRequest request) {
                        throw new AssertionError("本用例不应调用 Provider");
                    }
                },
                new ObjectMapper(),
                new RegionNavigationLimits(65_536, 6));
        RepositoryRegionCatalog catalog = RegionFixtures.catalog();

        int payloadBytes = extraction.catalogPayloadBytes(catalog);

        assertDoesNotThrow(() -> new RegionNavigationLimits(payloadBytes, 6)
                .requireCatalogWithinLimit(payloadBytes, catalog.analyzedRevision()));
        assertThrows(RepositoryRegionCatalogTooLargeException.class,
                () -> new RegionNavigationLimits(payloadBytes - 1, 6)
                        .requireCatalogWithinLimit(payloadBytes, catalog.analyzedRevision()));
    }
}
