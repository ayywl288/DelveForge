package com.ayywl.delveforge.application.repositoryanalysis.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Region 目录：调用作用域、引用的可判定性，以及越界/外来引用的处理。 */
class RepositoryRegionCatalogTest {

    private static String scopeOf(RepositoryRegionReference reference) {
        // RR-<scope>-<position>
        return reference.value().split("-")[1];
    }

    private static RepositoryRegionCatalog withScope(String scope) {
        return RepositoryRegionCatalog.of(
                RegionFixtures.REVISION, RegionFixtures.tree().rootRegions(), () -> scope);
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
    void defaultScopeIsWellFormed() {
        RepositoryRegionCatalog catalog = RegionFixtures.catalog();

        assertTrue(scopeOf(catalog.entries().get(0).reference()).matches("[0-9a-f]{32}"),
                "默认作用域必须是完整 UUID（32 位小写十六进制），截短会碰撞");
    }

    /**
     * 调用身份不能由内容决定：相同输入的不同调用必须得到不同的引用。
     *
     * <p>否则上一次调用留下的响应会被这一次接受。
     */
    @Test
    void identicalInputFromDifferentInvocationsProducesDifferentReferences() {
        RepositoryRegionCatalog first = RegionFixtures.catalog();
        RepositoryRegionCatalog second = RegionFixtures.catalog();

        assertNotEquals(first.entries().get(0).reference(),
                second.entries().get(0).reference(),
                "相同输入的不同调用不得产生相同引用");
    }

    /** 用注入的确定作用域复现同一件事，不依赖随机性。 */
    @Test
    void referenceFromAnotherInvocationWithIdenticalInputResolvesToEmpty() {
        RepositoryRegionCatalog first = withScope(RegionFixtures.SCOPE_A);
        RepositoryRegionCatalog second = withScope(RegionFixtures.SCOPE_B);

        assertEquals(first.regions(), second.regions(), "两份目录内容完全相同");
        assertTrue(second.find(first.entries().get(0).reference()).isEmpty(),
                "内容相同也不行：它来自另一次调用");
        assertTrue(first.find(second.entries().get(0).reference()).isEmpty());
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

        assertTrue(catalog.find(new RepositoryRegionReference(RegionFixtures.absentRef(99))).isEmpty());
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
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryRegionCatalog.of(
                        RegionFixtures.REVISION, regions, (java.util.function.Supplier<String>) null));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryRegionCatalog.of(RegionFixtures.REVISION, regions, () -> "NOT-HEX"));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryRegionCatalog.of(RegionFixtures.REVISION, regions, () -> null));
    }

    @Test
    void entryAndRegionOrdersAgree() {
        RepositoryRegionCatalog catalog = RegionFixtures.catalog();

        for (int i = 0; i < catalog.size(); i++) {
            assertEquals(catalog.regions().get(i), catalog.entries().get(i).region());
        }
    }
}
